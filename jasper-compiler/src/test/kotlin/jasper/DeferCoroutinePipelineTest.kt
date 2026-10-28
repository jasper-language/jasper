// ============================================================================
// DeferCoroutinePipelineTest.kt —— S4-C 第十九片「defer」端到端验收
// ============================================================================
// 做什么：用真实 ANTLR 解析、类型检查、挂起效果分析、协程降级、IR 与 JVM 后端编译
//   含 defer 的 Jasper 函数：函数体开头两个 defer（单语句形式与块形式），三段式 for 的
//   循环体开头一个 defer，循环体在 await input 之后可能 continue / break / return；defer 体
//   改写局部并调用 `Program.mark(k)` 记录次数与最近两次清理的编号。加载生成帧在真实
//   JvmExecutor 上运行：defer 在所在块（循环体每趟、函数体）结束时执行，后声明先执行；
//   正常完成、跨块跳转、挂起点失败、段内异常（除零）、defer 体自身抛出与作用域取消都要求
//   清理恰好执行一次且先于结果发布；并以固定种子与 Kotlin oracle 比对。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做反射驱动与有界等待，defer 的降级、清理、
//   展开与结果发布均由生成的 Jasper AST 表达。
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

/** 验证 defer 生成帧的作用域清理语义。 */
class DeferCoroutinePipelineTest {

    /**
     * 被测函数：函数体先 `defer Program.mark(1);`，再 `defer { total = total + 1000; Program.mark(3); }`；
     * for 循环体开头 `defer { total = total + 1; Program.mark(2); ... }`（mode 为 77 时除零抛出），随后
     * await input，`got + r == mode` 时 continue、超过 5000 break、累计超过 1000 时 return；for 之后
     * await gate 并以 100 / g 累加（g 为 0 时除零）。函数结束时后声明的 defer 先执行（mark 3 再 mark 1，
     * 最近两次编号为 31）；return 的值在 defer 之前求出，所以 +1000 不出现在结果里。
     */
    private fun program() = """
        package demo;
        import std.Future;

        public class Program {
            private static var count: int32 = 0;
            private static var last: int32 = 0;

            public static function mark(code: int32): int32 {
                Program.count = Program.count + 1;
                Program.last = Program.last % 10 * 10 + code;
                return code;
            }

            public static function count(): int32 { return Program.count; }

            public static function last(): int32 { return Program.last; }

            public static function divide(a: int32, b: int32): int32 { return a / b; }

            public static function sum(input: Future<int32>, gate: Future<int32>, rows: int32, mode: int32): int32 {
                var total: int32 = 0;
                defer Program.mark(1);
                defer {
                    total = total + 1000;
                    Program.mark(3);
                }
                for (var r: int32 = 0; r < rows; r++) {
                    defer {
                        total = total + 1;
                        Program.mark(2);
                        if (mode == 77) {
                            Program.divide(1, 0);
                        }
                    }
                    var got: int32 = await input;
                    if (got + r == mode) {
                        continue;
                    }
                    if (got + r > 5000) {
                        break;
                    }
                    total = total + got + r;
                    if (total > 1000) {
                        return total;
                    }
                }
                var g: int32 = await gate;
                total = total + Program.divide(100, g);
                return total;
            }
        }
    """.trimIndent()

    /** oracle 结果：成功值或失败，以及本次调用期间的清理次数与最近两次清理编号。 */
    private data class Outcome(val value: Int?, val marks: Int, val last: Int)

    /**
     * 与源码同义的 Kotlin oracle；只描述语义，不参与生成。每个 defer 是其所在块剩余部分外的一层
     * finally（块结束时执行、后声明的在内层）。[lastBefore] 是调用前的最近清理编号，用来推出调用后的
     * `last`。输入失败用 [inputFails] / [gateFails] 表示。
     */
    private fun oracle(rows: Int, input: Int, gate: Int, mode: Int, lastBefore: Int,
                       inputFails: Boolean = false, gateFails: Boolean = false): Outcome {
        var marks = 0
        var last = lastBefore
        fun mark(code: Int) { marks++; last = last % 10 * 10 + code }
        val value = try {
            var total = 0
            try {
                try {
                    var result: Int? = null
                    for (r in 0 until rows) {
                        try {
                            if (inputFails) throw IllegalStateException("input failed")
                            val got = input
                            if (got + r == mode) continue
                            if (got + r > 5000) break
                            total += got + r
                            if (total > 1000) { result = total; break }
                        } finally {
                            total += 1
                            mark(2)
                            if (mode == 77) throw ArithmeticException("/ by zero")
                        }
                    }
                    result ?: run {
                        if (gateFails) throw IllegalStateException("gate failed")
                        total += 100 / gate
                        total
                    }
                } finally {
                    total += 1000
                    mark(3)
                }
            } finally {
                mark(1)
            }
        } catch (_: RuntimeException) {
            null
        }
        return Outcome(value, marks, last)
    }

    private val root: Path = Paths.get("build", "tmp", "defer-coroutine-pipeline")

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
        fun failure(message: String): Any =
            failureType.getConstructor(String::class.java, String::class.java).newInstance(message, "error")
        fun isDone(target: Any) = future.getMethod("isDone").invoke(target) as Boolean
        fun status(target: Any) = future.getMethod("status").invoke(target).toString()
        fun value(target: Any) = future.getMethod("get").invoke(target)
        fun failureOf(target: Any): Any? = future.getMethod("failureOrNull").invoke(target)
        fun marks() = program.getMethod("count").invoke(null) as Int
        fun last() = program.getMethod("last").invoke(null) as Int

        /** 构造并启动一个生成帧；参数布局 (Executor, output, 原参数...) 由发射器约定。 */
        fun start(result: Any, input: Any, gate: Any, rows: Int, mode: Int): Any {
            val frame = loader.loadClass("demo.Program_sum_Coroutine").getConstructor(
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

    /**
     * 做什么：单工作线程，rows=3：第 0 行挂在循环体内的 input 上，此时尚无清理；补齐 input=4 后三行
     *   各执行一次循环体的 defer，随后挂在 gate 上；补齐 gate=5 后函数体的两个 defer 按后声明先执行的
     *   顺序执行，结果为 (4+0+1)+(4+1+1)+(4+2+1)+20 = 38（+1000 不进入已求出的返回值），最近两次编号 31。
     *   生成类 major version 52。
     * 失败模式：挂起时提前清理、defer 作用域扩大到函数（循环体 defer 不按趟执行）、先声明的 defer 先执行
     *   （得到 13）、return 值在清理之后求值（得到 1038）。
     */
    @Test
    fun `defers run at the end of their block in reverse order`() {
        withRuntime("ordered") { rt ->
            val bytes = Files.readAllBytes(root.resolve("ordered/demo/Program_sum_Coroutine.class"))
            assertEquals(52, ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF))
            val input = rt.newFuture()
            val gate = rt.newFuture()
            val result = rt.newFuture()
            rt.start(result, input, gate, 3, -1)
            rt.assertStillPending(result, "input await inside the loop body")
            assertEquals(0, rt.marks(), "defer ran while suspended")
            rt.complete(input, 4)
            rt.assertStillPending(result, "gate await after the loop")
            assertEquals(3, rt.marks(), "loop-body defer must run once per row")
            rt.complete(gate, 5)
            rt.awaitDone(result, "ordered")
            assertEquals(38, rt.value(result))
            assertEquals(5, rt.marks())
            assertEquals(31, rt.last(), "later defer must run first")
            assertEquals(oracle(3, 4, 5, -1, 0), Outcome(38, 5, 31))
            rt.awaitSettled(result)
        }
    }

    /**
     * 做什么：输入就绪时跨出块的三种跳转同步走通：continue 与 break 只执行循环体的 defer，循环体内的
     *   return 先求值、再依次执行循环体与函数体的 defer（结果不含 defer 对 total 的改写）。
     * 失败模式：跳转漏掉循环体 defer、break 提前执行函数体 defer、return 只清理一层或顺序颠倒。
     */
    @Test
    fun `continue break and return leave blocks through their defers`() {
        withRuntime("jumps") { rt ->
            val gate = rt.newFuture().also { rt.complete(it, 7) }
            // (rows, input, mode)：continue（got + r == mode 的行）、break（> 5000）、return（累计 > 1000）、零行。
            for ((rows, inputValue, mode) in listOf(Triple(4, 2, 3), Triple(3, 6000, -1), Triple(5, 600, -1), Triple(0, 1, -1))) {
                val context = "rows=$rows input=$inputValue mode=$mode"
                val marksBefore = rt.marks()
                val expected = oracle(rows, inputValue, 7, mode, rt.last())
                val input = rt.newFuture().also { rt.complete(it, inputValue) }
                val result = rt.newFuture()
                rt.start(result, input, gate, rows, mode)
                rt.awaitDone(result, context)
                assertEquals(expected.value, rt.value(result), context)
                assertEquals(expected.marks, rt.marks() - marksBefore, context)
                assertEquals(expected.last, rt.last(), context)
                rt.awaitSettled(result)
            }
            // return 路径：600 → 601（循环体 defer）→ 601+601=1202 > 1000 返回 1202；随后循环体 defer 与
            // 函数体两个 defer 各执行一次。
            val marksBefore = rt.marks()
            val input = rt.newFuture().also { rt.complete(it, 600) }
            val returned = rt.newFuture()
            rt.start(returned, input, gate, 5, -1)
            rt.awaitDone(returned, "return inside the loop body")
            assertEquals(1202, rt.value(returned))
            assertEquals(4, rt.marks() - marksBefore)
            assertEquals(31, rt.last())
            rt.awaitSettled(returned)
        }
    }

    /**
     * 做什么：挂起点失败、段内异常与 defer 体自身抛出都先展开 defer 再发布：input 在循环体内失败时
     *   三个 defer 各一次后才发布原 Failure；gate（循环之后）失败时只执行函数体的两个 defer；g = 0 的
     *   除零变成 platform 失败且函数体 defer 已执行；mode 77 时循环体 defer 除零抛出，函数体 defer 仍
     *   执行、结果为 platform 失败。
     * 失败模式：失败直接发布不清理、清理次数或顺序错、defer 抛出后外层 defer 被跳过、结果在清理前可见。
     */
    @Test
    fun `failures unwind defers before the failure is published`() {
        withRuntime("failures") { rt ->
            val inputFailure = rt.failure("input failed")
            val input = rt.newFuture()
            val failed = rt.newFuture()
            var marksBefore = rt.marks()
            rt.start(failed, input, rt.newFuture(), 3, -1)
            rt.assertStillPending(failed, "before input failure")
            rt.fail(input, inputFailure)
            rt.awaitDone(failed, "input failure")
            assertEquals(3, rt.marks() - marksBefore, "defers must finish before the failure is visible")
            assertEquals(31, rt.last())
            assertSame(inputFailure, rt.failureOf(failed))
            rt.awaitSettled(failed)

            val gateFailure = rt.failure("gate failed")
            val gate = rt.newFuture()
            val gateFailed = rt.newFuture()
            marksBefore = rt.marks()
            rt.start(gateFailed, rt.newFuture().also { rt.complete(it, 1) }, gate, 2, -1)
            rt.assertStillPending(gateFailed, "before gate failure")
            assertEquals(2, rt.marks() - marksBefore)
            rt.fail(gate, gateFailure)
            rt.awaitDone(gateFailed, "gate failure")
            assertEquals(4, rt.marks() - marksBefore)
            assertEquals(31, rt.last())
            assertSame(gateFailure, rt.failureOf(gateFailed))
            rt.awaitSettled(gateFailed)

            for ((gateValue, mode) in listOf(0 to -1, 5 to 77)) {
                val context = "gate=$gateValue mode=$mode"
                marksBefore = rt.marks()
                val expected = oracle(2, 1, gateValue, mode, rt.last())
                val result = rt.newFuture()
                rt.start(result, rt.newFuture().also { rt.complete(it, 1) }, rt.newFuture().also { rt.complete(it, gateValue) }, 2, mode)
                rt.awaitDone(result, context)
                assertEquals("Failed", rt.status(result), context)
                val kind = rt.failureType.getMethod("kind").invoke(rt.failureOf(result))
                assertEquals("platform", kind, context)
                assertEquals(null, expected.value, context)
                assertEquals(expected.marks, rt.marks() - marksBefore, context)
                assertEquals(expected.last, rt.last(), context)
                rt.awaitSettled(result)
            }
        }
    }

    /**
     * 做什么：帧挂在循环体内时被作用域取消：工作线程展开三个 defer 各一次后才 settled；晚到输入不复活
     *   帧、不重跑 defer；挂在 gate（循环之后）时取消只执行函数体的两个 defer。
     * 失败模式：取消后不清理就回收、defer 重复执行、settled 早于清理。
     */
    @Test
    fun `scope cancellation unwinds defers before settling`() {
        withRuntime("cancel") { rt ->
            val input = rt.newFuture()
            val scoped = rt.newFuture()
            var marksBefore = rt.marks()
            rt.start(scoped, input, rt.newFuture(), 3, -1)
            rt.assertStillPending(scoped, "before scope cancel")
            rt.future.getMethod("cancel").invoke(scoped)
            rt.awaitSettled(scoped)
            assertEquals(3, rt.marks() - marksBefore, "settled before defers finished")
            assertEquals(31, rt.last())
            rt.complete(input, 4)
            Thread.sleep(50)
            assertEquals("Cancelled", rt.status(scoped), "late input revived a cancelled frame")
            assertEquals(3, rt.marks() - marksBefore, "cancelled frame re-ran a defer")

            val gate = rt.newFuture()
            val outerOnly = rt.newFuture()
            marksBefore = rt.marks()
            rt.start(outerOnly, rt.newFuture().also { rt.complete(it, 1) }, gate, 1, -1)
            rt.assertStillPending(outerOnly, "suspended on gate")
            rt.future.getMethod("cancel").invoke(outerOnly)
            rt.awaitSettled(outerOnly)
            assertEquals(3, rt.marks() - marksBefore, "one loop-body defer before suspending, two function defers on cancel")
            assertEquals(31, rt.last())
        }
    }

    /**
     * 做什么：固定种子 20261113 的 64 轮：随机 rows（0..8）/ input（含 continue、break 与 return 量级）/
     *   gate（含 0）/ mode（含 77）/ 挂起点失败 / 每个输入在启动前或启动后完成，结果、清理次数与
     *   最近两次清理编号与 Kotlin oracle 比对。
     * 失败模式：打印种子、轮次与全部参数；单轮即最小反例。
     */
    @Test
    fun `seeded defer frames match an independent oracle`() {
        val seed = 20261113
        val random = kotlin.random.Random(seed)
        withRuntime("seeded") { rt ->
            repeat(64) { round ->
                val rows = random.nextInt(0, 9)
                val inputValue = when (random.nextInt(4)) {
                    0 -> random.nextInt(5001, 6000)
                    1 -> random.nextInt(300, 700)
                    else -> random.nextInt(-3, 6)
                }
                val gateValue = random.nextInt(-2, 4)
                val mode = when (random.nextInt(5)) {
                    0 -> 77
                    else -> random.nextInt(-1, 6)
                }
                val inputFails = random.nextInt(6) == 0
                val gateFails = random.nextInt(6) == 0
                val inputEarly = random.nextBoolean()
                val gateEarly = random.nextBoolean()
                val context = "seed=$seed round=$round rows=$rows input=$inputValue/$inputFails/$inputEarly " +
                    "gate=$gateValue/$gateFails/$gateEarly mode=$mode"
                val marksBefore = rt.marks()
                val expected = oracle(rows, inputValue, gateValue, mode, rt.last(), inputFails, gateFails)
                fun settle(target: Any, fails: Boolean, value: Int) =
                    if (fails) rt.fail(target, rt.failure("seeded")) else rt.complete(target, value)
                val input = rt.newFuture().also { if (inputEarly) settle(it, inputFails, inputValue) }
                val gate = rt.newFuture().also { if (gateEarly) settle(it, gateFails, gateValue) }
                val result = rt.newFuture()
                rt.start(result, input, gate, rows, mode)
                if (!inputEarly) settle(input, inputFails, inputValue)
                if (!gateEarly) settle(gate, gateFails, gateValue)
                rt.awaitDone(result, context)
                if (expected.value == null) {
                    assertEquals("Failed", rt.status(result), context)
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
