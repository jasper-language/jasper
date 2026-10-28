// ============================================================================
// LinearCoroutinePipelineTest.kt —— S4-C 第八片「编译器真实发射并运行」验收
// ============================================================================
// 做什么：用真实 ANTLR 解析、类型检查、挂起效果分析、协程降级、IR 与 JVM 后端编译
//   Jasper 源码，再加载编译器按约定命名的生成帧类，在真实 JvmExecutor 上运行。
//   覆盖单工作线程进展、注册前/后完成、操作数与挂起前表达式只求值一次、失败/取消
//   通道以及 settled 回收；另有一组不支持形态必须明确诊断且不写 class。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做平台反射驱动和有界等待，
//   程序计数器、跨挂起字段、Future 回调与结果发布均由生成的 Jasper AST 表达。
//
// 前置条件：构建虚拟机可运行 JDK；所有 scratch 目录都在模块 build/ 下。
// 失败模式：编译诊断、类加载、反射签名或超时都由断言直接报告，不静默跳过。
// 为什么不用 Future.get 驱动：等待方是测试线程/生产者线程；生成帧内部只注册
//   onComplete 后返回，绝不以阻塞 get 冒充 await。
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

/** 验证编译器真实接入发射器后的运行时语义与诊断边界。 */
class LinearCoroutinePipelineTest {

    /** 单 await 直线函数的源码骨架；静态计数用于证明挂起前后各表达式只执行一次。 */
    private fun program() = """
        package demo;
        import std.Future;
        import std.Runnable;

        public class Program {
            private static var steps: int32 = 0;
            private static var fetches: int32 = 0;

            public static function step(value: int32): int32 {
                Program.steps = Program.steps + 1;
                return value;
            }

            public static function fetch(input: Future<int32>): Future<int32> {
                Program.fetches = Program.fetches + 1;
                return input;
            }

            public static function steps(): int32 { return Program.steps; }
            public static function fetches(): int32 { return Program.fetches; }

            public static function calculate(input: Future<int32>, kept: int32): int32 {
                var before: int32 = Program.step(kept) + 1;
                var got: int32 = await Program.fetch(input);
                var after: int32 = before + got;
                return after * 2;
            }
        }

        public class Producer implements Runnable {
            private var input: Future<int32>;
            public constructor(input: Future<int32>) { this.input = input; }
            public function run(): void { this.input.complete(10); }
        }
    """.trimIndent()

    /** 在模块 build 下编译标准库 + 源码并返回可加载产物；编译失败带聚合诊断。 */
    private fun compile(name: String, source: String): URLClassLoader {
        val output = freshOutput(name)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Program.jas" to source), output)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " +
                ((result as? JasperCompiler.CompileResult.Error)?.message ?: result.toString())
        )
        return URLClassLoader(arrayOf(output.toUri().toURL()))
    }

    /** 清空并创建模块内目录；绝不写仓库外路径。 */
    private fun freshOutput(name: String): Path {
        val output = Paths.get("build", "tmp", "linear-coroutine-pipeline", name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        return output
    }

    /** 断言生成类确实是 Java 8 class（major 52），不引入动态代码生成。 */
    private fun assertClassVersion52(output: Path, internalName: String) {
        val file = output.resolve("$internalName.class")
        assertTrue(Files.exists(file), "missing generated class $file")
        val bytes = Files.readAllBytes(file)
        assertTrue(bytes.size >= 8 && bytes[0] == 0xCA.toByte() && bytes[1] == 0xFE.toByte(), "bad class magic")
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals(52, major, "generated class must keep JVM class version 52")
    }

    /** 加载生成帧并构造一次；构造参数布局由发射器公开约定。 */
    private fun newFrame(loader: ClassLoader, executor: Any, output: Any, input: Any, kept: Int): Any {
        val future = loader.loadClass("std.Future")
        return loader.loadClass("demo.Program_calculate_Coroutine").getConstructor(
            loader.loadClass("std.Executor"), future, future, Int::class.javaPrimitiveType
        ).newInstance(executor, output, input, kept)
    }

    /** 有界轮询 Future 终态，失败时报告 mode，避免测试永久挂起。 */
    private fun awaitDone(future: Any, mode: String) {
        val done = future.javaClass.getMethod("isDone")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (done.invoke(future) != true && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(true, done.invoke(future), "generated frame did not finish: $mode")
    }

    /** 等待生成帧回收 settled；另起有界线程，任一实现缺陷都不会卡住整个测试套件。 */
    private fun awaitSettled(future: Any) {
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit { future.javaClass.getMethod("awaitTaskSettled").invoke(future) }
                .get(5, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    /** 读取 Jasper 静态计数；用于证明 await 操作数与挂起前表达式只求值一次。 */
    private fun staticInt(loader: ClassLoader, method: String): Int =
        loader.loadClass("demo.Program").getMethod(method).invoke(null) as Int

    /** 有界等待静态计数达到期望值，避免把调度异步误当成同步完成。 */
    private fun awaitStaticInt(loader: ClassLoader, method: String, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (staticInt(loader, method) != expected && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(expected, staticInt(loader, method), "timed out waiting for $method")
    }

    /** 关闭 Jasper 执行器并有界确认终止。 */
    private fun shutdown(executor: Any) {
        executor.javaClass.getMethod("shutdown").invoke(executor)
        assertEquals(
            true,
            executor.javaClass.getMethod("awaitTermination", Long::class.javaPrimitiveType)
                .invoke(executor, 5000L)
        )
    }

    /**
     * 做什么：单工作线程上，生成帧挂起后让生产者运行并完成输入，随后恢复得到 26。
     * 什么时候用：修改生成的恢复段、ContinuationDispatch 接线或局部字段布局时。
     * 失败模式：挂起不返回、生产者无法运行、结果错或表达式重复求值。
     */
    @Test
    fun `compiler emits a frame that progresses on one worker`() {
        val output = Paths.get("build", "tmp", "linear-coroutine-pipeline", "pending")
        compile("pending", program()).use { loader ->
            assertClassVersion52(output, "demo/Program_calculate_Coroutine")
            val future = loader.loadClass("std.Future")
            val executor = loader.loadClass("std.JvmExecutor")
                .getConstructor(Int::class.javaPrimitiveType).newInstance(1)
            val input = future.getConstructor().newInstance()
            val result = future.getConstructor().newInstance()
            try {
                val frame = newFrame(loader, executor, result, input, 2)
                frame.javaClass.getMethod("start").invoke(frame)
                // 第一段应已求值一次并注册回调，然后返回工作线程。
                awaitStaticInt(loader, "steps", 1)
                awaitStaticInt(loader, "fetches", 1)
                assertFalse(future.getMethod("isDone").invoke(result) as Boolean, "must suspend")

                val producer = loader.loadClass("demo.Producer")
                    .getConstructor(future).newInstance(input)
                executor.javaClass.getMethod("schedule", loader.loadClass("std.Runnable"))
                    .invoke(executor, producer)

                awaitDone(result, "pending")
                assertEquals(26, future.getMethod("get").invoke(result))
                assertEquals(1, staticInt(loader, "steps"), "pre-suspension expression re-executed")
                assertEquals(1, staticInt(loader, "fetches"), "await operand re-evaluated")
                awaitSettled(result)
            } finally {
                shutdown(executor)
            }
        }
    }

    /**
     * 做什么：输入在注册回调之前已经完成，生成帧仍必须恢复一次并发布结果。
     * 什么时候用：验证 Future.onComplete 的终态后注册立即回调路径不丢恢复。
     * 失败模式：结果停留在 Pending，或恢复段被重复执行。
     */
    @Test
    fun `ready input before registration still resumes`() {
        compile("ready", program()).use { loader ->
            val future = loader.loadClass("std.Future")
            val executor = loader.loadClass("std.JvmExecutor")
                .getConstructor(Int::class.javaPrimitiveType).newInstance(1)
            val input = future.getConstructor().newInstance()
            val result = future.getConstructor().newInstance()
            try {
                future.getMethod("complete", Any::class.java).invoke(input, 10)
                val frame = newFrame(loader, executor, result, input, 2)
                frame.javaClass.getMethod("start").invoke(frame)
                awaitDone(result, "ready")
                assertEquals(26, future.getMethod("get").invoke(result))
                assertEquals(1, staticInt(loader, "fetches"))
                awaitSettled(result)
            } finally {
                shutdown(executor)
            }
        }
    }

    /**
     * 做什么：输入失败与取消分别走结果 Future 的失败/取消通道，并完成 settled 回收。
     * 什么时候用：修改生成恢复段或 ContinuationDispatch 的失败发布路径时。
     * 失败模式：失败被覆盖为成功、取消伪装成业务失败、或 settled 永不回收。
     */
    @Test
    fun `failure and cancellation use their own result channels`() {
        compile("terminal", program()).use { loader ->
            val future = loader.loadClass("std.Future")
            val executor = loader.loadClass("std.JvmExecutor")
                .getConstructor(Int::class.javaPrimitiveType).newInstance(1)
            try {
                val failure = loader.loadClass("std.Failure")
                    .getConstructor(String::class.java, String::class.java)
                    .newInstance("expected", "error")
                val failedInput = future.getConstructor().newInstance()
                future.getMethod("fail", loader.loadClass("std.Failure")).invoke(failedInput, failure)
                val failedResult = future.getConstructor().newInstance()
                val failedFrame = newFrame(loader, executor, failedResult, failedInput, 2)
                failedFrame.javaClass.getMethod("start").invoke(failedFrame)
                awaitDone(failedResult, "failed")
                assertSame(failure, future.getMethod("failureOrNull").invoke(failedResult))
                awaitSettled(failedResult)

                val cancelledInput = future.getConstructor().newInstance()
                future.getMethod("cancel").invoke(cancelledInput)
                val cancelledResult = future.getConstructor().newInstance()
                val cancelledFrame = newFrame(loader, executor, cancelledResult, cancelledInput, 2)
                cancelledFrame.javaClass.getMethod("start").invoke(cancelledFrame)
                awaitDone(cancelledResult, "cancelled")
                assertEquals("Cancelled", future.getMethod("status").invoke(cancelledResult).toString())
                awaitSettled(cancelledResult)
            } finally {
                shutdown(executor)
            }
        }
    }

    /**
     * 做什么：固定种子生成 8 个参数/局部布局不同的直线函数，一次编译后逐个运行，
     *   与独立的 Kotlin 算术语义 oracle 比对；失败消息含种子、用例与最小源码。
     * 什么时候用：修改发射器表达式改写、字段布局或恢复段时锁定不变量。
     * 失败模式：任一用例结果不符、挂起不恢复或超时；单函数即最小反例，无需再缩。
     */
    @Test
    fun `seeded straight-line frames match an independent oracle`() {
        val seed = 20261027
        val random = kotlin.random.Random(seed)
        data class Case(val className: String, val kept: Int, val expected: Int, val declaration: String)
        val cases = (0 until 8).map { index ->
            val add = random.nextInt(0, 6)
            val bonus = random.nextInt(0, 4)
            val kept = random.nextInt(-3, 4)
            val multiplier = random.nextInt(1, 4)
            val withExtra = random.nextBoolean()
            val expected = (10 + kept + add + bonus + if (withExtra) bonus else 0) * multiplier
            val body = buildString {
                appendLine("        var before: int32 = kept + $add + $bonus;")
                appendLine("        var got: int32 = await input;")
                if (withExtra) {
                    appendLine("        var extra: int32 = before + $bonus;")
                    appendLine("        var after: int32 = before + got + extra - before;")
                } else {
                    appendLine("        var after: int32 = before + got;")
                }
                appendLine("        return after * $multiplier;")
            }
            Case(
                "Case$index", kept, expected,
                """
                public class Case$index {
                    public static function calculate(input: Future<int32>, kept: int32): int32 {
                $body        }
                }
                """.trimIndent()
            )
        }
        val source = "package demo;\nimport std.Future;\n\n" + cases.joinToString("\n\n") { it.declaration }
        compile("seeded", source).use { loader ->
            val future = loader.loadClass("std.Future")
            val executor = loader.loadClass("std.JvmExecutor")
                .getConstructor(Int::class.javaPrimitiveType).newInstance(1)
            try {
                for (case in cases) {
                    val input = future.getConstructor().newInstance()
                    future.getMethod("complete", Any::class.java).invoke(input, 10)
                    val result = future.getConstructor().newInstance()
                    val frame = loader.loadClass("demo.${case.className}_calculate_Coroutine").getConstructor(
                        loader.loadClass("std.Executor"), future, future, Int::class.javaPrimitiveType
                    ).newInstance(executor, result, input, case.kept)
                    frame.javaClass.getMethod("start").invoke(frame)
                    awaitDone(result, "seed=$seed case=${case.className}")
                    assertEquals(
                        case.expected,
                        future.getMethod("get").invoke(result),
                        "seed=$seed case=${case.className}\n${case.declaration}"
                    )
                    awaitSettled(result)
                }
            } finally {
                shutdown(executor)
            }
        }
    }

    /**
     * 做什么：多种超出本片支持范围的挂起形态必须在编译期明确诊断，且不写 class。
     * 什么时候用：扩展支持范围时锁定边界，防止退化成同步透传或 Future.get 冒充。
     * 失败模式：某种形态静默通过、只报内部错误或仍产出字节码。
     */
    @Test
    fun `unsupported suspension shapes stay explicit diagnostics`() {
        val cases = mapOf(
            // 第十片起多 await 与 if/else 内 await 已支持（见 BranchingCoroutinePipelineTest），
            // 第十一片起 while 内 await 已支持（见 LoopingCoroutinePipelineTest），第十二片起
            // do-while 与三段式 for 内 await 已支持（见 ForLoopCoroutinePipelineTest），第十三片起
            // 条件、return 值与算术子表达式中的 await 已支持（见 ExpressionAwaitCoroutinePipelineTest）；
            // 这里改锁有副作用运算（含调用）之后的 await。第十五片起短路右操作数中的
            // await 已支持（见 ShortCircuitAwaitCoroutinePipelineTest），同一表达式中更早的除法之后仍须诊断。
            "short-circuit-await-after-division" to """
                package demo;
                import std.Future;
                public class P {
                    public static function f(input: Future<int32>, flag: bool, d: int32): int32 {
                        if ((10 / d > 0) == (flag && (await input) > 0)) { return 1; }
                        return 0;
                    }
                }
            """.trimIndent(),
            // 第十四片起源码静态函数调用实参中的 await 已支持（见 CallArgumentAwaitCoroutinePipelineTest）；
            // 先于 await 的实参是调用、以及实参中有 await 的调用之后再 await 仍须诊断。
            "await-after-call-argument" to """
                package demo;
                import std.Future;
                public class P {
                    public static function add(a: int32, b: int32): int32 { return a + b; }
                    public static function f(input: Future<int32>): int32 { return P.add(P.add(1, 2), await input); }
                }
            """.trimIndent(),
            "await-after-call-with-awaited-argument" to """
                package demo;
                import std.Future;
                public class P {
                    public static function add(a: int32, b: int32): int32 { return a + b; }
                    public static function f(input: Future<int32>): int32 { return P.add(await input, 1) + (await input); }
                }
            """.trimIndent(),
            // 被调函数自身挂起时，即使实参中的 await 可提升，对它的同步调用仍须诊断。
            "awaited-argument-to-suspending-callee" to """
                package demo;
                import std.Future;
                public class P {
                    public static function leaf(input: Future<int32>, extra: int32): int32 {
                        var value: int32 = await input;
                        return value + extra;
                    }
                    public static function caller(input: Future<int32>): int32 { return P.leaf(input, await input); }
                }
            """.trimIndent(),
            "await-after-call" to """
                package demo;
                import std.Future;
                public class P {
                    public static function id(value: int32): int32 { return value; }
                    public static function f(input: Future<int32>): int32 { return P.id(1) + (await input); }
                }
            """.trimIndent(),
            "await-after-division" to """
                package demo;
                import std.Future;
                public class P {
                    public static function f(input: Future<int32>, d: int32): int32 { return 10 / d + (await input); }
                }
            """.trimIndent(),
            "synchronous-call-in-loop" to """
                package demo;
                import std.Future;
                public class P {
                    public static function leaf(input: Future<int32>): int32 {
                        var value: int32 = await input;
                        return value;
                    }
                    public static function caller(input: Future<int32>, count: int32): int32 {
                        var total: int32 = await input;
                        while (total < count) { total = total + P.leaf(input); }
                        return total;
                    }
                }
            """.trimIndent(),
            // 第十七片起 try/finally 内 await 已支持（见 TryFinallyCoroutinePipelineTest），第十八片起 try/catch
            // 内 await 已支持（见 TryCatchCoroutinePipelineTest）；处理体引用捕获参数、finally 中的 await 与
            // finally 中对挂起函数的同步调用仍须诊断。
            "catch-parameter-used" to """
                package demo;
                import std.Future;
                import std.Error;
                public class P {
                    public static function f(input: Future<int32>): int32 {
                        var value: int32 = 0;
                        try { value = await input; } catch (e: Error) { var m: string = e.message(); value = 1; }
                        return value;
                    }
                }
            """.trimIndent(),
            "await-in-finally" to """
                package demo;
                import std.Future;
                public class P {
                    public static function f(input: Future<int32>): int32 {
                        var value: int32 = 0;
                        try { value = 1; } finally { value = await input; }
                        return value;
                    }
                }
            """.trimIndent(),
            "synchronous-call-in-finally" to """
                package demo;
                import std.Future;
                public class P {
                    public static function leaf(input: Future<int32>): int32 {
                        var value: int32 = await input;
                        return value;
                    }
                    public static function caller(input: Future<int32>): int32 {
                        var first: int32 = 0;
                        try { first = await input; } finally { P.leaf(input); }
                        return first;
                    }
                }
            """.trimIndent(),
            "synchronous-call-in-branch" to """
                package demo;
                import std.Future;
                public class P {
                    public static function leaf(input: Future<int32>): int32 {
                        var value: int32 = await input;
                        return value;
                    }
                    public static function caller(input: Future<int32>, flag: bool): int32 {
                        var first: int32 = await input;
                        if (flag) { first = P.leaf(input); }
                        return first;
                    }
                }
            """.trimIndent(),
            "propagated-suspension" to """
                package demo;
                import std.Future;
                public class P {
                    public static function leaf(input: Future<int32>): int32 {
                        var value: int32 = await input;
                        return value;
                    }
                    public static function caller(input: Future<int32>): int32 { return P.leaf(input); }
                }
            """.trimIndent(),
            // 调用者自身形态合法（恰好一个 await），但又在非 await 位置同步调用另一个挂起函数：
            // 不能因被调函数已被生成帧替代而退化成内部错误或同步求值。
            "synchronous-call-to-suspending" to """
                package demo;
                import std.Future;
                public class P {
                    public static function leaf(input: Future<int32>): int32 {
                        var value: int32 = await input;
                        return value;
                    }
                    public static function caller(input: Future<int32>): int32 {
                        var first: int32 = await input;
                        var second: int32 = P.leaf(input);
                        return first + second;
                    }
                }
            """.trimIndent(),
            "non-int32" to """
                package demo;
                import std.Future;
                public class P {
                    public static function f(input: Future<int64>): int64 {
                        var value: int64 = await input;
                        return value;
                    }
                }
            """.trimIndent()
        )
        for ((name, source) in cases) {
            val output = freshOutput("unsupported-$name")
            val result = JasperCompiler().compileAllWithStdlib(mapOf("P.jas" to source), output)
            assertTrue(result is JasperCompiler.CompileResult.Error, "$name unexpectedly compiled")
            val message = (result as JasperCompiler.CompileResult.Error).message
            assertTrue(
                message.contains("non-blocking await lowering is not implemented yet"),
                "$name: $message"
            )
            Files.walk(output).use { paths ->
                assertFalse(paths.anyMatch { it.toString().endsWith(".class") }, "$name wrote bytecode")
            }
        }
    }
}
