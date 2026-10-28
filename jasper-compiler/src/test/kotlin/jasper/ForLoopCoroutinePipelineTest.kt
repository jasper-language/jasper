// ============================================================================
// ForLoopCoroutinePipelineTest.kt —— S4-C 第十二片「do-while 与三段式 for 内挂起」端到端验收
// ============================================================================
// 做什么：用真实 ANTLR 解析、类型检查、挂起效果分析、协程降级、IR 与 JVM 后端编译
//   含三段式 for（初始化段声明、`++` 步进、continue 前进到步进块、break）与 do-while
//   （至少一趟、continue 前进到条件块）的 Jasper 函数，两种循环体内都有 await；加载
//   生成帧在真实 JvmExecutor 上运行：单工作线程跨回边逐个挂起/恢复、输入已完成时连续
//   多趟同步恢复、零趟 for 与只跑一趟的 do-while、不挂起的 for 自旋在回边处响应作用域
//   取消、循环内挂起点的失败，以及固定种子与 Kotlin oracle 比对。
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

/** 验证 do-while 与三段式 for 内挂起的生成帧运行语义。 */
class ForLoopCoroutinePipelineTest {

    /**
     * 被测函数：`spin` 为真时先进入一个不挂起、不会自行结束的 for（只能被作用域取消）；
     * 主 for 每趟 await input 并按趟号加权累加（趟号模 3 余 2 时 continue，超过 1000 时
     * break）；随后 do-while 至少跑一趟，每趟 await gate 累加，第 2 趟 continue 直接去
     * 条件块。返回总和加 do-while 趟数。
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

            public static function sum(input: Future<int32>, gate: Future<int32>, limit: int32, spin: bool): int32 {
                var before: int32 = Program.step(limit);
                for (var s: int32 = 0; spin; s++) {
                }
                var total: int32 = 0;
                for (var i: int32 = 0; i < limit; i++) {
                    if (i % 3 == 2) {
                        continue;
                    }
                    var got: int32 = await input;
                    total = total + got * (i + 1);
                    if (total > 1000) {
                        break;
                    }
                }
                var rounds: int32 = 0;
                do {
                    rounds = rounds + 1;
                    var extra: int32 = await gate;
                    total = total + extra;
                    if (rounds == 2) {
                        continue;
                    }
                } while (rounds < limit % 4);
                return before + total + rounds;
            }
        }
    """.trimIndent()

    /** 与源码同义的 Kotlin oracle（spin 为假）；只描述语义，不参与生成。 */
    private fun oracle(limit: Int, input: Int, gate: Int): Int {
        var total = 0
        for (i in 0 until limit) {
            if (i % 3 == 2) continue
            total += input * (i + 1)
            if (total > 1000) break
        }
        var rounds = 0
        do {
            rounds += 1
            total += gate
            if (rounds == 2) continue
        } while (rounds < limit % 4)
        return limit + total + rounds
    }

    /** oracle 下 for 是否至少经过一次 input 挂起点（limit > 0 时第 0 趟必挂起）。 */
    private fun reachesInput(limit: Int) = limit > 0

    private val root: Path = Paths.get("build", "tmp", "for-loop-coroutine-pipeline")

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
        fun start(result: Any, input: Any, gate: Any, limit: Int, spin: Boolean): Any {
            val frame = loader.loadClass("demo.Program_sum_Coroutine").getConstructor(
                loader.loadClass("std.Executor"), future,
                future, future, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            ).newInstance(executor, result, input, gate, limit, spin)
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
     * 做什么：单工作线程，limit=5：for 第 0 趟挂在 input；补齐后其余趟经回边同步恢复
     *   （第 2 趟 continue 经步进块），随后 do-while 第 1 趟挂在 gate；补齐后 do-while
     *   再跑（limit%4=1 时只跑一趟）。挂起前表达式只执行一次；生成类 major version 52。
     * 失败模式：continue 跳过步进、do-while 首趟未执行就判断条件、恢复重放前段或结果错。
     */
    @Test
    fun `for and do-while suspend across back edges and resume in order`() {
        withRuntime("ordered") { rt ->
            val bytes = Files.readAllBytes(root.resolve("ordered/demo/Program_sum_Coroutine.class"))
            assertEquals(52, ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF))
            val input = rt.newFuture()
            val gate = rt.newFuture()
            val result = rt.newFuture()
            rt.start(result, input, gate, 5, false)
            rt.assertStillPending(result, "first for iteration await")
            assertEquals(1, rt.steps())
            rt.complete(input, 2)
            rt.assertStillPending(result, "gate await inside the first do-while iteration")
            rt.complete(gate, 7)
            rt.awaitDone(result, "ordered")
            // for：趟 0,1,3,4 → 2*(1+2+4+5)=24；do-while 一趟 +7；5 + 31 + 1。
            assertEquals(37, rt.value(result))
            assertEquals(oracle(5, 2, 7), 37)
            assertEquals(1, rt.steps(), "pre-loop expression re-executed on a back edge")
            rt.awaitSettled(result)
        }
    }

    /**
     * 做什么：输入在启动前已完成时，200 趟 for 与 3 趟 do-while（含第 2 趟 continue）每趟
     *   都经同步回调恢复，结果与 oracle 一致；输入很大时 for 提前 break；limit=0 时 for
     *   一趟不进，do-while 仍跑一趟并挂在 gate。
     * 失败模式：同步回调在长循环中丢失恢复、break 接错出口、零趟 for 等待输入、do-while 被跳过。
     */
    @Test
    fun `ready inputs drive many iterations, break leaves the for and do-while runs at least once`() {
        withRuntime("ready") { rt ->
            val input = rt.newFuture().also { rt.complete(it, 1) }
            val gate = rt.newFuture().also { rt.complete(it, 3) }
            val long = rt.newFuture()
            rt.start(long, input, gate, 203, false)
            rt.awaitDone(long, "203 iterations")
            assertEquals(oracle(203, 1, 3), rt.value(long))
            rt.awaitSettled(long)

            val big = rt.newFuture().also { rt.complete(it, 100) }
            val broke = rt.newFuture()
            rt.start(broke, big, gate, 50, false)
            rt.awaitDone(broke, "break")
            assertEquals(oracle(50, 100, 3), rt.value(broke))
            rt.awaitSettled(broke)

            val zeroGate = rt.newFuture()
            val zero = rt.newFuture()
            rt.start(zero, rt.newFuture(), zeroGate, 0, false)
            rt.assertStillPending(zero, "do-while must run once even when the for runs zero times")
            rt.complete(zeroGate, 9)
            rt.awaitDone(zero, "zero for iterations")
            assertEquals(oracle(0, 0, 9), rt.value(zero))
            assertEquals(10, rt.value(zero))
            rt.awaitSettled(zero)
        }
    }

    /**
     * 做什么：spin=true 时帧在一个空循环体、不挂起的 for 里占住唯一工作线程；外部取消结果
     *   后，分派循环在步进块回到循环头的那一趟开头返回，帧被回收，工作线程仍能运行下一个帧。
     * 失败模式：步进块回边不经过终态检查时自旋永不结束、settled 永不发生、执行器无法关闭。
     */
    @Test
    fun `non suspending for responds to scope cancellation at its back edge`() {
        withRuntime("spin") { rt ->
            val spinning = rt.newFuture()
            rt.start(spinning, rt.newFuture(), rt.newFuture(), 3, true)
            rt.assertStillPending(spinning, "spinning for")
            rt.future.getMethod("cancel").invoke(spinning)
            rt.awaitSettled(spinning)
            assertEquals("Cancelled", rt.status(spinning))

            val after = rt.newFuture()
            rt.start(after, rt.newFuture().also { rt.complete(it, 1) }, rt.newFuture().also { rt.complete(it, 0) }, 2, false)
            rt.awaitDone(after, "worker released after cancellation")
            assertEquals(oracle(2, 1, 0), rt.value(after))
            rt.awaitSettled(after)
        }
    }

    /**
     * 做什么：do-while 内 gate 挂起点失败时原样发布 Failure；for 第 0 趟挂起中的帧被作用域
     *   取消后回收，晚到输入不复活帧、不重跑任何段。
     * 失败模式：do-while 恢复块的结果消费缺失、取消后 settled 不回收或被晚到输入复活。
     */
    @Test
    fun `failure inside do-while and scope cancel while suspended in the for`() {
        withRuntime("terminal") { rt ->
            val failure = rt.loader.loadClass("std.Failure")
                .getConstructor(String::class.java, String::class.java).newInstance("expected", "error")
            val failed = rt.newFuture()
            val failingGate = rt.newFuture()
            rt.start(failed, rt.newFuture().also { rt.complete(it, 1) }, failingGate, 9, false)
            rt.assertStillPending(failed, "before gate failure")
            rt.future.getMethod("fail", rt.loader.loadClass("std.Failure")).invoke(failingGate, failure)
            rt.awaitDone(failed, "failed")
            assertSame(failure, rt.future.getMethod("failureOrNull").invoke(failed))
            rt.awaitSettled(failed)

            val scoped = rt.newFuture()
            val lateInput = rt.newFuture()
            val lateGate = rt.newFuture()
            rt.start(scoped, lateInput, lateGate, 9, false)
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
     * 做什么：固定种子 20261028 的 64 轮：随机 limit（0..12）/ input（含 break 量级）/
     *   gate / 每个输入在启动前或启动后完成，结果与 Kotlin oracle 比对。
     * 失败模式：打印种子、轮次与全部参数；单轮即最小反例。
     */
    @Test
    fun `seeded for and do-while frames match an independent oracle`() {
        val seed = 20261028
        val random = kotlin.random.Random(seed)
        withRuntime("seeded") { rt ->
            repeat(64) { round ->
                val limit = random.nextInt(0, 13)
                val inputValue = if (random.nextInt(4) == 0) random.nextInt(60, 200) else random.nextInt(-6, 7)
                val gateValue = random.nextInt(-20, 21)
                val inputEarly = random.nextBoolean()
                val gateEarly = random.nextBoolean()
                val context = "seed=$seed round=$round limit=$limit input=$inputValue/$inputEarly gate=$gateValue/$gateEarly"
                val input = rt.newFuture().also { if (inputEarly) rt.complete(it, inputValue) }
                val gate = rt.newFuture().also { if (gateEarly) rt.complete(it, gateValue) }
                val result = rt.newFuture()
                rt.start(result, input, gate, limit, false)
                if (!inputEarly && reachesInput(limit)) rt.assertStillPending(result, context)
                if (!inputEarly) rt.complete(input, inputValue)
                // do-while 至少跑一趟，gate 晚到时帧必然挂在它上面。
                if (!gateEarly) rt.assertStillPending(result, context)
                if (!gateEarly) rt.complete(gate, gateValue)
                rt.awaitDone(result, context)
                assertEquals(oracle(limit, inputValue, gateValue), rt.value(result), context)
                rt.awaitSettled(result)
            }
        }
    }
}
