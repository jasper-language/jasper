package jucs.runtime

/*
 * ============================================================================
 * CommandData.kt —— 命令结果的数据模型（runtime 层）
 * ============================================================================
 *
 * 职责：
 *   定义"命令返回什么"，以及调用方可以携带的输入（[CommandCall]）。
 *
 * 为什么定义自己的数据模型而不是直接用 Map/List：
 *   命令结果必须同时支持"单条记录"与"记录集合"两种形状，并且要能被 table/
 *   json/ndjson 三种渲染器一致地消费。用一个封闭的二分类型，渲染器可以穷尽
 *   处理，新增形状时编译期报错。
 *
 * 取值类型：只允许 String / Long / Boolean / null 以及它们的列表。
 * 其他类型属于渲染器无法安全序列化的值，渲染时会以内部错误拒绝，绝不静默丢弃。
 *
 * English summary:
 *   The closed result model (record or record set) plus the normalized call object.
 */

/**
 * 命令结果数据。
 * English: command result data.
 */
sealed interface CommandData {
    /** 单条记录：有序字段表。 */
    data class Record(val fields: List<Pair<String, Any?>>) : CommandData

    /** 记录集合：列名 + 行数据，行内元素顺序与 [columns] 一一对应。 */
    data class Records(val columns: List<String>, val rows: List<List<Any?>>) : CommandData
}

/**
 * 业务状态。
 * 为什么业务状态与退出码分开：传输/调用成功不等于业务成功；批量操作可能出现
 * "部分成功"。退出码是它的粗粒度投影，细节必须看这个状态与错误对象。
 * English: business status, separate from transport or exit-code success.
 */
enum class OutcomeStatus(val text: String, val exitCode: Int) {
    /** 全部成功。 */
    SUCCESS("success", 0),

    /** 部分成功。 */
    PARTIAL("partial", 11),

    /** 全部失败。 */
    FAILED("failed", 10),

    /** 调用方取消。 */
    CANCELLED("cancelled", 130),
}

/**
 * 一次命令执行的输出。
 * English: one command execution result.
 */
data class CommandOutcome(
    /** 成功时的业务数据；失败时为 null。 */
    val data: CommandData?,
    /** 业务状态。 */
    val status: OutcomeStatus = OutcomeStatus.SUCCESS,
    /** 非致命警告，进入 stderr，不污染 stdout 数据。 */
    val warnings: List<String> = emptyList(),
)

/**
 * 归一化后的命令调用：argv 与结构化 invoke 最终都变成这个对象。
 * 为什么必须归一化：只有一份参数形状，处理器与权限判定就不可能对同一个命令
 * 产生两种解释。
 * English: normalized call shared by argv and structured invocation.
 */
class CommandCall(
    /** 稳定命令 ID。 */
    val id: String,
    /** 参数名 → 取值序列（单值参数长度为 1）。 */
    val arguments: Map<String, List<Any?>>,
) {
    /** 取单值参数；不存在返回 null。 */
    fun scalar(name: String): Any? = arguments[name]?.firstOrNull()

    /** 取字符串参数；类型不符时抛内部错误（契约校验本应提前拦住）。 */
    fun string(name: String): String? = scalar(name) as? String

    /** 取整数参数。 */
    fun integer(name: String): Long? = scalar(name) as? Long

    /** 取布尔参数。 */
    fun boolean(name: String): Boolean? = scalar(name) as? Boolean

    /** 取时长参数。 */
    fun duration(name: String): jucs.schema.JucsDuration? = scalar(name) as? jucs.schema.JucsDuration
}
