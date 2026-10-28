// ============================================================================
// LabeledLoopCoroutinePipelineTest.kt —— S4-C 第十六片「带标签的 break / continue」端到端验收
// ============================================================================
// 做什么：用真实 ANTLR 解析、类型检查、挂起效果分析、协程降级、IR 与 JVM 后端编译
//   含带标签循环的 Jasper 函数：内层 while 里的 await 之后 `continue rows`（回到外层 for 的
//   步进块）、`break rows`（到外层出口）与无标签 break；内层 do-while 里的 await 之后
//   `continue rounds`（到外层 do-while 的条件块）；不挂起的 `continue spinning` 自旋只能被
//   作用域取消。加载生成帧在真实 JvmExecutor 上运行，并以固定种子与 Kotlin oracle 比对。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做反射驱动与有界等待，程序计数器、
//   回边、跨挂起字段、Future 回调与结果发布均由生成的 Jasper AST 表达。
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

/** 验证带标签 break / continue 的生成帧运行语义。 */
class LabeledLoopCoroutinePipelineTest {

    /**
     * 被测函数：`spin` 为真时先进入一个不挂起、只经 `continue spinning` 回到外层循环头的
     * 自旋；随后外层 for 每行最多 4 列，每列 await input 得到 cell：cell 为负 `continue rows`
     * （跳过本行剩余列与行尾加成，仍执行步进），超过 40 `break rows`，等于 3 只跳出内层；
     * 外层 do-while 每趟内层 do-while 两次 await gate，`g + k == rounds` 时 `continue rounds`
     * 直接去外层条件块。返回前序值、总和与外层趟数之和。
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

            public static function sum(input: Future<int32>, gate: Future<int32>, rows: int32, spin: bool): int32 {
                var before: int32 = Program.step(rows);
                label spinning: while (spin) {
                    for (var s: int32 = 0; s < 1; s++) {
                        continue spinning;
                    }
                }
                var total: int32 = 0;
                label rows: for (var r: int32 = 0; r < rows; r++) {
                    var c: int32 = 0;
                    while (c < 4) {
                        c = c + 1;
                        var got: int32 = await input;
                        var cell: int32 = got + r - c;
                        if (cell < 0) {
                            continue rows;
                        }
                        if (cell > 40) {
                            break rows;
                        }
                        if (cell == 3) {
                            break;
                        }
                        total = total + cell;
                    }
                    total = total + 100;
                }
                var rounds: int32 = 0;
                label rounds: do {
                    rounds = rounds + 1;
                    var k: int32 = 0;
                    do {
                        k = k + 1;
                        var g: int32 = await gate;
                        if (g + k == rounds) {
                            continue rounds;
                        }
                        total = total + g * k;
                    } while (k < 2);
                } while (rounds < rows % 3);
                return before + total + rounds;
            }
        }
    """.trimIndent()

    /** 与源码同义的 Kotlin oracle（spin 为假）；只描述语义，不参与生成。 */
    private fun oracle(rows: Int, input: Int, gate: Int): Int {
        var total = 0
        rowsLoop@ for (r in 0 until rows) {
            var c = 0
            while (c < 4) {
                c += 1
                val cell = input + r - c
                if (cell < 0) continue@rowsLoop
                if (cell > 40) break@rowsLoop
                if (cell == 3) break
                total += cell
            }
            total += 100
        }
        var rounds = 0
        roundsLoop@ do {
            rounds += 1
            var k = 0
            do {
                k += 1
                if (gate + k == rounds) continue@roundsLoop
                total += gate * k
            } while (k < 2)
        } while (rounds < rows % 3)
        return rows + total + rounds
    }

    private val root: Path = Paths.get("build", "tmp", "labeled-loop-coroutine-pipeline")

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
        fun start(result: Any, input: Any, gate: Any, rows: Int, spin: Boolean): Any {
            val frame = loader.loadClass("demo.Program_sum_Coroutine").getConstructor(
                loader.loadClass("std.Executor"), future,
                future, future, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            ).newInstance(executor, result, input, gate, rows, spin)
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
     * 做什么：单工作线程，rows=4：外层 for 第 0 行第 1 列挂在 input；补齐 input=2 后各行经
     *   `cell == 3` / `continue rows` 不同路径同步走完，随后外层 do-while 第 1 趟挂在 gate；
     *   补齐 gate=5 后跑完。挂起前表达式只执行一次；生成类 major version 52。
     * 失败模式：带标签跳转接到内层循环（行尾加成被多加 / 少加）、continue rows 跳过步进导致
     *   死循环、恢复重放前段或结果错。
     */
    @Test
    fun `labeled jumps leave inner loops after resuming in order`() {
        withRuntime("ordered") { rt ->
            val bytes = Files.readAllBytes(root.resolve("ordered/demo/Program_sum_Coroutine.class"))
            assertEquals(52, ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF))
            val input = rt.newFuture()
            val gate = rt.newFuture()
            val result = rt.newFuture()
            rt.start(result, input, gate, 4, false)
            rt.assertStillPending(result, "first input await inside the inner while")
            assertEquals(1, rt.steps())
            rt.complete(input, 2)
            rt.assertStillPending(result, "gate await inside the inner do-while")
            rt.complete(gate, 5)
            rt.awaitDone(result, "ordered")
            // 第 0 行：cell=1 累加后 c=2 时 cell=0，c=3 时 -1 → continue rows（无行尾加成）；
            // 第 1 行：cell=2，c=2 时 cell=1，c=3 时 0，c=4 时 -1 → continue rows；
            // 第 2 行：cell=3 → break，行尾 +100；第 3 行：cell=4,3 → break，+4+100。
            // do-while：rows%3=1，只跑一趟，k=1,2 各累加 5k；4 + (1+2+1+0+100+4+100) + 15 + 1。
            assertEquals(228, rt.value(result))
            assertEquals(oracle(4, 2, 5), 228)
            assertEquals(1, rt.steps(), "pre-loop expression re-executed on a back edge")
            rt.awaitSettled(result)
        }
    }

    /**
     * 做什么：输入在启动前已完成时三种出口都经同步恢复走通：大输入首格 `break rows` 直接到
     *   外层出口（所有行尾加成都不执行）；负输入每行首格 `continue rows`，40 行经步进块走完；
     *   gate 使 `g + k == rounds` 时 `continue rounds` 跳过内层剩余趟直达外层条件块。
     * 失败模式：break rows 只跳出内层（多出行尾加成）、continue rows 不经步进（死循环超时）、
     *   continue rounds 落到内层条件块（多累加）。
     */
    @Test
    fun `break and continue to outer labels with ready inputs`() {
        withRuntime("ready") { rt ->
            val gate = rt.newFuture().also { rt.complete(it, 0) }
            for ((rows, inputValue) in listOf(5 to 60, 40 to -10, 2 to 3, 0 to 0)) {
                val input = rt.newFuture().also { rt.complete(it, inputValue) }
                val result = rt.newFuture()
                rt.start(result, input, gate, rows, false)
                rt.awaitDone(result, "rows=$rows input=$inputValue")
                assertEquals(oracle(rows, inputValue, 0), rt.value(result), "rows=$rows input=$inputValue")
                rt.awaitSettled(result)
            }
            // break rows：首格 cell=59 > 40，没有任何累加；gate=0 时 k=1 的 0+1==1 → continue rounds。
            val input = rt.newFuture().also { rt.complete(it, 60) }
            val broke = rt.newFuture()
            rt.start(broke, input, gate, 5, false)
            rt.awaitDone(broke, "break rows")
            assertEquals(5 + 0 + 2, rt.value(broke))
            rt.awaitSettled(broke)
        }
    }

    /**
     * 做什么：spin=true 时帧在只经 `continue spinning` 回到外层 while 循环头、不挂起的自旋里占住
     *   唯一工作线程；外部取消结果后，分派循环在回到外层循环头的那一趟开头返回，帧被回收，
     *   工作线程仍能运行下一个帧。
     * 失败模式：带标签回边不经过终态检查时自旋永不结束、settled 永不发生、执行器无法关闭。
     */
    @Test
    fun `non suspending labeled continue responds to scope cancellation`() {
        withRuntime("spin") { rt ->
            val spinning = rt.newFuture()
            rt.start(spinning, rt.newFuture(), rt.newFuture(), 3, true)
            rt.assertStillPending(spinning, "spinning labeled continue")
            rt.future.getMethod("cancel").invoke(spinning)
            rt.awaitSettled(spinning)
            assertEquals("Cancelled", rt.status(spinning))

            val after = rt.newFuture()
            rt.start(after, rt.newFuture().also { rt.complete(it, 1) }, rt.newFuture().also { rt.complete(it, 2) }, 2, false)
            rt.awaitDone(after, "worker released after cancellation")
            assertEquals(oracle(2, 1, 2), rt.value(after))
            rt.awaitSettled(after)
        }
    }

    /**
     * 做什么：内层 do-while 的 gate 挂起点失败时原样发布 Failure；内层 while 挂起中的帧被作用域
     *   取消后回收，晚到输入不复活帧、不重跑任何段。
     * 失败模式：带标签循环内恢复块的结果消费缺失、取消后 settled 不回收或被晚到输入复活。
     */
    @Test
    fun `failure inside labeled do-while and scope cancel inside labeled for`() {
        withRuntime("terminal") { rt ->
            val failure = rt.loader.loadClass("std.Failure")
                .getConstructor(String::class.java, String::class.java).newInstance("expected", "error")
            val failed = rt.newFuture()
            val failingGate = rt.newFuture()
            rt.start(failed, rt.newFuture().also { rt.complete(it, 1) }, failingGate, 7, false)
            rt.assertStillPending(failed, "before gate failure")
            rt.future.getMethod("fail", rt.loader.loadClass("std.Failure")).invoke(failingGate, failure)
            rt.awaitDone(failed, "failed")
            assertSame(failure, rt.future.getMethod("failureOrNull").invoke(failed))
            rt.awaitSettled(failed)

            val scoped = rt.newFuture()
            val lateInput = rt.newFuture()
            val lateGate = rt.newFuture()
            rt.start(scoped, lateInput, lateGate, 7, false)
            rt.assertStillPending(scoped, "before scope cancel")
            rt.future.getMethod("cancel").invoke(scoped)
            rt.awaitSettled(scoped)
            rt.complete(lateInput, 4)
            rt.complete(lateGate, 4)
            Thread.sleep(50)
            assertEquals("Cancelled", rt.status(scoped), "late input revived a cancelled frame")
            assertEquals(2, rt.steps(), "cancelled frame re-ran a segment")
        }
    }

    /**
     * 做什么：固定种子 20261107 的 64 轮：随机 rows（0..12）/ input（含 break rows 与 continue rows
     *   量级）/ gate（含触发 continue rounds 的小值）/ 每个输入在启动前或启动后完成，结果与
     *   Kotlin oracle 比对。
     * 失败模式：打印种子、轮次与全部参数；单轮即最小反例。
     */
    @Test
    fun `seeded labeled-loop frames match an independent oracle`() {
        val seed = 20261107
        val random = kotlin.random.Random(seed)
        withRuntime("seeded") { rt ->
            repeat(64) { round ->
                val rows = random.nextInt(0, 13)
                val inputValue = when (random.nextInt(4)) {
                    0 -> random.nextInt(41, 80)
                    1 -> random.nextInt(-20, 0)
                    else -> random.nextInt(0, 8)
                }
                val gateValue = random.nextInt(-3, 4)
                val inputEarly = random.nextBoolean()
                val gateEarly = random.nextBoolean()
                val context = "seed=$seed round=$round rows=$rows input=$inputValue/$inputEarly gate=$gateValue/$gateEarly"
                val input = rt.newFuture().also { if (inputEarly) rt.complete(it, inputValue) }
                val gate = rt.newFuture().also { if (gateEarly) rt.complete(it, gateValue) }
                val result = rt.newFuture()
                rt.start(result, input, gate, rows, false)
                // rows > 0 时第 0 行第 1 列必然挂在 input 上；两层 do-while 至少各跑一趟，必然经过 gate。
                if (!inputEarly && rows > 0) rt.assertStillPending(result, context)
                if (!inputEarly) rt.complete(input, inputValue)
                if (!gateEarly) rt.assertStillPending(result, context)
                if (!gateEarly) rt.complete(gate, gateValue)
                rt.awaitDone(result, context)
                assertEquals(oracle(rows, inputValue, gateValue), rt.value(result), context)
                rt.awaitSettled(result)
            }
        }
    }
}
