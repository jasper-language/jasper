package jucs.schema

/*
 * ============================================================================
 * SchemaJson.kt —— 契约的 JSON Schema 投影（schema 层）
 * ============================================================================
 *
 * 职责：
 *   把 [CommandSchema] 投影成一份 JSON Schema Draft 2020-12 文档，
 *   供跨语言调用方、IDE、AI 工具与外部校验器使用。
 *
 * 关键原则：
 *   JSON Schema 是**导出投影**，不是事实来源。事实来源永远是 CommandSchema
 *   （在 Jasper 侧对应精确类型）。因此这里只做无损映射 + 少量 `x-jucs-*` 扩展，
 *   不引入只有 JSON 侧才理解的语义。
 *
 * English summary:
 *   Project a CommandSchema into a JSON Schema 2020-12 document.
 */

import jucs.render.Json

/**
 * 契约 → JSON Schema 投影器。
 * English: schema projector.
 */
object SchemaJson {

    /**
     * 生成 JSON Schema 文本（字段顺序稳定）。
     * English: render the projected JSON Schema document.
     */
    fun of(schema: CommandSchema): String = Json.toText(document(schema))

    /**
     * 生成 JSON Schema 的结构化形式（供全局目录复用，避免序列化后再解析）。
     * English: structured form of the projected schema document.
     */
    fun document(schema: CommandSchema): Map<String, Any?> {
        val properties = linkedMapOf<String, Any?>()
        schema.params.forEach { spec ->
            properties[spec.name] = propertySchema(spec)
        }
        val required = schema.params.filter { it.required }.map { it.name }

        return linkedMapOf(
            "\$schema" to "https://json-schema.org/draft/2020-12/schema",
            "jucsVersion" to OfficialVocabulary.VERSION,
            "id" to schema.id,
            "publisher" to schema.publisher,
            "version" to schema.version,
            "title" to schema.summaryZh,
            "description" to schema.summaryZh,
            "descriptionEn" to schema.summaryEn,
            "arguments" to linkedMapOf(
                "type" to "object",
                "properties" to properties,
                "required" to required,
                "additionalProperties" to false,
            ),
            "outputType" to schema.outputType,
            "effects" to linkedMapOf(
                "readOnly" to schema.effects.readOnly,
                "externalAccess" to schema.effects.externalAccess,
                "destructive" to schema.effects.destructive,
            ),
            "permissions" to schema.permissions.map { permission ->
                linkedMapOf<String, Any?>(
                    "capability" to permission.capability,
                    "scopeFrom" to permission.scopeFrom,
                )
            },
            "idempotent" to schema.idempotent,
            "retryable" to schema.retryable,
        )
    }

    /**
     * 单个参数的 JSON Schema 片段。
     * 时长用带单位正则表达，布尔/整数用标准类型，枚举用 `enum`。
     * English: JSON Schema fragment for one parameter.
     */
    private fun propertySchema(spec: ParamSpec): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>("description" to spec.summaryZh)
        when (spec.kind) {
            ParamKind.STRING -> base["type"] = "string"
            ParamKind.PATH -> {
                base["type"] = "string"
                base["minLength"] = 1
            }
            ParamKind.BOOL -> base["type"] = "boolean"
            ParamKind.INT -> base["type"] = "integer"
            ParamKind.DURATION -> {
                base["type"] = "string"
                base["pattern"] = "^[0-9]+(ms|s|m|h)$"
            }
            ParamKind.ENUM -> {
                base["type"] = "string"
                base["enum"] = spec.enumValues
            }
        }
        val default = spec.defaultValue
        if (default != null) {
            base["default"] = if (default is JucsDuration) default.toString() else default
        }
        if (spec.repeatable) base["x-jucs-repeatable"] = true
        if (spec.deprecated != null) {
            base["deprecated"] = true
            base["x-jucs-deprecated"] = spec.deprecated
        }
        return base
    }
}
