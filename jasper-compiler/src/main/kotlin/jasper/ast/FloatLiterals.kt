package jasper.ast

import java.math.BigInteger

/**
 * 十六进制浮点字面量的精确解析与取值范围（Q228，LANG-HEX-FLOAT-01）。
 *
 * 做什么：把 `0x1.8p1`、`0x.Cp-2f` 这类文本按 docs/02 §2.2.1 解析成正确舍入（就近、平局取偶）的
 *   float64 / float32 值；AST 构造用 [hexValue] 取值，检查器用 [hexProblem] 诊断。
 * 规则（与 Java / C99 一致）：
 * - 必须带二进制指数 `p` / `P`：没有指数时末尾的 `f` / `d` 是十六进制数字还是类型后缀无法区分。
 * - 后缀 `f` / `F` 是 float32，直接从精确值舍入到 24 位有效位（不经 float64 二次舍入）；其余是 float64。
 * - 舍入后超出有限范围、或非零值舍入成 0，都是编译错误，不静默变成无穷或 0。
 * 为什么不直接用 `java.lang.Double.parseDouble`：float32 走 double 再转 float 会二次舍入；
 *   测试用 parseDouble / parseFloat 做独立对照。
 */
object FloatLiterals {
    /** 指数绝对值的截断上限：远超任何有限值所需，又让后续整数运算不会溢出。 */
    private const val EXPONENT_CLAMP = 1L shl 24

    /** 文本是否是十六进制浮点字面量（`0x` / `0X` 前缀）。 */
    fun isHex(raw: String): Boolean = raw.startsWith("0x") || raw.startsWith("0X")

    /** 文本是否带 float32 后缀。 */
    fun isFloat32(raw: String): Boolean = raw.endsWith("f") || raw.endsWith("F")

    /** 拆好的十六进制浮点：值 = [mantissa] × 2^[exponent]，[float32] 表示目标精度。 */
    private class Parts(val mantissa: BigInteger, val exponent: Long, val float32: Boolean)

    /** 拆出尾数、二进制指数与目标精度；缺少指数或文本不合法时返回 null。 */
    private fun parse(raw: String): Parts? {
        if (!isHex(raw)) return null
        var text = raw.substring(2).replace("_", "")
        val p = text.indexOfFirst { it == 'p' || it == 'P' }
        if (p < 0) return null
        val float32 = isFloat32(text)
        if (float32 || text.endsWith("d") || text.endsWith("D")) text = text.dropLast(1)
        val significand = text.substring(0, p)
        val dot = significand.indexOf('.')
        val intDigits = if (dot < 0) significand else significand.substring(0, dot)
        val fracDigits = if (dot < 0) "" else significand.substring(dot + 1)
        val digits = intDigits + fracDigits
        if (digits.isEmpty() || !digits.all { Character.digit(it, 16) >= 0 }) return null
        val expText = text.substring(p + 1)
        val expDigits = expText.removePrefix("+").removePrefix("-")
        if (expDigits.isEmpty() || !expDigits.all { it in '0'..'9' }) return null
        val expMagnitude = BigInteger(expDigits).min(BigInteger.valueOf(EXPONENT_CLAMP)).toLong()
        val exp = if (expText.startsWith("-")) -expMagnitude else expMagnitude
        return Parts(BigInteger(digits, 16), exp - 4L * fracDigits.length, float32)
    }

    /**
     * 按 [mantBits] 位有效位（含隐含位）、最小正规指数 [minExp]、最大指数 [maxExp] 正确舍入
     * `m × 2^e`；溢出返回正无穷，下溢返回 0.0。返回值在目标格式中精确可表示。
     */
    private fun round(m: BigInteger, e: Long, mantBits: Int, minExp: Int, maxExp: Int): Double {
        if (m.signum() == 0) return 0.0
        val lead = e + m.bitLength() - 1
        if (lead > maxExp + 1L) return Double.POSITIVE_INFINITY
        val minUlpExp = (minExp - (mantBits - 1)).toLong()
        val ulpExp = maxOf(lead - (mantBits - 1), minUlpExp)
        val shift = ulpExp - e
        var q: BigInteger
        if (shift <= 0) {
            q = m.shiftLeft((-shift).toInt())
        } else {
            val s = minOf(shift, m.bitLength().toLong() + 2).toInt()
            q = m.shiftRight(s)
            val half = shift == s.toLong() && m.testBit(s - 1)
            val sticky = m.lowestSetBit < s - 1 || (shift != s.toLong())
            if (half && (sticky || q.testBit(0))) q = q.add(BigInteger.ONE)
        }
        if (q.signum() == 0) return 0.0
        if (ulpExp + q.bitLength() - 1 > maxExp) return Double.POSITIVE_INFINITY
        return Math.scalb(q.toDouble(), ulpExp.toInt())
    }

    /** 正确舍入后的值（float32 字面量返回其 float 值的精确 double）；不合法时 0.0，越界时无穷 / 0。 */
    fun hexValue(raw: String): Double {
        val parts = parse(raw) ?: return 0.0
        return if (parts.float32) {
            round(parts.mantissa, parts.exponent, 24, -126, 127)
        } else {
            round(parts.mantissa, parts.exponent, 53, -1022, 1023)
        }
    }

    /** 十六进制浮点字面量不合法或越界时返回诊断文本，否则 null。 */
    fun hexProblem(raw: String): String? {
        val parts = parse(raw)
            ?: return "Hexadecimal floating-point literal '$raw' needs a binary exponent (p or P), e.g. 0x1.8p0"
        val type = if (parts.float32) "float32" else "float64"
        val value = hexValue(raw)
        return when {
            value.isInfinite() -> "Floating-point literal '$raw' is out of $type range"
            value == 0.0 && parts.mantissa.signum() != 0 -> "Floating-point literal '$raw' is too small for $type and would round to zero"
            else -> null
        }
    }
}
