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
 * 字符串 / 字符字面量的转义（Q239，appfw FB-web-014，LANG-STRING-ESCAPE-01）。
 *
 * 修复前只认 `\n \t \r \\ \" \'`：`\u0041`、`\b`、`\f`、`\x41` 静默丢掉反斜杠；未知转义静默变成字符本身；
 * 孤立代理（`'\uD83D'`）作 char 不是 Unicode scalar，也被接受。期望值由 Kotlin 字符串直接写出或由生成器独立转义后比对。
 */
class StringEscapeTest {
    private fun compile(name: String, body: String, ret: String): Pair<JasperCompiler.CompileResult, java.nio.file.Path> {
        val dir = Paths.get("build", "scratch", "string-escape", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to "package t;\npublic function entry(): $ret {\n$body\n}\n"), dir) to dir
    }

    private fun run(name: String, ret: String, body: String): Any? {
        val (result, dir) = compile(name, body, ret)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = Files.list(dir.resolve("t")).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".class") }
                .map { loader.loadClass("t." + it.fileName.toString().removeSuffix(".class")) }
                .first { c -> c.methods.any { it.name == "entry" } }
            return owner.getMethod("entry").invoke(null)
        }
    }

    private fun rejected(name: String, body: String, expect: String) {
        val (result, _) = compile(name, body, "int32")
        val error = assertIs<JasperCompiler.CompileResult.Error>(result, "$name should not compile")
        assertTrue(error.errors.any { expect in it.message }, "$name: expected '$expect' in\n${error.message}")
    }

    @Test
    fun `every escape of the lexer set has its value`() {
        assertEquals("A\u4e2d", run("unicode", "string", "return \"\\u0041\\u4e2d\";"))
        assertEquals("a\bb\u000Cc", run("b-f", "string", "return \"a\\bb\\fc\";"))
        assertEquals("AZ", run("hex", "string", "return \"\\x41\\x5a\";"))
        assertEquals("\n\t\r\\\"'", run("classic", "string", "return \"\\n\\t\\r\\\\\\\"\\'\";"))
        assertEquals("😀", run("surrogate-pair", "string", "return \"\\uD83D\\uDE00\";"))
        assertEquals(0x4e2d, run("char-unicode", "int32", "var c: char = '\\u4e2d';\nreturn cast c as int32;"))
        assertEquals(12, run("char-f", "int32", "var c: char = '\\f';\nreturn cast c as int32;"))
    }

    @Test
    fun `bad escapes and lone surrogate char literals are diagnosed`() {
        rejected("unknown", "var s = \"a\\qb\";\nreturn 0;", "Unknown escape sequence")
        rejected("short-u", "var s = \"\\u12\";\nreturn 0;", "4 hexadecimal digits")
        rejected("short-x", "var s = \"\\x4\";\nreturn 0;", "2 hexadecimal digits")
        rejected("char-surrogate", "var c: char = '\\uD83D';\nreturn 0;", "lone surrogate")
    }

    @Test
    fun `random strings escaped with u and x round trip`() {
        val random = Random(20261119)
        val body = StringBuilder("var out = \"\";\n")
        val expected = StringBuilder()
        repeat(40) { k ->
            val chars = List(random.nextInt(1, 8)) { random.nextInt(0x20, 0x3000).toChar() }.filter { !it.isSurrogate() }
            val literal = chars.joinToString("") { c ->
                when (random.nextInt(3)) {
                    0 -> "\\u%04x".format(c.code)
                    1 -> if (c.code < 0x100) "\\x%02X".format(c.code) else "\\u%04X".format(c.code)
                    else -> when (c) { '"' -> "\\\""; '\\' -> "\\\\"; else -> c.toString() }
                }
            }
            body.append("out = out + \"$literal\" + \"|\";\n")
            expected.append(chars.joinToString("")).append('|')
        }
        body.append("return out;")
        assertEquals(expected.toString(), run("random", "string", body.toString()))
    }
}
