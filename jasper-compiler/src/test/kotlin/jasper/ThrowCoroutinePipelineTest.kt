// ============================================================================
// ThrowCoroutinePipelineTest.kt —— S4-C 第二十片「协程帧内 throw」端到端验收
// ============================================================================
// 做什么：用真实 ANTLR 解析、类型检查、挂起效果分析、协程降级、IR 与 JVM 后端编译两个含 throw
//   的 Jasper 函数并在真实 JvmExecutor 上运行生成帧：
//   - run：外层 try/catch(LimitError)/finally 包住三段式 for 与 gate 挂起点；for 体内的 try 在
//     await input 之后按值抛出 LimitError（被内层 catch 捕获并继续）或 Error（穿过内层 finally、
//     不被外层 LimitError 捕获）；内层处理块可再抛出 LimitError（处理块在 catch 区域之外，由外层
//     捕获）；gate 为 0 时在外层 try 内抛出未被捕获的 Error。
//   - plain：没有 catch 的帧：defer 作用域与 try/finally 内抛出；分支内先挂起在 gate 上，再按 gate
//     抛出 LimitError 或 Error（错误不是值，不能由函数返回，抛出值总是就地构造）。
//   未被捕获的 std.Error 发布为 Failure(message, "error")，与 TaskGroup.spawn 子任务体约定一致；
//   挂起点失败未被匹配时仍发布原 Failure 对象；并以固定种子与 Kotlin oracle 比对。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做反射驱动与有界等待，抛出、异常路由与清理
//   均由生成的 Jasper AST 表达。
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

/** 验证 throw 生成帧的抛出、路由与发布语义。 */
class ThrowCoroutinePipelineTest {

    /**
     * 被测源码。run：rows 行，每行内层 try 内 await input；got < 0 抛 LimitError("negative")，内层
     * catch (LimitError) mark(3)、total + 1，mode 为 1 时再抛 LimitError("rethrown")；否则 total 累加
     * got，超过 100 时抛 Error("overflow")；内层 finally mark(2)。循环后 await gate，为 0 时抛
     * Error("gate zero")，否则返回 total + g。外层 catch (LimitError) mark(5) 并返回 -total；外层
     * finally mark(1)。plain：await input 后 `defer Program.mark(7);`；got > limit 时 await gate，
     * gate > 0 抛 LimitError("picked")，否则抛 Error("picked-error")；
     * got == limit 时在 try/finally(mark 6) 内抛 LimitError("equal")；否则返回 got。
     */
    private fun program() = """
        package demo;
        import std.Future;
        import std.Error;

        public class LimitError extends Error {
            public constructor(message: string) {
                super(message);
            }
        }

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

            public static function run(input: Future<int32>, gate: Future<int32>, rows: int32, mode: int32): int32 {
                var total: int32 = 0;
                try {
                    for (var r: int32 = 0; r < rows; r++) {
                        try {
                            var got: int32 = await input;
                            if (got < 0) {
                                throw new LimitError("negative");
                            }
                            total = total + got;
                            if (total > 100) {
                                throw new Error("overflow");
                            }
                        } catch (e: LimitError) {
                            var m3: int32 = Program.mark(3);
                            total = total + 1;
                            if (mode == 1) {
                                throw new LimitError("rethrown");
                            }
                        } finally {
                            Program.mark(2);
                        }
                    }
                    var g: int32 = await gate;
                    if (g == 0) {
                        throw new Error("gate zero");
                    }
                    return total + g;
                } catch (e: LimitError) {
                    var m5: int32 = Program.mark(5);
                    return -total;
                } finally {
                    Program.mark(1);
                }
            }

            public static function plain(input: Future<int32>, gate: Future<int32>, limit: int32): int32 {
                var got: int32 = await input;
                defer Program.mark(7);
                if (got > limit) {
                    var code: int32 = await gate;
                    if (code > 0) {
                        throw new LimitError("picked");
                    }
                    throw new Error("picked-error");
                }
                try {
                    if (got == limit) {
                        throw new LimitError("equal");
                    }
                } finally {
                    Program.mark(6);
                }
                return got;
            }
        }
    """.trimIndent()

    /** 被等待 Future 的终态：成功值、失败或取消。 */
    private sealed class Settle {
        data class Value(val value: Int) : Settle()
        object Fail : Settle() { override fun toString() = "Fail" }
        object Cancel : Settle() { override fun toString() = "Cancel" }
    }

    /** 帧结果：成功值，或失败（消息 + 种类），或取消。 */
    private sealed class Result {
        data class Value(val value: Int) : Result()
        data class Failed(val message: String, val kind: String) : Result()
        object Cancelled : Result() { override fun toString() = "Cancelled" }
    }

    /** oracle 结果与本次调用期间的清理 / 处理次数与最近三次编号。 */
    private data class Outcome(val result: Result, val marks: Int, val last: Int)

    /** oracle 中源码错误：[limit] 为 true 表示 LimitError，否则是 Error 本身。 */
    private class OracleThrown(val limit: Boolean, message: String) : RuntimeException(message)
    /** oracle 中挂起点失败 / 取消（Future.get() 语义；源码只捕获 LimitError，二者都不会被捕获）。 */
    private class OracleAwait(val cancelled: Boolean) : RuntimeException()

    /** 种子失败的消息与种类；未被捕获时帧发布的就是这个对象本身。 */
    private val seededMessage = "seeded"

    /** 按源码语义执行 [body] 并把逃出的信号换成发布的终态。 */
    private fun settleOracle(body: () -> Int): Result = try {
        Result.Value(body())
    } catch (thrown: OracleThrown) {
        Result.Failed(requireNotNull(thrown.message), "error")
    } catch (failure: OracleAwait) {
        if (failure.cancelled) Result.Cancelled else Result.Failed(seededMessage, "error")
    }

    private fun get(settle: Settle): Int = when (settle) {
        is Settle.Value -> settle.value
        Settle.Fail -> throw OracleAwait(cancelled = false)
        Settle.Cancel -> throw OracleAwait(cancelled = true)
    }

    /** 与 run 同义的 Kotlin oracle；[lastBefore] 是调用前的最近编号。 */
    private fun runOracle(rows: Int, input: Settle, gate: Settle, mode: Int, lastBefore: Int): Outcome {
        var marks = 0
        var last = lastBefore
        fun mark(code: Int) { marks++; last = last % 100 * 10 + code }
        val result = settleOracle {
            var total = 0
            try {
                for (r in 0 until rows) {
                    try {
                        val got = get(input)
                        if (got < 0) throw OracleThrown(true, "negative")
                        total += got
                        if (total > 100) throw OracleThrown(false, "overflow")
                    } catch (thrown: OracleThrown) {
                        if (!thrown.limit) throw thrown
                        mark(3)
                        total += 1
                        if (mode == 1) throw OracleThrown(true, "rethrown")
                    } finally {
                        mark(2)
                    }
                }
                val g = get(gate)
                if (g == 0) throw OracleThrown(false, "gate zero")
                total + g
            } catch (thrown: OracleThrown) {
                if (!thrown.limit) throw thrown
                mark(5)
                -total
            } finally {
                mark(1)
            }
        }
        return Outcome(result, marks, last)
    }

    /** 与 plain 同义的 Kotlin oracle（defer 即包住其后语句的 finally）。 */
    private fun plainOracle(input: Settle, gate: Settle, limit: Int, lastBefore: Int): Outcome {
        var marks = 0
        var last = lastBefore
        fun mark(code: Int) { marks++; last = last % 100 * 10 + code }
        val result = settleOracle {
            val got = get(input)
            try {
                if (got > limit) {
                    val code = get(gate)
                    throw if (code > 0) OracleThrown(true, "picked") else OracleThrown(false, "picked-error")
                }
                try {
                    if (got == limit) throw OracleThrown(true, "equal")
                } finally {
                    mark(6)
                }
                got
            } finally {
                mark(7)
            }
        }
        return Outcome(result, marks, last)
    }

    private val root: Path = Paths.get("build", "tmp", "throw-coroutine-pipeline")

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
    private inner class Runtime(val loader: ClassLoader) {
        val future: Class<*> = loader.loadClass("std.Future")
        val failureType: Class<*> = loader.loadClass("std.Failure")
        val executor: Any = loader.loadClass("std.JvmExecutor")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(1)
        private val program = loader.loadClass("demo.Program")
        private val executorType = loader.loadClass("std.Executor")

        fun newFuture(): Any = future.getConstructor().newInstance()
        fun complete(target: Any, value: Int) { future.getMethod("complete", Any::class.java).invoke(target, value) }
        fun fail(target: Any, failure: Any) { future.getMethod("fail", failureType).invoke(target, failure) }
        fun cancel(target: Any) { future.getMethod("cancel").invoke(target) }
        fun failure(): Any =
            failureType.getConstructor(String::class.java, String::class.java).newInstance(seededMessage, "error")
        fun isDone(target: Any) = future.getMethod("isDone").invoke(target) as Boolean
        fun failureOf(target: Any): Any? = future.getMethod("failureOrNull").invoke(target)
        fun marks() = program.getMethod("count").invoke(null) as Int
        fun last() = program.getMethod("last").invoke(null) as Int

        /** 读出已终结结果。 */
        fun result(target: Any): Result = when (future.getMethod("status").invoke(target).toString()) {
            "Completed" -> Result.Value(future.getMethod("get").invoke(target) as Int)
            "Failed" -> {
                val failure = requireNotNull(failureOf(target))
                Result.Failed(failureType.getMethod("message").invoke(failure) as String,
                    failureType.getMethod("kind").invoke(failure) as String)
            }
            "Cancelled" -> Result.Cancelled
            else -> error("result not settled")
        }

        /** 按 [settle] 发布终态。 */
        fun settle(target: Any, settle: Settle) = when (settle) {
            is Settle.Value -> complete(target, settle.value)
            Settle.Fail -> fail(target, failure())
            Settle.Cancel -> cancel(target)
        }

        /** 构造并启动 run 的生成帧；参数布局 (Executor, output, 原参数...) 由发射器约定。 */
        fun startRun(result: Any, input: Any, gate: Any, rows: Int, mode: Int) = start("demo.Program_run_Coroutine",
            listOf(future, future, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            listOf(result, input, gate, rows, mode))

        /** 构造并启动 plain 的生成帧。 */
        fun startPlain(result: Any, input: Any, gate: Any, limit: Int) = start("demo.Program_plain_Coroutine",
            listOf(future, future, Int::class.javaPrimitiveType!!), listOf(result, input, gate, limit))

        private fun start(className: String, parameterTypes: List<Class<*>>, args: List<Any>) {
            val frame = loader.loadClass(className)
                .getConstructor(executorType, future, *parameterTypes.toTypedArray())
                .newInstance(executor, *args.toTypedArray())
            frame.javaClass.getMethod("start").invoke(frame)
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

    /** 以已发布终态的 input / gate 运行 run 一次并与 oracle 比对。 */
    private fun checkRun(rt: Runtime, rows: Int, input: Settle, gate: Settle, mode: Int, context: String, early: Pair<Boolean, Boolean> = true to true) {
        val marksBefore = rt.marks()
        val expected = runOracle(rows, input, gate, mode, rt.last())
        val inputFuture = rt.newFuture().also { if (early.first) rt.settle(it, input) }
        val gateFuture = rt.newFuture().also { if (early.second) rt.settle(it, gate) }
        val result = rt.newFuture()
        rt.startRun(result, inputFuture, gateFuture, rows, mode)
        if (!early.first) rt.settle(inputFuture, input)
        if (!early.second) rt.settle(gateFuture, gate)
        rt.awaitDone(result, context)
        assertEquals(expected, Outcome(rt.result(result), rt.marks() - marksBefore, rt.last()), context)
        rt.awaitSettled(result)
    }

    /** 以已发布终态的 input / gate 运行 plain 一次并与 oracle 比对。 */
    private fun checkPlain(rt: Runtime, input: Settle, gate: Settle, limit: Int, context: String, early: Pair<Boolean, Boolean> = true to true) {
        val marksBefore = rt.marks()
        val expected = plainOracle(input, gate, limit, rt.last())
        val inputFuture = rt.newFuture().also { if (early.first) rt.settle(it, input) }
        val gateFuture = rt.newFuture().also { if (early.second) rt.settle(it, gate) }
        val result = rt.newFuture()
        rt.startPlain(result, inputFuture, gateFuture, limit)
        if (!early.first) rt.settle(inputFuture, input)
        if (!early.second) rt.settle(gateFuture, gate)
        rt.awaitDone(result, context)
        assertEquals(expected, Outcome(rt.result(result), rt.marks() - marksBefore, rt.last()), context)
        rt.awaitSettled(result)
    }

    /**
     * 做什么：单工作线程，rows=2，帧挂在内层 try 内的 input 上；input = -1 时两行都抛 LimitError 并被内层
     *   处理块捕获（mark 3、内层 finally mark 2），随后挂在 gate 上；gate=4 后返回 2 + 4 = 6，外层 finally
     *   最后执行。mode=1 时处理块再抛出的 LimitError 先执行内层 finally、再被外层 catch 捕获，返回 -1。
     *   生成类 major 52。
     * 失败模式：源码抛出被直接发布而不是被捕获、处理块的抛出被自身 try 捕获、清理次数或顺序错。
     */
    @Test
    fun `source throws are caught by matching handlers and execution continues`() {
        withRuntime("caught") { rt ->
            val bytes = Files.readAllBytes(root.resolve("caught/demo/Program_run_Coroutine.class"))
            assertEquals(52, ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF))
            val input = rt.newFuture()
            val gate = rt.newFuture()
            val result = rt.newFuture()
            val marksBefore = rt.marks()
            val lastBefore = rt.last()
            rt.startRun(result, input, gate, 2, 0)
            rt.assertStillPending(result, "input await inside the inner try")
            rt.complete(input, -1)
            rt.assertStillPending(result, "gate await after both rows recovered")
            assertEquals(4, rt.marks() - marksBefore, "each row runs the handler and the inner cleanup once")
            assertEquals(232, rt.last())
            rt.complete(gate, 4)
            rt.awaitDone(result, "caught")
            assertEquals(Result.Value(6), rt.result(result))
            assertEquals(5, rt.marks() - marksBefore)
            assertEquals(321, rt.last(), "outer cleanup must run last")
            assertEquals(runOracle(2, Settle.Value(-1), Settle.Value(4), 0, lastBefore), Outcome(Result.Value(6), 5, 321))
            rt.awaitSettled(result)

            checkRun(rt, 2, Settle.Value(-1), Settle.Value(4), 1, "rethrown from the handler")
            assertEquals(251, rt.last(), "inner cleanup, outer catch, outer cleanup are the last three")
        }
    }

    /**
     * 做什么：未被捕获的 std.Error 发布为 Failure(message, "error")：内层 try 内的 Error("overflow") 穿过
     *   内层 finally、不被外层 catch (LimitError) 捕获，外层 finally 之后发布；外层 try 内的
     *   Error("gate zero") 同样只执行外层 finally。挂起点失败不被 LimitError 捕获，发布的是原 Failure
     *   对象本身；挂起点取消发布 Cancelled。
     * 失败模式：Error 被当成平台异常（kind 为 platform、消息为 toString）、catch 按 Error 父类误捕获、
     *   发布早于清理、原 Failure 被替换。
     */
    @Test
    fun `uncaught source errors publish error failures after cleanups`() {
        withRuntime("uncaught") { rt ->
            var marksBefore = rt.marks()
            val overflow = rt.newFuture()
            rt.startRun(overflow, rt.newFuture().also { rt.complete(it, 60) }, rt.newFuture(), 2, 0)
            rt.awaitDone(overflow, "overflow")
            assertEquals(Result.Failed("overflow", "error"), rt.result(overflow))
            assertEquals(3, rt.marks() - marksBefore, "two inner cleanups and the outer cleanup")
            assertEquals(221, rt.last())
            rt.awaitSettled(overflow)

            checkRun(rt, 1, Settle.Value(3), Settle.Value(0), 0, "gate zero")
            assertEquals(21, rt.last() % 100)

            marksBefore = rt.marks()
            val seeded = rt.failure()
            val failed = rt.newFuture()
            rt.startRun(failed, rt.newFuture().also { rt.fail(it, seeded) }, rt.newFuture(), 1, 0)
            rt.awaitDone(failed, "awaited failure")
            assertSame(seeded, rt.failureOf(failed), "unmatched await failure must publish the original Failure")
            assertEquals(2, rt.marks() - marksBefore)
            rt.awaitSettled(failed)

            checkRun(rt, 3, Settle.Cancel, Settle.Value(1), 0, "cancelled input")
        }
    }

    /**
     * 做什么：没有 catch 的帧（plain）：分支内挂起在 gate 上时 defer 不执行，gate 完成后才抛出；defer 与
     *   try/finally 在发布前执行（limit 相等时 mark 6 再 mark 7，大于时只有 mark 7）；LimitError / Error
     *   都以消息原样发布，kind 为 error。
     * 失败模式：抛出前漏挂起、无 catch 帧的发布仍走平台失败、defer 漏执行或早于 finally。
     */
    @Test
    fun `frames without catch throw after awaits and run defer first`() {
        withRuntime("plain") { rt ->
            val marksBefore = rt.marks()
            val gate = rt.newFuture()
            val result = rt.newFuture()
            rt.startPlain(result, rt.newFuture().also { rt.complete(it, 9) }, gate, 3)
            rt.assertStillPending(result, "gate await before the throw")
            assertEquals(0, rt.marks() - marksBefore, "defer must not run while suspended")
            rt.complete(gate, 2)
            rt.awaitDone(result, "picked")
            assertEquals(Result.Failed("picked", "error"), rt.result(result))
            assertEquals(1, rt.marks() - marksBefore)
            assertEquals(7, rt.last() % 10)
            rt.awaitSettled(result)

            checkPlain(rt, Settle.Value(9), Settle.Value(0), 3, "picked-error")
            checkPlain(rt, Settle.Value(3), Settle.Value(5), 3, "equal")
            assertEquals(67, rt.last() % 100, "finally before defer")
            checkPlain(rt, Settle.Value(1), Settle.Value(5), 3, "returns")
            checkPlain(rt, Settle.Value(9), Settle.Cancel, 3, "cancelled gate")
            checkPlain(rt, Settle.Fail, Settle.Value(5), 3, "failed input")
        }
    }

    /**
     * 做什么：固定种子 20261119：run 与 plain 各 64 轮，随机 rows（0..4）/ mode（0..1）/ limit（-2..6）/
     *   input 与 gate（含负值、溢出量级、0、失败、取消）/ 每个 Future 在启动前或启动后发布；结果（值、
     *   失败消息与种类、取消）、清理与处理次数、最近三次编号与 Kotlin oracle 比对。
     * 失败模式：打印种子、轮次与全部参数；单轮即最小反例。
     */
    @Test
    fun `seeded throwing frames match an independent oracle`() {
        val seed = 20261119
        val random = kotlin.random.Random(seed)
        fun settle(): Settle = when (random.nextInt(8)) {
            0 -> Settle.Fail
            1 -> Settle.Cancel
            2 -> Settle.Value(random.nextInt(40, 120))
            else -> Settle.Value(random.nextInt(-3, 7))
        }
        withRuntime("seeded") { rt ->
            repeat(64) { round ->
                val rows = random.nextInt(0, 5)
                val mode = random.nextInt(0, 2)
                val input = settle()
                val gate = settle()
                val early = random.nextBoolean() to random.nextBoolean()
                checkRun(rt, rows, input, gate, mode, "seed=$seed run round=$round rows=$rows mode=$mode input=$input gate=$gate early=$early", early)
            }
            repeat(64) { round ->
                val limit = random.nextInt(-2, 7)
                val input = settle()
                val gate = settle()
                val early = random.nextBoolean() to random.nextBoolean()
                checkPlain(rt, input, gate, limit, "seed=$seed plain round=$round limit=$limit input=$input gate=$gate early=$early", early)
            }
        }
    }
}
