package jasper

import jasper.compiler.JasperCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 全限定平台静态字段与平台调用链（Q163 / Q165，LANG-PLATFORM-STATIC-FIELD-01）。
 *
 * 修复前 `java.lang.System.err.println(x)` 被当成类 `java.lang.System.err` 上的静态调用而报错，
 * `java.io.File.pathSeparator` 报 Unresolved reference 'java'。期望值由 JDK 反射独立取得。
 */
class PlatformStaticFieldTest {
    private fun compile(name: String, ret: String, body: String): Pair<JasperCompiler.CompileResult, java.nio.file.Path> {
        val dir = Paths.get("build", "scratch", "platform-static-field", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val source = "package t;\npublic function entry(): $ret {\n$body\n}\n"
        return JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to source), dir) to dir
    }

    private fun run(name: String, ret: String, body: String): Any? {
        val (result, dir) = compile(name, ret, body)
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
    fun `static fields are read with their real types`() {
        assertEquals(Int.MAX_VALUE, run("int", "int32", "return java.lang.Integer.MAX_VALUE;"))
        assertEquals(Long.MIN_VALUE, run("long", "int64", "return java.lang.Long.MIN_VALUE;"))
        assertEquals(File.pathSeparator + "x", run("concat", "string", "return java.io.File.pathSeparator + \"x\";"))
        assertEquals(File.separator, run("local", "string", "var s = java.io.File.separator;\nreturn s;"))
        assertEquals(Int.MIN_VALUE.toString(), run("as-arg", "string", "return java.lang.Integer.toString(java.lang.Integer.MIN_VALUE);"))
    }

    @Test
    fun `instance calls on a static field value`() {
        assertEquals(0, run("comparator", "int32", "return java.lang.String.CASE_INSENSITIVE_ORDER.compare(\"abc\", \"ABC\");"))
        val err = ByteArrayOutputStream()
        val old = System.err
        System.setErr(PrintStream(err, true, "UTF-8"))
        try {
            run("stderr", "int32", "java.lang.System.err.println(\"q163\");\nreturn 0;")
        } finally {
            System.setErr(old)
        }
        assertEquals("q163" + System.lineSeparator(), err.toString("UTF-8"))
    }

    @Test
    fun `a qualified static call chain inside cast (Q165)`() {
        System.setProperty("jasper.q165", "v165")
        assertEquals("v165", run("cast-chain", "string", "return cast (java.lang.System.getProperties().get(\"jasper.q165\")) as string;"))
    }

    @Test
    fun `unknown or non static fields are diagnosed`() {
        val (missing, _) = compile("missing", "int32", "var x = java.lang.Integer.NOPE;\nreturn 0;")
        assertIs<JasperCompiler.CompileResult.Error>(missing)
        // value 是 Integer 的实例字段（private），不是 public static。
        val (instance, _) = compile("instance", "int32", "var x = java.lang.Integer.value;\nreturn 0;")
        assertIs<JasperCompiler.CompileResult.Error>(instance)
        // x 是 java.awt.Point 的 public 实例字段：只有 public static 才能按全限定名读。
        val (publicInstance, _) = compile("public-instance", "int32", "var x = java.awt.Point.x;\nreturn 0;")
        assertIs<JasperCompiler.CompileResult.Error>(publicInstance)
    }

    @Test
    fun `random static field reads match reflection`() {
        val fields = listOf(
            "java.lang.Integer" to "MAX_VALUE", "java.lang.Integer" to "MIN_VALUE", "java.lang.Integer" to "SIZE",
            "java.lang.Long" to "MAX_VALUE", "java.lang.Long" to "BYTES", "java.lang.Short" to "MIN_VALUE",
            "java.lang.Character" to "MAX_RADIX", "java.lang.Double" to "MAX_EXPONENT", "java.io.File" to "separator",
            "java.io.File" to "pathSeparator", "java.lang.Boolean" to "TRUE", "java.lang.Byte" to "MAX_VALUE"
        )
        val random = Random(20261112)
        val body = StringBuilder("var out = \"\";\n")
        val expected = StringBuilder()
        repeat(50) {
            val (cls, field) = fields[random.nextInt(fields.size)]
            body.append("out = out + $cls.$field + \";\";\n")
            expected.append(Class.forName(cls).getField(field).get(null)).append(';')
        }
        body.append("return out;")
        assertEquals(expected.toString(), run("random", "string", body.toString()))
    }
}
