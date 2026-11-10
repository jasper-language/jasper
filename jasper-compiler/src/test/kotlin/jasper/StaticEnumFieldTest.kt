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
 * 静态字段初值与枚举字段（Q221，LANG-STATIC-ENUM-FIELDS-01）。
 *
 * 修复前：① `public static var a: int64 = 5` 产 VerifyError（静态初值没做存储转换）；
 * ② 从外部读枚举实例字段 `Color.GREEN.weight` 检查器报 Cannot resolve member；
 * ③ 枚举方法体不经检查器，方法里读字段简单名得到 VerifyError 而不是诊断。期望值独立写出。
 */
class StaticEnumFieldTest {
    private fun compile(name: String, source: String): Pair<JasperCompiler.CompileResult, java.nio.file.Path> {
        val dir = Paths.get("build", "scratch", "static-enum", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return JasperCompiler().compileAllWithStdlib(mapOf("t.jas" to source), dir) to dir
    }

    private fun run(name: String, source: String): Any? {
        val (result, dir) = compile(name, source)
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
    fun `static initializers convert to the declared type`() {
        assertEquals(5L + 2L + 1.5 + 3.0f, run("static-widen", """
            package t;
            public class S {
                public static var a: int64 = 5;
                public static var b: int64 = 2L;
                public static var c: float64 = 1.5;
                public static var d: float32 = 3.0f;
                public static var e: float64 = 3;
                public static final var f: int64 = 7;
            }
            public function entry(): float64 { return S.a + S.b + S.c + S.d + S.e - 3.0 + S.f - 7.0; }
        """.trimIndent()))
    }

    @Test
    fun `random static initializers widen exactly`() {
        val random = Random(20261115)
        repeat(12) { round ->
            val v = random.nextInt(-1000, 1000)
            // int32 → float32 不是隐式拓宽（可能丢精度），这里只取允许的目标。
            val target = listOf("int64", "float64", "int32")[random.nextInt(3)]
            val want: Any = when (target) { "int64" -> v.toLong(); "float64" -> v.toDouble(); else -> v }
            assertEquals(want, run("r$round", """
                package t;
                public class S { public static var a: $target = $v; }
                public function entry(): $target { return S.a; }
            """.trimIndent()), "round $round $target $v")
        }
    }

    @Test
    fun `enum instance fields are readable from outside`() {
        assertEquals(12, run("enum-field", """
            package t;
            public enum Color {
                RED, GREEN;
                public var weight: int32 = 6;
                public function twice(): int32 { return this.weight * 2; }
            }
            public function entry(): int32 { return Color.RED.weight + Color.GREEN.weight; }
        """.trimIndent()))
    }

    @Test
    fun `enum method bodies are type checked`() {
        val (result, _) = compile("enum-simple-name", """
            package t;
            public enum Color {
                RED;
                public var weight: int32 = 5;
                public function bad(): int32 { return weight; }
            }
            public function entry(): int32 { return 0; }
        """.trimIndent())
        val error = assertIs<JasperCompiler.CompileResult.Error>(result)
        assertTrue(error.errors.any { "this.weight" in it.message }, error.message)
        val (typeErr, _) = compile("enum-type", """
            package t;
            public enum Color {
                RED;
                public function bad(): int32 { return "x"; }
            }
            public function entry(): int32 { return 0; }
        """.trimIndent())
        assertIs<JasperCompiler.CompileResult.Error>(typeErr)
        assertEquals(10, run("enum-method", """
            package t;
            public enum Color {
                RED;
                public var weight: int32 = 5;
                public function twice(): int32 { return this.weight * 2; }
            }
            public function entry(): int32 { return Color.RED.twice(); }
        """.trimIndent()))
    }
}
