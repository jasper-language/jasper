package jucs.schema

/*
 * ============================================================================
 * CommandSchema.kt —— 命令契约（schema 层）
 * ============================================================================
 *
 * 职责：
 *   一条命令的完整契约：稳定身份、版本、中文/英文说明、参数表、输出类型、
 *   效果声明、权限需求、幂等与可重试语义。
 *
 * 边界：
 *   Schema 只声明"是什么"，不包含"怎么做"。处理器由 registry 层绑定。
 *
 * 单一事实来源：
 *   `--help`、`--schema`、argv 校验、结构化解调用、将来的补全与 MCP 工具描述
 *   都必须从同一个 CommandSchema 派生。
 *
 * English summary:
 *   The single source of truth for one JUCS command contract.
 */

/**
 * 效果声明：命令对外部世界的影响。
 * 为什么单独建模：权限判定需要"操作分类"，而操作分类不能由处理器自己说了算，
 * 必须在契约里声明，再由安全层复核（声明只是待验证的元数据）。
 * English: declared side effects; treated as metadata that the security layer verifies.
 */
data class Effects(
    /** 是否只读：不改变任何持久状态。 */
    val readOnly: Boolean,
    /** 是否触及宿主外部资源（网络、设备、远端服务）。 */
    val externalAccess: Boolean = false,
    /** 是否不可逆（删除、覆盖且无回滚）。 */
    val destructive: Boolean = false,
) {
    /**
     * 操作分类，供权限层做粗粒度裁决。
     * English: coarse operation class derived from the declared effects.
     */
    fun operationClass(): OperationClass = when {
        destructive -> OperationClass.IRREVERSIBLE_WRITE
        externalAccess -> OperationClass.EXTERNAL_EFFECT
        readOnly -> OperationClass.READ_ONLY
        else -> OperationClass.REVERSIBLE_WRITE
    }
}

/**
 * 操作分类。
 * English: operation classes used by the permission gate.
 */
enum class OperationClass {
    /** 只读。 */
    READ_ONLY,

    /** 可逆修改。 */
    REVERSIBLE_WRITE,

    /** 不可逆修改。 */
    IRREVERSIBLE_WRITE,

    /** 外部影响。 */
    EXTERNAL_EFFECT,
}

/**
 * 权限需求：能力名 + 作用域来源参数。
 * 例：`filesystem.read` 的作用域来自 `path` 参数。
 * English: one required capability plus the parameter that scopes it.
 */
data class Permission(
    /** 点分小写能力名，例如 `filesystem.read`。 */
    val capability: String,
    /** 作用域来源参数名；null 表示能力本身没有参数化作用域。 */
    val scopeFrom: String? = null,
)

/**
 * 命令契约。
 * English summary: one immutable command contract.
 */
data class CommandSchema(
    /** 稳定命令 ID：`program.resource.action`。 */
    val id: String,
    /** 发布者命名空间，例如 `org.jasper.toolchain`；用于同名消歧，不做信任判定。 */
    val publisher: String,
    /** 契约版本（语义版本）。与模块版本、语言版本互相独立。 */
    val version: String,
    /** 简体中文摘要。 */
    val summaryZh: String,
    /** 英文一行摘要。 */
    val summaryEn: String,
    /** 业务参数表；通用参数 `--output/--verbose/--quiet` 由运行时隐式提供，不在此声明。 */
    val params: List<ParamSpec>,
    /** 输出类型描述，例如 `Record<SystemInfo>`、`Stream<FileEntry>`。 */
    val outputType: String,
    /** 效果声明。 */
    val effects: Effects,
    /** 权限需求；只读命令也需要声明读能力，便于将来做作用域绑定。 */
    val permissions: List<Permission> = emptyList(),
    /** 同一请求重复执行是否产生相同结果（不含时间戳类字段时由命令自行保证）。 */
    val idempotent: Boolean = true,
    /** 失败后是否允许自动重试。 */
    val retryable: Boolean = false,
) {
    /** 按名字查参数；不存在返回 null。 */
    fun param(name: String): ParamSpec? = params.firstOrNull { it.name == name }
}
