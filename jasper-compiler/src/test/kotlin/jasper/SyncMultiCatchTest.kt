package jasper

import jasper.compiler.JasperCompiler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 同步函数路径 try 带多个 catch 子句（LANG-SYNC-CATCH-01）：异常按子句书写顺序匹配，**只执行第一个**
 * 类型匹配的 catch 体；不匹配任何子句的异常执行 finally 后向外传播；catch 体自身抛出的异常不被同一
 * try 的兄弟 catch 截获，只经过本 try 的 finally；每个 catch 参数按其声明类型取值。
 *
 * 全部经真实 ANTLR 解析 → 检查器 → IR → JVM 字节码并执行（类加载即过字节码校验）；随机部分用固定种子
 * 生成嵌套 try/catch/finally 程序，与按规范语义直接解释的 Kotlin oracle 比对返回值、异常与轨迹。
 * 异常全部取自平台：`7 / dz`（ArithmeticException）、`Integer.parseInt("q")`（NumberFormatException ⊂
 * IllegalArgumentException）、`sb.setLength(-1)`（StringIndexOutOfBoundsException ⊂ IndexOutOfBoundsException），
 * 三者都 ⊂ RuntimeException，覆盖「子类型子句在后」「父类型子句在前」两种顺序。
 */
class SyncMultiCatchTest {

    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "sync-multi-catch", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 编译源码并按函数名收集生成的静态方法。 */
    private fun compileFunctions(source: String, name: String): Map<String, Method> {
        val out = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, out)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        val loader = URLClassLoader(arrayOf(out.toUri().toURL()))
        val methods = mutableMapOf<String, Method>()
        Files.walk(out).use { paths ->
            paths.filter { it.toString().endsWith(".class") }.forEach { file ->
                val className = out.relativize(file).toString().removeSuffix(".class").replace('/', '.')
                val clazz = loader.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                        m.isAccessible = true
                        methods.putIfAbsent(m.name, m)
                    }
                }
            }
        }
        return methods
    }

    /** 调用结果：返回值或抛出的异常类名，以及 StringBuilder 轨迹。 */
    private data class Outcome(val result: String, val trace: String)

    private fun call(method: Method, vararg args: Any): Outcome {
        val sb = java.lang.StringBuilder()
        val result = try {
            method.invoke(null, sb, *args).toString()
        } catch (e: InvocationTargetException) {
            "throw:" + e.targetException.javaClass.simpleName
        }
        return Outcome(result, sb.toString())
    }

    private val handWritten = """
        package test;
        function pick(sb: java.lang.StringBuilder, k: int32): int32 {
            var dz: int32 = 0;
            try {
                sb.append("t");
                if (k == 1) { sb.append(7 / dz); }
                if (k == 2) { sb.append(java.lang.Integer.parseInt("q")); }
                if (k == 3) { sb.setLength(-1); }
                sb.append("n");
            } catch (e: java.lang.ArithmeticException) {
                sb.append("A");
            } catch (e: java.lang.IllegalArgumentException) {
                sb.append("I");
            } catch (e: java.lang.RuntimeException) {
                sb.append("R");
            } finally {
                sb.append("F");
            }
            return k;
        }
        function supertypeFirst(sb: java.lang.StringBuilder, k: int32): int32 {
            var dz: int32 = 0;
            try {
                if (k == 1) { sb.append(7 / dz); }
                if (k == 2) { sb.append(java.lang.Integer.parseInt("q")); }
            } catch (e: java.lang.RuntimeException) {
                sb.append("R");
            } catch (e: java.lang.ArithmeticException) {
                sb.append("A");
            }
            return k;
        }
        function unmatched(sb: java.lang.StringBuilder, k: int32): int32 {
            var dz: int32 = 0;
            try {
                if (k == 1) { sb.setLength(-1); }
                sb.append("n");
            } catch (e: java.lang.ArithmeticException) {
                sb.append("A");
            } catch (e: java.lang.NumberFormatException) {
                sb.append("N");
            } finally {
                sb.append("F");
            }
            return k;
        }
        function catchThrows(sb: java.lang.StringBuilder, k: int32): int32 {
            var dz: int32 = 0;
            try {
                try {
                    sb.append(java.lang.Integer.parseInt("q"));
                } catch (e: java.lang.NumberFormatException) {
                    sb.append("N");
                    if (k == 1) { sb.append(7 / dz); }
                    if (k == 2) { return 20; }
                } catch (e: java.lang.ArithmeticException) {
                    sb.append("A");
                } finally {
                    sb.append("F");
                }
            } catch (e: java.lang.ArithmeticException) {
                sb.append("O");
            }
            return k;
        }
        function message(sb: java.lang.StringBuilder, k: int32): string {
            var dz: int32 = 0;
            try {
                if (k == 1) { sb.append(7 / dz); }
                sb.append(java.lang.Integer.parseInt("q"));
            } catch (e: java.lang.ArithmeticException) {
                return e.getMessage();
            } catch (e: java.lang.NumberFormatException) {
                return e.getMessage();
            }
            return "none";
        }
    """.trimIndent()

    @Test
    fun `only the first matching catch runs and finally runs once`() {
        val fns = compileFunctions(handWritten, "hand-pick")
        val pick = fns.getValue("pick")
        assertEquals(Outcome("0", "tnF"), call(pick, 0))
        assertEquals(Outcome("1", "tAF"), call(pick, 1))
        assertEquals(Outcome("2", "tIF"), call(pick, 2))
        assertEquals(Outcome("3", "tRF"), call(pick, 3))
        // 父类型子句在前：它先匹配，其后的子类型子句不再执行。
        val supertypeFirst = fns.getValue("supertypeFirst")
        assertEquals(Outcome("1", "R"), call(supertypeFirst, 1))
        assertEquals(Outcome("2", "R"), call(supertypeFirst, 2))
        assertEquals(Outcome("0", ""), call(supertypeFirst, 0))
    }

    @Test
    fun `an exception no clause matches runs finally and propagates`() {
        val fns = compileFunctions(handWritten, "hand-unmatched")
        val unmatched = fns.getValue("unmatched")
        assertEquals(Outcome("throw:StringIndexOutOfBoundsException", "F"), call(unmatched, 1))
        assertEquals(Outcome("0", "nF"), call(unmatched, 0))
    }

    @Test
    fun `a catch body that throws is not caught by a sibling clause`() {
        val fns = compileFunctions(handWritten, "hand-catch-throws")
        val catchThrows = fns.getValue("catchThrows")
        assertEquals(Outcome("0", "NF"), call(catchThrows, 0))
        // 第一个 catch 抛出 ArithmeticException：兄弟子句 catch (ArithmeticException) 不截获，finally 后外层捕获。
        assertEquals(Outcome("1", "NFO"), call(catchThrows, 1))
        // catch 体内 return：先执行 finally 再返回。
        assertEquals(Outcome("20", "NF"), call(catchThrows, 2))
    }

    @Test
    fun `each catch parameter is read with its own declared type`() {
        val fns = compileFunctions(handWritten, "hand-message")
        val message = fns.getValue("message")
        assertEquals(Outcome("/ by zero", ""), call(message, 1))
        assertEquals(Outcome("For input string: \"q\"", ""), call(message, 0))
    }

    // ---------------------------------------------------------------------
    // 随机程序：固定种子生成嵌套 try/catch/finally，Kotlin oracle 按规范语义直接解释。
    // ---------------------------------------------------------------------

    /** 可抛出的平台异常：渲染为抛出它的 Jasper 语句。 */
    private enum class Kind(val simpleName: String, val source: String) {
        ARITH("ArithmeticException", "sb.append(7 / dz);"),
        FORMAT("NumberFormatException", "sb.append(java.lang.Integer.parseInt(\"q\"));"),
        INDEX("StringIndexOutOfBoundsException", "sb.setLength(-1);")
    }

    /** catch 子句可声明的类型及其捕获的异常种类（平台类型层次）。 */
    private enum class CatchType(val jvmName: String, val covers: Set<Kind>) {
        ARITH("java.lang.ArithmeticException", setOf(Kind.ARITH)),
        FORMAT("java.lang.NumberFormatException", setOf(Kind.FORMAT)),
        ILLEGAL_ARGUMENT("java.lang.IllegalArgumentException", setOf(Kind.FORMAT)),
        STRING_INDEX("java.lang.StringIndexOutOfBoundsException", setOf(Kind.INDEX)),
        INDEX("java.lang.IndexOutOfBoundsException", setOf(Kind.INDEX)),
        RUNTIME("java.lang.RuntimeException", Kind.values().toSet())
    }

    private sealed class Stmt {
        data class Append(val c: Char) : Stmt()
        /** `if (x > guard) { 抛出 kind }`。 */
        data class Raise(val guard: Int, val kind: Kind) : Stmt()
        /** `if (x > guard) { return value; }`。 */
        data class Return(val guard: Int, val value: Int) : Stmt()
        data class Try(val body: List<Stmt>, val catches: List<Catch>, val finally: List<Stmt>?) : Stmt()
    }

    private data class Catch(val id: Int, val type: CatchType, val body: List<Stmt>)

    private sealed class Signal {
        object Normal : Signal()
        data class Return(val value: Int) : Signal()
        data class Throw(val kind: Kind) : Signal()
    }

    private class Stats {
        var laterClauseCaught = 0
        var catchBodyThrew = 0
        var unmatchedPropagated = 0
        var multiCatchTries = 0
    }

    private class Generator(private val random: Random, private val stats: Stats) {
        private var letter = 0
        private var nextCatch = 0

        private fun nextChar(): Char = "abcdefghijklmnopqrstuvwxyz"[letter++ % 26]

        fun program(): List<Stmt> = block(depth = 0, inFinally = false)

        private fun block(depth: Int, inFinally: Boolean): List<Stmt> {
            val length = random.nextInt(1, if (depth >= 2) 3 else 5)
            return List(length) { statement(depth, inFinally) }
        }

        private fun statement(depth: Int, inFinally: Boolean): Stmt {
            val roll = random.nextInt(100)
            return when {
                roll < 30 || depth >= 3 -> Stmt.Append(nextChar())
                roll < 55 -> Stmt.Raise(random.nextInt(0, 4), Kind.values().random(random))
                // finally 体内不放 return（跳出 finally 不在本测试范围）。
                roll < 65 && !inFinally -> Stmt.Return(random.nextInt(0, 4), random.nextInt(1, 9))
                roll < 65 -> Stmt.Append(nextChar())
                else -> tryStatement(depth, inFinally)
            }
        }

        private fun tryStatement(depth: Int, inFinally: Boolean): Stmt.Try {
            val body = block(depth + 1, inFinally)
            val count = random.nextInt(if (random.nextInt(4) == 0) 0 else 1, 4)
            val types = CatchType.values().toMutableList().apply { shuffle(random) }.take(count)
            val catches = types.map { Catch(nextCatch++, it, block(depth + 1, inFinally)) }
            if (catches.size >= 2) stats.multiCatchTries++
            val finally = if (catches.isEmpty() || random.nextBoolean()) block(depth + 1, inFinally = true) else null
            return Stmt.Try(body, catches, finally)
        }
    }

    private fun render(name: String, program: List<Stmt>): String {
        val out = StringBuilder()
        out.append("function $name(sb: java.lang.StringBuilder, x: int32): int32 {\n")
        out.append("    var dz: int32 = 0;\n")
        renderBlock(program, out, 1)
        out.append("    return 99;\n}\n")
        return out.toString()
    }

    private fun renderBlock(stmts: List<Stmt>, out: StringBuilder, indent: Int) {
        val pad = "    ".repeat(indent)
        for (s in stmts) {
            when (s) {
                is Stmt.Append -> out.append("${pad}sb.append(\"${s.c}\");\n")
                is Stmt.Raise -> out.append("${pad}if (x > ${s.guard}) { ${s.kind.source} }\n")
                is Stmt.Return -> out.append("${pad}if (x > ${s.guard}) { return ${s.value}; }\n")
                is Stmt.Try -> {
                    out.append("${pad}try {\n")
                    renderBlock(s.body, out, indent + 1)
                    for (c in s.catches) {
                        out.append("$pad} catch (e${c.id}: ${c.type.jvmName}) {\n")
                        renderBlock(c.body, out, indent + 1)
                    }
                    if (s.finally != null) {
                        out.append("$pad} finally {\n")
                        renderBlock(s.finally, out, indent + 1)
                    }
                    out.append("$pad}\n")
                }
            }
        }
    }

    /** 规范语义：子句按书写顺序匹配、只执行第一个；catch 体的结果再经 finally；finally 非正常结束时取代之前的结果。 */
    private fun exec(stmts: List<Stmt>, x: Int, trace: StringBuilder, stats: Stats?): Signal {
        for (s in stmts) {
            val signal = when (s) {
                is Stmt.Append -> { trace.append(s.c); Signal.Normal }
                is Stmt.Raise -> if (x > s.guard) Signal.Throw(s.kind) else Signal.Normal
                is Stmt.Return -> if (x > s.guard) Signal.Return(s.value) else Signal.Normal
                is Stmt.Try -> {
                    var result = exec(s.body, x, trace, stats)
                    if (result is Signal.Throw) {
                        val thrown = result.kind
                        val index = s.catches.indexOfFirst { thrown in it.type.covers }
                        if (index >= 0) {
                            if (index > 0) stats?.let { it.laterClauseCaught++ }
                            result = exec(s.catches[index].body, x, trace, stats)
                            if (result is Signal.Throw) stats?.let { it.catchBodyThrew++ }
                        } else if (s.catches.isNotEmpty()) {
                            stats?.let { it.unmatchedPropagated++ }
                        }
                    }
                    if (s.finally != null) {
                        val cleanup = exec(s.finally, x, trace, stats)
                        if (cleanup != Signal.Normal) result = cleanup
                    }
                    result
                }
            }
            if (signal != Signal.Normal) return signal
        }
        return Signal.Normal
    }

    private fun oracle(program: List<Stmt>, x: Int, stats: Stats?): Outcome {
        val trace = StringBuilder()
        val result = when (val signal = exec(program, x, trace, stats)) {
            Signal.Normal -> "99"
            is Signal.Return -> signal.value.toString()
            is Signal.Throw -> "throw:" + signal.kind.simpleName
        }
        return Outcome(result, trace.toString())
    }

    @Test
    fun `seeded random multi-catch programs match the specification oracle`() {
        val seed = 20261117
        val programCount = 300
        val generatorStats = Stats()
        val runStats = Stats()
        val random = Random(seed)
        val programs = List(programCount) { Generator(random, generatorStats).program() }
        val source = buildString {
            append("package test;\n")
            programs.forEachIndexed { i, p -> append(render("p$i", p)) }
        }
        val fns = compileFunctions(source, "random-$seed")
        var executions = 0
        programs.forEachIndexed { i, program ->
            for (x in 0..3) {
                val expected = oracle(program, x, runStats)
                val actual = call(fns.getValue("p$i"), x)
                assertEquals(expected, actual, "seed=$seed program p$i x=$x\n${render("p$i", program)}")
                executions++
            }
        }
        assertEquals(programCount * 4, executions)
        // 覆盖下限：多 catch try、非首子句命中、catch 体抛出、无子句匹配后传播都要足够多。
        assertTrue(generatorStats.multiCatchTries > 40, "multi-catch tries: ${generatorStats.multiCatchTries}")
        assertTrue(runStats.laterClauseCaught > 40, "later clause caught: ${runStats.laterClauseCaught}")
        assertTrue(runStats.catchBodyThrew > 40, "catch body threw: ${runStats.catchBodyThrew}")
        assertTrue(runStats.unmatchedPropagated > 40, "unmatched propagated: ${runStats.unmatchedPropagated}")
    }
}
