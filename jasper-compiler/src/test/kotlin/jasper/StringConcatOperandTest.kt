package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `+` 只要有一侧是 string 就是拼接（Q241，appfw FB-merge-003 / FB-data-13 / FB-theme-07，LANG-CONCAT-OPERAND-01）。
 *
 * 修复前翻译器按左操作数算 `+` 的结果类型，后端也只在左侧是 string 时拼接：`n + " local"`、
 * `b.code() + " x"`、枚举 + 字符串都发成 IADD → VerifyError。期望值由用例写出或由生成器按
 * 同一左结合规则独立算出。
 */
class StringConcatOperandTest {
    private val extra = """
        public enum Color { RED, GREEN }
        public class Box {
            public constructor() { }
            public function code(): int32 { return 201; }
            public function name(): string { return "box"; }
        }
    """.trimIndent()

    private fun run(name: String, body: String): Any? {
        val dir = Paths.get("build", "scratch", "concat-operand", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val source = "package t;\n$extra\npublic function entry(): string {\n$body\n}\n"
        val result = JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to source), dir)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = Files.list(dir.resolve("t")).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".class") }
                .map { loader.loadClass("t." + it.fileName.toString().removeSuffix(".class")) }
                .first { c -> c.methods.any { it.name == "entry" } }
            return owner.getMethod("entry").invoke(null)
        }
    }

    @Test
    fun `a non string left operand concatenates with a string right operand`() {
        assertEquals("7 local", run("local", "var n: int32 = 7;\nreturn n + \" local\";"))
        assertEquals("201 call-left", run("call", "var b = new Box();\nreturn b.code() + \" call-left\";"))
        assertEquals("RED!", run("enum", "var c = Color.RED;\nreturn c + \"!\";"))
        assertEquals("5000000000L", run("int64", "var x: int64 = 5000000000L;\nreturn x + \"L\";"))
        assertEquals("2.5d", run("float64", "var d: float64 = 2.5;\nreturn d + \"d\";"))
        assertEquals("true?", run("bool", "var f = true;\nreturn f + \"?\";"))
        assertEquals("😀x", run("char", "var c: char = '😀';\nreturn c + \"x\";"))
        assertEquals("200b", run("byte", "var b: byte = cast 200 as byte;\nreturn b + \"b\";"))
    }

    @Test
    fun `a platform string on the right and chains keep left associativity`() {
        assertEquals("7ab", run("platform-right", "var n: int32 = 7;\nvar s = \" ab \";\nreturn n + s.trim();"))
        // 平台静态调用的 IR 类型在翻译期仍是 Unit，只有后端按真实描述符知道右侧是 String。
        assertEquals("75", run("platform-static-right", "var n: int32 = 7;\nreturn n + java.lang.String.valueOf(5);"))
        assertEquals("3x", run("chain-num-first", "return 1 + 2 + \"x\";"))
        assertEquals("x12", run("chain-str-first", "return \"x\" + 1 + 2;"))
        assertEquals("201box", run("call-call", "var b = new Box();\nreturn b.code() + b.name();"))
        assertEquals("10", run("numeric-unchanged", "var a: int32 = 4;\nvar b: int32 = 6;\nvar s = a + b;\nreturn \"\" + s;"))
    }

    @Test
    fun `random mixes of numbers and strings follow left to right evaluation`() {
        val random = Random(20261115)
        val body = StringBuilder("var out = \"\";\n")
        val expected = StringBuilder()
        repeat(60) { k ->
            val terms = List(random.nextInt(2, 6)) {
                if (random.nextInt(3) == 0) "s" + random.nextInt(100) else random.nextInt(-50, 50).toString()
            }
            // 左结合：在第一个字符串出现之前做 int 加法，之后全部按字符串拼接。
            var acc: Any = terms.first().let { it.toIntOrNull() ?: it }
            for (t in terms.drop(1)) {
                val n = t.toIntOrNull()
                acc = if (acc is Int && n != null) acc + n else acc.toString() + t
            }
            val source = terms.joinToString(" + ") { t -> if (t.toIntOrNull() != null) "(" + t + ")" else "\"" + t + "\"" }
            body.append("var v$k = $source;\nout = out + v$k + \";\";\n")
            expected.append(acc).append(';')
        }
        body.append("return out;")
        assertEquals(expected.toString(), run("random", body.toString()))
    }
}
