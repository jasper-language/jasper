package jucs.runtime

/*
 * ============================================================================
 * ArgvParser.kt —— 原生命令的 argv 解析与类型绑定（runtime 层）
 * ============================================================================
 *
 * 职责：
 *   按命令契约把 token 序列解析成规范化参数表，并在此处完成全部结构校验：
 *   未知参数、位置参数、`--` 分隔符、缺值、重复、互斥、缺必填、类型非法。
 *
 * 关键设计：
 *   1. **Schema 决定消费**：单值参数一律消费下一个 token，因此值可以以 `-` 开头；
 *      解析器不会把值误判成另一个选项。
 *   2. **只有一份类型转换**：全部交给 [ValueCodec]，argv 与 invoke 共用。
 *   3. **元命令优先但不误判**：[scanMeta] 在扫描时尊重"前一个选项会消费下一个
 *      token"的规则，所以 `--name --help` 里的 `--help` 是值，不是元命令。
 *   4. **失败即拒绝**：任何结构错误都会抛出 [jucs.error.JucsException]，
 *      处理器绝不会被调用，因此不存在"先产生副作用再报参数错"的窗口。
 *
 * 边界：
 *   不做权限判定，不做命令查找，不做输出渲染。
 *
 * English summary:
 *   Strict schema-driven argv parsing with a single value-codec path.
 */

import jucs.error.JucsCode
import jucs.error.JucsError
import jucs.error.JucsException
import jucs.render.OutputFormat
import jucs.schema.CommandSchema
import jucs.schema.OfficialVocabulary
import jucs.schema.ParamKind
import jucs.schema.ParamSpec

/**
 * 解析结果：业务参数 + 三个通用参数。
 * English: parsed business arguments plus the three runtime-provided options.
 */
data class ParsedArgs(
    /** 参数名 → 规范值序列（含默认值）。 */
    val values: Map<String, List<Any?>>,
    /** 输出格式。 */
    val output: OutputFormat,
    /** 是否输出更多诊断。 */
    val verbose: Boolean,
    /** 是否抑制诊断。 */
    val quiet: Boolean,
) {
    /** 构造归一化调用对象，供执行器直接使用。 */
    fun toCall(id: String): CommandCall = CommandCall(id, values)
}

/**
 * argv 解析器。
 * English: the argv parser.
 */
object ArgvParser {

    /**
     * 扫描只读元命令。
     *
     * 返回按出现顺序记录的元命令名（`help` / `version` / `schema`）。
     * 扫描遵循"单值参数消费下一个 token"的规则，避免把参数值误当元命令。
     * English: locate meta options that really appear in option position.
     */
    fun scanMeta(schema: CommandSchema, tokens: List<String>): List<String> {
        val found = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.startsWith("--")) {
                val name = token.substring(2)
                if (name in OfficialVocabulary.META_PARAMS) {
                    found += name
                    i += 1
                    continue
                }
                val resolved = resolve(schema, name)
                // 只有"未取反且会消费取值"的参数才跳过一个 token。
                if (resolved != null && !resolved.second && resolved.first.kind != ParamKind.BOOL) {
                    i += 2
                    continue
                }
            }
            i += 1
        }
        return found
    }

    /**
     * 解析业务参数。
     *
     * 前置条件：调用方已经处理过元命令（[scanMeta] 的结果为空）。
     * 失败模式：抛出带稳定错误码的 [JucsException]，处理器不会被调用。
     * English: parse business arguments; throws on the first structural problem.
     */
    fun parse(schema: CommandSchema, tokens: List<String>, requestId: String): ParsedArgs {
        val collected = linkedMapOf<String, MutableList<Any?>>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token == "--") {
                throw JucsException(
                    JucsError(
                        JucsCode.ARGUMENT_SEPARATOR,
                        "原生命令不接受 -- 分隔符；-- 只用于外部进程兼容桥",
                        requestId,
                        linkedMapOf("command" to schema.id),
                    ),
                )
            }
            if (!token.startsWith("--")) {
                throw JucsException(
                    JucsError(
                        JucsCode.ARGUMENT_POSITIONAL,
                        "原生命令的业务参数必须全部具名，收到位置参数 \"$token\"",
                        requestId,
                        linkedMapOf("command" to schema.id, "token" to token),
                    ),
                )
            }
            val name = token.substring(2)
            if (name.isEmpty()) {
                throw JucsException(
                    JucsError(JucsCode.ARGUMENT_SEPARATOR, "空选项名 \"--\"", requestId, linkedMapOf("command" to schema.id)),
                )
            }
            val resolved = resolve(schema, name) ?: throw unknownParam(schema, name, requestId)
            val (spec, negated) = resolved
            if (spec.kind == ParamKind.BOOL) {
                put(collected, spec, !negated, requestId)
                i += 1
            } else {
                if (i + 1 >= tokens.size) {
                    throw JucsException(
                        JucsError(
                            JucsCode.ARGUMENT_MISSING_VALUE,
                            "参数 --${spec.name} 需要一个取值",
                            requestId,
                            linkedMapOf("command" to schema.id, "parameter" to spec.name),
                        ),
                    )
                }
                val raw = tokens[i + 1]
                put(collected, spec, ValueCodec.fromText(spec, raw, requestId), requestId)
                i += 2
            }
        }

        // 补默认值并检查必填。
        val missing = mutableListOf<String>()
        schema.params.forEach { spec ->
            if (!collected.containsKey(spec.name)) {
                when {
                    spec.defaultValue != null -> collected[spec.name] = mutableListOf(spec.defaultValue)
                    spec.required -> missing += spec.name
                }
            }
        }
        if (missing.isNotEmpty()) {
            throw JucsException(
                JucsError(
                    JucsCode.ARGUMENT_MISSING,
                    "缺少必填参数：${missing.joinToString(" ") { "--$it" }}",
                    requestId,
                    linkedMapOf("command" to schema.id, "missing" to missing),
                ),
            )
        }

        // 通用参数：未给出时取字典默认值。
        val outputSpec = OfficialVocabulary.IMPLICIT_PARAMS.getValue("output")
        val verboseSpec = OfficialVocabulary.IMPLICIT_PARAMS.getValue("verbose")
        val quietSpec = OfficialVocabulary.IMPLICIT_PARAMS.getValue("quiet")
        val outputText = collected.remove("output")?.firstOrNull() as? String
            ?: outputSpec.defaultValue as String
        val verbose = collected.remove("verbose")?.firstOrNull() as? Boolean
            ?: verboseSpec.defaultValue as Boolean
        val quiet = collected.remove("quiet")?.firstOrNull() as? Boolean
            ?: quietSpec.defaultValue as Boolean

        return ParsedArgs(
            values = collected.mapValues { it.value.toList() },
            output = OutputFormat.fromWireName(outputText, requestId),
            verbose = verbose,
            quiet = quiet,
        )
    }

    /**
     * 把参数名解析成"契约 + 是否取反"。
     *
     * 规则优先级：精确匹配业务参数 → 精确匹配通用参数 → `no-` 前缀且基名为布尔参数。
     * 为什么精确匹配优先：避免把真实存在的 `no-x` 参数误判成对 `x` 的取反。
     * English: resolve a parameter name, honouring the `no-` negation for booleans.
     */
    private fun resolve(schema: CommandSchema, name: String): Pair<ParamSpec, Boolean>? {
        schema.param(name)?.let { return it to false }
        OfficialVocabulary.IMPLICIT_PARAMS[name]?.let { return it to false }
        if (name.startsWith("no-")) {
            val base = name.removePrefix("no-")
            val spec = schema.param(base) ?: OfficialVocabulary.IMPLICIT_PARAMS[base]
            if (spec != null && spec.kind == ParamKind.BOOL) return spec to true
        }
        return null
    }

    /** 记录一个取值，处理重复与互斥。 */
    private fun put(
        collected: MutableMap<String, MutableList<Any?>>,
        spec: ParamSpec,
        value: Any?,
        requestId: String,
    ) {
        val existing = collected[spec.name]
        if (existing == null) {
            collected[spec.name] = mutableListOf(value)
            return
        }
        if (spec.repeatable) {
            existing += value
            return
        }
        if (spec.kind == ParamKind.BOOL && existing.firstOrNull() != value) {
            throw JucsException(
                JucsError(
                    JucsCode.ARGUMENT_CONFLICT,
                    "参数 --${spec.name} 同时给出真与假",
                    requestId,
                    linkedMapOf("parameter" to spec.name),
                ),
            )
        }
        throw JucsException(
            JucsError(
                JucsCode.ARGUMENT_DUPLICATE,
                "参数 --${spec.name} 只能出现一次",
                requestId,
                linkedMapOf("parameter" to spec.name),
            ),
        )
    }

    /** 构造未知参数错误，details 中带上该命令的真实参数表以便调用方自助修正。 */
    private fun unknownParam(schema: CommandSchema, name: String, requestId: String): JucsException =
        JucsException(
            JucsError(
                JucsCode.ARGUMENT_UNKNOWN,
                "命令 ${schema.id} 不认识参数 --$name",
                requestId,
                linkedMapOf(
                    "command" to schema.id,
                    "parameter" to name,
                    "known" to (schema.params.map { it.name } + OfficialVocabulary.IMPLICIT_PARAMS.keys + OfficialVocabulary.META_PARAMS),
                ),
            ),
        )
}
