package jasper

import jasper.compiler.JasperCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random

/**
 * 后缀 `++` / `--` 按目标的真实类型更新（Q222）。
 *
 * 修复前翻译器全程按 int32 建 IR：int64 / float 目标产 VerifyError；字段的接收者求值两次；
 * 字段和数组元素的 `--` 作表达式时得到新值，而局部变量和 `++` 得到旧值。
 * 这里编译并运行，期望值都独立算出，不经过被测的降级路径。
 */
class PostfixIncDecTest {
    private fun scratch(name: String): Path {
        val dir = Paths.get("build", "scratch", "postfix-inc-dec", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return dir
    }

    private fun run(name: String, source: String): Any? {
        val dir = scratch(name)
        val result = JasperCompiler().compileToDirWithIr(source, dir)
        assertTrue(result is JasperCompiler.CompileResult.Success, "compile $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { loader ->
            return loader.loadClass("t.main").getMethod("main").invoke(null)
        }
    }

    private fun compileError(name: String, source: String): String {
        val result = runCatching { JasperCompiler().compileToDirWithIr(source, scratch(name)) }
        result.exceptionOrNull()?.let { return it.message.orEmpty() }
        val outcome = result.getOrThrow()
        assertFalse(outcome is JasperCompiler.CompileResult.Success, "$name should be rejected")
        return (outcome as JasperCompiler.CompileResult.Error).message
    }

    /** 每种数值类型：初值、`++` 后、`--` 后；窄类型取边界，确认按类型回绕。 */
    private data class Case(val type: String, val init: String, val inc: Any, val dec: Any)

    private val cases = listOf(
        Case("int32", "5", 6, 4),
        Case("int32", "2147483647", Int.MIN_VALUE, 2147483646),
        Case("int32", "-2147483648", -2147483647, Int.MAX_VALUE),
        Case("int64", "5L", 6L, 4L),
        Case("int64", "5", 6L, 4L),
        Case("int64", "9223372036854775807L", Long.MIN_VALUE, 9223372036854775806L),
        Case("float64", "1.5", 2.5, 0.5),
        Case("float32", "cast 1.5 as float32", 2.5f, 0.5f),
        Case("byte", "cast 127 as byte", (-128).toByte(), 126.toByte()),
        Case("byte", "cast -128 as byte", (-127).toByte(), 127.toByte()),
        Case("char", "cast 65535 as char", 0.toChar(), 65534.toChar()),
        Case("char", "cast 0 as char", 1.toChar(), 65535.toChar()),
    )

    @Test
    fun `local statement updates by the declared type`() {
        cases.forEachIndexed { i, c ->
            for ((op, want) in listOf("++" to c.inc, "--" to c.dec)) {
                val got = run("local-$i-${op[0].code}", """
                    package t;
                    public function main(): ${c.type} {
                        var x: ${c.type} = ${c.init};
                        x$op;
                        return x;
                    }
                """)
                assertEquals(want, got, "${c.type} ${c.init} $op")
            }
        }
    }

    @Test
    fun `postfix expression yields the old value for every target and direction`() {
        for (op in listOf("++", "--")) {
            val sign = if (op == "++") 1L else -1L
            val code = op[0].code
            assertEquals(5L, run("value-local-$code", """
                package t;
                public function main(): int64 { var x: int64 = 5; var r: int64 = x$op; return r; }
            """), "local $op")
            assertEquals(5L, run("value-field-$code", """
                package t;
                public class B { public var n: int64 = 5; }
                public function main(): int64 { var b: B = new B(); var r: int64 = b.n$op; return r; }
            """), "field $op")
            assertEquals(5L, run("value-static-$code", """
                package t;
                public class S { public static var n: int64 = 5L; }
                public function main(): int64 { var r: int64 = S.n$op; return r; }
            """), "static $op")
            assertEquals(5L, run("value-array-$code", """
                package t;
                public function main(): int64 { var a: int64[] = new int64[2]; a[1] = 5; var r: int64 = a[1]$op; return r; }
            """), "array $op")
            assertEquals(5L + sign, run("store-field-$code", """
                package t;
                public class B { public var n: int64 = 5; }
                public function main(): int64 { var b: B = new B(); b.n$op; return b.n; }
            """), "field store $op")
            assertEquals(5L + sign, run("store-static-$code", """
                package t;
                public class S { public static var n: int64 = 5L; }
                public function main(): int64 { S.n$op; return S.n; }
            """), "static store $op")
            assertEquals(5L + sign, run("store-array-$code", """
                package t;
                public function main(): int64 { var a: int64[] = new int64[2]; a[1] = 5; a[1]$op; return a[1]; }
            """), "array store $op")
        }
    }

    @Test
    fun `narrow fields and array elements wrap in place`() {
        assertEquals((-128).toByte(), run("field-byte", """
            package t;
            public class B { public var n: byte = cast 127 as byte; }
            public function main(): byte { var b: B = new B(); b.n++; return b.n; }
        """))
        assertEquals((-128).toByte(), run("array-byte", """
            package t;
            public function main(): byte { var a: byte[] = new byte[1]; a[0] = cast 127 as byte; a[0]++; return a[0]; }
        """))
        assertEquals(0.toChar(), run("array-char", """
            package t;
            public function main(): char { var a: char[] = new char[1]; a[0] = cast 65535 as char; a[0]++; return a[0]; }
        """))
        assertEquals(2.5, run("array-float64", """
            package t;
            public function main(): float64 { var a: float64[] = new float64[1]; a[0] = 1.5; a[0]++; return a[0]; }
        """))
    }

    @Test
    fun `receiver and index evaluate exactly once and in order`() {
        assertEquals(6L * 1000 + 1, run("once-field", """
            package t;
            public class B {
                public var n: int64 = 5;
                public static var calls: int64 = 0L;
                public static var only: B = new B();
                public static function pick(): B { B.calls = B.calls + 1; return B.only; }
            }
            public function main(): int64 { B.pick().n++; return B.only.n * 1000 + B.calls; }
        """))
        assertEquals(1L * 1000 + 11, run("once-array", """
            package t;
            public class L {
                public static var log: int64 = 0L;
                public static var data: int64[] = new int64[3];
                public static function arr(): int64[] { L.log = L.log * 10 + 1; return L.data; }
                public static function idx(): int32 { L.log = L.log * 10 + 1; return 2; }
            }
            public function main(): int64 { L.arr()[L.idx()]++; return L.data[2] * 1000 + L.log; }
        """))
        assertEquals(5L, run("value-once-field", """
            package t;
            public class B {
                public var n: int64 = 5;
                public static var calls: int32 = 0;
                public static var only: B = new B();
                public static function pick(): B { B.calls = B.calls + 1; return B.only; }
            }
            public function main(): int64 { var r: int64 = B.pick().n--; if (B.calls != 1) { return -1L; } return r; }
        """))
    }

    @Test
    fun `statements in loops keep the operand stack balanced`() {
        assertEquals(30L, run("loop-all", """
            package t;
            public class B { public var n: int64 = 0; public static var s: int64 = 0L; }
            public function main(): int64 {
                var i: int64 = 0;
                var a: int64[] = new int64[1];
                var b: B = new B();
                while (i < 10L) {
                    a[0]++;
                    b.n++;
                    B.s++;
                    i++;
                }
                return a[0] + b.n + B.s;
            }
        """))
        assertEquals(3.0, run("for-update-float64", """
            package t;
            public function main(): float64 {
                var total: float64 = 0.0;
                for (var x: float64 = 0.0; x < 3.0; x++) { total = total + 1.0; }
                return total;
            }
        """))
        assertEquals(4L, run("for-update-array", """
            package t;
            public function main(): int64 {
                var a: int64[] = new int64[1];
                for (var i: int32 = 0; i < 4; a[0]++) { i++; }
                return a[0];
            }
        """))
    }

    @Test
    fun `cast narrows to the target range`() {
        assertEquals(44.toByte(), run("cast-int-byte", """
            package t;
            public function main(): byte { var x: int32 = 300; return cast x as byte; }
        """))
        assertEquals(44.toByte(), run("cast-long-byte", """
            package t;
            public function main(): byte { var x: int64 = 300L; return cast x as byte; }
        """))
        assertEquals(65.toChar(), run("cast-float64-char", """
            package t;
            public function main(): char { var x: float64 = 65.9; return cast x as char; }
        """))
        assertEquals(255L, run("cast-byte-long", """
            package t;
            public function main(): int64 { var x: byte = cast -1 as byte; return cast x as int64; }
        """))
        assertEquals(65535.0, run("cast-char-float64", """
            package t;
            public function main(): float64 { var x: char = cast 65535 as char; return cast x as float64; }
        """))
        assertEquals(300, run("cast-int-long-int-keeps", """
            package t;
            public function main(): int32 { var x: int32 = 300; var y: int64 = cast x as int64; return cast y as int32; }
        """))
    }

    @Test
    fun `non numeric targets are refused at compile time`() {
        assertTrue("needs a numeric operand" in compileError("string", """
            package t;
            public function main(): int32 { var s: string = "a"; s++; return 0; }
        """))
        assertTrue("needs a numeric operand" in compileError("any", """
            package t;
            public function main(): int32 { var s: any = 1; s++; return 0; }
        """))
    }

    /** 随机序列：判据用 Kotlin 对同一操作序列独立求值，不经过被测降级路径。 */
    @Test
    fun `random update sequences match an independent model`() {
        val random = Random(20261107)
        repeat(60) { round ->
            data class Kind(val name: String, val init: Long, val step: (Long, Int) -> Long)
            val kind = listOf(
                Kind("int32", 2147483640L) { v, d -> (v.toInt() + d).toLong() },
                Kind("int64", 9223372036854775800L) { v, d -> v + d },
                Kind("byte", 250L) { v, d -> ((v.toInt() + d) and 0xFF).toLong() },  // byte 无符号（Q229），在 255/0 处回绕
            )[random.nextInt(3)]
            val shape = random.nextInt(4)
            val ops = List(random.nextInt(1, 25)) { if (random.nextBoolean()) "++" else "--" }
            val target = when (shape) {
                0 -> "x"
                1 -> "b.n"
                2 -> "S.n"
                else -> "a[0]"
            }
            var model = kind.init
            var seen = 0L
            val body = StringBuilder()
            ops.forEachIndexed { i, op ->
                val d = if (op == "++") 1 else -1
                if (i % 3 == 0) {
                    // 表达式位置：记下返回的旧值，序列结束时一并核对。
                    body.append("var r$i: ${kind.name} = $target$op;\nseen = seen * 31 + cast r$i as int64;\n")
                    seen = seen * 31 + model
                } else {
                    body.append("$target$op;\n")
                }
                model = kind.step(model, d)
            }
            val init = "cast ${kind.init}L as ${kind.name}"
            val got = run("random-$round", """
                package t;
                public class B { public var n: ${kind.name} = $init; }
                public class S { public static var n: ${kind.name} = $init; }
                public function main(): int64 {
                    var seen: int64 = 0;
                    var x: ${kind.name} = $init;
                    var b: B = new B();
                    var a: ${kind.name}[] = new ${kind.name}[1];
                    a[0] = $init;
                    $body
                    var end: ${kind.name} = $target;
                    return seen * 31 + cast end as int64;
                }
            """)
            assertEquals(seen * 31 + model, got, "round $round ${kind.name} $target $ops")
        }
    }
}
