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
 * 平台类型的静默错误组（Q158，LANG-PLATFORM-SILENT-01）。
 *
 * 修复前五种写法都能编译，却在类加载或运行时才失败：① 平台构造器描述符取实参静态类型；
 * ② 平台子类型赋给平台父类型被拒；③ `HashMap.keySet()` 作实参产 `(V)V`；④ `this.field.get(k)`
 * （TreeMap 字段）描述符错；⑤ 静态字段初始化器里调平台静态方法内部错误。期望值由 JDK 独立算出。
 */
class PlatformTypeSilentErrorTest {
    private fun run(name: String, ret: String, body: String, extra: String = ""): Any? {
        val dir = Paths.get("build", "scratch", "platform-silent", name)
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
    fun `1 platform constructors use the declared parameter types`() {
        assertEquals(3, run("ctor", "int32", """
            var buf = new java.io.ByteArrayOutputStream();
            buf.write(1);
            buf.write(2);
            buf.write(3);
            var input = new java.io.ByteArrayInputStream(buf.toByteArray());
            var buffered = new java.io.BufferedInputStream(input);
            return buffered.available();
        """))
    }

    @Test
    fun `2 a platform subtype is assignable to its platform supertype`() {
        assertEquals("ab", run("subtype", "string", """
            var builder: java.lang.CharSequence = new java.lang.StringBuilder("ab");
            return builder.toString();
        """))
        assertEquals(2, run("subtype-list", "int32", """
            var list: java.util.List = new java.util.ArrayList();
            list.add("x");
            list.add("y");
            return list.size();
        """))
    }

    @Test
    fun `unrelated platform types are still rejected`() {
        val dir = Paths.get("build", "scratch", "platform-silent", "unrelated")
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val source = "package t;\npublic function entry(): int32 {\nvar c: java.lang.CharSequence = new java.util.ArrayList();\nreturn 0;\n}\n"
        val result = JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to source), dir)
        val error = assertIs<JasperCompiler.CompileResult.Error>(result)
        kotlin.test.assertTrue(error.errors.any { "Type mismatch" in it.message }, error.message)
    }

    @Test
    fun `3 a platform call result passed as an argument`() {
        assertEquals(2, run("keyset-arg", "int32", """
            var m = new java.util.HashMap();
            m.put("a", "1");
            m.put("b", "2");
            var copy = new java.util.ArrayList(m.keySet());
            return copy.size();
        """))
    }

    @Test
    fun `4 calls on a platform typed field`() {
        val extra = """
            public class Index {
                private var map: java.util.TreeMap = new java.util.TreeMap();
                public function put(k: string, v: string): void { this.map.put(k, v); }
                public function get(k: string): string { return cast (this.map.get(k)) as string; }
            }
        """.trimIndent()
        assertEquals("2", run("field-get", "string", "var i = new Index();\ni.put(\"a\", \"1\");\ni.put(\"b\", \"2\");\nreturn i.get(\"b\");", extra))
    }

    @Test
    fun `5 platform static calls in static field initializers`() {
        val extra = """
            public class Config {
                public static var LIMIT: int32 = java.lang.Integer.parseInt("42");
            }
        """.trimIndent()
        assertEquals(42, run("static-init", "int32", "return Config.LIMIT;", extra))
    }

    @Test
    fun `random platform collection programs match the JDK`() {
        val random = Random(20261113)
        val body = StringBuilder("var m = new java.util.TreeMap();\n")
        val expected = java.util.TreeMap<String, String>()
        repeat(40) { k ->
            val key = "k" + random.nextInt(0, 15)
            val value = "v$k"
            body.append("m.put(\"$key\", \"$value\");\n")
            expected[key] = value
        }
        body.append("var keys = new java.util.ArrayList(m.keySet());\n")
        body.append("var out = \"\";\nfor (var i = 0; i < keys.size(); i++) { var key = cast (keys.get(i)) as string; out = out + key + \"=\" + (cast (m.get(key)) as string) + \";\"; }\nreturn out;")
        val want = expected.entries.joinToString("") { "${it.key}=${it.value};" }
        assertEquals(want, run("random", "string", body.toString()))
    }
}
