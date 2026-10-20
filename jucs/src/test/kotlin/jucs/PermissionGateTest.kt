package jucs

/*
 * ============================================================================
 * PermissionGateTest.kt —— 权限门对官方动词 remove 的裁决测试
 * ============================================================================
 *
 * 覆盖：
 *   - `remove` 作为能力末段时被视为写入类能力；
 *   - 即使契约自称只读，只要申请了 `remove` 能力，权限门仍必须拒绝，
 *     防止"只读声明 + 写能力"的自相矛盾契约绕过最小权限门。
 *
 * 为什么单独成文件：
 *   权限门是安全层独立的变化点；把它的行为从执行器测试中拆出来，
 *   将来替换为真实作用域判定时，可以只改这里的期望而不牵动执行路径测试。
 *
 * English summary:
 *   The minimal permission gate treats the official `remove` verb as a write
 *   capability, even when the contract claims to be read-only.
 */

import jucs.registry.Registration
import jucs.runtime.CommandCall
import jucs.runtime.CommandData
import jucs.runtime.CommandOutcome
import jucs.runtime.InvocationMode
import jucs.runtime.RequestContext
import jucs.schema.CommandSchema
import jucs.schema.Effects
import jucs.schema.Permission
import jucs.security.MinimalReadOnlyGate
import jucs.security.PermissionDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermissionGateTest {

    private fun ctx() = RequestContext("req-test", InvocationMode.MACHINE)

    private fun registration(schema: CommandSchema): Registration =
        Registration(schema) { _, _ -> CommandOutcome(CommandData.Record(emptyList())) }

    @Test
    fun removeCapabilityIsTreatedAsWriteEvenWhenSchemaClaimsReadOnly() {
        val schema = CommandSchema(
            id = "demo.thing.remove",
            publisher = "org.jasper.test",
            version = "1.0.0",
            summaryZh = "移除资源",
            summaryEn = "Remove a resource.",
            params = emptyList(),
            outputType = "Record<Demo>",
            effects = Effects(readOnly = true),
            permissions = listOf(Permission("demo.remove")),
        )
        val decision = MinimalReadOnlyGate().check(
            registration(schema),
            CommandCall(schema.id, emptyMap()),
            ctx(),
        )

        assertTrue(decision is PermissionDecision.Denied)
        assertEquals("demo.remove", (decision as PermissionDecision.Denied).capability)
    }
}
