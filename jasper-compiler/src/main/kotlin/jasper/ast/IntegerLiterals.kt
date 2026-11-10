package jasper.ast

import java.math.BigInteger

/**
 * 整数字面量的精确解析与取值范围（Q151，LANG-INT-LITERAL-RANGE-01）。
 *
 * 做什么：把源码文本（含下划线、进制前缀、`L` 后缀）解析成精确的非负大整数，并给出
 *   该字面量允许的最大绝对值；AST 构造用 [wrappedValue] 取 64 位补码值，检查器用 [overflow] 诊断。
 * 范围规则（与 Java 一致）：
 * - 无后缀十进制：≤ 2^31-1；紧跟在一元负号后时允许 2^31（即 int32 最小值）。
 * - 无后缀十六 / 八 / 二进制：≤ 2^32-1，按 32 位补码解释（`0xFFFFFFFF` 是 -1）。
 * - 带 `L` 的同理换成 2^63-1 / 2^63 / 2^64-1。
 * 为什么不在 AST 里报错：AST 构造不产诊断；范围是类型规则，归检查器。
 */
object IntegerLiterals {
    private val TWO = BigInteger.valueOf(2)

    /** 文本是否带 int64 后缀。 */
    fun isLong(raw: String): Boolean = raw.endsWith("L") || raw.endsWith("l")

    /** 精确数值（非负）；文本不是合法整数时返回 null。 */
    fun magnitude(raw: String): BigInteger? {
        var text = raw.replace("_", "")
        if (isLong(text)) text = text.dropLast(1)
        val radix = radix(text)
        if (radix != 10) text = text.substring(2)
        if (text.isEmpty()) return null
        return try { BigInteger(text, radix) } catch (_: NumberFormatException) { null }
    }

    /** 64 位补码值（超出 64 位时取低 64 位；越界由检查器诊断，不会进入翻译）。 */
    fun wrappedValue(raw: String): Long = magnitude(raw)?.toLong() ?: 0L

    /**
     * 越界时返回诊断文本，否则 null。[negated] 表示字面量是一元负号的直接操作数。
     */
    fun overflow(raw: String, negated: Boolean): String? {
        val value = magnitude(raw) ?: return "Malformed integer literal '$raw'"
        val long = isLong(raw)
        val decimal = radix(raw.replace("_", "")) == 10
        val bits = if (long) 64 else 32
        val limit = when {
            !decimal -> TWO.pow(bits).subtract(BigInteger.ONE)
            negated -> TWO.pow(bits - 1)
            else -> TWO.pow(bits - 1).subtract(BigInteger.ONE)
        }
        if (value <= limit) return null
        return if (long) {
            "Integer literal '$raw' is out of int64 range"
        } else {
            "Integer literal '$raw' is out of int32 range; add the L suffix for an int64 literal"
        }
    }

    private fun radix(text: String): Int = when {
        text.startsWith("0x") || text.startsWith("0X") -> 16
        text.startsWith("0o") || text.startsWith("0O") -> 8
        text.startsWith("0b") || text.startsWith("0B") -> 2
        else -> 10
    }
}
