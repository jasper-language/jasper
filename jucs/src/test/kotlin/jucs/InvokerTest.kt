package jucs

/*
 * ============================================================================
 * InvokerTest.kt —— 唯一执行者测试
 * ============================================================================
 *
 * 覆盖：未知命令、绑定失败、处理器内部异常、权限门拒绝、结构化调用与
 * argv 结果一致。
 *
 * English summary:
 *   The single execution path: failures, permission denial and argv/invoke parity.
 */

import jucs.commands.SystemCommands
import jucs.error.JucsCode
import jucs.error.JucsException
import jucs.registry.CommandHandler
import jucs.registry.CommandRegistry
import jucs.registry.RegistrationResult
import jucs.runtime.CommandData
import jucs.runtime.CommandOutcome
import jucs.runtime.InvocationMode
import jucs.runtime.Invoker
import jucs.runtime.RequestContext
import jucs.schema.CommandSchema
import jucs.schema.Effects
import jucs.schema.ParamKind
import jucs.schema.ParamSpec
import jucs.schema.Permission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InvokerTest {

    private fun ctx() = RequestContext("req-test", InvocationMode.MACHINE)

    private fun registryWithoutDefaults(): CommandRegistry {
        val registry = CommandRegistry()
        val read = CommandSchema(
            id = "demo.read.get",
            publisher = "org.jasper.test",
            version = "1.0.0",
            summaryZh = "读取",
            summaryEn = "Read.",
            params = listOf(ParamSpec("name", ParamKind.STRING, "名称", "Name.", required = true)),
            outputType = "Record<Demo>",
            effects = Effects(readOnly = true),
            permissions = listOf(Permission("demo.read")),
        )
        assertEquals(
            RegistrationResult.Ok,
            registry.register(read) { call, _ ->
                CommandOutcome(CommandData.Record(listOf("echo" to call.string("name"))))
            },
        )
        return registry
    }

    @Test
    fun unknownCommandIsRejected() {
        val invoker = Invoker(registryWithoutDefaults())
        val error = assertFailsWith<JucsException> { invoker.invoke("demo.missing.get", emptyMap(), ctx()) }
        assertEquals(JucsCode.COMMAND_UNKNOWN, error.error.code)
    }

    @Test
    fun missingArgumentIsRejectedBeforeHandler() {
        val invoker = Invoker(registryWithoutDefaults())
        val error = assertFailsWith<JucsException> { invoker.invoke("demo.read.get", emptyMap(), ctx()) }
        assertEquals(JucsCode.ARGUMENT_MISSING, error.error.code)
    }

    @Test
    fun unknownArgumentIsRejected() {
        val invoker = Invoker(registryWithoutDefaults())
        val error = assertFailsWith<JucsException> {
            invoker.invoke("demo.read.get", mapOf("name" to "x", "nope" to 1), ctx())
        }
        assertEquals(JucsCode.ARGUMENT_UNKNOWN, error.error.code)
    }

    @Test
    fun nullArgumentIsRejected() {
        val invoker = Invoker(registryWithoutDefaults())
        val error = assertFailsWith<JucsException> {
            invoker.invoke("demo.read.get", mapOf("name" to null), ctx())
        }
        assertEquals(JucsCode.ARGUMENT_TYPE, error.error.code)
    }

    @Test
    fun listForScalarParameterIsRejected() {
        val invoker = Invoker(registryWithoutDefaults())
        val error = assertFailsWith<JucsException> {
            invoker.invoke("demo.read.get", mapOf("name" to listOf("a", "b")), ctx())
        }
        assertEquals(JucsCode.ARGUMENT_DUPLICATE, error.error.code)
    }

    @Test
    fun structuredCallMatchesHandlerExpectations() {
        val invoker = Invoker(registryWithoutDefaults())
        val outcome = invoker.invoke("demo.read.get", mapOf("name" to "报告"), ctx())
        val record = outcome.data as CommandData.Record
        assertEquals("报告", record.fields.single().second)
    }

    @Test
    fun handlerExceptionBecomesInternalError() {
        val registry = CommandRegistry()
        val schema = CommandSchema(
            id = "demo.boom.run",
            publisher = "org.jasper.test",
            version = "1.0.0",
            summaryZh = "爆炸",
            summaryEn = "Boom.",
            params = emptyList(),
            outputType = "Record<Demo>",
            effects = Effects(readOnly = true),
            permissions = listOf(Permission("demo.read")),
        )
        registry.register(schema) { _, _ -> error("处理器故意失败") }
        val invoker = Invoker(registry)
        val error = assertFailsWith<JucsException> { invoker.invoke("demo.boom.run", emptyMap(), ctx()) }
        assertEquals(JucsCode.INTERNAL, error.error.code)
        assertTrue(error.error.message.contains("处理器故意失败"))
    }

    @Test
    fun writeCommandIsDeniedByTheMinimalGate() {
        val registry = CommandRegistry()
        var called = false
        val schema = CommandSchema(
            id = "demo.thing.remove",
            publisher = "org.jasper.test",
            version = "1.0.0",
            summaryZh = "移除",
            summaryEn = "Remove.",
            params = listOf(ParamSpec("id", ParamKind.STRING, "标识", "Identifier.", required = true)),
            outputType = "Record<Demo>",
            effects = Effects(readOnly = false, destructive = true),
            permissions = listOf(Permission("demo.write")),
        )
        registry.register(schema) { _, _ ->
            called = true
            CommandOutcome(CommandData.Record(emptyList()))
        }
        val invoker = Invoker(registry)
        val error = assertFailsWith<JucsException> { invoker.invoke("demo.thing.remove", mapOf("id" to "x"), ctx()) }
        assertEquals(JucsCode.PERMISSION_DENIED, error.error.code)
        assertTrue(!called, "权限拒绝时处理器不得被调用")
    }

    @Test
    fun contradictoryReadOnlyWithWriteCapabilityIsDenied() {
        val registry = CommandRegistry()
        val schema = CommandSchema(
            id = "demo.thing.list",
            publisher = "org.jasper.test",
            version = "1.0.0",
            summaryZh = "伪装只读",
            summaryEn = "Pretend read-only.",
            params = emptyList(),
            outputType = "Record<Demo>",
            effects = Effects(readOnly = true),
            permissions = listOf(Permission("demo.write")),
        )
        registry.register(schema) { _, _ -> CommandOutcome(CommandData.Record(emptyList())) }
        val invoker = Invoker(registry)
        val error = assertFailsWith<JucsException> { invoker.invoke("demo.thing.list", emptyMap(), ctx()) }
        assertEquals(JucsCode.PERMISSION_DENIED, error.error.code)
    }

    @Test
    fun systemCommandIsReadableThroughInvoke() {
        val invoker = Invoker(jucs.commands.Builtins.registry())
        val outcome = invoker.invoke("system.info.get", emptyMap(), ctx())
        val record = outcome.data as CommandData.Record
        val names = record.fields.map { it.first }
        assertTrue("osName" in names)
        assertTrue("cpuCount" in names)
        assertEquals(SystemCommands.infoGetSchema.id, "system.info.get")
    }
}
