package jucs.render

/*
 * ============================================================================
 * Renderers.kt —— 结果与错误的呈现（render 层）
 * ============================================================================
 *
 * 职责：
 *   把 [CommandOutcome] 与 [JucsError] 渲染成 table / json / ndjson 三种文本。
 *
 * 硬性规则：
 *   1. stdout 只放数据；诊断与错误文字一律写 stderr。
 *   2. json 模式下 stdout 恰好一个对象：`{"jucsVersion","requestId","status","data"}`。
 *   3. ndjson 模式下 stdout 每个数据项一行，没有信封；状态由退出码承载。
 *   4. table 模式只服务于人，允许对齐造成的空格差异，但不允许出现 ANSI 颜色码。
 *
 * English summary:
 *   Rendering of results and errors into table / json / ndjson.
 */

import jucs.error.JucsError
import jucs.runtime.CommandData
import jucs.runtime.CommandOutcome
import jucs.schema.OfficialVocabulary
import jucs.schema.JucsDuration

/**
 * 渲染器集合。
 * English: renderer entry points.
 */
object Renderers {

    /**
     * 渲染成功结果。
     * English: render a successful outcome.
     */
    fun success(outcome: CommandOutcome, format: OutputFormat, requestId: String): String {
        val data = outcome.data
            ?: throw IllegalArgumentException("成功状态必须携带数据")
        return when (format) {
            OutputFormat.TABLE -> renderTable(data, outcome)
            OutputFormat.JSON -> Json.toText(
                linkedMapOf(
                    "jucsVersion" to OfficialVocabulary.VERSION,
                    "requestId" to requestId,
                    "status" to outcome.status.text,
                    "data" to toJsonValue(data),
                ),
            )
            OutputFormat.NDJSON -> renderNdjson(data)
        }
    }

    /**
     * 渲染错误，用于 stderr。
     * `json` / `ndjson` 输出结构化错误对象；`table` 输出单行人读文本。
     * English: render an error for stderr.
     */
    fun error(error: JucsError, format: OutputFormat): String = when (format) {
        OutputFormat.TABLE ->
            "error: ${error.code.text}: ${error.message} (requestId=${error.requestId})"
        OutputFormat.JSON, OutputFormat.NDJSON -> Json.toText(error.toMap())
    }

    /** 把结果数据转成 JSON 可序列化结构。 */
    private fun toJsonValue(data: CommandData): Any? = when (data) {
        is CommandData.Record -> linkedMapOf<String, Any?>().apply {
            data.fields.forEach { (name, value) -> put(name, value) }
        }
        is CommandData.Records -> data.rows.map { row ->
            linkedMapOf<String, Any?>().apply {
                data.columns.forEachIndexed { index, column -> put(column, row.getOrNull(index)) }
            }
        }
    }

    /** ndjson：每个数据项一行。 */
    private fun renderNdjson(data: CommandData): String = when (data) {
        is CommandData.Record -> Json.toText(toJsonValue(data)) + "\n"
        is CommandData.Records -> {
            val sink = StringBuilder()
            data.rows.forEach { row ->
                sink.append(
                    Json.toText(
                        linkedMapOf<String, Any?>().apply {
                            data.columns.forEachIndexed { index, column -> put(column, row.getOrNull(index)) }
                        },
                    ),
                )
                sink.append('\n')
            }
            sink.toString()
        }
    }

    /**
     * table：单条记录按「字段: 值」逐行输出。
     * 注意：警告不在这里输出。它们由执行器统一写入 stderr 诊断通道，
     * 否则同一个警告会在 stdout 与 stderr 各出现一次。
     * English: table output only; warnings are emitted by the invoker on stderr.
     */
    private fun renderTable(data: CommandData, outcome: CommandOutcome): String = when (data) {
        is CommandData.Record -> {
            val sink = StringBuilder()
            val width = data.fields.maxOfOrNull { DisplayWidth.of(it.first) } ?: 0
            data.fields.forEach { (name, value) ->
                sink.append(name)
                sink.append(" ".repeat(width - DisplayWidth.of(name)))
                sink.append("  ")
                sink.append(cellText(value))
                sink.append('\n')
            }
            sink.toString()
        }
        is CommandData.Records -> {
            if (data.rows.isEmpty()) {
                "(empty)\n"
            } else {
                val widths = data.columns.mapIndexed { index, column ->
                    maxOf(
                        DisplayWidth.of(column),
                        data.rows.maxOf { DisplayWidth.of(cellText(it.getOrNull(index))) },
                    )
                }
                val sink = StringBuilder()
                sink.append(joinRow(data.columns, widths)).append('\n')
                sink.append(widths.joinToString("  ") { "-".repeat(it) }).append('\n')
                data.rows.forEach { row ->
                    sink.append(joinRow(row.map { cellText(it) }, widths)).append('\n')
                }
                sink.toString()
            }
        }
    }

    /** 把一行的单元格按列宽补空格后拼接。 */
    private fun joinRow(cells: List<String>, widths: List<Int>): String =
        cells.mapIndexed { index, cell ->
            val width = widths.getOrElse(index) { DisplayWidth.of(cell) }
            cell + " ".repeat((width - DisplayWidth.of(cell)).coerceAtLeast(0))
        }.joinToString("  ").trimEnd()

    /** 单元格文本：null 显示为空串，时长按其规范文本，其余 toString。 */
    private fun cellText(value: Any?): String = when (value) {
        null -> ""
        is JucsDuration -> value.toString()
        else -> value.toString()
    }
}
