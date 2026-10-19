package jucs.schema

/*
 * ============================================================================
 * Names.kt —— JUCS 标识符命名规则（schema 层）
 * ============================================================================
 *
 * 职责：
 *   集中定义命令 ID、程序名、参数名、枚举值、能力名、语义版本的判定规则。
 *   所有校验路径（Schema 注册、argv 解析、结构化解调用）共用同一份实现，
 *   避免"帮助里合法、解析器里不合法"的分裂。
 *
 * 边界：
 *   只做纯字符串判定，不读环境、不做 IO、不抛异常（返回 Boolean 或错误描述）。
 *
 * English summary:
 *   Single source of truth for JUCS identifier syntax (kebab-case segments,
 *   command ids, enum values, capability names and semantic versions).
 */

/**
 * JUCS 命名规则集合。
 * 为什么用 object：这些是不可变的口径常量，不需要实例，也不需要被继承。
 */
object JucsNames {

    /** 单个 kebab-case 片段的最大字节数（ASCII 下字节数等于字符数）。 */
    const val MAX_SEGMENT_BYTES: Int = 32

    /** 命令 ID 最多由三段组成（program.resource.action）。 */
    const val COMMAND_ID_SEGMENTS: Int = 3

    private val KEBAB = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

    private val SEMVER = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$")

    private val CAPABILITY = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*(\\.[a-z][a-z0-9]*(-[a-z0-9]+)*)*$")

    /**
     * 判定一个片段是否为合法 kebab-case。
     * 规则：小写字母开头；只含小写字母/数字/连字符；连字符不得出现在首尾或连续出现；
     * 长度 1..32 字节。
     * English: true when the segment is a valid lowercase kebab-case word.
     */
    fun isKebab(segment: String): Boolean =
        segment.length in 1..MAX_SEGMENT_BYTES && KEBAB.matches(segment)

    /**
     * 判定完整命令 ID（`program.resource.action`）是否合法。
     * English: true when the id has exactly three valid kebab-case segments.
     */
    fun isCommandId(id: String): Boolean {
        val parts = id.split('.')
        return parts.size == COMMAND_ID_SEGMENTS && parts.all(::isKebab)
    }

    /**
     * 判定语义版本号（`major.minor.patch[-prerelease]`，不允许前导零）。
     * English: true when the text is a semantic version without leading zeros.
     */
    fun isSemanticVersion(text: String): Boolean = SEMVER.matches(text)

    /**
     * 判定能力名（`filesystem.read`、`system.info.read` 这类点分小写）。
     * English: true when the text is a dotted lowercase capability name.
     */
    fun isCapability(text: String): Boolean =
        text.isNotEmpty() && text.length <= 96 && CAPABILITY.matches(text)

    /**
     * 校验命令 ID 并返回人读原因（合法时返回 null）。
     * 为什么返回原因而不是布尔：Schema 注册失败需要把具体哪一段不合法告诉作者。
     * English: returns null when valid, otherwise a human-readable reason.
     */
    fun commandIdProblem(id: String): String? {
        val parts = id.split('.')
        if (parts.size != COMMAND_ID_SEGMENTS) {
            return "命令 ID 必须形如 <program>.<resource>.<action>，实际为 $COMMAND_ID_SEGMENTS 段以外的 \"$id\""
        }
        parts.forEachIndexed { index, part ->
            if (!isKebab(part)) {
                val role = when (index) {
                    0 -> "program"
                    1 -> "resource"
                    else -> "action"
                }
                return "命令 ID 的第 ${index + 1} 段（$role）不是合法 kebab-case：\"$part\""
            }
        }
        return null
    }
}
