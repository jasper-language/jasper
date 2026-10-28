// ============================================================================
// ShortCircuitAwaitCoroutinePipelineTest.kt —— S4-C 第十五片「短路右操作数中的 await」端到端验收
// ============================================================================
// 做什么：用真实 ANTLR 解析、类型检查、挂起效果分析、协程降级、IR 与 JVM 后端编译
//   await 出现在 `&&` / `||` 右操作数中的 Jasper 函数（if 条件、嵌套在 while 条件里的
//   `||`），加载生成帧在真实 JvmExecutor 上运行：左操作数决定结果时右操作数中的 await
//   不挂起、对应 Future 从不完成也能跑完；需要右操作数时按序挂起；右操作数挂起点的失败
//   与挂起中被作用域取消；以及固定种子与 Kotlin oracle 比对结果和调用次数。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做反射驱动与有界等待，程序计数器、
//   合成 bool / int32 临时局部、Future 回调与结果发布均由生成的 Jasper AST 表达。
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

/** 验证短路右操作数中的 await 经分支降级后的生成帧运行语义。 */
class ShortCircuitAwaitCoroutinePipelineTest {

    /**
     * 被测函数：`probe(v)` 计数自身调用次数并原样返回。if 条件 `limit > 0 && (await gate) > 2`
     * 只在 limit 为正时等待 gate；while 条件 `i < 30 && (probe(i) > limit || (await input) > i)`
     * 中左侧调用每趟执行一次，只有它为假时才等待 input。
     */
    private fun program() = """
        package demo;
        import std.Future;

        public class Program {
            private static var calls: int32 = 0;

            public static function probe(v: int32): int32 {
                Program.calls = Program.calls + 1;
                return v;
            }

            public static function calls(): int32 { return Program.calls; }

            public static function run(input: Future<int32>, gate: Future<int32>, limit: int32): int32 {
                var total: int32 = 0;
                var i: int32 = 0;
                if (limit > 0 && (await gate) > 2) {
                    total = total + 10;
                }
                while (i < 30 && (Program.probe(i) > limit || (await input) > i)) {
                    total = total + i;
                    i = i + 1;
                }
                return total + i;
            }
        }
    """.trimIndent()

    /** oracle 结果：返回值、probe 调用次数、gate / input 是否被等待过。 */
    private data class Expected(val value: Int, val calls: Int, val awaitsGate: Boolean, val awaitsInput: Boolean)

    /** 与源码同义的 Kotlin oracle；只描述语义，不参与生成。 */
    private fun oracle(limit: Int, input: Int, gate: Int): Expected {
        var calls = 0
        var awaitsInput = false
        fun probe(v: Int): Int { calls++; return v }
        var total = 0
        var i = 0
        if (limit > 0 && gate > 2) total += 10
        while (i < 30 && (probe(i) > limit || input.also { awaitsInput = true } > i)) {
            total += i
            i += 1
        }
        return Expected(total + i, calls, limit > 0, awaitsInput)
    }

    private val root: Path = Paths.get("build", "tmp", "short-circuit-await-coroutine-pipeline")

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
     * 做什么：limit 为负时 `&&` 左侧为假、`||` 左侧每趟为真：gate 与 input 从不完成，帧仍须
     *   同步跑完 30 趟；probe 恰好调用 30 次。生成类 major version 52。
     * 失败模式：右操作数被无条件求值（帧永远挂在从不完成的 Future 上）或调用次数错。
     */
    @Test
    fun `short-circuited right operands never suspend`() {
        withRuntime("skipped") { rt ->
            val bytes = Files.readAllBytes(root.resolve("skipped/demo/Program_run_Coroutine.class"))
            assertEquals(52, ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF))
            val result = rt.newFuture()
            rt.start(result, rt.newFuture(), rt.newFuture(), -1)
            rt.awaitDone(result, "skipped")
            val expected = oracle(-1, 0, 0)
            assertFalse(expected.awaitsGate || expected.awaitsInput)
            assertEquals(expected.value, rt.value(result))
            assertEquals(expected.calls, rt.calls())
            rt.awaitSettled(result)
        }
    }

    /**
     * 做什么：单工作线程，limit = 1：帧先挂在 if 条件右操作数的 gate 上（probe 未执行）；补齐 gate
     *   后进入循环，首趟 probe(0) 不大于 limit，挂在 `||` 右操作数的 input 上（probe 恰好 1 次）；
     *   补齐 input 后跑完，结果与调用次数与 oracle 一致。
     * 失败模式：右侧块未挂起、分支方向接反、恢复后重放左操作数调用。
     */
    @Test
    fun `needed right operands suspend in order`() {
        withRuntime("ordered") { rt ->
            val input = rt.newFuture()
            val gate = rt.newFuture()
            val result = rt.newFuture()
            rt.start(result, input, gate, 1)
            rt.assertStillPending(result, "if condition right operand")
            assertEquals(0, rt.calls(), "loop condition ran before the gate resumed")
            rt.complete(gate, 5)
            rt.assertStillPending(result, "while condition right operand")
            assertEquals(1, rt.calls(), "left operand call must run exactly once before the suspension")
            rt.complete(input, 3)
            rt.awaitDone(result, "ordered")
            val expected = oracle(1, 3, 5)
            assertEquals(expected.value, rt.value(result))
            assertEquals(expected.calls, rt.calls(), "call count differs from the oracle")
            rt.awaitSettled(result)
        }
    }

    /**
     * 做什么：挂在 if 条件右操作数上的帧收到 gate 失败时原样发布、循环一次都不执行；另一个帧
     *   挂在 while 条件 `||` 右操作数上被作用域取消后回收，晚到输入不复活、不再调用 probe。
     * 失败模式：失败被包装、取消后仍执行左操作数调用或帧被复活。
     */
    @Test
    fun `failure and scope cancel at a right-operand suspension`() {
        withRuntime("terminal") { rt ->
            val failure = rt.loader.loadClass("std.Failure")
                .getConstructor(String::class.java, String::class.java).newInstance("expected", "error")
            val failed = rt.newFuture()
            val failingGate = rt.newFuture()
            rt.start(failed, rt.newFuture(), failingGate, 2)
            rt.assertStillPending(failed, "before gate failure")
            rt.future.getMethod("fail", rt.loader.loadClass("std.Failure")).invoke(failingGate, failure)
            rt.awaitDone(failed, "failed")
            assertSame(failure, rt.future.getMethod("failureOrNull").invoke(failed))
            assertEquals(0, rt.calls(), "loop ran after a failed condition operand")
            rt.awaitSettled(failed)

            val scoped = rt.newFuture()
            val lateInput = rt.newFuture()
            rt.start(scoped, lateInput, rt.newFuture().also { rt.complete(it, 0) }, 4)
            rt.assertStillPending(scoped, "before scope cancel")
            assertEquals(1, rt.calls())
            rt.future.getMethod("cancel").invoke(scoped)
            rt.awaitSettled(scoped)
            rt.complete(lateInput, 9)
            Thread.sleep(50)
            assertEquals("Cancelled", rt.status(scoped), "late input revived a cancelled frame")
            assertEquals(1, rt.calls(), "cancelled frame ran the left operand again")
        }
    }

    /**
     * 做什么：固定种子 20261105 的 64 轮：随机 limit / input / gate、每个输入在启动前或启动后完成；
     *   oracle 说明会等待的晚到输入，帧必须先挂在它上面；结果与每轮调用次数都与 oracle 比对。
     * 失败模式：打印种子、轮次与全部参数；单轮即最小反例。
     */
    @Test
    fun `seeded short-circuit await frames match an independent oracle`() {
        val seed = 20261105
        val random = kotlin.random.Random(seed)
        withRuntime("seeded") { rt ->
            repeat(64) { round ->
                val limit = random.nextInt(-3, 9)
                val inputValue = random.nextInt(-2, 36)
                val gateValue = random.nextInt(-2, 7)
                val inputEarly = random.nextBoolean()
                val gateEarly = random.nextBoolean()
                val context = "seed=$seed round=$round limit=$limit input=$inputValue/$inputEarly gate=$gateValue/$gateEarly"
                val expected = oracle(limit, inputValue, gateValue)
                val before = rt.calls()
                val input = rt.newFuture().also { if (inputEarly) rt.complete(it, inputValue) }
                val gate = rt.newFuture().also { if (gateEarly) rt.complete(it, gateValue) }
                val result = rt.newFuture()
                rt.start(result, input, gate, limit)
                // gate 是第一个可能的挂起点：会被等待且晚到时帧必然挂在它上面，probe 尚未执行。
                if (expected.awaitsGate && !gateEarly) {
                    rt.assertStillPending(result, context)
                    assertEquals(before, rt.calls(), context)
                }
                if (!gateEarly) rt.complete(gate, gateValue)
                if (expected.awaitsInput && !inputEarly) rt.assertStillPending(result, context)
                if (!inputEarly) rt.complete(input, inputValue)
                rt.awaitDone(result, context)
                assertEquals(expected.value, rt.value(result), context)
                assertEquals(expected.calls, rt.calls() - before, "$context call count")
                rt.awaitSettled(result)
            }
        }
    }
}
