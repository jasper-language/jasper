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
 * int32 实参传给 int64 / float64 形参按形参类型拓宽（Q237，appfw FB-app-010 / FB-web-015 / FB-003 / FB-theme-08，LANG-ARG-WIDEN-01）。
 *
 * 修复前小方法里运行期 VerifyError（栈上 int 不能当 long），大方法 / 多 try 时编译期无位置的 `[INTERNAL] -1/-2/-4`。
 * 覆盖顶层函数、类静态方法、实例方法、构造器、std 方法（Deadline.afterNanos），实参为字面量、局部、方法返回值、
 * 集合 size()、算术表达式；期望值由用例写出或由生成器用 Kotlin 独立算出。
 */
class ArgumentWideningTest {
    private val extra = """
        import std.Deadline;
        function eq64(a: int64, b: int64): bool { return a == b; }
        function sum64(a: int64, b: int64, c: int64): int64 { return a + b + c; }
        function half(x: float64): float64 { return x / 2.0; }
        class Wide {
            private var total: int64 = 0L;
            public constructor(start: int64) { this.total = start; }
            public static function same(a: int64, b: int64): bool { return a == b; }
            public function add(n: int64): int64 { this.total = this.total + n; return this.total; }
            public function count(): int32 { return 2; }
        }
    """.trimIndent()

    private fun run(name: String, ret: String, body: String): Any? {
        val dir = Paths.get("build", "scratch", "arg-widen", name)
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
    fun `int32 arguments widen at every call kind`() {
        assertEquals(true, run("toplevel-literal", "bool", "return eq64(1, 1);"))
        assertEquals(true, run("toplevel-size", "bool", "var names = new MutableList<string>();\nnames.add(\"a\");\nreturn eq64(names.size(), 1);"))
        assertEquals(true, run("static-local", "bool", "var length: int32 = 3;\nreturn Wide.same(length, 3);"))
        assertEquals(7L, run("instance", "int64", "var w = new Wide(5);\nreturn w.add(w.count());"))
        assertEquals(12L, run("mixed", "int64", "var a: int32 = 2;\nvar b: int64 = 4L;\nreturn sum64(a, b, a * 3);"))
        assertEquals(1.5, run("float", "float64", "var n: int32 = 3;\nreturn half(n);"))
        assertEquals(false, run("std-deadline", "bool", "var d: Deadline = Deadline.afterNanos(100000000);\nreturn d.isExpired();"))
    }

    @Test
    fun `widening inside loops and try blocks of a large method (FB-web-015 INTERNAL)`() {
        val body = StringBuilder("var acc: int64 = 0L;\nvar names = new MutableList<string>();\nnames.add(\"x\");\n")
        repeat(12) { k ->
            body.append("try {\n  for (var i$k: int32 = 0; i$k < 3; i$k = i$k + 1) {\n    if (eq64(i$k, $k % 3)) { acc = sum64(acc, i$k, names.size()); }\n  }\n} catch (e$k: std.Error) { acc = acc + 1000L; } finally { acc = acc + 1L; }\n")
        }
        body.append("return acc;")
        // 第 k 段：i == k % 3 时 acc += i + 1，再 finally +1。
        val expected = (0 until 12).sumOf { k -> (k % 3).toLong() + 1L + 1L }
        assertEquals(expected, run("large", "int64", "import std.Error;\n".let { "" } + body.toString()))
    }

    @Test
    fun `random argument expressions widen to the declared parameter types`() {
        val random = Random(20261118)
        val body = StringBuilder("var out: int64 = 0L;\n")
        var expected = 0L
        repeat(60) { k ->
            val a = random.nextInt(-1000, 1000)
            val b = random.nextInt(-1000, 1000)
            body.append("var a$k: int32 = $a;\nout = out + sum64(a$k, $b, a$k - $b);\n")
            expected += a.toLong() + b.toLong() + (a - b).toLong()
        }
        body.append("return out;")
        assertEquals(expected, run("random", "int64", body.toString()))
    }
}
