package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `x == null` / `x != null` 是空判断，不调用用户 equals（Q166，LANG-NULL-COMPARE-01）。
 *
 * 修复前走 Objects.equals(x, null)：x 非 null 时调用 x.equals(null)，用户 equals 的计数、异常都会发生。
 * 这里的 equals 每次调用都计数，且收到 null 时抛出，修复前的行为会直接失败。
 */
class NullCompareTest {
    private val probe = """
        package t;
        import std.Error;
        public class Box {
            public static var calls: int32 = 0;
            public var v: int32 = 1;
            public function equals(other: any?): bool {
                Box.calls = Box.calls + 1;
                if (other == null) { throw new Error("equals(null) called"); }
                return true;
            }
        }
    """.trimIndent()

    private fun run(name: String, body: String): Int {
        val dir = Paths.get("build", "scratch", "null-compare", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val source = probe + "\npublic function entry(): int32 {\n$body\n}\n"
        val result = JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to source), dir)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = Files.list(dir.resolve("t")).use { it.toList() }
                .map { loader.loadClass("t." + it.fileName.toString().removeSuffix(".class")) }
                .first { c -> c.methods.any { it.name == "entry" } }
            return owner.getMethod("entry").invoke(null) as Int
        }
    }

    @Test
    fun `null comparisons never call equals`() {
        // 结果编码：每个判断贡献一位，最后加上 equals 调用次数 * 1000（应为 0）。
        val body = """
            var b: Box? = new Box();
            var n: Box? = null;
            var r = 0;
            if (b != null) { r = r + 1; }
            if (b == null) { r = r + 2; }
            if (null != b) { r = r + 4; }
            if (n == null) { r = r + 8; }
            if (null == n) { r = r + 16; }
            if (n != null) { r = r + 32; }
            var flag: bool = b == null;
            if (!flag) { r = r + 64; }
            return r + Box.calls * 1000;
        """
        assertEquals(1 + 4 + 8 + 16 + 64, run("basic", body))
    }

    @Test
    fun `value equality still calls equals`() {
        assertEquals(1001, run("value", """
            var a: Box = new Box();
            var c: Box = new Box();
            var r = 0;
            if (a == c) { r = r + 1; }
            return r + Box.calls * 1000;
        """))
    }

    @Test
    fun `strings and nullable primitives compare with null`() {
        assertEquals(7, run("mixed", """
            var s: string? = "x";
            var t: string? = null;
            var i: int32? = 5;
            var r = 0;
            if (s != null) { r = r + 1; }
            if (t == null) { r = r + 2; }
            if (i != null) { r = r + 4; }
            return r;
        """))
    }
}
