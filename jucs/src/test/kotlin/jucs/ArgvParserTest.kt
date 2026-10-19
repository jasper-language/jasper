package jucs

/*
 * ============================================================================
 * ArgvParserTest.kt —— argv 解析与元命令扫描测试
 * ============================================================================
 *
 * 覆盖成功路径（默认值、值以 `--` 开头、通用参数）与全部结构失败路径
 * （未知、位置参数、分隔符、缺值、重复、互斥、缺必填、类型错误）。
 *
 * English summary:
 *   argv parsing: success and every structural failure path.
 */

import jucs.commands.FsCommands
import jucs.error.JucsCode
import jucs.error.JucsException
import jucs.render.OutputFormat
import jucs.runtime.ArgvParser
import jucs.schema.JucsDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArgvParserTest {

    private val schema = FsCommands.fileListSchema
    private val requestId = "req-test"

    private fun codeOf(tokens: List<String>): JucsCode =
        assertFailsWith<JucsException> { ArgvParser.parse(schema, tokens, requestId) }.error.code

    @Test
    fun appliesDefaultsAndSimpleValue() {
        val parsed = ArgvParser.parse(schema, listOf("--path", "/tmp"), requestId)
        assertEquals("/tmp", parsed.values.getValue("path").single())
        assertEquals(false, parsed.values.getValue("all").single())
        assertEquals("name", parsed.values.getValue("sort").single())
        assertEquals("asc", parsed.values.getValue("order").single())
        assertEquals(0L, parsed.values.getValue("limit").single())
        assertEquals(OutputFormat.TABLE, parsed.output)
    }

    @Test
    fun valueMayStartWithDashes() {
        val parsed = ArgvParser.parse(schema, listOf("--path", "--not-an-option"), requestId)
        assertEquals("--not-an-option", parsed.values.getValue("path").single())
    }

    @Test
    fun booleanFalseFormIsSupported() {
        val parsed = ArgvParser.parse(schema, listOf("--path", "/tmp", "--no-all"), requestId)
        assertEquals(false, parsed.values.getValue("all").single())
        val enabled = ArgvParser.parse(schema, listOf("--path", "/tmp", "--all"), requestId)
        assertEquals(true, enabled.values.getValue("all").single())
    }

    @Test
    fun unknownParameterIsRejected() {
        assertEquals(JucsCode.ARGUMENT_UNKNOWN, codeOf(listOf("--path", "/tmp", "--nope", "x")))
        assertEquals(JucsCode.ARGUMENT_UNKNOWN, codeOf(listOf("--no-path")))
    }

    @Test
    fun positionalArgumentIsRejected() {
        assertEquals(JucsCode.ARGUMENT_POSITIONAL, codeOf(listOf("/tmp")))
    }

    @Test
    fun separatorIsRejectedForNativeCommands() {
        assertEquals(JucsCode.ARGUMENT_SEPARATOR, codeOf(listOf("--path", "/tmp", "--")))
    }

    @Test
    fun missingValueIsRejected() {
        assertEquals(JucsCode.ARGUMENT_MISSING_VALUE, codeOf(listOf("--path")))
    }

    @Test
    fun duplicateSingleValueIsRejected() {
        assertEquals(JucsCode.ARGUMENT_DUPLICATE, codeOf(listOf("--path", "/a", "--path", "/b")))
    }

    @Test
    fun contradictoryBooleansAreRejected() {
        assertEquals(JucsCode.ARGUMENT_CONFLICT, codeOf(listOf("--path", "/tmp", "--all", "--no-all")))
    }

    @Test
    fun missingRequiredParameterIsRejected() {
        assertEquals(JucsCode.ARGUMENT_MISSING, codeOf(emptyList()))
    }

    @Test
    fun typeErrorsAreRejected() {
        assertEquals(JucsCode.ARGUMENT_TYPE, codeOf(listOf("--path", "/tmp", "--limit", "abc")))
        assertEquals(JucsCode.ARGUMENT_TYPE, codeOf(listOf("--path", "/tmp", "--output", "yaml")))
        assertEquals(JucsCode.ARGUMENT_TYPE, codeOf(listOf("--path", "")))
    }

    @Test
    fun outputFormatIsParsed() {
        val parsed = ArgvParser.parse(schema, listOf("--path", "/tmp", "--output", "json"), requestId)
        assertEquals(OutputFormat.JSON, parsed.output)
        val ndjson = ArgvParser.parse(schema, listOf("--path", "/tmp", "--output", "ndjson"), requestId)
        assertEquals(OutputFormat.NDJSON, ndjson.output)
    }

    @Test
    fun diagnosticsFlagsAreParsed() {
        val parsed = ArgvParser.parse(schema, listOf("--path", "/tmp", "--verbose", "--quiet"), requestId)
        assertEquals(true, parsed.verbose)
        assertEquals(true, parsed.quiet)
    }

    @Test
    fun metaScanRespectsValueConsumption() {
        assertEquals(listOf("help"), ArgvParser.scanMeta(schema, listOf("--help")))
        assertEquals(emptyList(), ArgvParser.scanMeta(schema, listOf("--path", "--help")))
        assertEquals(listOf("help", "version"), ArgvParser.scanMeta(schema, listOf("--help", "--version")))
        assertEquals(listOf("help"), ArgvParser.scanMeta(schema, listOf("--no-all", "--help")))
    }

    @Test
    fun durationParsingIsStrict() {
        assertEquals(30_000L, JucsDuration.parse("30s")!!.millis)
        assertEquals(500L, JucsDuration.parse("500ms")!!.millis)
        assertEquals(7_200_000L, JucsDuration.parse("2h")!!.millis)
        assertEquals(null, JucsDuration.parse("30"))
        assertEquals(null, JucsDuration.parse("30 s"))
        assertEquals(null, JucsDuration.parse("-1s"))
        assertEquals(null, JucsDuration.parse("1.5s"))
        assertEquals(null, JucsDuration.parse("99999999999999999999h"))
    }
}
