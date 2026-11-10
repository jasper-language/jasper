// ============================================================================
// NumericPromotionRegressionTest.kt —— 左窄右宽的算术表达式结果类型回归测试
// ============================================================================
// 缺陷一：二元运算的 IR 结果类型只取左操作数，`var x: int64 = 4 + y` 在赋值前多插一条
//   I2L，加载时 VerifyError。缺陷二：数组写元素的指令跟右值类型走，`int64[]` 存 int32
//   同样 VerifyError。此处经真实编译、真实加载、真实执行守门。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 左操作数较窄时，结果类型必须是较宽者；移位仍取左操作数。 */
class NumericPromotionRegressionTest {
    @Test
    fun `narrow left operand takes the wider result type`() {
        val source = """
            package p;
            public class N {
                public static function take(v: int64): int64 { return v; }
                public static function local(y: int64): int64 { var x: int64 = 4 + y * 16; return x; }
                public static function ret(y: int64): int64 { return 1 - y; }
                public static function arg(y: int64): int64 { return N.take(2 * y); }
                public static function bits(y: int64): int64 { var x: int64 = 255 & y; return x; }
                public static function float64Mix(i: int32, d: float64): float64 { var r: float64 = i * d; return r; }
                public static function longFloat(y: int64, d: float64): float64 { var r: float64 = y + d; return r; }
                public static function shift(i: int32, n: int64): int32 { var r: int32 = i << 3; return r; }
                public static function storeLong(i: int32): int64 { var a: int64[] = new int64[2]; a[1] = i; return a[1]; }
                public static function storeDouble(y: int64): float64 { var a: float64[] = new float64[1]; a[0] = y; return a[0]; }
                public static function storeIntFromByte(b: byte[]): int32 { var a: int32[] = new int32[1]; a[0] = b[0]; return a[0]; }
                public static function storeLongFromByte(b: byte[]): int64 { var a: int64[] = new int64[1]; a[0] = b[0]; return a[0]; }
            }
        """.trimIndent()
        val out = Paths.get("build", "tmp", "numeric-promotion")
        out.toFile().deleteRecursively()
        Files.createDirectories(out)
        val result = JasperCompiler().compileToDirWithIr(source, out)
        assertTrue(result is JasperCompiler.CompileResult.Success, "$result")
        URLClassLoader(arrayOf(out.toUri().toURL())).use { loader ->
            val cls = loader.loadClass("p.N")
            fun call(name: String, vararg args: Any): Any? = cls.methods.first { it.name == name }.invoke(null, *args)
            assertEquals(5_000_000_000L * 16 + 4, call("local", 5_000_000_000L))
            assertEquals(1 - 5_000_000_000L, call("ret", 5_000_000_000L))
            assertEquals(10_000_000_000L, call("arg", 5_000_000_000L))
            assertEquals(255L, call("bits", 0x1_0000_00FFL))
            assertEquals(7.5, call("float64Mix", 3, 2.5))
            assertEquals(3.5, call("longFloat", 3L, 0.5))
            assertEquals(8, call("shift", 1, 3L))
            assertEquals(-7L, call("storeLong", -7))
            assertEquals(5e9, call("storeDouble", 5_000_000_000L))
            // byte 是无符号 uint8（Q229）：位模式 0xFE 读出是 254。
            assertEquals(254, call("storeIntFromByte", byteArrayOf(-2)))
            assertEquals(254L, call("storeLongFromByte", byteArrayOf(-2)))
        }
    }
}
