package jucs.render

/*
 * ============================================================================
 * OutputFormat.kt —— 输出格式枚举（render 层）
 * ============================================================================
 *
 * 职责：
 *   表示 `--output` 的三个合法取值，并把"线上名字"解析成枚举。
 *
 * English summary:
 *   The three output formats and their single parsing rule.
 */

import jucs.error.JucsCode
import jucs.error.JucsError
import jucs.error.JucsException

/**
 * 输出格式。
 * English: output formats.
 */
enum class OutputFormat(val wireName: String) {
    /** 人读表格；不承诺可解析。 */
    TABLE("table"),

    /** stdout 一个 JSON 对象。 */
    JSON("json"),

    /** stdout 每行一个 JSON 对象。 */
    NDJSON("ndjson"),
    ;

    /** 是否为机器可解析格式。 */
    val machineReadable: Boolean get() = this != TABLE

    companion object {
        /**
         * 按线上名字解析；非法取值抛出 `JUCS.ARGUMENT.TYPE`。
         * English: parse the wire name, throwing ARGUMENT.TYPE when unknown.
         */
        fun fromWireName(name: String, requestId: String): OutputFormat =
            entries.firstOrNull { it.wireName == name } ?: throw JucsException(
                JucsError(
                    JucsCode.ARGUMENT_TYPE,
                    "输出格式必须是 table/json/ndjson 之一，实际为 \"$name\"",
                    requestId,
                    linkedMapOf("parameter" to "output", "value" to name),
                ),
            )
    }
}
