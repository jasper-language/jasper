package jucs.render

/*
 * ============================================================================
 * DisplayWidth.kt —— 终端显示宽度（render 层）
 * ============================================================================
 *
 * 职责：
 *   估算一个字符串在等宽终端里占用的列数：ASCII 计 1 列，CJK 与全角字符计 2 列，
 *   组合附加符号计 0 列。
 *
 * 为什么需要：中文文档与中文文件名是常态，若按 `String.length` 对齐，表格会歪。
 * 这里采用 UAX#11 East Asian Width 的近似区间，不覆盖全部 Unicode 表格，
 * 但覆盖 CJK、假名、谚文、全角标点与常见符号。已知近似处：
 * 未覆盖的冷僻宽字符按 1 列处理。
 *
 * English summary:
 *   Terminal column width estimation (1 for ASCII, 2 for East Asian wide).
 */

/**
 * 显示宽度估算。
 * English: display width estimator.
 */
object DisplayWidth {

    /**
     * 返回字符串的显示列数。
     * English: display columns of the text.
     */
    fun of(text: String): Int {
        var width = 0
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            width += when {
                isZeroWidth(cp) -> 0
                isWide(cp) -> 2
                else -> 1
            }
            index += Character.charCount(cp)
        }
        return width
    }

    /** 组合附加符号与零宽连接符不占列。 */
    private fun isZeroWidth(cp: Int): Boolean =
        cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF ||
            (cp in 0x0300..0x036F) || (cp in 0xFE00..0xFE0F)

    /** East Asian Wide / Fullwidth 的主要区间。 */
    private fun isWide(cp: Int): Boolean = when (cp) {
        in 0x1100..0x115F, // 谚文字母
        in 0x2E80..0x303E, // CJK 部首、标点
        in 0x3041..0x33FF, // 假名、汉字兼容、CJK 符号
        in 0x3400..0x4DBF, // CJK 扩展 A
        in 0x4E00..0x9FFF, // CJK 基本区
        in 0xA000..0xA4CF, // 彝文
        in 0xAC00..0xD7A3, // 谚文音节
        in 0xF900..0xFAFF, // CJK 兼容汉字
        in 0xFE30..0xFE4F, // CJK 兼容形式
        in 0xFF00..0xFF60, // 全角 ASCII
        in 0xFFE0..0xFFE6, // 全角符号
        in 0x1F300..0x1F64F, // 表情
        in 0x1F900..0x1F9FF,
        in 0x20000..0x3FFFD, // CJK 扩展 B 及以后
        -> true
        else -> false
    }
}
