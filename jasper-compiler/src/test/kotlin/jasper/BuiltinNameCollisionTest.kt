package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 用户声明与后端内建语义名同名（Q224、Q152）。
 *
 * 修复前：后端按调用名分派内建指令（size → ARRAYLENGTH，get → 数组取元素，add → 集合 add 等），
 * 用户顶层函数 / 方法、平台方法只要同名就被内建路径抢走：用户 `size()` 报 AssertionError，
 * `BigInteger.add(q)` 运行时 NoSuchMethodError。期望值由测试独立写出。
 */
class BuiltinNameCollisionTest {
    private fun run(name: String, source: String): Any? {
        val dir = Paths.get("build", "scratch", "builtin-names", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("main.jas" to source), dir)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            val classes = Files.list(dir.resolve("t")).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".class") }
                .map { loader.loadClass("t." + it.fileName.toString().removeSuffix(".class")) }
            return classes.flatMap { it.methods.toList() }.single { it.name == "entry" }.invoke(null)
        }
    }

    /** 与后端内建语义名相同的用户顶层函数：调用必须落到用户函数。 */
    @Test
    fun `top level functions named like intrinsics are called as written`() {
        val cases = mapOf(
            "size" to ("public function size(): int32 { return 41; }" to "size() + 1"),
            "get" to ("public function get(i: int32): int32 { return i * 3; }" to "get(14)"),
            "put" to ("public function put(a: int32, b: int32): int32 { return a + b; }" to "put(40, 2)"),
            "add" to ("public function add(a: int32): int32 { return a + 2; }" to "add(40)"),
            "negate" to ("public function negate(a: int32): int32 { return a; }" to "negate(42)"),
            "inc" to ("public function inc(a: int32): int32 { return a * 2; }" to "inc(21)"),
            "dec" to ("public function dec(a: int32): int32 { return a - 1; }" to "dec(43)"),
            "arraySet" to ("public function arraySet(a: int32, b: int32, c: int32): int32 { return a + b + c; }" to "arraySet(40, 1, 1)")
        )
        for ((name, case) in cases) {
            val src = "package t;\n${case.first}\npublic function entry(): int32 { return ${case.second}; }\n"
            assertEquals(42, run("top-$name", src), name)
        }
    }

    @Test
    fun `methods named like intrinsics dispatch to the user class`() {
        assertEquals(42, run("methods", """
            package t;
            public class Bag {
                private var n: int32 = 40;
                public function size(): int32 { return this.n; }
                public function add(x: int32): int32 { this.n = this.n + x; return this.n; }
                public function get(i: int32): int32 { return this.n + i; }
                public static function negate(x: int32): int32 { return x; }
            }
            public function entry(): int32 {
                var b = new Bag();
                var s = b.size();
                b.add(1);
                return b.get(1) - s + Bag.negate(40);
            }
        """.trimIndent()))
        assertEquals(42, run("in-class", """
            package t;
            public class Counter {
                public static function size(): int32 { return 21; }
                public static function twice(): int32 { return size() + size(); }
            }
            public function entry(): int32 { return Counter.twice(); }
        """.trimIndent()))
    }

    @Test
    fun `platform methods named add are platform calls (Q152)`() {
        // 修复前 p.add(q) 被拼成 add(Object)V（实参按标准库同名 add(any) 转成 Object），运行时 NoSuchMethodError。
        assertEquals("42", run("biginteger-add", """
            package t;
            public function entry(): string {
                var p = new java.math.BigInteger("40");
                var q = new java.math.BigInteger("2");
                return p.add(q).toString();
            }
        """.trimIndent()))
        assertEquals("6", run("biginteger-chain", """
            package t;
            public function entry(): string {
                var p = new java.math.BigInteger("40");
                var q = new java.math.BigInteger("2");
                var big = new java.math.BigInteger("50");
                return big.subtract(p.add(q).add(q)).toString();
            }
        """.trimIndent()))
    }

    @Test
    fun `built in array and collection members still work`() {
        assertEquals(9, run("builtins", """
            package t;
            public function entry(): int32 {
                var a: int32[] = new int32[3];
                a[1] = 4;
                var l = new MutableList<int32>();
                l.add(5);
                return a.length + a[1] + l.size() + 1;
            }
        """.trimIndent()))
    }
}
