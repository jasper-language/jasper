// S4-C 调度门真实线程池验收；Kotlin 仅编译/加载与超时测试驱动，状态机仍写在 Jasper。
package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 单线程进展与多线程恢复竞争，使用有界等待且在失败路径释放全部测试资源。 */
class ContinuationThreadPoolTest {
    private val source = """
        package std;
        public class ThreadWake implements Action<FutureOutcome> {
            private var dispatch: ContinuationDispatch;
            public constructor(dispatch: ContinuationDispatch) { this.dispatch = dispatch; }
            public function accept(outcome: FutureOutcome): void { this.dispatch.resume(); }
        }
        public class ThreadAwait implements Runnable {
            public var count: int32;
            public var dispatch: ContinuationDispatch;
            public var done: java.util.concurrent.Semaphore;
            public var input: Future<int32>;
            public constructor(executor: Executor) {
                this.count = 0;
                this.done = new java.util.concurrent.Semaphore(0);
                this.input = new Future<int32>();
                this.dispatch = new ContinuationDispatch(executor, this);
            }
            public function run(): void {
                this.count = this.count + 1;
                if (this.count == 1) {
                    this.input.onComplete(new ThreadWake(this.dispatch));
                    return;
                }
                this.dispatch.finish();
                this.done.release();
            }
        }
        public class ThreadProducer implements Runnable {
            private var input: Future<int32>;
            public constructor(input: Future<int32>) { this.input = input; }
            public function run(): void { this.input.complete(42); }
        }
        public class ConcurrentSegment implements Runnable {
            public var dispatch: ContinuationDispatch;
            public var entered: java.util.concurrent.Semaphore;
            public var releaseFirst: java.util.concurrent.Semaphore;
            public var done: java.util.concurrent.Semaphore;
            public var count: int32;
            public var overlap: int32;
            private var active: int32;
            private var mutex: java.util.concurrent.Semaphore;
            public constructor(executor: Executor) {
                this.dispatch = new ContinuationDispatch(executor, this);
                this.entered = new java.util.concurrent.Semaphore(0);
                this.releaseFirst = new java.util.concurrent.Semaphore(0);
                this.done = new java.util.concurrent.Semaphore(0);
                this.mutex = new java.util.concurrent.Semaphore(1);
                this.count = 0;
                this.overlap = 0;
                this.active = 0;
            }
            public function run(): void {
                this.mutex.acquireUninterruptibly();
                this.active = this.active + 1;
                this.count = this.count + 1;
                var ordinal: int32 = this.count;
                if (this.active > 1) { this.overlap = this.overlap + 1; }
                this.mutex.release();
                if (ordinal == 1) {
                    this.entered.release();
                    this.releaseFirst.acquireUninterruptibly();
                }
                this.mutex.acquireUninterruptibly();
                this.active = this.active - 1;
                this.mutex.release();
                if (ordinal >= 2) {
                    this.dispatch.finish();
                    this.done.release();
                }
            }
        }
    """.trimIndent()

    /** 在 build 下编译 Jasper 夹具；调用方通过 use 关闭生成类加载器。 */
    private fun compile(name: String): URLClassLoader {
        val output = Paths.get("build", "tmp", "continuation-thread-pool", name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Threads.jas" to source), output)
        assertTrue(result is JasperCompiler.CompileResult.Success, result.toString())
        return URLClassLoader(arrayOf(output.toUri().toURL()))
    }

    /** 一个 JVM 工作线程仍可完成生产者并恢复消费者；执行段中没有阻塞 get。 */
    @Test
    fun `single worker runs producer after consumer suspends`() {
        compile("single").use { loader ->
            val executorType = loader.loadClass("std.Executor")
            val executor = loader.loadClass("std.JvmExecutor").getConstructor(Int::class.javaPrimitiveType).newInstance(1)
            val step = loader.loadClass("std.ThreadAwait").getConstructor(executorType).newInstance(executor)
            val dispatch = step.javaClass.getField("dispatch").get(step)
            val done = step.javaClass.getField("done").get(step) as Semaphore
            try {
                dispatch.javaClass.getMethod("resume").invoke(dispatch)
                val input = step.javaClass.getField("input").get(step)
                val producer = loader.loadClass("std.ThreadProducer").getConstructor(loader.loadClass("std.Future")).newInstance(input)
                executor.javaClass.getMethod("schedule", loader.loadClass("std.Runnable")).invoke(executor, producer)
                assertTrue(done.tryAcquire(5, TimeUnit.SECONDS), "single worker did not resume consumer")
                assertEquals(2, step.javaClass.getField("count").getInt(step))
                assertEquals(42, input.javaClass.getMethod("get").invoke(input))
            } finally {
                dispatch.javaClass.getMethod("finish").invoke(dispatch)
                executor.javaClass.getMethod("shutdown").invoke(executor)
                assertEquals(true, executor.javaClass.getMethod("awaitTermination", Long::class.javaPrimitiveType).invoke(executor, 5000L))
            }
        }
    }

    /** 首段运行时八个生产者并发唤醒，应合并为一个后续段且无执行重叠。 */
    @Test
    fun `concurrent wake requests serialize on real worker pool`() {
        compile("concurrent").use { loader ->
            val executor = loader.loadClass("std.JvmExecutor").getConstructor(Int::class.javaPrimitiveType).newInstance(4)
            val step = loader.loadClass("std.ConcurrentSegment").getConstructor(loader.loadClass("std.Executor")).newInstance(executor)
            val dispatch = step.javaClass.getField("dispatch").get(step)
            val resume = dispatch.javaClass.getMethod("resume")
            val release = step.javaClass.getField("releaseFirst").get(step) as Semaphore
            val producers = Executors.newFixedThreadPool(8)
            try {
                resume.invoke(dispatch)
                assertTrue((step.javaClass.getField("entered").get(step) as Semaphore).tryAcquire(5, TimeUnit.SECONDS))
                val requests = (1..8).map { producers.submit { repeat(100) { resume.invoke(dispatch) } } }
                requests.forEach { it.get(5, TimeUnit.SECONDS) }
                assertEquals(1, step.javaClass.getField("count").getInt(step), "wakes ran before current segment returned")
                release.release()
                assertTrue((step.javaClass.getField("done").get(step) as Semaphore).tryAcquire(5, TimeUnit.SECONDS))
                assertEquals(2, step.javaClass.getField("count").getInt(step))
                assertEquals(0, step.javaClass.getField("overlap").getInt(step))
            } finally {
                dispatch.javaClass.getMethod("finish").invoke(dispatch)
                release.release(8)
                producers.shutdownNow()
                assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS))
                executor.javaClass.getMethod("shutdown").invoke(executor)
                assertEquals(true, executor.javaClass.getMethod("awaitTermination", Long::class.javaPrimitiveType).invoke(executor, 5000L))
            }
        }
    }
}
