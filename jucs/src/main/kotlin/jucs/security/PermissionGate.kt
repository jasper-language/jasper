package jucs.security

/*
 * ============================================================================
 * PermissionGate.kt —— 权限门（security 层）
 * ============================================================================
 *
 * 职责：
 *   在执行命令之前，依据"契约声明的效果与能力"做一次真实裁决。
 *
 * 本阶段的真实边界（必须如实说明）：
 *   这是 P1 的最小权限门：只放行只读命令，任何声明了写入/不可逆/外部影响的
 *   命令一律拒绝。它**不是**完整的作用域判定（路径根、对象归属、TOCTOU 复核
 *   都还没有实现），因此不能宣传为"安全隔离已完成"。
 *
 * 为什么仍然要有一个门而不是留空：
 *   留空等于"所有命令默认放行"，将来接入真实策略时无法验证调用链是否真的经过
 *   判定点。门必须从现在起就在调用路径上，且默认拒绝。
 *
 * English summary:
 *   A real but minimal permission gate: read-only commands pass, everything else
 *   is denied. Scoped authorization is not implemented yet and is not claimed.
 */

import jucs.runtime.CommandCall
import jucs.runtime.RequestContext
import jucs.schema.OperationClass
import jucs.registry.Registration

/**
 * 裁决结果。
 * English: gate decision.
 */
sealed interface PermissionDecision {
    /** 放行。 */
    data object Allowed : PermissionDecision

    /** 拒绝，附带被拒绝的能力与中文原因。 */
    data class Denied(val capability: String, val reasonZh: String) : PermissionDecision
}

/**
 * 权限门接口。
 * English: permission gate interface.
 */
interface PermissionGate {
    /** 对一次具体调用做裁决。 */
    fun check(registration: Registration, call: CommandCall, ctx: RequestContext): PermissionDecision
}

/**
 * P1 最小权限门：只允许只读命令。
 *
 * 判定顺序：
 * 1. 契约声明的操作分类不是 [OperationClass.READ_ONLY] → 拒绝；
 * 2. 声明的任一能力名的末段属于写入类（write/manage/remove/delete/execute/admin 等）→ 拒绝
 *    （防止"自称只读却申请写能力"的自相矛盾契约混过去）；
 * 3. 其余放行。
 *
 * 已知限制：不做路径作用域校验，不做调用主体身份校验，不处理 TOCTOU。
 * English: allow only read-only commands; no scoping yet.
 */
class MinimalReadOnlyGate : PermissionGate {

    private val writeVerbs = setOf("write", "manage", "remove", "execute", "admin", "create", "update",
        // delete 虽已不是官方命令动词，但能力名仍可能以它结尾；拒绝集合只增不减，宁严勿松。
        // `delete` stays in the deny set: retiring a verb must never loosen the gate.
        "delete")

    override fun check(
        registration: Registration,
        call: CommandCall,
        ctx: RequestContext,
    ): PermissionDecision {
        val schema = registration.schema
        val operation = schema.effects.operationClass()
        if (operation != OperationClass.READ_ONLY) {
            return PermissionDecision.Denied(
                capability = schema.permissions.firstOrNull()?.capability ?: "unknown",
                reasonZh = "当前权限策略只允许只读命令，${schema.id} 声明的操作分类为 $operation",
            )
        }
        val writeCapability = schema.permissions.firstOrNull { permission ->
            permission.capability.substringAfterLast('.') in writeVerbs
        }
        if (writeCapability != null) {
            return PermissionDecision.Denied(
                capability = writeCapability.capability,
                reasonZh = "契约声明为只读，却申请了写入类能力 ${writeCapability.capability}",
            )
        }
        ctx.diagnostic(
            jucs.runtime.DiagnosticLevel.DEBUG,
            "permission: allowed ${schema.id} (class=$operation, requestId=${ctx.requestId})",
        )
        return PermissionDecision.Allowed
    }
}
