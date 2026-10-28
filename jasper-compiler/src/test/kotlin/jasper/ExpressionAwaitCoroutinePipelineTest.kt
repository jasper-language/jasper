// ============================================================================
// ExpressionAwaitCoroutinePipelineTest.kt —— S4-C 第十三片「条件、return 值与子表达式中的 await」端到端验收
// ============================================================================
// 做什么：用真实 ANTLR 解析、类型检查、挂起效果分析、协程降级、IR 与 JVM 后端编译
//   一个 await 出现在 while 条件（每趟重新挂起）、算术子表达式、`!` 下的 if 条件、
//   三段式 for 步进段子表达式、do-while 条件与 return 值中的 Jasper 函数；加载生成帧在
//   真实 JvmExecutor 上运行：单工作线程逐个挂起/恢复且挂起前表达式只执行一次、输入
//   已完成时多趟同步恢复、条件挂起点的失败与挂起中被作用域取消，以及固定种子与
//   Kotlin oracle 比对。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做反射驱动与有界等待，程序计数器、
//   回边、临时局部、Future 回调与结果发布均由生成的 Jasper AST 表达。
//
// 前置条件：构建虚拟机可运行 JDK；scratch 目录都在模块 build/ 下。
// 失败模式：编译诊断、类加载、反射签名或超时都由断言直接报告，不静默跳过。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 验证表达式内 await 经提升后的生成帧运行语义。 */
class ExpressionAwaitCoroutinePipelineTest {

    /**
     * 被测函数：while 每趟在条件里 await gate，与趟号比较；循环体在乘法子表达式里 await
     * input；if 条件在 `!` 下 await input；for 步进段在子表达式里 await gate；do-while 条件
     * await input；return 值在局部读取之后 await gate。
     */
    private fun program() = """
        package demo;
        import std.Future;

        public class Program {
            private static var steps: int32 = 0;

            public static function step(value: int32): int32 {
                Program.steps = Program.steps + 1;
                return value;
            }

            public static function steps(): int32 { return Program.steps; }

            public static function mix(input: Future<int32>, gate: Future<int32>, limit: int32): int32 {
                var before: int32 = Program.step(limit);
                var total: int32 = 0;
                var i: int32 = 0;
                while ((await gate) > i) {
                    total = total + (await input) * 2 - i;
                    i = i + 1;
                }
                if (!((await input) > limit)) {
                    total = total - 1;
                }
                for (var k: int32 = 0; k < 3; k = (await gate) * 0 + k + 1) {
                    total = total + k;
                }
                do {
                    i = i - 1;
                } while ((await input) + i > limit);
                return before + total + (await gate);
            }
        }
    """.trimIndent()

    /** 与源码同义的 Kotlin oracle；只描述语义，不参与生成。 */
    private fun oracle(limit: Int, input: Int, gate: Int): Int {
        var total = 0
        var i = 0
        while (gate > i) {
            total = total + input * 2 - i
            i += 1
        }
        if (!(input > limit)) total -= 1
        for (k in 0 until 3) total += k
        do {
            i -= 1
        } while (input + i > limit)
        return limit + total + gate
    }

    private val root: Path = Paths.get("build", "tmp", "expression-await-coroutine-pipeline")

    /** 在模块 build 下编译标准库 + 源码；编译失败带聚合诊断。 */
    private fun compile(name: String): URLClassLoader {
        val output = root.resolve(name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Program.jas" to program()), output)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " +
                ((result as? JasperCompiler.CompileResult.Error)?.message ?: result.toString())
        )
        return URLClassLoader(arrayOf(output.toUri().toURL()))
    }

    /** 反射句柄集合，避免每个测试重复查找。 */
    private class Runtime(val loader: ClassLoader) {
        val future: Class<*> = loader.loadClass("std.Future")
        val executor: Any = loader.loadClass("std.JvmExecutor")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(1)

        fun newFuture(): Any = future.getConstructor().newInstance()
        fun complete(target: Any, value: Int) { future.getMethod("complete", Any::class.java).invoke(target, value) }
        fun isDone(target: Any) = future.getMethod("isDone").invoke(target) as Boolean
        fun status(target: Any) = future.getMethod("status").invoke(target).toString()
        fun value(target: Any) = future.getMethod("get").invoke(target)
        fun steps() = loader.loadClass("demo.Program").getMethod("steps").invoke(null) as Int

        /** 构造并启动一个生成帧；参数布局 (Executor, output, 原参数...) 由发射器约定。 */
        fun start(result: Any, input: Any, gate: Any, limit: Int): Any {
            val frame = loader.loadClass("demo.Program_mix_Coroutine").getConstructor(
                loader.loadClass("std.Executor"), future,
                future, future, Int::class.javaPrimitiveType
            ).newInstance(executor, result, input, gate, limit)
            frame.javaClass.getMethod("start").invoke(frame)
            return frame
        }

        /** 有界等待终态。 */
        fun awaitDone(target: Any, mode: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!isDone(target) && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(isDone(target), "generated frame did not finish: $mode")
        }

        /** 有界等待 settled 回收；另起线程，缺陷不会卡住整个测试套件。 */
        fun awaitSettled(target: Any) {
            val pool = Executors.newSingleThreadExecutor()
            try {
                pool.submit { future.getMethod("awaitTaskSettled").invoke(target) }.get(5, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }
        }

        /** 有界确认帧仍挂起：给工作线程时间跑完当前段，期间结果不得终结。 */
        fun assertStillPending(target: Any, mode: String) {
            Thread.sleep(50)
            assertFalse(isDone(target), "frame must stay suspended: $mode")
        }

        fun shutdown() {
            executor.javaClass.getMethod("shutdown").invoke(executor)
            assertEquals(true, executor.javaClass.getMethod("awaitTermination", Long::class.javaPrimitiveType)
                .invoke(executor, 5000L))
        }
    }

    /** 带运行时执行测试体并保证关闭执行器。 */
    private fun withRuntime(name: String, body: (Runtime) -> Unit) {
        compile(name).use { loader ->
            val runtime = Runtime(loader)
            try {
                body(runtime)
            } finally {
                runtime.shutdown()
            }
        }
    }

    /**
     * 做什么：单工作线程，gate 与 input 都晚到：帧先挂在 while 条件的 gate 上；补齐 gate=2
     *   后挂在循环体子表达式的 input 上；补齐 input=5 后其余挂起点同步恢复跑完。挂起前
     *   表达式只执行一次；生成类 major version 52。
     * 失败模式：条件在挂起前用旧值分支、恢复重放前段、子表达式求值顺序被改变或结果错。
     */
    @Test
    fun `condition and subexpression awaits suspend and resume in order`() {
        withRuntime("ordered") { rt ->
            val bytes = Files.readAllBytes(root.resolve("ordered/demo/Program_mix_Coroutine.class"))
            assertEquals(52, ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF))
            val input = rt.newFuture()
            val gate = rt.newFuture()
            val result = rt.newFuture()
            rt.start(result, input, gate, 4)
            rt.assertStillPending(result, "while condition await")
            assertEquals(1, rt.steps())
            rt.complete(gate, 2)
            rt.assertStillPending(result, "subexpression await in the while body")
            rt.complete(input, 5)
            rt.awaitDone(result, "ordered")
            // while 两趟：10 + 9 = 19；5 > 4 不减；for +3；do-while 至少一趟；4 + 22 + 2。
            assertEquals(28, rt.value(result))
            assertEquals(oracle(4, 5, 2), 28)
            assertEquals(1, rt.steps(), "pre-loop expression re-executed after a resume")
            rt.awaitSettled(result)
        }
    }

    /**
     * 做什么：输入在启动前已完成时，while 条件每趟都经同步回调恢复（150 趟），do-while
     *   条件也多趟挂起恢复；gate 为负时 while 零趟。结果与 oracle 一致。
     * 失败模式：条件挂起点在回边处丢失恢复、零趟循环仍执行循环体。
     */
    @Test
    fun `ready inputs drive condition suspensions across many back edges`() {
        withRuntime("ready") { rt ->
            for ((limit, input, gate) in listOf(Triple(3, 1, 150), Triple(-40, 2, 6), Triple(0, 0, -3))) {
                val result = rt.newFuture()
                rt.start(result, rt.newFuture().also { rt.complete(it, input) },
                    rt.newFuture().also { rt.complete(it, gate) }, limit)
                rt.awaitDone(result, "limit=$limit input=$input gate=$gate")
                assertEquals(oracle(limit, input, gate), rt.value(result), "limit=$limit input=$input gate=$gate")
                rt.awaitSettled(result)
            }
        }
    }

    /**
     * 做什么：while 条件的 gate 失败时原样发布 Failure；挂在 while 条件上的帧被作用域取消后
     *   回收，晚到输入不复活帧、不重跑任何段。
     * 失败模式：条件恢复块未消费失败快照（把失败当 0 继续）、取消后 settled 不回收或被复活。
     */
    @Test
    fun `failure and scope cancel at a condition suspension`() {
        withRuntime("terminal") { rt ->
            val failure = rt.loader.loadClass("std.Failure")
                .getConstructor(String::class.java, String::class.java).newInstance("expected", "error")
            val failed = rt.newFuture()
            val failingGate = rt.newFuture()
            rt.start(failed, rt.newFuture(), failingGate, 1)
            rt.assertStillPending(failed, "before gate failure")
            rt.future.getMethod("fail", rt.loader.loadClass("std.Failure")).invoke(failingGate, failure)
            rt.awaitDone(failed, "failed")
            assertSame(failure, rt.future.getMethod("failureOrNull").invoke(failed))
            rt.awaitSettled(failed)

            val scoped = rt.newFuture()
            val lateInput = rt.newFuture()
            val lateGate = rt.newFuture()
            rt.start(scoped, lateInput, lateGate, 1)
            rt.assertStillPending(scoped, "before scope cancel")
            rt.future.getMethod("cancel").invoke(scoped)
            rt.awaitSettled(scoped)
            rt.complete(lateGate, 3)
            rt.complete(lateInput, 3)
            Thread.sleep(50)
            assertEquals("Cancelled", rt.status(scoped), "late input revived a cancelled frame")
            assertEquals(2, rt.steps(), "cancelled frame re-ran a segment")
        }
    }

    /**
     * 做什么：固定种子 20261101 的 64 轮：随机 limit / input / gate（含零趟与负值）、每个输入
     *   在启动前或启动后完成，结果与 Kotlin oracle 比对。
     * 失败模式：打印种子、轮次与全部参数；单轮即最小反例。
     */
    @Test
    fun `seeded expression await frames match an independent oracle`() {
        val seed = 20261101
        val random = kotlin.random.Random(seed)
        withRuntime("seeded") { rt ->
            repeat(64) { round ->
                val limit = random.nextInt(-8, 9)
                val inputValue = random.nextInt(-6, 7)
                val gateValue = random.nextInt(-3, 40)
                val inputEarly = random.nextBoolean()
                val gateEarly = random.nextBoolean()
                val context = "seed=$seed round=$round limit=$limit input=$inputValue/$inputEarly gate=$gateValue/$gateEarly"
                val input = rt.newFuture().also { if (inputEarly) rt.complete(it, inputValue) }
                val gate = rt.newFuture().also { if (gateEarly) rt.complete(it, gateValue) }
                val result = rt.newFuture()
                rt.start(result, input, gate, limit)
                // while 条件第一个求值的就是 gate：晚到时帧必然挂在它上面。
                if (!gateEarly) rt.assertStillPending(result, context)
                if (!gateEarly) rt.complete(gate, gateValue)
                // gate 已有、input 晚到：之后必经 if 条件中的 input 挂起点。
                if (!inputEarly) rt.assertStillPending(result, context)
                if (!inputEarly) rt.complete(input, inputValue)
                rt.awaitDone(result, context)
                assertEquals(oracle(limit, inputValue, gateValue), rt.value(result), context)
                rt.awaitSettled(result)
            }
        }
    }
}
