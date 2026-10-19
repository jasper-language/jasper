package jucs.schema

/*
 * ============================================================================
 * JucsDuration.kt —— 带单位的时长值（schema 层）
 * ============================================================================
 *
 * 职责：
 *   表示 `--timeout 30s` 这类带单位时长，并提供唯一的解析/格式化口径。
 *
 * 为什么不用裸整数：无单位时长必须被拒绝，否则"30"到底是毫秒还是秒会成为
 * 第二个隐式约定。JUCS 规定单位必填，因此需要一个把"数值 + 单位"一次解析
 * 完毕的值类型。
 *
 * English summary:
 *   A duration value with an explicit unit; parsing and formatting have exactly
 *   one implementation so no caller can invent its own unit convention.
 */

/**
 * 时长值，内部统一以毫秒表示。
 * English: duration in milliseconds with strict parsing.
 */
data class JucsDuration(val millis: Long) {

    /** 规范化文本形式：始终以毫秒为单位、带单位后缀。 */
    override fun toString(): String = "${millis}ms"

    companion object {
        private val PATTERN = Regex("^([0-9]+)(ms|s|m|h)$")

        private val UNIT_MILLIS: Map<String, Long> = mapOf(
            "ms" to 1L,
            "s" to 1_000L,
            "m" to 60_000L,
            "h" to 3_600_000L,
        )

        /**
         * 解析带单位时长；非法时返回 null。
         *
         * 接受：非负十进制整数 + `ms|s|m|h`。拒绝：缺单位、负号、小数、空白、
         * 未知单位、溢出 [Long] 的写法。
         *
         * English: parse `<non-negative integer><ms|s|m|h>`; null when invalid.
         */
        fun parse(text: String): JucsDuration? {
            val match = PATTERN.matchEntire(text) ?: return null
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            val unit = UNIT_MILLIS[match.groupValues[2]] ?: return null
            val millis = try {
                Math.multiplyExact(amount, unit)
            } catch (_: ArithmeticException) {
                return null
            }
            return JucsDuration(millis)
        }
    }
}
