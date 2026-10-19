package jucs.runtime

/*
 * ============================================================================
 * Invoker.kt —— 唯一执行者（runtime 层）
 * ============================================================================
 *
 * 职责：
 *   把一次调用按固定顺序推进：查找命令 → 绑定参数 → 权限裁决 → 执行 → 归一化结果。
 *   人类命令行、脚本、将来的 AI/MCP 适配器都必须经过这里。
 *
 * 为什么"唯一执行者"是关键：
 *   生态不统一的根源就是每条命令各走一条执行路径。只要执行器只有一份，
 *   权限、错误码、输出校验就不可能因入口不同而产生分歧。
 *
 * 边界：
 *   不解析 argv（由 [ArgvParser] 负责），不渲染输出（由 render 层负责）。
 *
 * English summary:
 *   The single execution path: lookup, bind, authorize, execute, normalize.
 */

import jucs.error.JucsCode
import jucs.error.JucsError
import jucs.error.JucsException
import jucs.registry.CommandRegistry
import jucs.registry.Registration
import jucs.security.MinimalReadOnlyGate
import jucs.security.PermissionDecision
import jucs.security.PermissionGate

/**
 * 命令执行器。
 * English: the command invoker.
 */
class Invoker(
    private val registry: CommandRegistry,
    private val gate: PermissionGate = MinimalReadOnlyGate(),
) {

    /** 供调用方查询命令是否存在（例如 Shell 消歧）。 */
    fun registration(id: String): Registration? = registry.find(id)

    /**
     * 执行一次结构化调用。
     *
     * 参数以"原始值"形式传入：允许 String / Boolean / Int / Long / JucsDuration /
     * List，绑定器统一转换。已归一化的 [CommandCall] 也可直接传入 [invokeCall]。
     *
     * 失败模式：抛出带稳定错误码的 [JucsException]；处理器自身抛出的非 JUCS 异常
     * 会被转换成 `JUCS.INTERNAL`，不会泄漏栈信息到调用方。
     * English: execute a structured call.
     */
    fun invoke(id: String, arguments: Map<String, Any?>, ctx: RequestContext): CommandOutcome {
        val registration = registry.find(id) ?: throw JucsException(
            JucsError(
                JucsCode.COMMAND_UNKNOWN,
                "没有注册命令 $id",
                ctx.requestId,
                linkedMapOf("id" to id, "known" to registry.all().map { it.schema.id }),
            ),
        )
        val call = ArgumentBinder.bind(registration.schema, arguments, ctx.requestId)
        return invokeCall(registration, call, ctx)
    }

    /**
     * 执行一次已归一化的调用（argv 路径使用）。
     * 仍然经过绑定器，保证与结构化调用完全同形。
     * English: execute an already-normalized call; still re-bound for uniformity.
     */
    fun invokeCall(registration: Registration, call: CommandCall, ctx: RequestContext): CommandOutcome {
        // 已归一化的值本身就是列表；绑定器对列表按"可重复参数"处理，因此幂等。
        // Already-normalized values are lists already, so re-binding is idempotent.
        val rebound = ArgumentBinder.bind(registration.schema, call.arguments, ctx.requestId)
        when (val decision = gate.check(registration, rebound, ctx)) {
            is PermissionDecision.Denied -> throw JucsException(
                JucsError(
                    JucsCode.PERMISSION_DENIED,
                    decision.reasonZh,
                    ctx.requestId,
                    linkedMapOf("command" to registration.schema.id, "capability" to decision.capability),
                ),
            )
            PermissionDecision.Allowed -> Unit
        }
        val outcome = try {
            registration.handler.handle(rebound, ctx)
        } catch (e: JucsException) {
            throw e
        } catch (e: Throwable) {
            throw JucsException(
                JucsError(
                    JucsCode.INTERNAL,
                    "命令 ${registration.schema.id} 内部错误：${e.message ?: e.javaClass.simpleName}",
                    ctx.requestId,
                    linkedMapOf("command" to registration.schema.id, "type" to e.javaClass.name),
                ),
            )
        }
        outcome.warnings.forEach { ctx.diagnostic(DiagnosticLevel.WARN, "warning: $it") }
        return outcome
    }
}
