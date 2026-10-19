package jucs.registry

/*
 * ============================================================================
 * CommandRegistry.kt —— 命令注册与发现（registry 层）
 * ============================================================================
 *
 * 职责：
 *   保存"契约 + 处理器"的绑定，提供按 ID 与按三段身份查找，检测重复注册，
 *   并向发现类调用方（帮助、将来的补全与 MCP 工具导出）提供稳定顺序的命令表。
 *
 * 为什么注册期就做契约校验：
 *   契约错误一旦进入运行时，会表现为"某个参数永远过不了校验"这类难查问题。
 *   注册期拒绝能把问题定位到作者的一行声明上。
 *
 * 边界：
 *   不执行命令，不做权限判定，不渲染。
 *
 * English summary:
 *   Command registration, duplicate detection and discovery with stable ordering.
 */

import jucs.runtime.CommandCall
import jucs.runtime.CommandOutcome
import jucs.runtime.RequestContext
import jucs.schema.CommandSchema
import jucs.schema.SchemaValidation

/**
 * 命令处理器：接收已归一化的调用，返回业务结果。
 * 契约：不得向 stdout 写任何内容；诊断走 [RequestContext.diagnostics]。
 * English: a command handler operating on a normalized call.
 */
fun interface CommandHandler {
    /** 执行命令；失败时抛出 [jucs.error.JucsException]。 */
    fun handle(call: CommandCall, ctx: RequestContext): CommandOutcome
}

/**
 * 一条已注册的命令。
 * English: one registered command.
 */
data class Registration(val schema: CommandSchema, val handler: CommandHandler)

/**
 * 注册结果。
 * English: registration result.
 */
sealed interface RegistrationResult {
    /** 注册成功。 */
    data object Ok : RegistrationResult

    /** 注册被拒绝，problems 是可直接展示的原因列表。 */
    data class Rejected(val problems: List<String>) : RegistrationResult
}

/**
 * 命令注册表。
 * English: the command registry.
 */
class CommandRegistry {

    private val byId = LinkedHashMap<String, Registration>()

    /**
     * 注册一条命令。
     *
     * 拒绝条件：契约不合法、ID 与已注册命令重复。
     * 失败时不做任何修改（原子性），已注册内容保持不变。
     * English: register a command atomically; rejects invalid or duplicate ids.
     */
    fun register(schema: CommandSchema, handler: CommandHandler): RegistrationResult {
        val problems = SchemaValidation.validate(schema).toMutableList()
        if (byId.containsKey(schema.id)) {
            problems += "命令 ID 重复注册：${schema.id}"
        }
        if (problems.isNotEmpty()) return RegistrationResult.Rejected(problems)
        byId[schema.id] = Registration(schema, handler)
        return RegistrationResult.Ok
    }

    /** 按完整 ID 查找；不存在返回 null。 */
    fun find(id: String): Registration? = byId[id]

    /** 按三段身份查找。 */
    fun lookup(program: String, resource: String, action: String): Registration? =
        byId["$program.$resource.$action"]

    /** 全部已注册命令，按 ID 字典序排列，保证帮助与导出的稳定顺序。 */
    fun all(): List<Registration> = byId.values.sortedBy { it.schema.id }

    /** 已注册的 program 段集合（Shell 消歧时需要）。 */
    fun programs(): Set<String> = byId.keys.map { it.substringBefore('.') }.toSortedSet()
}
