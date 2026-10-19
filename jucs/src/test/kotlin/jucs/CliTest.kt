package jucs

/*
 * ============================================================================
 * CliTest.kt —— 命令行端到端测试
 * ============================================================================
 *
 * 覆盖验收场景里的关键几条：
 *   - 元命令不执行业务处理器（用"被调用即记录"的探针命令验证）；
 *   - JSON 模式 stdout 恰好一个对象，诊断只在 stderr；
 *   - 错误对象带稳定错误码，退出码与错误码一致；
 *   - 两个不同命令对 `--output` 的解析与信封完全一致。
 *
 * English summary:
 *   End-to-end CLI behaviour: meta commands, JSON purity, exit codes, parity.
 */

import jucs.registry.CommandRegistry
import jucs.registry.RegistrationResult
import jucs.runtime.CommandData
import jucs.runtime.CommandOutcome
import jucs.schema.CommandSchema
import jucs.schema.Effects
import jucs.schema.ParamKind
import jucs.schema.ParamSpec
import jucs.schema.Permission
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {

    @Test
    fun noArgumentsIsAUsageError() {
        val run = runCli()
        assertEquals(2, run.exitCode)
        assertEquals("", run.stdout)
        assertTrue(run.stderr.contains("JUCS.COMMAND.MISSING"))
    }

    @Test
    fun globalHelpListsRegisteredCommands() {
        val run = runCli("--help")
        assertEquals(0, run.exitCode)
        assertTrue(run.stdout.contains("fs.file.list"))
        assertTrue(run.stdout.contains("system.info.get"))
        assertTrue(run.stdout.contains("官方动词"))
        assertEquals("", run.stderr)
    }

    @Test
    fun globalVersionAndSchemaAreReadOnly() {
        val version = runCli("--version")
        assertEquals(0, version.exitCode)
        assertTrue(version.stdout.contains("JUCS 1.0"))
        val schema = runCli("--schema")
        assertEquals(0, schema.exitCode)
        val document = TestJson.parseObject(schema.stdout)
        assertEquals("1.0", document["jucsVersion"])
        @Suppress("UNCHECKED_CAST")
        val commands = document["commands"] as List<Map<String, Any?>>
        assertEquals(2, commands.size)
        assertTrue(commands.all { it["arguments"] is Map<*, *> })
    }

    @Test
    fun unknownGlobalOptionIsRejected() {
        val run = runCli("--nope")
        assertEquals(2, run.exitCode)
        assertTrue(run.stderr.contains("JUCS.ARGUMENT.UNKNOWN"))
    }

    @Test
    fun commandHelpNeverInvokesTheHandler() {
        var called = false
        val registry = CommandRegistry()
        val schema = CommandSchema(
            id = "demo.probe.get",
            publisher = "org.jasper.test",
            version = "1.0.0",
            summaryZh = "探针",
            summaryEn = "Probe.",
            params = listOf(ParamSpec("id", ParamKind.STRING, "标识", "Identifier.", required = true)),
            outputType = "Record<Probe>",
            effects = Effects(readOnly = true),
            permissions = listOf(Permission("demo.read")),
        )
        assertEquals(
            RegistrationResult.Ok,
            registry.register(schema) { _, _ ->
                called = true
                CommandOutcome(CommandData.Record(listOf("ok" to true)))
            },
        )
        val help = runCliWith(registry, "demo", "probe", "get", "--help")
        assertEquals(0, help.exitCode)
        assertTrue(help.stdout.contains("用法 / Usage"))
        assertTrue(!called, "--help 不得执行处理器")

        // --help 优先于业务参数错误，且同样不执行处理器。
        val helpWins = runCliWith(registry, "demo", "probe", "get", "--help", "--bogus")
        assertEquals(0, helpWins.exitCode)
        assertTrue(!called, "--help 优先时也不得执行处理器")

        val version = runCliWith(registry, "demo", "probe", "get", "--version")
        assertEquals(0, version.exitCode)
        assertTrue(version.stdout.contains("demo.probe.get 1.0.0"))

        val schemaOut = runCliWith(registry, "demo", "probe", "get", "--schema")
        assertEquals(0, schemaOut.exitCode)
        val document = TestJson.parseObject(schemaOut.stdout)
        assertEquals("demo.probe.get", document["id"])
        assertTrue(!called, "--schema 不得执行处理器")
    }

    @Test
    fun metaCommandsConflictWhenCombined() {
        val run = runCli("fs", "file", "list", "--help", "--schema")
        assertEquals(2, run.exitCode)
        assertTrue(run.stderr.contains("JUCS.ARGUMENT.CONFLICT"))
    }

    @Test
    fun unknownCommandIsReportedWithExitCodeThree() {
        val run = runCli("fs", "dir", "list", "--path", "/tmp")
        assertEquals(3, run.exitCode)
        assertTrue(run.stderr.contains("JUCS.COMMAND.UNKNOWN"))
        assertEquals("", run.stdout)
    }

    @Test
    fun malformedCommandSegmentIsRejected() {
        val run = runCli("FS", "file", "list", "--path", "/tmp")
        assertEquals(3, run.exitCode)
        assertTrue(run.stderr.contains("JUCS.ARGUMENT.FORMAT"))
    }

    @Test
    fun jsonOutputIsASingleCleanObject() = withTempDir { dir ->
        Files.writeString(dir.resolve("a.txt"), "abc")
        val run = runCli("fs", "file", "list", "--path", dir.toString(), "--output", "json")
        assertEquals(0, run.exitCode)
        val document = TestJson.parseObject(run.stdout)
        assertEquals("1.0", document["jucsVersion"])
        assertEquals("success", document["status"])
        assertTrue(document["requestId"] is String)
        @Suppress("UNCHECKED_CAST")
        val data = document["data"] as List<Map<String, Any?>>
        assertEquals(1, data.size)
        assertEquals("a.txt", data[0]["name"])
        assertEquals("file", data[0]["kind"])
        assertEquals(3L, data[0]["size"])
        assertEquals("", run.stderr)
    }

    @Test
    fun ndjsonOutputHasOneObjectPerLine() = withTempDir { dir ->
        Files.writeString(dir.resolve("a.txt"), "a")
        Files.writeString(dir.resolve("b.txt"), "bb")
        val run = runCli("fs", "file", "list", "--path", dir.toString(), "--output", "ndjson")
        assertEquals(0, run.exitCode)
        val lines = run.stdout.trim().lines()
        assertEquals(2, lines.size)
        lines.forEach { line ->
            val obj = TestJson.parseObject(line)
            assertTrue(obj["name"] is String)
        }
    }

    @Test
    fun errorObjectIsStructuredOnStderrOnly() = withTempDir { dir ->
        val run = runCli("fs", "file", "list", "--path", dir.toString(), "--bogus", "1", "--output", "json")
        assertEquals(2, run.exitCode)
        assertEquals("", run.stdout)
        val error = TestJson.parseObject(run.stderr.trim())
        assertEquals("JUCS.ARGUMENT.UNKNOWN", error["code"])
        assertEquals("argument", error["category"])
        assertEquals(false, error["retryable"])
        assertTrue(error["requestId"] is String)
    }

    @Test
    fun missingDirectoryMapsToExitCodeFour() {
        val run = runCli("fs", "file", "list", "--path", "/definitely/not/here/jucs", "--output", "json")
        assertEquals(4, run.exitCode)
        val error = TestJson.parseObject(run.stderr.trim())
        assertEquals("JUCS.RESOURCE.NOT_FOUND", error["code"])
    }

    @Test
    fun bothCommandsAcceptOutputTheSameWay() = withTempDir { dir ->
        val system = runCli("system", "info", "get", "--output", "json")
        val fs = runCli("fs", "file", "list", "--path", dir.toString(), "--output", "json")
        assertEquals(0, system.exitCode)
        assertEquals(0, fs.exitCode)
        val systemDoc = TestJson.parseObject(system.stdout)
        val fsDoc = TestJson.parseObject(fs.stdout)
        assertEquals(systemDoc.keys.filter { it != "data" }, fsDoc.keys.filter { it != "data" })
        assertEquals("success", systemDoc["status"])
        assertEquals("success", fsDoc["status"])
    }

    @Test
    fun tableOutputIsHumanReadable() = withTempDir { dir ->
        Files.writeString(dir.resolve("note.txt"), "hi")
        val run = runCli("fs", "file", "list", "--path", dir.toString())
        assertEquals(0, run.exitCode)
        assertTrue(run.stdout.contains("name"))
        assertTrue(run.stdout.contains("note.txt"))
        assertTrue(!run.stdout.contains("\u001B["), "stdout 不得包含 ANSI 转义")
    }
}
