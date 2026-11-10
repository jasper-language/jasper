package jasper

import jasper.compiler.JasperCompiler
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `char` 是 32 位 Unicode scalar（规范 A5 char32，用户 2026-11-10 裁决；Q229 第二部分，LANG-CHAR32-01）。
 *
 * 修复前 char 是 UTF-16 码元：'😀' 被截成 16 位，`"" + c` 对补充平面字符出错。现在 char 在 JVM 上是 int 码点，
 * 拼接 / 打印 / toString 按码点转字符串。期望值由 Kotlin 的 codePoint API 独立算出。
 */
class Char32Test {
    private fun run(name: String, ret: String, body: String): Any? {
        val dir = Paths.get("build", "scratch", "char32", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to "package t;\npublic function entry(): $ret {\n$body\n}\n"), dir)
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
    fun `supplementary characters keep their full code point`() {
        assertEquals(0x1F600, run("emoji-cp", "int32", "var c: char = '😀';\nreturn cast c as int32;"))
        assertEquals("a😀中", run("emoji-concat", "string", "var a: char = 'a';\nvar e: char = '😀';\nvar z: char = '中';\nreturn \"\" + a + e + z;"))
    }

    @Test
    fun `char arrays hold code points`() {
        assertEquals("😀x", run("array", "string", """
            var cs: char[] = new char[2];
            cs[0] = '😀';
            cs[1] = 'x';
            return "" + cs[0] + cs[1];
        """))
    }

    @Test
    fun `println prints the character`() {
        val out = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(out, true, "UTF-8"))
        try {
            run("println", "int32", "var c: char = '😀';\nprintln(c);\nreturn 0;")
        } finally {
            System.setOut(old)
        }
        assertEquals("😀", out.toString("UTF-8").trim())
    }

    @Test
    fun `platform char parameters receive the character`() {
        assertEquals("ab", run("platform", "string", """
            var sb = new java.lang.StringBuilder();
            var c: char = 'a';
            sb.append(c);
            sb.append('b');
            return sb.toString();
        """))
    }

    @Test
    fun `random code points round trip through int32 and string`() {
        val random = Random(20261117)
        repeat(20) { round ->
            var cp: Int
            do { cp = random.nextInt(0x20, 0x110000) } while (cp in 0xD800..0xDFFF)
            val text = String(Character.toChars(cp))
            val literal = text.replace("\\", "\\\\").replace("'", "\\'")
            assertEquals(cp * 1L + text.length, run("r$round", "int64", """
                var c: char = '$literal';
                var s: string = "" + c;
                var n: int64 = cast c as int64;
                var len: int32 = s.length();
                return n + len;
            """), "cp=$cp")
        }
    }
}
