package jucs.error

/*
 * ============================================================================
 * JucsError.kt —— 结构化错误与退出码（error 层）
 * ============================================================================
 *
 * 职责：
 *   定义稳定的错误码集合、错误对象与"错误码 → 退出码"的唯一映射。
 *
 * 为什么错误码是枚举而不是散落字符串：
 *   错误码会进入第三方脚本、AI 调用与审计记录，是长期契约。散落字符串无法在
 *   编译期发现拼写错误，也无法保证同一语义只有一种写法。
 *
 * 边界：
 *   不负责渲染（渲染层决定输出 JSON 还是人读文本），也不负责判定权限。
 *
 * English summary:
 *   Stable error codes, the structured error object and the exit-code mapping.
 */

/**
 * 错误大类，与粗粒度退出码对应。
 * English: coarse error categories.
 */
enum class JucsErrorCategory(val text: String) {
    /** 调用参数结构错误。 */
    ARGUMENT("argument"),

    /** 命令身份或命令本身的问题。 */
    COMMAND("command"),

    /** 权限判定拒绝。 */
    PERMISSION("permission"),

    /** 资源不存在、不可读或冲突。 */
    RESOURCE("resource"),

    /** 超时。 */
    TIMEOUT("timeout"),

    /** 服务不可用。 */
    SERVICE("service"),

    /** 部分成功。 */
    PARTIAL("partial"),

    /** 调用方取消。 */
    CANCELLED("cancelled"),

    /** 内部错误。 */
    INTERNAL("internal"),
}

/**
 * 稳定错误码。
 * 每个常量同时携带大类、退出码与默认可重试性，避免"错误码表与退出码映射"两处漂移。
 * English: stable error codes with their category, exit code and retryability.
 */
enum class JucsCode(
    val text: String,
    val category: JucsErrorCategory,
    val exitCode: Int,
    val retryable: Boolean = false,
) {
    /** 没有给出任何命令（连 program 段都没有）。 */
    COMMAND_MISSING("JUCS.COMMAND.MISSING", JucsErrorCategory.COMMAND, 2),

    /** 参数名不在契约内。 */
    ARGUMENT_UNKNOWN("JUCS.ARGUMENT.UNKNOWN", JucsErrorCategory.ARGUMENT, 2),

    /** 原生命令出现位置参数。 */
    ARGUMENT_POSITIONAL("JUCS.ARGUMENT.POSITIONAL", JucsErrorCategory.ARGUMENT, 2),

    /** 原生命令使用了 `--` 分隔符。 */
    ARGUMENT_SEPARATOR("JUCS.ARGUMENT.SEPARATOR", JucsErrorCategory.ARGUMENT, 2),

    /** 单值参数后缺少取值。 */
    ARGUMENT_MISSING_VALUE("JUCS.ARGUMENT.MISSING_VALUE", JucsErrorCategory.ARGUMENT, 2),

    /** 重复给出单值参数。 */
    ARGUMENT_DUPLICATE("JUCS.ARGUMENT.DUPLICATE", JucsErrorCategory.ARGUMENT, 2),

    /** 互斥写法同时出现（例如 --flag 与 --no-flag）。 */
    ARGUMENT_CONFLICT("JUCS.ARGUMENT.CONFLICT", JucsErrorCategory.ARGUMENT, 2),

    /** 缺少必填参数。 */
    ARGUMENT_MISSING("JUCS.ARGUMENT.MISSING", JucsErrorCategory.ARGUMENT, 3),

    /** 取值类型、枚举取值或时长格式非法。 */
    ARGUMENT_TYPE("JUCS.ARGUMENT.TYPE", JucsErrorCategory.ARGUMENT, 3),

    /** 标识符格式非法（命令 ID、参数名等）。 */
    ARGUMENT_FORMAT("JUCS.ARGUMENT.FORMAT", JucsErrorCategory.ARGUMENT, 3),

    /** 命令不存在。 */
    COMMAND_UNKNOWN("JUCS.COMMAND.UNKNOWN", JucsErrorCategory.COMMAND, 3),

    /** 权限拒绝。 */
    PERMISSION_DENIED("JUCS.PERMISSION.DENIED", JucsErrorCategory.PERMISSION, 5),

    /** 目标资源不存在。 */
    RESOURCE_NOT_FOUND("JUCS.RESOURCE.NOT_FOUND", JucsErrorCategory.RESOURCE, 4),

    /** 资源冲突（已存在、被占用）。 */
    RESOURCE_CONFLICT("JUCS.RESOURCE.CONFLICT", JucsErrorCategory.RESOURCE, 6),

    /** 宿主 IO 失败。 */
    RESOURCE_IO("JUCS.RESOURCE.IO", JucsErrorCategory.RESOURCE, 8),

    /** 超时。 */
    TIMEOUT("JUCS.TIMEOUT", JucsErrorCategory.TIMEOUT, 7, retryable = true),

    /** 依赖服务不可用。 */
    SERVICE_UNAVAILABLE("JUCS.SERVICE.UNAVAILABLE", JucsErrorCategory.SERVICE, 9, retryable = true),

    /** 部分成功：有成功的条目，也有失败条目。 */
    PARTIAL_SUCCESS("JUCS.PARTIAL.SUCCESS", JucsErrorCategory.PARTIAL, 11),

    /** 调用方取消。 */
    CANCELLED("JUCS.CANCELLED", JucsErrorCategory.CANCELLED, 130),

    /** 未预期的内部错误。 */
    INTERNAL("JUCS.INTERNAL", JucsErrorCategory.INTERNAL, 10),
}

/**
 * 结构化错误对象。
 * 字段与对外协议一致：code / category / message / details / retryable / requestId。
 * English: the structured error object carried on the wire.
 */
data class JucsError(
    val code: JucsCode,
    val message: String,
    val requestId: String,
    val details: Map<String, Any?> = emptyMap(),
    val retryable: Boolean = code.retryable,
) {
    /** 退出码由错误码唯一决定。 */
    val exitCode: Int get() = code.exitCode

    /** 转成可直接序列化的有序映射；顺序稳定便于测试与审计比对。 */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "code" to code.text,
        "category" to code.category.text,
        "message" to message,
        "details" to details,
        "retryable" to retryable,
        "requestId" to requestId,
    )
}

/**
 * 携带结构化错误的异常。
 * 为什么需要异常：处理器内部可能有深层调用栈，逐层返回 Result 会把签名污染成
 * `Result<T, JucsError>`；执行器在边界统一捕获并转换，异常不会泄漏到调用方。
 * English: internal control-flow exception converted at the runtime boundary.
 */
class JucsException(val error: JucsError) : RuntimeException(error.message)
