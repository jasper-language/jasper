package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 元组值（docs/03 §3.7、docs/05 §5.3、docs/04 §4.3；Q223，LANG-TUPLE-01）。
 *
 * 修复前元组表达式被翻译成 Unit：`var t = (1, 2)` 能编译但值丢失，`return a, b` 只留下 a，
 * `return (1, 2)` 与声明的元组返回类型互不相认。现在元组在 JVM 上是装箱的 Object[]，
 * 常量下标读元素，`var a, b = e` 按位置绑定。期望值都由用例写出或由生成器独立算出。
 */
class TupleTest {
    private fun compile(name: String, source: String): JasperCompiler.CompileResult {
        val dir = Paths.get("build", "scratch", "tuple", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to source), dir)
    }

    private fun run(name: String, ret: String, body: String, extra: String = ""): Any? {
        val source = "package t;\n$extra\npublic function entry(): $ret {\n$body\n}\n"
        val result = compile(name, source)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        val dir = Paths.get("build", "scratch", "tuple", name)
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = Files.list(dir.resolve("t")).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".class") }
                .map { loader.loadClass("t." + it.fileName.toString().removeSuffix(".class")) }
                .first { c -> c.methods.any { it.name == "entry" } }
            return owner.getMethod("entry").invoke(null)
        }
    }

    private fun rejected(name: String, body: String, expect: String, extra: String = "") {
        val result = compile(name, "package t;\n$extra\npublic function entry(): int32 {\n$body\n}\n")
        val error = assertIs<JasperCompiler.CompileResult.Error>(result, "$name should not compile")
        assertTrue(error.errors.any { expect in it.message }, "$name: expected '$expect' in\n${error.message}")
    }

    @Test
    fun `tuple literals keep their values and are read by constant index`() {
        assertEquals("Ada:36", run("pair", "string", "var p: (string, int32) = (\"Ada\", 36);\nreturn p[0] + \":\" + p[1];"))
        // Q220 的原始复现：不写类型的元组以前被丢弃。
        assertEquals(3, run("untyped", "int32", "var t = (1, 2);\nreturn t[0] + t[1];"))
        assertEquals(7, run("nested", "int32", "var n: (string, (int32, bool)) = (\"x\", (7, true));\nvar inner = n[1];\nif (inner[1]) { return inner[0]; }\nreturn -1;"))
    }

    @Test
    fun `multi value return and positional binding`() {
        val divide = "function divide(a: int32, b: int32): (int32, int32) {\n    return a / b, a % b;\n}\n"
        assertEquals(32, run("divide", "int32", "var q, r = divide(17, 5);\nreturn q * 10 + r;", divide))
        assertEquals(3, run("divide-index", "int32", "var t = divide(17, 5);\nreturn t[0];", divide))
        val paren = "function pair(): (string, int32) {\n    return (\"Ada\", 36);\n}\n"
        assertEquals("Ada36", run("paren-return", "string", "const name, age = pair();\nreturn name + age;", paren))
        val swap = "function swap(p: (int32, string)): (string, int32) {\n    return p[1], p[0];\n}\n"
        assertEquals("b1", run("swap", "string", "var s, n = swap((1, \"b\"));\nreturn s + n;", swap))
    }

    @Test
    fun `literal elements are converted to the declared element types`() {
        assertEquals(1L, run("widen", "int64", "var p: (int64, float64) = (1, 2);\nreturn p[0];"))
        assertEquals(2.5, run("widen-f", "float64", "var p: (int64, float64) = (1, 2);\nreturn p[1] + 0.5;"))
        assertEquals(200 + 0x1F600, run("byte-char", "int32", "var t: (byte, char) = (cast 200 as byte, '😀');\nreturn (cast t[0] as int32) + (cast t[1] as int32);"))
        assertEquals("none3", run("nullable", "string", "var t: (string?, int32) = (null, 3);\nreturn (t[0] ?? \"none\") + t[1];"))
        assertEquals(5L, run("return-widen", "int64", "var t = f();\nreturn t[0] + t[1];", "function f(): (int64, int64) { return 2, 3; }\n"))
    }

    @Test
    fun `misuse is diagnosed instead of silently dropped`() {
        rejected("arity", "var t: (int32, int32) = (1, 2, 3);\nreturn 0;", "Type mismatch")
        rejected("range", "var t = (1, 2);\nreturn t[2];", "out of range")
        rejected("var-index", "var t = (1, 2);\nvar i = 0;\nreturn t[i];", "integer literal")
        rejected("bind-arity", "var a, b = (1, 2, 3);\nreturn a;", "Positional binding of 2 names")
        rejected("bind-scalar", "var a, b = 5;\nreturn a;", "Positional binding of 2 names")
        rejected("eq", "var t = (1, 2);\nif (t == t) { return 1; }\nreturn 0;", "cannot be applied to a tuple")
        rejected("concat", "var t = (1, 2);\nvar s = \"\" + t;\nreturn 0;", "cannot be applied to a tuple")
        rejected("print", "var t = (1, 2);\nprintln(t);\nreturn 0;", "no string form")
        // 元组模式的 for-in 目前文法就解析不了（单列队列项）；解析失败本身就不是静默。
        rejected("for-pattern", "var xs: int32[] = [1, 2];\nfor ((a, b) in xs) { }\nreturn 0;", "Failed to parse")
        rejected("for-names", "var xs: int32[] = [1, 2];\nfor (a, b in xs) { }\nreturn 0;", "for-in bindings")
        rejected("value-widen", "var t: (int32, int32) = (1, 2);\nvar u: (int64, int64) = t;\nreturn 0;", "Type mismatch")
        rejected("return-arity", "return 1, 2;", "Return type mismatch")
        rejected("void-element", "var t = (1, g());\nreturn 0;", "must have a value", "function g(): void { }\n")
    }

    @Test
    fun `ordinary multi declarations are unchanged`() {
        assertEquals(3, run("multi-init", "int32", "var a = 1, b = 2;\nreturn a + b;"))
        assertEquals(5, run("typed-first", "int32", "var a: int32, b = 5;\na = 0;\nreturn a + b;"))
    }

    @Test
    fun `random tuples round trip through calls and positional binding`() {
        val random = Random(20261110)
        val kinds = listOf("int32", "int64", "string", "bool", "float64")
        val functions = StringBuilder()
        val body = StringBuilder("var out = \"\";\n")
        val expected = StringBuilder()
        repeat(40) { k ->
            val arity = random.nextInt(2, 5)
            val types = List(arity) { kinds[random.nextInt(kinds.size)] }
            val values = types.map { t ->
                when (t) {
                    "int32" -> random.nextInt(-1000, 1000).toString()
                    "int64" -> (random.nextLong(-5_000_000_000L, 5_000_000_000L)).toString()
                    "string" -> "s" + random.nextInt(0, 100)
                    "bool" -> random.nextBoolean().toString()
                    else -> (random.nextInt(-100, 100) / 4.0).toString()
                }
            }
            val literal = values.mapIndexed { i, v ->
                when (types[i]) {
                    "string" -> "\"$v\""
                    "int64" -> v + "L"
                    else -> v
                }
            }
            val tupleType = types.joinToString(", ", "(", ")")
            functions.append("function make$k(): $tupleType {\n    return ${literal.joinToString(", ")};\n}\n")
            functions.append("function pass$k(t: $tupleType): $tupleType {\n    return t;\n}\n")
            val names = List(arity) { "v${k}x$it" }
            body.append("var ${names.joinToString(", ")} = pass$k(make$k());\n")
            // 反转顺序拼接，确认每个名字绑定到自己的位置。
            names.reversed().forEach { body.append("out = out + $it + \"|\";\n") }
            values.reversed().forEach { expected.append(it).append('|') }
            if (random.nextBoolean()) {
                val i = random.nextInt(arity)
                body.append("out = out + pass$k(make$k())[$i] + \";\";\n")
                expected.append(values[i]).append(';')
            }
        }
        body.append("return out;")
        assertEquals(expected.toString(), run("random", "string", body.toString(), functions.toString()))
    }
}
