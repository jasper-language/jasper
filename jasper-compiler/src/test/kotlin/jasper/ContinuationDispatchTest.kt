// S4-C 内部恢复门验收：真实编译 Jasper 状态机与确定性队列，不把手写执行段称为 await lowering。
package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 恢复调度协议的端到端测试，Jasper 实现全部队列操作与执行状态。 */
class ContinuationDispatchTest {
    private val fixture = """
        package std;
        public class StepQueue implements Executor {
            private var tasks: MutableList<Runnable>;
            private var cursor: int32;
            public constructor() { this.tasks = new MutableList<Runnable>(); this.cursor = 0; }
            public function schedule(task: Runnable): void { this.tasks.add(task); }
            public function shutdown(): void { }
            public function pending(): int32 { return this.tasks.size() - this.cursor; }
            public function next(): void {
                var task: Runnable = this.tasks.get(this.cursor);
                this.cursor = this.cursor + 1;
                task.run();
            }
        }
        public class ResumeStep implements Action<FutureOutcome> {
            private var dispatch: ContinuationDispatch;
            public constructor(dispatch: ContinuationDispatch) { this.dispatch = dispatch; }
            public function accept(value: FutureOutcome): void { this.dispatch.resume(); }
        }
        public class RejectQueue implements Executor {
            public function schedule(task: Runnable): void { throw new Error("queue rejected"); }
            public function shutdown(): void { }
        }
        public class ThrowingStep implements Runnable {
            public var count: int32;
            public constructor() { this.count = 0; }
            public function run(): void { this.count = this.count + 1; throw new Error("segment failed"); }
        }
        public class RejectResumeQueue implements Executor {
            private var task: Runnable?;
            private var submissions: int32;
            public constructor() { this.task = null; this.submissions = 0; }
            public function schedule(task: Runnable): void {
                this.submissions = this.submissions + 1;
                if (this.submissions > 1) { throw new Error("resume rejected"); }
                this.task = task;
            }
            public function shutdown(): void { }
            public function first(): void { var queuedWork: Runnable = cast this.task as Runnable; queuedWork.run(); }
        }
        public class WakeWhileRunning implements Runnable {
            public var dispatch: ContinuationDispatch;
            public constructor(executor: Executor) { this.dispatch = new ContinuationDispatch(executor, this); }
            public function run(): void { this.dispatch.resume(); }
        }
        public class BadFailureNotice implements Action<FutureOutcome> {
            public function accept(outcome: FutureOutcome): void { throw new Error("failure notice threw"); }
        }
        public class TwoSteps implements Runnable {
            public var count: int32;
            public var dispatch: ContinuationDispatch;
            private var source: Future<int32>;
            public constructor(queue: Executor, source: Future<int32>) {
                this.count = 0;
                this.source = source;
                this.dispatch = new ContinuationDispatch(queue, this);
            }
            public function run(): void {
                this.count = this.count + 1;
                if (this.count == 1) {
                    this.source.onComplete(new ResumeStep(this.dispatch));
                    return;
                }
                this.dispatch.finish();
            }
        }
    """.trimIndent()

    /** 编译并运行 std.main；Kotlin 只负责测试驱动和生成类的加载。 */
    private fun run(name: String, body: String) {
        val output = Paths.get("build", "tmp", "continuation-dispatch", name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Main.jas" to
            fixture + "\npublic function main(): int32 {\n" + body + "\n}"), output)
        assertTrue(result is JasperCompiler.CompileResult.Success, result.toString())
        URLClassLoader(arrayOf(output.toUri().toURL())).use { loader ->
            val invocation = FutureTask<Any?> { loader.loadClass("std.main").getMethod("main").invoke(null) }
            val worker = Thread(invocation, "continuation-contract-$name").also { it.isDaemon = true }
            worker.start()
            try {
                assertEquals(0, invocation.get(10, TimeUnit.SECONDS), name)
            } finally {
                invocation.cancel(true)
            }
        }
    }

    /** 挂起的执行段退出后队列仍可处理生产者，完成通知只入队、不内联恢复。 */
    @Test
    fun `pending future releases segment and completion enqueues resume`() = run("pending", """
        var queue: StepQueue = new StepQueue();
        var source: Future<int32> = new Future<int32>();
        var steps: TwoSteps = new TwoSteps(queue, source);
        steps.dispatch.resume();
        steps.dispatch.resume();
        if (queue.pending() != 1) { return 1; }
        queue.next();
        if (steps.count != 1 || queue.pending() != 0) { return 2; }
        source.complete(42);
        if (steps.count != 1 || queue.pending() != 1) { return 3; }
        queue.next();
        if (steps.count != 2 || queue.pending() != 0) { return 4; }
        if (steps.dispatch.resume()) { return 5; }
        steps.dispatch.run();
        if (steps.count != 2) { return 6; }
        return 0;
    """.trimIndent())

    /** 已完成 Future 在注册时同步通知，运行中的唤醒必须留到当前段退出后处理。 */
    @Test
    fun `ready future wake during running is retained and never runs inline`() = run("ready", """
        var queue: StepQueue = new StepQueue();
        var source: Future<int32> = new Future<int32>();
        source.complete(42);
        var steps: TwoSteps = new TwoSteps(queue, source);
        steps.dispatch.resume();
        queue.next();
        if (steps.count != 1 || queue.pending() != 1) { return 1; }
        queue.next();
        if (steps.count != 2 || queue.pending() != 0) { return 2; }
        return 0;
    """.trimIndent())

    /** 已排队后终止，陈旧任务不能重新执行该状态。 */
    @Test
    fun `finish invalidates queued work`() = run("finished", """
        var queue: StepQueue = new StepQueue();
        var source: Future<int32> = new Future<int32>();
        var steps: TwoSteps = new TwoSteps(queue, source);
        steps.dispatch.resume();
        steps.dispatch.finish();
        queue.next();
        if (steps.count != 0) { return 1; }
        if (steps.dispatch.resume()) { return 2; }
        return 0;
    """.trimIndent())

    /** 拒绝必须能被任务桥观察，且不能让调度门一直卡在 queued。 */
    @Test
    fun `scheduling rejection closes dispatch and propagates`() = run("rejected", """
        var queue: RejectQueue = new RejectQueue();
        var segment: ThrowingStep = new ThrowingStep();
        var dispatch: ContinuationDispatch = new ContinuationDispatch(queue, segment);
        try {
            dispatch.resume();
            return 1;
        } catch (error: Error) {
            if (error.message() != "queue rejected") { return 2; }
        }
        if (dispatch.resume()) { return 3; }
        if (segment.count != 0) { return 4; }
        return 0;
    """.trimIndent())

    /** 执行段失败不能因晚到唤醒而从头重放，原异常应向执行器传播。 */
    @Test
    fun `throwing segment is never replayed`() = run("throwing", """
        var queue: StepQueue = new StepQueue();
        var segment: ThrowingStep = new ThrowingStep();
        var dispatch: ContinuationDispatch = new ContinuationDispatch(queue, segment);
        dispatch.resume();
        try {
            queue.next();
            return 1;
        } catch (error: Error) {
            if (error.message() != "segment failed") { return 2; }
        }
        if (dispatch.resume()) { return 3; }
        dispatch.run();
        if (segment.count != 1 || queue.pending() != 0) { return 4; }
        return 0;
    """.trimIndent())

    /** 当前段退出后的重新入队拒绝发生在调度门 finally，仍必须终止已绑定结果。 */
    @Test
    fun `deferred requeue rejection fails bound result`() = run("requeue-result", """
        var queue: RejectResumeQueue = new RejectResumeQueue();
        var segment: WakeWhileRunning = new WakeWhileRunning(queue);
        var result: Future<int32> = new Future<int32>();
        result.onComplete(new BadFailureNotice());
        segment.dispatch.bindResult(result);
        segment.dispatch.resume();
        try { queue.first(); return 1; } catch (error: Error) { }
        if (result.status() != FutureStatus.Failed) { return 2; }
        var snapshot: Failure? = result.failureOrNull();
        var failure: Failure = cast snapshot as Failure;
        if (failure.kind() != "schedule") { return 3; }
        result.awaitTaskSettled();
        if (segment.dispatch.resume()) { return 4; }
        return 0;
    """.trimIndent())

    /** 执行段异常也应出现在 Future 中，而不是只落在执行器的未捕获异常通道。 */
    @Test
    fun `segment exception fails bound result`() = run("segment-result", """
        var queue: StepQueue = new StepQueue();
        var segment: ThrowingStep = new ThrowingStep();
        var dispatch: ContinuationDispatch = new ContinuationDispatch(queue, segment);
        var result: Future<int32> = new Future<int32>();
        dispatch.bindResult(result);
        dispatch.resume();
        try { queue.next(); return 1; } catch (error: Error) { }
        if (result.status() != FutureStatus.Failed) { return 2; }
        var snapshot: Failure? = result.failureOrNull();
        var failure: Failure = cast snapshot as Failure;
        if (failure.kind() != "platform") { return 3; }
        result.awaitTaskSettled();
        if (dispatch.resume()) { return 4; }
        return 0;
    """.trimIndent())

    /** 绑定必须发生在启动前且只有一次，不能把运行中的结果替换到另一个 Future。 */
    @Test
    fun `result binding cannot be replaced or installed after start`() = run("bind-once", """
        var queue: StepQueue = new StepQueue();
        var segment: ThrowingStep = new ThrowingStep();
        var dispatch: ContinuationDispatch = new ContinuationDispatch(queue, segment);
        var first: Future<int32> = new Future<int32>();
        dispatch.bindResult(first);
        try { dispatch.bindResult(new Future<int32>()); return 1; } catch (error: Error) { }
        dispatch.resume();
        try { dispatch.bindResult(new Future<int32>()); return 2; } catch (error: Error) { }
        try { queue.next(); return 3; } catch (error: Error) { }
        if (first.status() != FutureStatus.Failed) { return 4; }
        return 0;
    """.trimIndent())
}
