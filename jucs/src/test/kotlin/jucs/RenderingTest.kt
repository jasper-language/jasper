package jucs

/*
 * ============================================================================
 * RenderingTest.kt —— 序列化与表格渲染测试
 * ============================================================================
 *
 * 覆盖：JSON 转义与控制字符、拒绝不可序列化类型、CJK 表格对齐、ndjson 行数。
 *
 * English summary:
 *   JSON escaping, unsupported values, CJK table alignment and ndjson shape.
 */

import jucs.error.JucsCode
import jucs.error.JucsError
import jucs.render.Json
import jucs.render.OutputFormat
import jucs.render.Renderers
import jucs.runtime.CommandData
import jucs.runtime.CommandOutcome
import jucs.runtime.OutcomeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RenderingTest {

    @Test
    fun jsonEscapesControlCharactersAndQuotes() {
        val text = Json.toText(mapOf("k" to "引号\" 反斜杠\\ 换行\n 制表\t 控制\u0001"))
        val parsed = TestJson.parseObject(text)
        assertEquals("引号\" 反斜杠\\ 换行\n 制表\t 控制\u0001", parsed["k"])
    }

    @Test
    fun jsonRejectsUnsupportedValues() {
        assertFailsWith<IllegalArgumentException> { Json.toText(mapOf("k" to Any())) }
    }

    @Test
    fun tableAlignsCjkWithoutAnsi() {
        val outcome = CommandOutcome(
            data = CommandData.Record(listOf("中文字段" to "值", "ascii" to "value")),
        )
        val text = Renderers.success(outcome, OutputFormat.TABLE, "req")
        val lines = text.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(!text.contains("\u001B["))
        // 「中文字段」显示宽度 8，「ascii」5，对齐后值列应落在同一显示列上。
        // 注意必须比较显示宽度而不是字符下标：4 个汉字只占 4 个 char 但占 8 列。
        val firstValueColumn = jucs.render.DisplayWidth.of(lines[0].substringBefore("值"))
        val secondValueColumn = jucs.render.DisplayWidth.of(lines[1].substringBefore("value"))
        assertEquals(firstValueColumn, secondValueColumn)
    }

    @Test
    fun ndjsonEmitsOneLinePerRow() {
        val outcome = CommandOutcome(
            data = CommandData.Records(
                columns = listOf("a", "b"),
                rows = listOf(listOf("1", 2L), listOf(null, true)),
            ),
        )
        val text = Renderers.success(outcome, OutputFormat.NDJSON, "req")
        val lines = text.trim().lines()
        assertEquals(2, lines.size)
        assertEquals("1", TestJson.parseObject(lines[0])["a"])
        assertEquals(null, TestJson.parseObject(lines[1])["a"])
    }

    @Test
    fun emptyRecordSetRendersExplicitMarker() {
        val outcome = CommandOutcome(data = CommandData.Records(columns = listOf("a"), rows = emptyList()))
        assertEquals("(empty)\n", Renderers.success(outcome, OutputFormat.TABLE, "req"))
    }

    @Test
    fun errorRendersCodeAndRequestId() {
        val error = JucsError(JucsCode.PERMISSION_DENIED, "拒绝", "req-1")
        val text = Renderers.error(error, OutputFormat.TABLE)
        assertTrue(text.contains("JUCS.PERMISSION.DENIED"))
        assertTrue(text.contains("req-1"))
        val json = TestJson.parseObject(Renderers.error(error, OutputFormat.JSON))
        assertEquals("permission", json["category"])
    }

    @Test
    fun outcomeStatusMapsToExitCodes() {
        assertEquals(0, OutcomeStatus.SUCCESS.exitCode)
        assertEquals(11, OutcomeStatus.PARTIAL.exitCode)
        assertEquals(130, OutcomeStatus.CANCELLED.exitCode)
    }
}
