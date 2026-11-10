package jasper

import jasper.compiler.JasperCompiler
import java.math.BigInteger
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 平台调用结果的类型（Q226，LANG-PLATFORM-RESULT-01）。
 *
 * 修复前平台成员调用在翻译器里的 IR 类型是 Unit：`var r = p.add(q)` 的局部被定成 void，
 * `r.toString()` 发成 `void.toString` 运行时 NoClassDefFoundError；`p.add(q).toString() + ","`
 * 的 + 按数值加发成 iadd。期望值由 Kotlin 的 BigInteger / String 独立算出。
 */
class PlatformResultTypeTest {
    private fun run(name: String, ret: String, body: String, extra: String = ""): Any? {
        val dir = Paths.get("build", "scratch", "platform-result", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val source = "package t;\n$extra\npublic function entry(): $ret {\n$body\n}\n"
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
    fun `a platform call result stored in a local keeps its type`() {
        assertEquals("12", run("local", "string", """
            var p = new java.math.BigInteger("5");
            var q = new java.math.BigInteger("7");
            var r = p.add(q);
            return r.toString();
        """))
        assertEquals(3, run("local-int", "int32", """
            var p = new java.math.BigInteger("5");
            var n = p.bitLength();
            return n;
        """))
        assertEquals(true, run("local-bool", "bool", """
            var p = new java.math.BigInteger("5");
            var b = p.testBit(0);
            return b;
        """))
    }

    @Test
    fun `string concatenation on a platform result is not numeric addition`() {
        assertEquals("12,", run("concat", "string", """
            var p = new java.math.BigInteger("5");
            var q = new java.math.BigInteger("7");
            return p.add(q).toString() + ",";
        """))
        assertEquals("x35", run("concat-left", "string", """
            var p = new java.math.BigInteger("5");
            return "x" + p.multiply(new java.math.BigInteger("7"));
        """))
        assertEquals(8L, run("numeric", "int64", """
            var p = new java.math.BigInteger("5");
            return p.longValue() + 3L;
        """))
    }

    @Test
    fun `platform results flow into user functions and fields`() {
        val extra = """
            function twice(s: string): string { return s + s; }
            public class Box {
                public var v: java.math.BigInteger = new java.math.BigInteger("0");
            }
        """.trimIndent()
        assertEquals("1212", run("arg", "string", """
            var p = new java.math.BigInteger("5");
            return twice(p.add(new java.math.BigInteger("7")).toString());
        """, extra))
        assertEquals("9", run("field", "string", """
            var b = new Box();
            b.v = new java.math.BigInteger("4").add(new java.math.BigInteger("5"));
            var got = b.v;
            return got.toString();
        """, extra))
    }

    @Test
    fun `random arithmetic chains stored in locals match BigInteger`() {
        val random = Random(20261111)
        val body = StringBuilder("var acc = new java.math.BigInteger(\"1\");\nvar out = \"\";\n")
        var acc = BigInteger.ONE
        val expected = StringBuilder()
        repeat(60) { k ->
            val v = random.nextLong(-1_000_000L, 1_000_000L)
            val op = listOf("add", "subtract", "multiply").random(random)
            body.append("var step$k = acc.$op(new java.math.BigInteger(\"$v\"));\n")
            body.append("acc = step$k;\n")
            acc = when (op) {
                "add" -> acc.add(BigInteger.valueOf(v))
                "subtract" -> acc.subtract(BigInteger.valueOf(v))
                else -> acc.multiply(BigInteger.valueOf(v)).mod(BigInteger.TEN.pow(30))
            }
            if (op == "multiply") body.append("acc = acc.mod(new java.math.BigInteger(\"1000000000000000000000000000000\"));\n")
            if (k % 7 == 0) {
                body.append("out = out + acc.signum() + \":\" + acc.toString().length() + \";\";\n")
                expected.append(acc.signum()).append(':').append(acc.toString().length).append(';')
            }
        }
        body.append("return out + acc.toString();")
        expected.append(acc.toString())
        assertEquals(expected.toString(), run("random", "string", body.toString()))
    }
}
