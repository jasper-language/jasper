package jucs.runtime

/*
 * ============================================================================
 * ValueCodec.kt —— 参数取值的类型转换与校验（runtime 层）
 * ============================================================================
 *
 * 职责：
 *   把"命令行文本"或"结构化调用的原始值"统一转换成命令处理器看到的规范类型：
 *   String / Boolean / Long / JucsDuration。
 *
 * 为什么单独抽出来：
 *   argv 与 invoke 是两条入口，如果各自写一份类型转换，就一定会出现
 *   "命令行能过、AI 调用过不了"的分裂。转换口径只能有一份。
 *
 * English summary:
 *   The single conversion/validation path for parameter values used by both the
 *   argv parser and the structured invoke binder.
 */

import jucs.error.JucsCode
import jucs.error.JucsError
import jucs.error.JucsException
import jucs.schema.JucsDuration
import jucs.schema.ParamKind
import jucs.schema.ParamSpec

/**
 * 取值编解码器。
 * English: parameter value codec.
 */
object ValueCodec {

    /**
     * 把命令行文本转成规范值。
     * 失败时抛 [JucsCode.ARGUMENT_TYPE]，details 里带上参数名与原始文本，
     * 让调用方无需解析人类文案就能定位问题。
     * English: convert CLI text into the canonical value, or throw ARGUMENT_TYPE.
     */
    fun fromText(spec: ParamSpec, text: String, requestId: String): Any = when (spec.kind) {
        ParamKind.STRING -> text
        ParamKind.PATH -> {
            if (text.isEmpty()) throw typeError(spec, text, "路径不能为空", requestId)
            if (text.contains('\u0000')) throw typeError(spec, text, "路径不能包含 NUL 字符", requestId)
            text
        }
        ParamKind.BOOL -> when (text) {
            "true" -> true
            "false" -> false
            else -> throw typeError(spec, text, "布尔参数不接受取值，请使用 --${spec.name} 或 --no-${spec.name}", requestId)
        }
        ParamKind.INT -> text.toLongOrNull() ?: throw typeError(spec, text, "不是合法的 64 位十进制整数", requestId)
        ParamKind.DURATION -> JucsDuration.parse(text)
            ?: throw typeError(spec, text, "时长必须写成带单位的非负整数，如 30s、500ms、2m、1h", requestId)
        ParamKind.ENUM -> if (text in spec.enumValues) text else throw typeError(
            spec, text, "取值必须是 ${spec.enumValues.joinToString("/")} 之一", requestId,
        )
    }

    /**
     * 把结构化调用的原始值转成规范值。
     * 接受 JSON 能表达的类型：字符串、布尔、整数、以及它们的列表（可重复参数）。
     * 拒绝浮点数、对象、任意 null（必需场景由调用方判断）。
     * English: convert a structured value into the canonical value.
     */
    fun fromTyped(spec: ParamSpec, value: Any?, requestId: String): Any = when (spec.kind) {
        ParamKind.STRING -> value as? String ?: throw typeError(spec, value?.toString(), "需要字符串", requestId)
        ParamKind.PATH -> {
            val text = value as? String ?: throw typeError(spec, value?.toString(), "需要路径字符串", requestId)
            if (text.isEmpty()) throw typeError(spec, text, "路径不能为空", requestId)
            if (text.contains('\u0000')) throw typeError(spec, text, "路径不能包含 NUL 字符", requestId)
            text
        }
        ParamKind.BOOL -> value as? Boolean ?: throw typeError(spec, value?.toString(), "需要布尔值", requestId)
        ParamKind.INT -> when (value) {
            is Int -> value.toLong()
            is Long -> value
            is Short -> value.toLong()
            is Byte -> value.toLong()
            else -> throw typeError(spec, value?.toString(), "需要 64 位整数", requestId)
        }
        ParamKind.DURATION -> when (value) {
            is JucsDuration -> value
            is String -> JucsDuration.parse(value)
                ?: throw typeError(spec, value, "时长必须写成带单位的非负整数，如 30s", requestId)
            else -> throw typeError(spec, value?.toString(), "需要带单位的时长字符串", requestId)
        }
        ParamKind.ENUM -> {
            val text = value as? String ?: throw typeError(spec, value?.toString(), "需要字符串枚举值", requestId)
            if (text in spec.enumValues) text else throw typeError(
                spec, text, "取值必须是 ${spec.enumValues.joinToString("/")} 之一", requestId,
            )
        }
    }

    /**
     * 构造类型错误。
     * English: build an ARGUMENT_TYPE error with structured details.
     */
    fun typeError(spec: ParamSpec, raw: String?, reason: String, requestId: String): JucsException =
        JucsException(
            JucsError(
                code = JucsCode.ARGUMENT_TYPE,
                message = "参数 --${spec.name} 取值非法：$reason",
                requestId = requestId,
                details = linkedMapOf(
                    "parameter" to spec.name,
                    "value" to raw,
                    "expected" to spec.kind.name.lowercase(),
                ),
            ),
        )
}
