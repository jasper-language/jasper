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
 * 整数字面量的取值范围（Q151，LANG-INT-LITERAL-RANGE-01）。
 *
 * 修复前：无后缀字面量超出 int32 时被静默截断（`x & 4294967295` 按 -1 计算），超出 int64 时静默变 0。
 * 期望值用 Kotlin 的 Int / Long 运算独立算出。
 */
class IntegerLiteralRangeTest {
    private fun compile(name: String, ret: String, expr: String): Pair<JasperCompiler.CompileResult, java.nio.file.Path> {
        val dir = Paths.get("build", "scratch", "int-literal", name)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
        return JasperCompiler().compileToDirWithIr("package t;\npublic function main(): $ret {\nreturn $expr;\n}\n", dir) to dir
    }

    private fun value(name: String, ret: String, expr: String): Any? {
        val (result, dir) = compile(name, ret, expr)
        assertIs<JasperCompiler.CompileResult.Success>(result, "$expr: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty())
        URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader).use { return it.loadClass("t.main").getMethod("main").invoke(null) }
    }

    private fun rejected(name: String, ret: String, expr: String, fragment: String) {
        val error = assertIs<JasperCompiler.CompileResult.Error>(compile(name, ret, expr).first, "$expr should be rejected")
        assertTrue(error.errors.any { fragment in it.message }, error.message)
    }

    @Test
    fun `boundary literals keep their exact value`() {
        assertEquals(Int.MAX_VALUE, value("imax", "int32", "2147483647"))
        assertEquals(Int.MIN_VALUE, value("imin", "int32", "-2147483648"))
        assertEquals(-1, value("hex32", "int32", "0xFFFFFFFF"))
        assertEquals(Int.MIN_VALUE, value("bin32", "int32", "0b1000_0000_0000_0000_0000_0000_0000_0000"))
        assertEquals(Long.MAX_VALUE, value("lmax", "int64", "9223372036854775807L"))
        assertEquals(Long.MIN_VALUE, value("lmin", "int64", "-9223372036854775808L"))
        assertEquals(-1L, value("hex64", "int64", "0xFFFF_FFFF_FFFF_FFFFL"))
        assertEquals(5000000000L and 4294967295L, value("mask", "int64", "5000000000L & 4294967295L"))
        assertEquals(511L, value("oct", "int64", "0o777L"))
    }

    @Test
    fun `out of range literals are diagnosed instead of truncated`() {
        rejected("q151", "int32", "7 & 4294967295", "out of int32 range")
        rejected("i32+1", "int32", "2147483648", "add the L suffix")
        rejected("neg-i32-1", "int32", "-2147483649", "out of int32 range")
        rejected("hex33", "int32", "0x1_0000_0000", "out of int32 range")
        rejected("i64+1", "int64", "9223372036854775808L", "out of int64 range")
        rejected("huge", "int64", "99999999999999999999L", "out of int64 range")
        rejected("hex65", "int64", "0x1_0000_0000_0000_0000L", "out of int64 range")
        // 负号只放宽它的直接操作数：括号里或二元减号右边的 2147483648 仍越界。
        rejected("sub", "int32", "0 - 2147483648", "out of int32 range")
    }

    @Test
    fun `random literals either round trip or are rejected`() {
        val random = Random(20261113)
        var ok = 0
        var bad = 0
        repeat(60) { round ->
            val long = random.nextBoolean()
            val radix = listOf(10, 16, 2, 8)[random.nextInt(4)]
            val prefix = mapOf(10 to "", 16 to "0x", 2 to "0b", 8 to "0o").getValue(radix)
            val negate = radix == 10 && random.nextBoolean()
            val width = if (long) 64 else 32
            val limit = when {
                radix != 10 -> java.math.BigInteger.ONE.shiftLeft(width).subtract(java.math.BigInteger.ONE)
                negate -> java.math.BigInteger.ONE.shiftLeft(width - 1)
                else -> java.math.BigInteger.ONE.shiftLeft(width - 1).subtract(java.math.BigInteger.ONE)
            }
            // 一半取边界附近（limit-2 .. limit+2），一半取随机位宽，两侧都有足够样本。
            val magnitude = if (random.nextBoolean()) {
                limit.add(java.math.BigInteger.valueOf(random.nextLong(-2, 3)))
            } else {
                java.math.BigInteger(random.nextInt(1, width + 2), java.util.Random(random.nextLong()))
            }
            val raw = prefix + magnitude.toString(radix) + if (long) "L" else ""
            val expr = if (negate) "-$raw" else raw
            val type = if (long) "int64" else "int32"
            if (magnitude > limit) {
                rejected("r$round", type, expr, "out of $type range")
                bad++
            } else {
                val signed = if (negate) magnitude.negate() else magnitude
                val want: Any = if (long) signed.toLong() else signed.toInt()
                assertEquals(want, value("r$round", type, expr), expr)
                ok++
            }
        }
        assertTrue(ok >= 10 && bad >= 10, "ok=$ok bad=$bad")
    }
}
