// ============================================================================
// CallArgumentAwaitCoroutinePipelineTest.kt —— S4-C 第十四片「调用实参中的 await」端到端验收
// ============================================================================
// 做什么：用真实 ANTLR 解析、类型检查、挂起效果分析、协程降级、IR 与 JVM 后端编译
//   await 出现在源码静态函数调用实参中的 Jasper 函数（局部初始化、while 条件、嵌套调用的
//   最后一个实参、if 条件与 return 值）；加载生成帧在真实 JvmExecutor 上运行：被调函数
//   只在实参 await 恢复之后执行、且每次调用恰好执行一次，输入已完成时多趟同步恢复，
//   实参挂起点的失败与挂起中被作用域取消，以及固定种子与 Kotlin oracle 比对。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做反射驱动与有界等待，程序计数器、
//   临时局部、Future 回调与结果发布均由生成的 Jasper AST 表达。
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

/** 验证调用实参中的 await 经提升后的生成帧运行语义。 */
class CallArgumentAwaitCoroutinePipelineTest {

    /**
     * 被测函数：`mix(a, b) = 3a - b` 计数自身调用次数。局部初始化里 limit 先于 await gate 的实参；
     * while 条件在调用实参里 await input，后接 `&&` 与上限；循环体嵌套调用的最后一个实参 await gate；
     * if 条件与 return 值的调用实参各自 await。
     */
    private fun program() = """
        package demo;
        import std.Future;

        public class Program {
            private static var calls: int32 = 0;

            public static function mix(a: int32, b: int32): int32 {
                Program.calls = Program.calls + 1;
                return a * 3 - b;
            }

            public static function calls(): int32 { return Program.calls; }

            public static function run(input: Future<int32>, gate: Future<int32>, limit: int32): int32 {
                var total: int32 = Program.mix(limit, await gate);
                var i: int32 = 0;
                while (Program.mix(i, await input) < total && i < 40) {
                    total = total - Program.mix(1, Program.mix(i, await gate));
                    i = i + 1;
                }
                if (Program.mix(await input, limit) > 0) {
                    total = total + 1;
                }
                return Program.mix(await gate, total) + i;
            }
        }
    """.trimIndent()

    /** 与源码同义的 Kotlin oracle：返回 (结果, mix 调用次数)；只描述语义，不参与生成。 */
    private fun oracle(limit: Int, input: Int, gate: Int): Pair<Int, Int> {
        var calls = 0
        fun mix(a: Int, b: Int): Int { calls++; return a * 3 - b }
        var total = mix(limit, gate)
        var i = 0
        while (mix(i, input) < total && i < 40) {
            total -= mix(1, mix(i, gate))
            i += 1
        }
        if (mix(input, limit) > 0) total += 1
        return mix(gate, total) + i to calls
    }

    private val root: Path = Paths.get("build", "tmp", "call-argument-await-coroutine-pipeline")

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
        fun calls() = loader.loadClass("demo.Program").getMethod("calls").invoke(null) as Int

        /** 构造并启动一个生成帧；参数布局 (Executor, output, 原参数...) 由发射器约定。 */
        fun start(result: Any, input: Any, gate: Any, limit: Int): Any {
            val frame = loader.loadClass("demo.Program_run_Coroutine").getConstructor(
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
     * 做什么：单工作线程，gate 与 input 都晚到：帧先挂在局部初始化调用实参的 gate 上，此时
     *   mix 一次都没执行；补齐 gate 后执行第一次调用并挂在 while 条件实参的 input 上；补齐
     *   input 后跑完。结果与调用次数都与 oracle 一致；生成类 major version 52。
     * 失败模式：调用被提前到挂起之前、恢复重放调用、实参顺序被改变或结果错。
     */
    @Test
    fun `call arguments suspend before the call runs`() {
        withRuntime("ordered") { rt ->
            val bytes = Files.readAllBytes(root.resolve("ordered/demo/Program_run_Coroutine.class"))
            assertEquals(52, ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF))
            val input = rt.newFuture()
            val gate = rt.newFuture()
            val result = rt.newFuture()
            rt.start(result, input, gate, 4)
            rt.assertStillPending(result, "initializer argument await")
            assertEquals(0, rt.calls(), "call ran before its awaited argument")
            rt.complete(gate, 2)
            rt.assertStillPending(result, "while condition argument await")
            assertEquals(1, rt.calls(), "initializer call must run exactly once after the resume")
            rt.complete(input, 5)
            rt.awaitDone(result, "ordered")
            val (expected, calls) = oracle(4, 5, 2)
            assertEquals(expected, rt.value(result))
            assertEquals(calls, rt.calls(), "call count differs from the oracle")
            rt.awaitSettled(result)
        }
    }

    /**
     * 做什么：两个输入都在启动前完成，循环多趟（至 40 趟上限）全部同步恢复，结果与调用次数
     *   与 oracle 一致；另测 while 零趟。
     * 失败模式：回边后不再重新挂起条件实参、嵌套调用次数错位。
     */
    @Test
    fun `ready inputs resume argument suspensions across back edges`() {
        withRuntime("ready") { rt ->
            for ((limit, inputValue, gateValue) in listOf(Triple(40, -6, -3), Triple(-5, 6, 0))) {
                val before = rt.calls()
                val input = rt.newFuture().also { rt.complete(it, inputValue) }
                val gate = rt.newFuture().also { rt.complete(it, gateValue) }
                val result = rt.newFuture()
                rt.start(result, input, gate, limit)
                rt.awaitDone(result, "ready limit=$limit")
                val (expected, calls) = oracle(limit, inputValue, gateValue)
                assertEquals(expected, rt.value(result), "limit=$limit")
                assertEquals(calls, rt.calls() - before, "limit=$limit call count")
                rt.awaitSettled(result)
            }
        }
    }

    /**
     * 做什么：挂在 while 条件实参上的帧收到失败时原样发布且不再调用；另一个帧挂在局部初始化
     *   实参上被作用域取消后回收，晚到输入不复活、调用一次都不执行。
     * 失败模式：失败被包装、取消后调用仍执行或帧被复活。
     */
    @Test
    fun `failure and scope cancel at an argument suspension`() {
        withRuntime("terminal") { rt ->
            val failure = rt.loader.loadClass("std.Failure")
                .getConstructor(String::class.java, String::class.java).newInstance("expected", "error")
            val failed = rt.newFuture()
            val failingInput = rt.newFuture()
            val readyGate = rt.newFuture().also { rt.complete(it, 1) }
            rt.start(failed, failingInput, readyGate, 1)
            rt.assertStillPending(failed, "before input failure")
            assertEquals(1, rt.calls())
            rt.future.getMethod("fail", rt.loader.loadClass("std.Failure")).invoke(failingInput, failure)
            rt.awaitDone(failed, "failed")
            assertSame(failure, rt.future.getMethod("failureOrNull").invoke(failed))
            assertEquals(1, rt.calls(), "condition call ran with a failed argument")
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
            assertEquals(1, rt.calls(), "cancelled frame ran a call")
        }
    }

    /**
     * 做什么：固定种子 20261103 的 64 轮：随机 limit / input / gate、每个输入在启动前或启动后
     *   完成，结果与每轮调用次数都与 Kotlin oracle 比对。
     * 失败模式：打印种子、轮次与全部参数；单轮即最小反例。
     */
    @Test
    fun `seeded call-argument await frames match an independent oracle`() {
        val seed = 20261103
        val random = kotlin.random.Random(seed)
        withRuntime("seeded") { rt ->
            repeat(64) { round ->
                val limit = random.nextInt(-8, 9)
                val inputValue = random.nextInt(-6, 7)
                val gateValue = random.nextInt(-3, 11)
                val inputEarly = random.nextBoolean()
                val gateEarly = random.nextBoolean()
                val context = "seed=$seed round=$round limit=$limit input=$inputValue/$inputEarly gate=$gateValue/$gateEarly"
                val before = rt.calls()
                val input = rt.newFuture().also { if (inputEarly) rt.complete(it, inputValue) }
                val gate = rt.newFuture().also { if (gateEarly) rt.complete(it, gateValue) }
                val result = rt.newFuture()
                rt.start(result, input, gate, limit)
                // 第一个挂起点就是局部初始化实参里的 gate：晚到时帧必然挂在它上面，调用尚未执行。
                if (!gateEarly) {
                    rt.assertStillPending(result, context)
                    assertEquals(before, rt.calls(), context)
                    rt.complete(gate, gateValue)
                }
                // gate 已有、input 晚到：之后必经 while 条件实参中的 input 挂起点。
                if (!inputEarly) rt.assertStillPending(result, context)
                if (!inputEarly) rt.complete(input, inputValue)
                rt.awaitDone(result, context)
                val (expected, calls) = oracle(limit, inputValue, gateValue)
                assertEquals(expected, rt.value(result), context)
                assertEquals(calls, rt.calls() - before, "$context call count")
                rt.awaitSettled(result)
            }
        }
    }
}
