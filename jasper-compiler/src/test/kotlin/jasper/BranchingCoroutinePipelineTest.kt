// ============================================================================
// BranchingCoroutinePipelineTest.kt —— S4-C 第十片「多 await + if/else 分支」端到端验收
// ============================================================================
// 做什么：用真实 ANTLR 解析、类型检查、挂起效果分析、协程降级、IR 与 JVM 后端编译
//   含 else-if 链、分支内 await、提前 return、分支汇合后第二个 await 的 Jasper 函数，
//   加载生成帧在真实 JvmExecutor 上运行：单工作线程逐个挂起/恢复、注册前完成、无挂起
//   路径、第二个挂起点的失败/取消/作用域取消回收，以及固定种子与 Kotlin oracle 比对。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做反射驱动与有界等待，程序计数器、
//   跨挂起字段、Future 回调与结果发布均由生成的 Jasper AST 表达。
//
// 前置条件：构建虚拟机可运行 JDK；scratch 目录都在模块 build/ 下。
// 失败模式：编译诊断、类加载、反射签名或超时都由断言直接报告，不静默跳过。
// 为什么不用 Future.get 驱动帧：等待方是测试线程；生成帧内部只注册 onComplete 后返回。
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

/** 验证分支与多挂起点生成帧的运行语义。 */
class BranchingCoroutinePipelineTest {

    /**
     * 被测函数：then 分支挂起一次并声明分支局部；else-if 分支直接 return（不挂起）；
     * else 分支以 await 初始化分支局部；三路汇合后再 await 一次，最后按结果分支 return。
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

            public static function choose(flag: bool, first: Future<int32>, second: Future<int32>, base: int32): int32 {
                var before: int32 = Program.step(base);
                var value: int32 = 0;
                if (flag) {
                    value = await first;
                    var bonus: int32 = value * 10;
                    value = value + bonus;
                } else if (base > 100) {
                    return before + 1000;
                } else {
                    var got: int32 = await first;
                    value = got - 1;
                }
                var tail: int32 = await second;
                if (tail < 0) {
                    return -1;
                } else {
                    return before + value + tail;
                }
            }
        }
    """.trimIndent()

    /** 与源码同义的 Kotlin oracle；只描述语义，不参与生成。 */
    private fun oracle(flag: Boolean, base: Int, first: Int, second: Int): Int {
        val before = base
        val value = when {
            flag -> first + first * 10
            base > 100 -> return before + 1000
            else -> first - 1
        }
        return if (second < 0) -1 else before + value + second
    }

    private val root: Path = Paths.get("build", "tmp", "branching-coroutine-pipeline")

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
        fun steps() = loader.loadClass("demo.Program").getMethod("steps").invoke(null) as Int

        /** 构造并启动一个生成帧；参数布局 (Executor, output, 原参数...) 由发射器约定。 */
        fun start(result: Any, flag: Boolean, first: Any, second: Any, base: Int): Any {
            val frame = loader.loadClass("demo.Program_choose_Coroutine").getConstructor(
                loader.loadClass("std.Executor"), future,
                Boolean::class.javaPrimitiveType, future, future, Int::class.javaPrimitiveType
            ).newInstance(executor, result, flag, first, second, base)
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
     * 做什么：then 分支在单工作线程上先挂在第一个 await，补齐后挂在汇合后的第二个 await，
     *   再补齐得到 40；挂起前表达式只执行一次；生成类 major version 52。
     * 失败模式：分支后不汇合、第二挂起点被跳过、恢复重放前段或结果错。
     */
    @Test
    fun `then branch suspends twice and joins`() {
        withRuntime("then") { rt ->
            val bytes = Files.readAllBytes(root.resolve("then/demo/Program_choose_Coroutine.class"))
            assertEquals(52, ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF))
            val first = rt.newFuture()
            val second = rt.newFuture()
            val result = rt.newFuture()
            rt.start(result, true, first, second, 2)
            rt.assertStillPending(result, "first await")
            assertEquals(1, rt.steps())
            rt.complete(first, 3)
            rt.assertStillPending(result, "second await")
            rt.complete(second, 5)
            rt.awaitDone(result, "then")
            assertEquals(40, rt.future.getMethod("get").invoke(result))
            assertEquals(1, rt.steps(), "pre-suspension expression re-executed")
            rt.awaitSettled(result)
        }
    }

    /**
     * 做什么：else 分支在两个输入都已完成时启动（注册前完成）仍逐个恢复得到 9；
     *   else-if 分支直接 return 1202，不需要任何输入完成。
     * 失败模式：注册前完成丢恢复；无挂起路径错误地等待输入。
     */
    @Test
    fun `else branch with ready inputs and early return without suspension`() {
        withRuntime("else") { rt ->
            val first = rt.newFuture().also { rt.complete(it, 3) }
            val second = rt.newFuture().also { rt.complete(it, 5) }
            val result = rt.newFuture()
            rt.start(result, false, first, second, 2)
            rt.awaitDone(result, "else")
            assertEquals(9, rt.future.getMethod("get").invoke(result))
            rt.awaitSettled(result)

            val early = rt.newFuture()
            rt.start(early, false, rt.newFuture(), rt.newFuture(), 202)
            rt.awaitDone(early, "early return")
            assertEquals(1202, rt.future.getMethod("get").invoke(early))
            rt.awaitSettled(early)
        }
    }

    /**
     * 做什么：第二个挂起点上的失败与取消分别走结果 Future 的失败/取消通道；挂起中的帧
     *   被外部取消结果（作用域取消）后回收，晚到输入不复活帧。
     * 失败模式：第二恢复块的结果消费缺失、取消伪装成失败、取消后 settled 不回收或被复活。
     */
    @Test
    fun `second suspension point failure cancellation and scope cancel`() {
        withRuntime("terminal") { rt ->
            val failure = rt.loader.loadClass("std.Failure")
                .getConstructor(String::class.java, String::class.java).newInstance("expected", "error")
            val failed = rt.newFuture()
            val failingSecond = rt.newFuture()
            rt.start(failed, true, rt.newFuture().also { rt.complete(it, 1) }, failingSecond, 0)
            rt.assertStillPending(failed, "before failure")
            rt.future.getMethod("fail", rt.loader.loadClass("std.Failure")).invoke(failingSecond, failure)
            rt.awaitDone(failed, "failed")
            assertSame(failure, rt.future.getMethod("failureOrNull").invoke(failed))
            rt.awaitSettled(failed)

            val cancelled = rt.newFuture()
            val cancelledSecond = rt.newFuture()
            rt.start(cancelled, false, rt.newFuture().also { rt.complete(it, 1) }, cancelledSecond, 0)
            rt.assertStillPending(cancelled, "before cancel")
            rt.future.getMethod("cancel").invoke(cancelledSecond)
            rt.awaitDone(cancelled, "cancelled")
            assertEquals("Cancelled", rt.status(cancelled))
            rt.awaitSettled(cancelled)

            val scoped = rt.newFuture()
            val lateFirst = rt.newFuture()
            val lateSecond = rt.newFuture()
            rt.start(scoped, true, lateFirst, lateSecond, 0)
            rt.assertStillPending(scoped, "before scope cancel")
            rt.future.getMethod("cancel").invoke(scoped)
            rt.awaitSettled(scoped)
            rt.complete(lateFirst, 4)
            rt.complete(lateSecond, 4)
            Thread.sleep(50)
            assertEquals("Cancelled", rt.status(scoped), "late input revived a cancelled frame")
            assertEquals(3, rt.steps(), "cancelled frame re-ran a segment")
        }
    }

    /**
     * 做什么：固定种子 20261029 的 48 轮：随机 flag / base / 两个输入值 / 每个输入在启动前
     *   或启动后完成，结果与 Kotlin oracle 比对。
     * 失败模式：打印种子、轮次与全部参数；单轮即最小反例。
     */
    @Test
    fun `seeded branch frames match an independent oracle`() {
        val seed = 20261029
        val random = kotlin.random.Random(seed)
        withRuntime("seeded") { rt ->
            repeat(48) { round ->
                val flag = random.nextBoolean()
                val base = if (random.nextInt(4) == 0) random.nextInt(101, 120) else random.nextInt(-5, 6)
                val firstValue = random.nextInt(-5, 6)
                val secondValue = random.nextInt(-3, 6)
                val firstEarly = random.nextBoolean()
                val secondEarly = random.nextBoolean()
                val context = "seed=$seed round=$round flag=$flag base=$base first=$firstValue/" +
                    "$firstEarly second=$secondValue/$secondEarly"
                val first = rt.newFuture().also { if (firstEarly) rt.complete(it, firstValue) }
                val second = rt.newFuture().also { if (secondEarly) rt.complete(it, secondValue) }
                val result = rt.newFuture()
                rt.start(result, flag, first, second, base)
                if (!firstEarly) rt.complete(first, firstValue)
                if (!secondEarly) rt.complete(second, secondValue)
                rt.awaitDone(result, context)
                assertEquals(oracle(flag, base, firstValue, secondValue), rt.future.getMethod("get").invoke(result), context)
                rt.awaitSettled(result)
            }
        }
    }
}
