package jucs.runtime

/*
 * ============================================================================
 * ArgumentBinder.kt —— 结构化调用的参数绑定（runtime 层）
 * ============================================================================
 *
 * 职责：
 *   把"机器调用传入的 Map"绑定成与 argv 完全同形的 [CommandCall]：
 *   未知键拒绝、必填检查、默认值补齐、逐值类型转换、可重复参数展开。
 *
 * 为什么 argv 之后还要再绑定一次：
 *   两条入口共存时，最危险的缺陷是"argv 路径有校验、invoke 路径悄悄放行"。
 *   让两条路径都经过同一个绑定器，且绑定器对已归一化的值幂等，
 *   就从结构上消除了这种分叉。
 *
 * English summary:
 *   Bind structured arguments into the same normalized shape as argv, idempotently.
 */

import jucs.error.JucsCode
import jucs.error.JucsError
import jucs.error.JucsException
import jucs.schema.CommandSchema
import jucs.schema.OfficialVocabulary

/**
 * 结构化参数绑定器。
 * English: structured argument binder.
 */
object ArgumentBinder {

    /**
     * 绑定参数。
     *
     * @param schema 命令契约
     * @param rawArguments 参数名 → 原始值；可重复参数可以是列表
     * @param requestId 请求标识，进入错误对象
     * @return 归一化调用（含默认值）
     * 失败模式：未知参数、空值、类型不符、缺必填，均抛 [JucsException]。
     * English: bind raw arguments; throws on any contract violation.
     */
    fun bind(schema: CommandSchema, rawArguments: Map<String, Any?>, requestId: String): CommandCall {
        val values = linkedMapOf<String, List<Any?>>()
        rawArguments.forEach { (name, raw) ->
            if (name in OfficialVocabulary.META_PARAMS || OfficialVocabulary.isImplicitParam(name)) {
                throw JucsException(
                    JucsError(
                        JucsCode.ARGUMENT_UNKNOWN,
                        "结构化调用不接受通用参数或元命令参数 --$name，它们由调用适配器处理",
                        requestId,
                        linkedMapOf("command" to schema.id, "parameter" to name),
                    ),
                )
            }
            val spec = schema.param(name) ?: throw JucsException(
                JucsError(
                    JucsCode.ARGUMENT_UNKNOWN,
                    "命令 ${schema.id} 不认识参数 --$name",
                    requestId,
                    linkedMapOf(
                        "command" to schema.id,
                        "parameter" to name,
                        "known" to schema.params.map { it.name },
                    ),
                ),
            )
            if (raw == null) {
                throw JucsException(
                    JucsError(
                        JucsCode.ARGUMENT_TYPE,
                        "参数 --$name 不接受 null；不需要时请省略该键",
                        requestId,
                        linkedMapOf("parameter" to name),
                    ),
                )
            }
            val list = if (raw is List<*>) raw else listOf(raw)
            if (list.isEmpty()) {
                throw JucsException(
                    JucsError(
                        JucsCode.ARGUMENT_TYPE,
                        "参数 --$name 不接受空列表",
                        requestId,
                        linkedMapOf("parameter" to name),
                    ),
                )
            }
            if (list.size > 1 && !spec.repeatable) {
                throw JucsException(
                    JucsError(
                        JucsCode.ARGUMENT_DUPLICATE,
                        "参数 --$name 只能给出一个取值",
                        requestId,
                        linkedMapOf("parameter" to name),
                    ),
                )
            }
            values[name] = list.map { ValueCodec.fromTyped(spec, it, requestId) }
        }

        val missing = schema.params.filter { it.required && !values.containsKey(it.name) && it.defaultValue == null }
        if (missing.isNotEmpty()) {
            throw JucsException(
                JucsError(
                    JucsCode.ARGUMENT_MISSING,
                    "缺少必填参数：${missing.joinToString(" ") { "--${it.name}" }}",
                    requestId,
                    linkedMapOf("command" to schema.id, "missing" to missing.map { it.name }),
                ),
            )
        }
        schema.params.forEach { spec ->
            val default = spec.defaultValue
            if (default != null && !values.containsKey(spec.name)) {
                values[spec.name] = listOf(default)
            }
        }
        return CommandCall(schema.id, values)
    }
}
