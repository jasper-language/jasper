// ============================================================================
// TryCatchCoroutinePipelineTest.kt —— S4-C 第十八片「协程帧内 try/catch」端到端验收
// ============================================================================
// 做什么：用真实 ANTLR 解析、类型检查、挂起效果分析、协程降级、IR 与 JVM 后端编译
//   含 try/catch/finally 的 Jasper 函数：外层 try/catch(Error)/finally 包住三段式 for 与 gate
//   挂起点，for 体内的 try 在 await input 之后可能除零，按 FutureFailureError / FutureCancelledError
//   分别处理（取消处理块内再 await gate），内层 finally 记录清理。加载生成帧在真实 JvmExecutor
//   上运行：被等待 Future 的失败 / 取消按 Future.get() 语义进入匹配的 catch；未匹配的平台异常
//   （除零）穿过两层 catch 区域、清理后发布 platform 失败；本任务自身的取消（作用域取消）对 catch
//   不可见（2026-10-28 裁决 C），只执行 finally；并以固定种子与 Kotlin oracle 比对。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做反射驱动与有界等待，异常路由、处理块与
//   清理均由生成的 Jasper AST 表达。
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

/** 验证 try/catch 生成帧的异常路由语义。 */
class TryCatchCoroutinePipelineTest {

    /**
     * 被测函数：rows 行，每行在内层 try 内 await input，total 加 `got * 10 / (got - r)`（got == r 时
     * 除零，平台异常不是 Error），超过 1000 时在两层区域内 return；input 失败时 FutureFailureError
     * 处理块加 100 并 mark(3)，mode 为 1 时 break；input 取消时 FutureCancelledError 处理块 mark(4)
     * 后 await gate 并累加；内层 finally mark(2)。循环后 mode 为 2 时调用 boom() 抛出 Error；随后
     * await gate 返回 total + g。外层 catch (Error) mark(5) 并返回 -total；外层 finally mark(1)。
     */
    private fun program() = """
        package demo;
        import std.Future;
        import std.Error;
        import std.FutureFailureError;
        import std.FutureCancelledError;

        public class Program {
            private static var count: int32 = 0;
            private static var last: int32 = 0;

            public static function mark(code: int32): int32 {
                Program.count = Program.count + 1;
                Program.last = Program.last % 100 * 10 + code;
                return code;
            }

            public static function count(): int32 { return Program.count; }

            public static function last(): int32 { return Program.last; }

            public static function divide(a: int32, b: int32): int32 { return a / b; }

            public static function boom(): int32 { throw new Error("boom"); }

            public static function run(input: Future<int32>, gate: Future<int32>, rows: int32, mode: int32): int32 {
                var total: int32 = 0;
                try {
                    for (var r: int32 = 0; r < rows; r++) {
                        try {
                            var got: int32 = await input;
                            total = total + Program.divide(got * 10, got - r);
                            if (total > 1000) {
                                return total;
                            }
                        } catch (e: FutureFailureError) {
                            total = total + 100;
                            var m3: int32 = Program.mark(3);
                            if (mode == 1) {
                                break;
                            }
                        } catch (e: FutureCancelledError) {
                            var m4: int32 = Program.mark(4);
                            var again: int32 = await gate;
                            total = total + again;
                        } finally {
                            Program.mark(2);
                        }
                    }
                    if (mode == 2) {
                        var b: int32 = Program.boom();
                    }
                    var g: int32 = await gate;
                    return total + g;
                } catch (e: Error) {
                    var m5: int32 = Program.mark(5);
                    return -total;
                } finally {
                    Program.mark(1);
                }
            }
        }
    """.trimIndent()

    /** 被等待 Future 的终态：成功值、失败或取消。 */
    private sealed class Settle {
        data class Value(val value: Int) : Settle()
        object Fail : Settle() { override fun toString() = "Fail" }
        object Cancel : Settle() { override fun toString() = "Cancel" }
    }

    /** oracle 结果：成功值或失败（null），以及本次调用期间的清理 / 处理次数与最近三次编号。 */
    private data class Outcome(val value: Int?, val marks: Int, val last: Int)

    /** oracle 中与 Future.get() 对应的错误：业务失败与取消都是 Error 的子类，除零不是。 */
    private open class OracleError : RuntimeException()
    private class OracleFailure : OracleError()
    private class OracleCancelled : OracleError()

    /** 与源码同义的 Kotlin oracle；只描述语义，不参与生成。[lastBefore] 是调用前的最近编号。 */
    private fun oracle(rows: Int, input: Settle, gate: Settle, mode: Int, lastBefore: Int): Outcome {
        var marks = 0
        var last = lastBefore
        fun mark(code: Int) { marks++; last = last % 100 * 10 + code }
        fun get(settle: Settle): Int = when (settle) {
            is Settle.Value -> settle.value
            Settle.Fail -> throw OracleFailure()
            Settle.Cancel -> throw OracleCancelled()
        }
        fun body(): Int {
            var total = 0
            try {
                for (r in 0 until rows) {
                    try {
                        val got = get(input)
                        total += got * 10 / (got - r)
                        if (total > 1000) return total
                    } catch (_: OracleFailure) {
                        total += 100
                        mark(3)
                        if (mode == 1) break
                    } catch (_: OracleCancelled) {
                        mark(4)
                        val again = get(gate)
                        total += again
                    } finally {
                        mark(2)
                    }
                }
                if (mode == 2) throw OracleError()
                val g = get(gate)
                return total + g
            } catch (_: OracleError) {
                mark(5)
                return -total
            } finally {
                mark(1)
            }
        }
        val value = try {
            body()
        } catch (_: ArithmeticException) {
            null
        }
        return Outcome(value, marks, last)
    }

    private val root: Path = Paths.get("build", "tmp", "try-catch-coroutine-pipeline")

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
        val failureType: Class<*> = loader.loadClass("std.Failure")
        val executor: Any = loader.loadClass("std.JvmExecutor")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(1)
        private val program = loader.loadClass("demo.Program")

        fun newFuture(): Any = future.getConstructor().newInstance()
        fun complete(target: Any, value: Int) { future.getMethod("complete", Any::class.java).invoke(target, value) }
        fun fail(target: Any, failure: Any) { future.getMethod("fail", failureType).invoke(target, failure) }
        fun cancel(target: Any) { future.getMethod("cancel").invoke(target) }
        fun failure(message: String): Any =
            failureType.getConstructor(String::class.java, String::class.java).newInstance(message, "error")
        fun isDone(target: Any) = future.getMethod("isDone").invoke(target) as Boolean
        fun status(target: Any) = future.getMethod("status").invoke(target).toString()
        fun value(target: Any) = future.getMethod("get").invoke(target)
        fun failureOf(target: Any): Any? = future.getMethod("failureOrNull").invoke(target)
        fun kind(target: Any) = failureType.getMethod("kind").invoke(failureOf(target))
        fun marks() = program.getMethod("count").invoke(null) as Int
        fun last() = program.getMethod("last").invoke(null) as Int

        /** 按 [settle] 发布终态。 */
        fun settle(target: Any, settle: Settle) = when (settle) {
            is Settle.Value -> complete(target, settle.value)
            Settle.Fail -> fail(target, failure("seeded"))
            Settle.Cancel -> cancel(target)
        }

        /** 构造并启动一个生成帧；参数布局 (Executor, output, 原参数...) 由发射器约定。 */
        fun start(result: Any, input: Any, gate: Any, rows: Int, mode: Int): Any {
            val frame = loader.loadClass("demo.Program_run_Coroutine").getConstructor(
                loader.loadClass("std.Executor"), future,
                future, future, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).newInstance(executor, result, input, gate, rows, mode)
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

    /** 以已发布终态的 input / gate 运行一次并与 oracle 比对。 */
    private fun checkAgainstOracle(rt: Runtime, rows: Int, input: Settle, gate: Settle, mode: Int, context: String) {
        val marksBefore = rt.marks()
        val expected = oracle(rows, input, gate, mode, rt.last())
        val result = rt.newFuture()
        rt.start(result, rt.newFuture().also { rt.settle(it, input) }, rt.newFuture().also { rt.settle(it, gate) }, rows, mode)
        rt.awaitDone(result, context)
        if (expected.value == null) {
            assertEquals("Failed", rt.status(result), context)
            assertEquals("platform", rt.kind(result), context)
        } else {
            assertEquals(expected.value, rt.value(result), context)
        }
        assertEquals(expected.marks, rt.marks() - marksBefore, context)
        assertEquals(expected.last, rt.last(), context)
        rt.awaitSettled(result)
    }

    /**
     * 做什么：单工作线程，rows=2：第 0 行挂在内层 try 内的 input 上；input 失败后两行都进入
     *   FutureFailureError 处理块（不进入取消处理块与外层 catch），各执行一次内层 finally，随后挂在
     *   外层 try 内的 gate 上；gate=5 后结果 200 + 5 = 205，外层 finally 最后执行。生成类 major 52。
     * 失败模式：失败被发布而不是被捕获、匹配到错误的处理块、处理块落在 catch 区域内、清理次数或
     *   顺序错。
     */
    @Test
    fun `awaited failures are caught by the matching handler and execution continues`() {
        withRuntime("caught") { rt ->
            val bytes = Files.readAllBytes(root.resolve("caught/demo/Program_run_Coroutine.class"))
            assertEquals(52, ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF))
            val input = rt.newFuture()
            val gate = rt.newFuture()
            val result = rt.newFuture()
            val marksBefore = rt.marks()
            val lastBefore = rt.last()
            rt.start(result, input, gate, 2, 0)
            rt.assertStillPending(result, "input await inside the inner try")
            rt.fail(input, rt.failure("input failed"))
            rt.assertStillPending(result, "gate await after both rows recovered")
            assertEquals(4, rt.marks() - marksBefore, "each row must run the failure handler and the inner cleanup once")
            assertEquals(232, rt.last())
            rt.complete(gate, 5)
            rt.awaitDone(result, "caught")
            assertEquals(205, rt.value(result))
            assertEquals(5, rt.marks() - marksBefore)
            assertEquals(321, rt.last(), "outer cleanup must run last")
            assertEquals(oracle(2, Settle.Fail, Settle.Value(5), 0, lastBefore), Outcome(205, 5, 321))
            rt.awaitSettled(result)
        }
    }

    /**
     * 做什么：被等待 Future 的取消按 FutureCancelledError 进入取消处理块，处理块内再挂起在 gate 上；
     *   gate 失败时处理块中的 FutureFailureError 不被同一 try 的 catch 捕获（处理块在 catch 区域之外），
     *   穿过内层 finally 被外层 catch (Error) 捕获，返回 -total；mode 1 时失败处理块的 break 只清理内层。
     *   boom() 抛出的源码 Error 被外层 catch 捕获。
     * 失败模式：取消被当作业务失败、处理块的失败被自身 try 捕获、break 多清理外层、源码错误漏捕获。
     */
    @Test
    fun `cancelled awaits handlers that suspend and source errors route through regions`() {
        withRuntime("routes") { rt ->
            // 取消处理块挂在 gate 上：此时 mark(4) 已执行、内层 finally 尚未执行。
            val gate = rt.newFuture()
            val result = rt.newFuture()
            val marksBefore = rt.marks()
            rt.start(result, rt.newFuture().also { rt.cancel(it) }, gate, 1, 0)
            rt.assertStillPending(result, "gate await inside the cancellation handler")
            assertEquals(1, rt.marks() - marksBefore)
            assertEquals(4, rt.last() % 10)
            rt.complete(gate, 7)
            rt.awaitDone(result, "cancel handler")
            // 处理块 total = 7，内层清理；循环结束后 await gate 再得 7：7 + 7 = 14。
            assertEquals(14, rt.value(result))
            assertEquals(3, rt.marks() - marksBefore)
            assertEquals(421, rt.last())
            rt.awaitSettled(result)

            for ((rows, input, gateSettle, mode) in listOf(
                Quad(2, Settle.Cancel, Settle.Fail, 0),
                Quad(3, Settle.Fail, Settle.Value(4), 1),
                Quad(2, Settle.Value(9), Settle.Value(4), 2),
                Quad(1, Settle.Value(9), Settle.Cancel, 0),
                Quad(4, Settle.Value(600), Settle.Value(4), 0)
            )) {
                checkAgainstOracle(rt, rows, input, gateSettle, mode, "rows=$rows input=$input gate=$gateSettle mode=$mode")
            }
        }
    }

    private data class Quad(val rows: Int, val input: Settle, val gate: Settle, val mode: Int)

    /**
     * 做什么：除零（平台异常，不是 Error）不被 FutureFailureError / FutureCancelledError / Error 中任何
     *   一个捕获：内层、外层 finally 各执行一次后发布 platform 失败；未被匹配的挂起点失败（只有外层
     *   catch 匹配时）仍被外层捕获。
     * 失败模式：catch 按 Throwable 捕获了平台异常、未匹配时漏清理、发布在清理之前。
     */
    @Test
    fun `unmatched platform errors pass every catch region after cleanups`() {
        withRuntime("unmatched") { rt ->
            val marksBefore = rt.marks()
            val result = rt.newFuture()
            // got = 1、r = 1 时除零：第 0 行 10 / 1 = 10，第 1 行除零。
            rt.start(result, rt.newFuture().also { rt.complete(it, 1) }, rt.newFuture(), 2, 0)
            rt.awaitDone(result, "division by zero")
            assertEquals("Failed", rt.status(result))
            assertEquals("platform", rt.kind(result))
            assertEquals(3, rt.marks() - marksBefore, "two inner cleanups and the outer cleanup before publication")
            assertEquals(221, rt.last())
            rt.awaitSettled(result)
        }
    }

    /**
     * 做什么：本任务自身的取消对 catch 不可见（裁决 C）：帧挂在内层 try 内（input）时作用域取消，
     *   只执行内层、外层 finally（mark 2、1），不执行任何处理块（mark 3/4/5）；挂在取消处理块内（gate）
     *   时作用域取消同样只执行两层 finally；晚到输入不复活帧、不重跑处理块与清理。
     * 失败模式：取消以异常形式进入 catch（处理块执行、结果被改写）、清理缺失或重复、settled 早于清理。
     */
    @Test
    fun `own scope cancellation is invisible to catch and only runs finally`() {
        withRuntime("cancel") { rt ->
            val input = rt.newFuture()
            val scoped = rt.newFuture()
            var marksBefore = rt.marks()
            rt.start(scoped, input, rt.newFuture(), 3, 0)
            rt.assertStillPending(scoped, "before scope cancel")
            rt.cancel(scoped)
            rt.awaitSettled(scoped)
            assertEquals("Cancelled", rt.status(scoped))
            assertEquals(2, rt.marks() - marksBefore, "only the two finally blocks may run")
            assertEquals(21, rt.last() % 100)
            rt.fail(input, rt.failure("late"))
            Thread.sleep(50)
            assertEquals("Cancelled", rt.status(scoped), "late input revived a cancelled frame")
            assertEquals(2, rt.marks() - marksBefore, "cancelled frame ran a handler or a cleanup again")

            val gate = rt.newFuture()
            val inHandler = rt.newFuture()
            marksBefore = rt.marks()
            rt.start(inHandler, rt.newFuture().also { rt.cancel(it) }, gate, 1, 0)
            rt.assertStillPending(inHandler, "suspended in the cancellation handler")
            assertEquals(1, rt.marks() - marksBefore)
            rt.cancel(inHandler)
            rt.awaitSettled(inHandler)
            assertEquals("Cancelled", rt.status(inHandler))
            assertEquals(3, rt.marks() - marksBefore, "handler mark, inner and outer finally")
            assertEquals(421, rt.last())
            rt.complete(gate, 1)
            Thread.sleep(50)
            assertEquals(3, rt.marks() - marksBefore)
        }
    }

    /**
     * 做什么：固定种子 20261111 的 64 轮：随机 rows（0..5）/ input 与 gate（成功值含除零与 return 量级、
     *   失败、取消）/ mode（0..2）/ 每个 Future 在启动前或启动后发布，结果、处理与清理次数、最近三次编号
     *   与 Kotlin oracle 比对。
     * 失败模式：打印种子、轮次与全部参数；单轮即最小反例。
     */
    @Test
    fun `seeded try catch frames match an independent oracle`() {
        val seed = 20261111
        val random = kotlin.random.Random(seed)
        fun settle(): Settle = when (random.nextInt(6)) {
            0 -> Settle.Fail
            1 -> Settle.Cancel
            2 -> Settle.Value(random.nextInt(300, 700))
            else -> Settle.Value(random.nextInt(-3, 6))
        }
        withRuntime("seeded") { rt ->
            repeat(64) { round ->
                val rows = random.nextInt(0, 6)
                val input = settle()
                val gate = settle()
                val mode = random.nextInt(0, 3)
                val inputEarly = random.nextBoolean()
                val gateEarly = random.nextBoolean()
                val context = "seed=$seed round=$round rows=$rows input=$input/$inputEarly gate=$gate/$gateEarly mode=$mode"
                val marksBefore = rt.marks()
                val expected = oracle(rows, input, gate, mode, rt.last())
                val inputFuture = rt.newFuture().also { if (inputEarly) rt.settle(it, input) }
                val gateFuture = rt.newFuture().also { if (gateEarly) rt.settle(it, gate) }
                val result = rt.newFuture()
                rt.start(result, inputFuture, gateFuture, rows, mode)
                if (!inputEarly) rt.settle(inputFuture, input)
                if (!gateEarly) rt.settle(gateFuture, gate)
                rt.awaitDone(result, context)
                if (expected.value == null) {
                    assertEquals("Failed", rt.status(result), context)
                    assertEquals("platform", rt.kind(result), context)
                } else {
                    assertEquals(expected.value, rt.value(result), context)
                }
                assertEquals(expected.marks, rt.marks() - marksBefore, context)
                assertEquals(expected.last, rt.last(), context)
                rt.awaitSettled(result)
            }
        }
    }
}
