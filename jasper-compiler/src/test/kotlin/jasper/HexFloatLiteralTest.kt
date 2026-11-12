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
 * 十六进制浮点字面量（Q228，LANG-HEX-FLOAT-01，docs/02 §2.2.1）。
 *
 * 修复前：`0x1.8p1` 等一律编译成 0.0。期望值用 `java.lang.Double.parseDouble` /
 * `java.lang.Float.parseFloat` 独立算出（Java 的十六进制浮点按目标精度直接正确舍入）。
 */
class HexFloatLiteralTest {
    private fun compile(name: String, ret: String, expr: String): Pair<JasperCompiler.CompileResult, java.nio.file.Path> {
        val dir = Paths.get("build", "scratch", "hex-float", name)
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
    fun `hex float literals keep their exact value`() {
        assertEquals(3.0, value("q228", "float64", "0x1.8p1"))
        assertEquals(6.0, value("arith", "float64", "0x1.8p1 * 2.0"))
        assertEquals(-3.0, value("neg", "float64", "-0x1.8p1"))
        assertEquals(3.0, value("upper", "float64", "0X1.8P+1D"))
        assertEquals(0.1875, value("nolead", "float64", "0x.Cp-2"))
        assertEquals(1024.0, value("doc", "float64", "0x1.0p10"))
        // 词法只允许指数里写下划线（JasperLexer.g4 HEX_EXPONENT），有效数字里不行。
        assertEquals(16896.0, value("under", "float64", "0x10.8p1_0"))
        assertEquals(255.0, value("intonly", "float64", "0xFFp0"))
        assertEquals(0.0, value("zero", "float64", "0x0p99999"))
        assertEquals(Double.MAX_VALUE, value("dmax", "float64", "0x1.fffffffffffffp1023"))
        assertEquals(Double.MIN_VALUE, value("dmin", "float64", "0x1p-1074"))
        assertEquals(java.lang.Double.MIN_NORMAL, value("dnorm", "float64", "0x1p-1022"))
        // 略高于最小次正规数的一半：舍入到最小次正规数，不是 0。
        assertEquals(Double.MIN_VALUE, value("dhalf+", "float64", "0x1.0000000000001p-1075"))
    }

    @Test
    fun `float32 suffix rounds directly to 24 bits`() {
        assertEquals(1.5f, value("f32", "float32", "0x1.8p0f"))
        assertEquals(0.75f, value("f32neg", "float32", "0x1.8p-1F"))
        assertEquals(Float.MAX_VALUE, value("fmax", "float32", "0x1.fffffep127f"))
        assertEquals(Float.MIN_VALUE, value("fmin", "float32", "0x1p-149f"))
        // 平局取偶：1 + 2^-24 → 1；1 + 3·2^-24 → 1 + 2^-22。
        assertEquals(1.0f, value("tie-even", "float32", "0x1.000001p0f"))
        assertEquals(java.lang.Float.intBitsToFloat(0x3f800002), value("tie-up", "float32", "0x1.000003p0f"))
        // 1 + 2^-24 + 2^-60：直接舍入得 1 + 2^-23；经 float64 中转会二次舍入成 1.0。
        assertEquals(java.lang.Float.intBitsToFloat(0x3f800001), value("double-round", "float32", "0x1.000001000000001p0f"))
    }

    @Test
    fun `malformed and out of range hex floats are diagnosed`() {
        rejected("noexp", "float64", "0x1.8", "needs a binary exponent")
        rejected("noexp-f", "float32", "0x1.8f", "needs a binary exponent")
        rejected("noexp-dot", "float64", "0x.8", "needs a binary exponent")
        rejected("dover", "float64", "0x1p1024", "out of float64 range")
        rejected("dover-round", "float64", "0x1.fffffffffffff8p1023", "out of float64 range")
        rejected("huge-exp", "float64", "0x1p99999999999", "out of float64 range")
        rejected("fover", "float32", "0x1p128f", "out of float32 range")
        rejected("fover-round", "float32", "0x1.ffffffp127f", "out of float32 range")
        rejected("dunder", "float64", "0x1p-1075", "too small for float64")
        rejected("tiny-exp", "float64", "0x1p-99999999999", "too small for float64")
        rejected("funder", "float32", "0x1p-150f", "too small for float32")
    }

    @Test
    fun `random hex floats match Java parsing or are rejected`() {
        val random = Random(20261110)
        var ok = 0
        var bad = 0
        repeat(80) { round ->
            val float32 = random.nextBoolean()
            val hex = "0123456789abcdef"
            val intPart = (0 until random.nextInt(0, 6)).map { hex[random.nextInt(16)] }.joinToString("")
            val fracPart = (0 until random.nextInt(if (intPart.isEmpty()) 1 else 0, 14)).map { hex[random.nextInt(16)] }.joinToString("")
            val significand = if (fracPart.isEmpty() && random.nextBoolean()) intPart else "$intPart.$fracPart"
            // 一半把量级放在上下边界附近，一半放在常规区间，通过与拒绝两边都有样本。
            val (low, high) = if (float32) -150 to 129 else -1076 to 1025
            val lead = when (random.nextInt(4)) {
                0 -> low + random.nextInt(-4, 5)
                1 -> high + random.nextInt(-4, 5)
                else -> random.nextInt(-40, 41)
            }
            val exp = lead - 4 * intPart.length
            val suffix = when {
                float32 -> if (random.nextBoolean()) "f" else "F"
                else -> listOf("", "d", "D")[random.nextInt(3)]
            }
            val raw = "0x${significand}p$exp$suffix"
            val oracleText = raw.replace("_", "")
            val type = if (float32) "float32" else "float64"
            val digitsNonZero = (intPart + fracPart).any { it != '0' }
            val want: Any = if (float32) java.lang.Float.parseFloat(oracleText) else java.lang.Double.parseDouble(oracleText)
            val wantDouble = (want as Number).toDouble()
            when {
                wantDouble.isInfinite() -> { rejected("r$round", type, raw, "out of $type range"); bad++ }
                wantDouble == 0.0 && digitsNonZero -> { rejected("r$round", type, raw, "too small for $type"); bad++ }
                else -> { assertEquals(want, value("r$round", type, raw), raw); ok++ }
            }
        }
        assertTrue(ok >= 15 && bad >= 10, "ok=$ok bad=$bad")
    }
}
