// ============================================================================
// ValueTypeCoroutinePipelineTest.kt —— S4-C 第二十一片「帧值类型」验收
// ============================================================================
// 做什么：经真实 ANTLR 解析、类型检查、协程降级、IR 与 JVM 后端编译 Jasper 源码，加载生成帧
//   并在真实 JvmExecutor 上运行，验证 int64 / float64 / bool / string 作参数、局部、await
//   结果、合成临时局部与返回值时值与装箱类型都正确；另有固定种子属性测试与拒绝清单。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做平台反射驱动和有界等待。
//
// 失败模式：编译诊断、VerifyError、拆箱失败或超时都由断言直接报告，不静默跳过。
// 为什么同时断言装箱类型：`Future<int64>` 里混进 Integer 时比较 toString 看不出来，下游拆箱才炸。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证生成帧在 int32 以外的值类型上的编译与运行语义。 */
class ValueTypeCoroutinePipelineTest {

    private val root: Path = Paths.get("build", "tmp", "value-type-coroutine-pipeline")

    /** 清空并创建模块内目录；绝不写仓库外路径。 */
    private fun freshOutput(name: String): Path {
        val output = root.resolve(name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        return output
    }

    /** 编译标准库 + 源码；编译失败带聚合诊断。 */
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

    /** 反射句柄集合。 */
    private class Runtime(val loader: ClassLoader) {
        val future: Class<*> = loader.loadClass("std.Future")
        val executorType: Class<*> = loader.loadClass("std.Executor")
        val executor: Any = loader.loadClass("std.JvmExecutor")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(1)

        fun newFuture(): Any = future.getConstructor().newInstance()
        fun complete(target: Any, value: Any) { future.getMethod("complete", Any::class.java).invoke(target, value) }
        fun fail(target: Any, message: String) {
            val failure = loader.loadClass("std.Failure").getConstructor(String::class.java, String::class.java)
                .newInstance(message, "test")
            future.getMethod("fail", failure.javaClass).invoke(target, failure)
        }
        fun cancel(target: Any) { future.getMethod("cancel").invoke(target) }
        fun status(target: Any): String = future.getMethod("status").invoke(target).toString()

        /** 有界等待终态后读值。 */
        fun awaitDone(target: Any, label: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (future.getMethod("isDone").invoke(target) != true && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(true, future.getMethod("isDone").invoke(target), "frame did not finish: $label")
        }

        fun value(target: Any, label: String): Any? {
            awaitDone(target, label)
            if (status(target) != "Completed") {
                throw AssertionError("$label: ${status(target)} ${future.getMethod("failureOrNull").invoke(target)?.let { it.javaClass.getMethod("message").invoke(it) }}")
            }
            return try {
                future.getMethod("get").invoke(target)
            } catch (error: InvocationTargetException) {
                throw AssertionError("$label: ${error.targetException}", error.targetException)
            }
        }

        /** 启动 `demo.Program_<function>_Coroutine`；构造器形如 (Executor, Future<结果>, 参数...)。 */
        fun start(function: String, output: Any, vararg args: Any) {
            val type = loader.loadClass("demo.Program_${function}_Coroutine")
            val constructor = type.constructors.single()
            assertEquals(executorType, constructor.parameterTypes[0], function)
            val frame = constructor.newInstance(executor, output, *args)
            type.getMethod("start").invoke(frame)
        }

        fun shutdown() {
            executor.javaClass.getMethod("shutdown").invoke(executor)
            assertEquals(true, executor.javaClass.getMethod("awaitTermination", Long::class.javaPrimitiveType)
                .invoke(executor, 5000L))
        }
    }

    /** 单个函数的运行：输入未完成时启动，确认挂起后再逐个完成。 */
    private fun Runtime.runSuspended(function: String, inputs: List<Any>, vararg plain: Any): Any? {
        val output = newFuture()
        val futures = inputs.map { newFuture() }
        start(function, output, *(futures + plain.toList()).toTypedArray())
        futures.zip(inputs).forEach { (future, value) -> complete(future, value) }
        return value(output, function)
    }

    private val program = """
        package demo;
        import std.Future;
        import std.Error;

        public class Program {
            private static var cleanups: string = "";

            public static function note(text: string): void { Program.cleanups = Program.cleanups + text; }
            public static function cleanups(): string { return Program.cleanups; }
            public static function twice(value: int64): int64 { return value * 2L; }

            // 宽类型跨挂起：int64 / float64 占两个局部槽，字段写入后紧跟 int32 参数不能串位。
            public static function wide(input: Future<int64>, scale: int32, base: int64): int64 {
                var before: int64 = base + 1L;
                var got: int64 = await input;
                var after: int64 = before * got + scale;
                return after;
            }

            public static function real(input: Future<float64>, base: float64): float64 {
                var got: float64 = await input;
                return got / 4.0 + base;
            }

            public static function text(input: Future<string>, prefix: string): string {
                var got: string = await input;
                var joined: string = prefix + got;
                return joined + "!";
            }

            public static function flag(input: Future<bool>, other: bool): bool {
                var got: bool = await input;
                return got && other;
            }

            // int32 结果隐式宽化：return / 局部初始化都要按目标类型装箱。
            public static function widenReturn(input: Future<int32>): int64 {
                var small: int32 = await input;
                var big: int64 = small + 1;
                if (big > 100L) { return 7; }
                return big;
            }

            public static function widenFloat(input: Future<int32>): float64 {
                var small: int32 = await input;
                return small;
            }

            // await 出现在子表达式：合成临时局部按 await 结果类型分配。
            public static function hoisted(a: Future<int64>, b: Future<float64>, c: Future<string>): string {
                var sum: int64 = 1L + await a;
                var half: bool = (await b) > 0.5;
                if (half) { return "big:" + await c; }
                return "small:" + sum;
            }

            public static function shortCircuit(flag: bool, gate: Future<bool>): bool {
                return flag && await gate;
            }

            public static function callArgument(input: Future<int64>): int64 {
                return Program.twice(await input) + 1L;
            }

            // 跨类型混用：string 函数等待 int32 Future，int64 循环变量自增。
            public static function loop(step: Future<int64>, limit: int64): string {
                var out: string = "";
                for (var i: int64 = 0L; i < limit; i++) {
                    var got: int64 = await step;
                    out = out + (i + got) + ",";
                }
                return out;
            }

            public static function countDown(input: Future<int64>): int64 {
                var n: int64 = await input;
                n--;
                n--;
                return n;
            }

            // try 体内 return：返回值先固定（临时局部按返回类型），清理改写局部不影响结果。
            public static function pinned(input: Future<string>): string {
                var tag: string = "a";
                try {
                    tag = await input;
                    return tag + "-kept";
                } finally {
                    var mark: string = tag + ";";
                    Program.note(mark);
                    tag = "changed";
                }
            }

            public static function cleanupIncrement(input: Future<int64>): int64 {
                var n: int64 = 0L;
                try {
                    n = await input;
                } finally {
                    n++;
                }
                return n;
            }

            public static function caught(input: Future<int64>): int64 {
                var code: int64 = 5L;
                try {
                    code = await input;
                } catch (e: std.FutureFailureError) {
                    code = code + await Program.ready();
                }
                return code;
            }

            private static var readyFuture: Future<int64> = new Future<int64>();
            public static function ready(): Future<int64> {
                Program.readyFuture.complete(100L);
                return Program.readyFuture;
            }
        }
    """.trimIndent()

    /** 同一份编译产物跑一组用例，降低编译开销。 */
    private fun withProgram(name: String, body: Runtime.() -> Unit) {
        compile(name, program).use { loader ->
            val runtime = Runtime(loader)
            try { runtime.body() } finally { runtime.shutdown() }
        }
    }

    @Test
    fun `each value type crosses a suspension with the right value and box`() = withProgram("basic") {
        // (3 + 1) * 5 + 2；超出 int32 的值证明不是窄化存放。
        assertEquals(22L, runSuspended("wide", listOf(5L), 2, 3L))
        assertEquals(4_000_000_000L * 3 + 1 + 0L, runSuspended("wide", listOf(4_000_000_000L), 1, 2L))
        assertEquals(1.25 + 0.5, runSuspended("real", listOf(5.0), 0.5))
        assertEquals("ab!", runSuspended("text", listOf("b"), "a"))
        assertEquals(true, runSuspended("flag", listOf(true), true))
        assertEquals(false, runSuspended("flag", listOf(true), false))
        assertEquals(false, runSuspended("flag", listOf(false), true))
    }

    @Test
    fun `int32 values widen to the declared type before boxing`() = withProgram("widen") {
        assertEquals(10L, runSuspended("widenReturn", listOf(9)))
        assertEquals(7L, runSuspended("widenReturn", listOf(100)))
        assertEquals(3.0, runSuspended("widenFloat", listOf(3)))
    }

    @Test
    fun `hoisted awaits get temporaries of their own result type`() = withProgram("hoist") {
        assertEquals("big:x", runSuspended("hoisted", listOf(1L, 0.75, "x")))
        assertEquals("small:6", runSuspended("hoisted", listOf(5L, 0.25, "x")))
        assertEquals(21L, runSuspended("callArgument", listOf(10L)))
    }

    @Test
    fun `short circuit on awaited bool does not touch the gate`() = withProgram("short") {
        val pending = newFuture()
        val skipped = newFuture()
        start("shortCircuit", skipped, false, pending)
        assertEquals(false, value(skipped, "skipped"))
        val taken = newFuture()
        start("shortCircuit", taken, true, pending)
        assertFalse(future.getMethod("isDone").invoke(taken) as Boolean, "must suspend on gate")
        complete(pending, true)
        assertEquals(true, value(taken, "taken"))
    }

    @Test
    fun `int64 loop variable and increment keep their width`() = withProgram("loop") {
        val step = newFuture()
        complete(step, 10L)
        val output = newFuture()
        start("loop", output, step, 3L)
        assertEquals("10,11,12,", value(output, "loop"))
        // 跨 int32 边界的自减：用 int32 算术会回绕。
        assertEquals(2_147_483_648L - 2, runSuspended("countDown", listOf(2_147_483_648L)))
    }

    @Test
    fun `return value is pinned before cleanup for non int32 results`() = withProgram("pinned") {
        assertEquals("v-kept", runSuspended("pinned", listOf("v")))
        val cleanups = loader.loadClass("demo.Program").getMethod("cleanups").invoke(null)
        assertEquals("v;", cleanups, "cleanup ran once and saw the assigned local")
        assertEquals(2_147_483_648L, runSuspended("cleanupIncrement", listOf(2_147_483_647L)))
    }

    @Test
    fun `failure and cancellation channels are independent of the result type`() = withProgram("channels") {
        val failing = newFuture()
        val failed = newFuture()
        start("wide", failed, failing, 1, 1L)
        fail(failing, "boom")
        awaitDone(failed, "failed")
        assertEquals("Failed", status(failed))

        val cancelling = newFuture()
        val cancelled = newFuture()
        start("text", cancelled, cancelling, "p")
        cancel(cancelling)
        awaitDone(cancelled, "cancelled")
        assertEquals("Cancelled", status(cancelled))

        // catch 处理块里读取 int64 局部并再次挂起。
        val caughtInput = newFuture()
        val caught = newFuture()
        start("caught", caught, caughtInput)
        fail(caughtInput, "boom")
        assertEquals(105L, value(caught, "caught"))
    }

    /**
     * 固定种子的属性测试：随机生成多类型混用的挂起函数，生成时同步算出每个局部的期望值，
     * 运行结果（值与装箱类型）必须完全一致。每个函数跑两次：输入预先完成、启动后才完成。
     * 为什么在生成时求值：表达式只含确定性运算，oracle 用 Kotlin 的同宽度运算，不经被测编译器。
     */
    @Test
    fun `seeded mixed type programs agree with the Kotlin oracle`() {
        val random = Random(20261141L)
        val programs = (0 until 160).map { generate(it, random) }
        val source = buildString {
            appendLine("package demo;")
            appendLine("import std.Future;")
            appendLine("public class Program {")
            programs.forEach { appendLine(it.source) }
            appendLine("}")
        }
        val byType = mutableMapOf<String, Int>()
        var awaitsInExpressions = 0
        compile("seeded", source).use { loader ->
            val runtime = Runtime(loader)
            try {
                programs.forEach { program ->
                    val label = "${program.name}\n${program.source}"
                    val pre = program.inputs.map { value -> runtime.newFuture().also { runtime.complete(it, value) } }
                    val first = runtime.newFuture()
                    runtime.start(program.name, first, *pre.toTypedArray())
                    assertEquals(program.expected, runtime.value(first, label), label)
                    assertEquals(program.expected, runtime.runSuspended(program.name, program.inputs), label)
                    byType.merge(program.returnType, 1, Int::plus)
                    awaitsInExpressions += program.hoisted
                }
            } finally {
                runtime.shutdown()
            }
        }
        listOf("int32", "int64", "float64", "bool", "string").forEach { type ->
            assertTrue(byType.getOrDefault(type, 0) >= 15, "coverage of $type: $byType")
        }
        assertTrue(awaitsInExpressions >= 100, "hoisted awaits: $awaitsInExpressions")
    }

    private class Generated(
        val name: String,
        val source: String,
        val inputs: List<Any>,
        val expected: Any,
        val returnType: String,
        val hoisted: Int
    )

    /** 生成中的局部：源码名、类型与当前值。 */
    private data class Local(val name: String, val type: String, val value: Any)

    /** 一个带值的表达式：源码文本 + 期望值。 */
    private data class Valued(val text: String, val value: Any)

    private fun generate(index: Int, random: Random): Generated {
        val inputs = mutableListOf<Any>()
        val parameters = mutableListOf<String>()
        val locals = mutableListOf<Local>()
        val body = StringBuilder()
        var hoisted = 0
        var nextLocal = 0

        fun literal(type: String): Valued = when (type) {
            "int32" -> random.nextInt(2000).minus(1000).let { Valued(if (it < 0) "(0 - ${-it})" else "$it", it) }
            "int64" -> (random.nextInt(2000).toLong() * 5_000_000_000L + random.nextInt(7)).let { Valued("${it}L", it) }
            "float64" -> (random.nextInt(64) * 0.25 + 0.25).let { Valued("$it", it) }
            "bool" -> random.nextBoolean().let { Valued("$it", it) }
            else -> "s${random.nextInt(10)}".let { Valued("\"$it\"", it) }
        }

        /** 新增一个 Future<type> 形参并返回其 await 文本与值。 */
        fun awaited(type: String): Valued {
            val value = literal(type).value
            val parameter = "in${inputs.size}"
            inputs += value
            parameters += "$parameter: Future<$type>"
            return Valued("await $parameter", value)
        }

        fun operand(type: String): Valued {
            val choices = locals.filter { it.type == type }
            return if (choices.isNotEmpty() && random.nextInt(3) != 0) {
                choices[random.nextInt(choices.size)].let { Valued(it.name, it.value) }
            } else {
                literal(type)
            }
        }

        /** 不含 await 的右操作数在 await 之后求值，保持原位；左操作数只用局部 / 字面量，提升合法。 */
        fun expression(type: String): Valued {
            val left = operand(type)
            val right = if (type != "bool" && random.nextInt(3) == 0) {
                hoisted++
                awaited(type).let { Valued("(${it.text})", it.value) }
            } else {
                operand(type)
            }
            return when (type) {
                "int32" -> when (random.nextInt(3)) {
                    0 -> Valued("${left.text} + ${right.text}", (left.value as Int) + (right.value as Int))
                    1 -> Valued("${left.text} - ${right.text}", (left.value as Int) - (right.value as Int))
                    else -> Valued("${left.text} * ${right.text}", (left.value as Int) * (right.value as Int))
                }
                "int64" -> when (random.nextInt(4)) {
                    0 -> Valued("${left.text} + ${right.text}", (left.value as Long) + (right.value as Long))
                    1 -> Valued("${left.text} - ${right.text}", (left.value as Long) - (right.value as Long))
                    2 -> Valued("${left.text} * ${right.text}", (left.value as Long) * (right.value as Long))
                    else -> {
                        // int32 操作数隐式宽化。
                        val small = operand("int32")
                        Valued("${left.text} + ${small.text}", (left.value as Long) + (small.value as Int))
                    }
                }
                "float64" -> when (random.nextInt(3)) {
                    0 -> Valued("${left.text} + ${right.text}", (left.value as Double) + (right.value as Double))
                    1 -> Valued("${left.text} * ${right.text}", (left.value as Double) * (right.value as Double))
                    else -> Valued("${left.text} - ${right.text}", (left.value as Double) - (right.value as Double))
                }
                "bool" -> when (random.nextInt(3)) {
                    0 -> operand("int64").let { a ->
                        val b = operand("int64")
                        Valued("${a.text} < ${b.text}", (a.value as Long) < (b.value as Long))
                    }
                    1 -> operand("float64").let { a ->
                        val b = operand("float64")
                        Valued("${a.text} >= ${b.text}", (a.value as Double) >= (b.value as Double))
                    }
                    else -> operand("string").let { a ->
                        val b = operand("string")
                        Valued("${a.text} == ${b.text}", a.value == b.value)
                    }
                }
                else -> when (random.nextInt(3)) {
                    0 -> Valued("${left.text} + ${right.text}", (left.value as String) + (right.value as String))
                    1 -> operand("int64").let { n -> Valued("${left.text} + ${n.text}", (left.value as String) + n.value) }
                    else -> operand("int32").let { n -> Valued("${left.text} + ${n.text}", (left.value as String) + n.value) }
                }
            }
        }

        val types = listOf("int32", "int64", "float64", "bool", "string")
        // 第一条总是直接 await，保证函数是挂起函数。
        val firstType = types[index % types.size]
        awaited(firstType).let { first ->
            val name = "v${nextLocal++}"
            body.appendLine("        var $name: $firstType = ${first.text};")
            locals += Local(name, firstType, first.value)
        }
        repeat(2 + random.nextInt(5)) {
            val type = types[random.nextInt(types.size)]
            val name = "v${nextLocal++}"
            if (random.nextInt(4) == 0) {
                val direct = awaited(type)
                body.appendLine("        var $name: $type = ${direct.text};")
                locals += Local(name, type, direct.value)
            } else {
                val value = expression(type)
                body.appendLine("        var $name: $type = ${value.text};")
                locals += Local(name, type, value.value)
            }
        }
        val returnType = types[(index / types.size + index) % types.size]
        val result = expression(returnType)
        body.appendLine("        return ${result.text};")
        val name = "f$index"
        val source = "    public static function $name(${parameters.joinToString(", ")}): $returnType {\n$body    }"
        return Generated(name, source, inputs.toList(), result.value, returnType, hoisted)
    }

    /** 仍不支持的形态：明确诊断、不写 class；不退化为同步求值或静默转换。 */
    @Test
    fun `unsupported value shapes are diagnosed and write no class`() {
        val cases = mapOf(
            "binding-widen" to """
                public static function f(input: Future<int32>): int64 {
                    var value: int64 = await input;
                    return value;
                }
            """,
            "assign-widen" to """
                public static function f(input: Future<int32>): int64 {
                    var value: int64 = 1L;
                    value = await input;
                    return value;
                }
            """,
            "array-result" to """
                public static function f(input: Future<int32[]>): int32 {
                    var value: int32[] = await input;
                    return 1;
                }
            """,
            "narrow-number" to """
                public static function f(input: Future<float32>): float32 {
                    var value: float32 = await input;
                    return value;
                }
            """,
            "float-increment" to """
                public static function f(input: Future<float64>): float64 {
                    var value: float64 = await input;
                    value++;
                    return value;
                }
            """
        )
        for ((name, body) in cases) {
            val output = freshOutput("unsupported-$name")
            val source = """
                package demo;
                import std.Future;
                public class Program {
                $body
                }
            """.trimIndent()
            val result = JasperCompiler().compileAllWithStdlib(mapOf("Program.jas" to source), output)
            assertTrue(result is JasperCompiler.CompileResult.Error, "$name unexpectedly compiled")
            val message = (result as JasperCompiler.CompileResult.Error).message
            assertTrue(message.contains("non-blocking await lowering is not implemented yet"), "$name: $message")
            Files.walk(output).use { paths ->
                assertFalse(paths.anyMatch { it.toString().endsWith(".class") }, "$name wrote bytecode")
            }
        }
    }
}
