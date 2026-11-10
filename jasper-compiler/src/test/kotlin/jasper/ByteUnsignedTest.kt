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
 * `byte` 是无符号 uint8（规范 A5，用户 2026-11-10 裁决；Q229 第一部分，LANG-BYTE-UINT8-01）。
 *
 * 修复前 byte 与 int8 同为有符号 8 位：位模式 0xFE 读出 -2，`b > 127` 永远为假。现在所有读出点
 * （数组元素、字段、静态字段、方法返回、转换、装箱后的集合元素）都是 0..255；int8 仍是有符号。
 * 期望值由 Kotlin 的 `and 0xFF` 独立算出。
 */
class ByteUnsignedTest {
    private fun run(name: String, ret: String, body: String, extra: String = ""): Any? {
        val dir = Paths.get("build", "scratch", "byte-unsigned", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val src = "package t;\n$extra\npublic function entry(): $ret {\n$body\n}\n"
        val result = JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to src), dir)
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
    fun `every read of a byte is zero extended`() {
        val holder = """
            public class H {
                public var f: byte = cast 254 as byte;
                public static var s: byte = cast 253 as byte;
                public static function give(): byte { return cast 252 as byte; }
            }
        """.trimIndent()
        assertEquals(254 + 254 + 253 + 252 + 251, run("reads", "int32", """
            var a: byte[] = new byte[2];
            a[0] = cast 254 as byte;
            var fromArray: int32 = a[0];
            var h = new H();
            var fromField: int32 = h.f;
            var fromStatic: int32 = H.s;
            var fromCall: int32 = H.give();
            var local: byte = cast -5 as byte;
            var fromLocal: int32 = local;
            return fromArray + fromField + fromStatic + fromCall + fromLocal;
        """, holder))
    }

    @Test
    fun `comparisons and widening see the unsigned value`() {
        assertEquals(7, run("compare", "int32", """
            var b: byte = cast 200 as byte;
            var r = 0;
            if (b > 127) { r = r + 1; }
            var wide: int64 = b;
            if (wide == 200L) { r = r + 2; }
            var signed: int8 = cast b as int8;
            if (signed == cast -56 as int8) { r = r + 4; }
            return r;
        """))
    }

    @Test
    fun `arithmetic on bytes is int arithmetic`() {
        assertEquals(255 + 255 + 1, run("arith", "int32", """
            var b: byte = cast 255 as byte;
            var s = b + b;
            var t = s + 1;
            return t;
        """))
        assertEquals(-200, run("negate", "int32", "var b: byte = cast 200 as byte;\nvar n = -b;\nreturn n;"))
        // 运算结果是 int32：装箱成 Integer 存进 int32 集合，不会被当成 byte 截断。
        assertEquals(510, run("boxed-sum", "int32", "var b: byte = cast 255 as byte;\nvar l = new MutableList<int32>();\nl.add(b + b);\nreturn l.get(0);"))
    }

    @Test
    fun `platform byte arrays read unsigned`() {
        // "é" 的 UTF-8 是 C3 A9。
        assertEquals(0xC3 * 1000 + 0xA9, run("utf8", "int32", """
            var buf: byte[] = "é".getBytes("UTF-8");
            return buf[0] * 1000 + buf[1];
        """))
    }

    @Test
    fun `boxed bytes in collections unbox unsigned`() {
        assertEquals(255 + 128, run("boxed", "int32", """
            var l = new MutableList<byte>();
            l.add(cast 255 as byte);
            l.add(cast 128 as byte);
            return l.get(0) + l.get(1);
        """))
    }

    @Test
    fun `random casts to byte keep the low eight bits unsigned`() {
        val random = Random(20261116)
        repeat(20) { round ->
            val v = random.nextLong(-100_000L, 100_000L)
            val want = (v and 0xFF).toInt()
            assertEquals(want, run("cast$round", "int32", "var x: int64 = ${v}L;\nvar b: byte = cast x as byte;\nvar r: int32 = b;\nreturn r;"), "v=$v")
        }
    }
}
