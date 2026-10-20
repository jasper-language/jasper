package jucs.schema

/*
 * ============================================================================
 * SchemaValidation.kt —— 命令契约自校验（schema 层）
 * ============================================================================
 *
 * 职责：
 *   在注册一个命令之前，检查契约内部的全部不变量：身份、版本、动词、参数类型、
 *   官方参数字典一致性、默认值类型、权限作用域引用。
 *
 * 为什么必须在注册期做：
 *   契约错误如果拖到运行时才发现，就会表现为"某个参数永远校验不过"或
 *   "帮助里说 A、解析器按 B 处理"这类难以定位的生态级不一致。
 *   注册期拒绝 = 作者立刻拿到明确原因。
 *
 * 边界：
 *   只看契约本身，不检查处理器行为，也不做权限策略判断。
 *
 * English summary:
 *   Structural validation of a CommandSchema, performed at registration time.
 */

/**
 * 契约校验器。
 * English: schema validator entry point.
 */
object SchemaValidation {

    /**
     * 校验一个命令契约。
     * 返回问题列表；空列表表示合法。每个元素都是可以直接展示给作者的中文原因。
     * English: returns all problems, empty when the schema is valid.
     */
    fun validate(schema: CommandSchema): List<String> {
        val problems = mutableListOf<String>()

        JucsNames.commandIdProblem(schema.id)?.let { problems += it }
        if (!JucsNames.isCapability(schema.publisher)) {
            problems += "发布者必须是点分小写标识（如 org.jasper.toolchain），实际为 \"${schema.publisher}\""
        }
        if (!JucsNames.isSemanticVersion(schema.version)) {
            problems += "契约版本必须是语义版本（major.minor.patch），实际为 \"${schema.version}\""
        }
        if (schema.summaryZh.isBlank()) problems += "缺少中文摘要（summaryZh）"
        if (schema.summaryEn.isBlank()) problems += "缺少英文摘要（summaryEn）"
        if (schema.outputType.isBlank()) problems += "缺少输出类型（outputType）"

        // 动作段必须是官方动词，否则生态会出现 show/info/rm 这类同义变体。
        val action = schema.id.split('.').getOrNull(2)
        if (action != null && JucsNames.isKebab(action) && !OfficialVocabulary.isOfficialVerb(action)) {
            // 非官方动词一律拒绝；同时给出可操作的迁移提示。
            // 这里特意不把已退役名字作为可接受别名：注册与解析路径只有官方表一份事实来源，
            // 提示只影响错误信息，绝不改变"拒绝"这一结果。
            // The retired verb is mentioned only in prose; it is never accepted as an alias.
            val removeHint = "若意图是移除资源，请改用 \"remove\"（delete 已退役，不再作为命令动词）"
            problems += "动作 \"$action\" 是非官方动词；官方动词：${OfficialVocabulary.VERBS.keys.joinToString(" ")}；$removeHint"
        }

        val seen = mutableSetOf<String>()
        schema.params.forEach { spec ->
            if (OfficialVocabulary.isImplicitParam(spec.name)) {
                problems += "参数 --${spec.name} 由运行时隐式提供，命令契约不得重复声明"
            }
            if (spec.name in OfficialVocabulary.META_PARAMS) {
                problems += "参数 --${spec.name} 是保留的只读元命令，命令契约不得声明"
            }
            if (!JucsNames.isKebab(spec.name)) {
                problems += "参数名不是合法 kebab-case：\"${spec.name}\""
            }
            if (!seen.add(spec.name)) {
                problems += "参数 --${spec.name} 重复声明"
            }
            if (spec.summaryZh.isBlank() || spec.summaryEn.isBlank()) {
                problems += "参数 --${spec.name} 缺少中英文说明"
            }

            val official = OfficialVocabulary.officialParam(spec.name)
            if (official != null) {
                if (official.kind != spec.kind) {
                    problems += "参数 --${spec.name} 是官方参数，类型必须为 ${official.kind}，实际为 ${spec.kind}"
                }
                if (official.enumValues.isNotEmpty() && official.enumValues != spec.enumValues) {
                    problems += "参数 --${spec.name} 是官方参数，取值集合必须为 ${official.enumValues}，实际为 ${spec.enumValues}"
                }
            }

            when (spec.kind) {
                ParamKind.ENUM -> {
                    if (spec.enumValues.isEmpty()) {
                        problems += "参数 --${spec.name} 是枚举类型但未声明取值"
                    }
                    if (spec.enumValues.toSet().size != spec.enumValues.size) {
                        problems += "参数 --${spec.name} 的枚举取值有重复"
                    }
                    spec.enumValues.filter { it.isBlank() || it.contains(' ') }.forEach {
                        problems += "参数 --${spec.name} 的枚举取值 \"$it\" 含空白或为空"
                    }
                    if (spec.defaultValue != null && spec.defaultValue !in spec.enumValues) {
                        problems += "参数 --${spec.name} 的默认值不在枚举取值内"
                    }
                }
                ParamKind.BOOL -> if (spec.defaultValue != null && spec.defaultValue !is Boolean) {
                    problems += "参数 --${spec.name} 是布尔类型但默认值不是布尔"
                }
                ParamKind.INT -> if (spec.defaultValue != null && !isIntegral(spec.defaultValue)) {
                    problems += "参数 --${spec.name} 是整数类型但默认值不是整数"
                }
                ParamKind.DURATION -> if (spec.defaultValue != null && spec.defaultValue !is JucsDuration) {
                    problems += "参数 --${spec.name} 是时长类型但默认值不是 JucsDuration"
                }
                ParamKind.STRING, ParamKind.PATH -> if (spec.defaultValue != null && spec.defaultValue !is String) {
                    problems += "参数 --${spec.name} 是字符串类型但默认值不是字符串"
                }
            }
            if (spec.kind != ParamKind.ENUM && spec.enumValues.isNotEmpty()) {
                problems += "参数 --${spec.name} 不是枚举类型却声明了枚举取值"
            }
            if (spec.repeatable && spec.defaultValue != null) {
                problems += "参数 --${spec.name} 可重复出现，不能同时声明默认值"
            }
            if (spec.required && spec.defaultValue != null) {
                problems += "参数 --${spec.name} 声明为必填，不能同时声明默认值"
            }
        }

        if (schema.effects.readOnly && schema.effects.destructive) {
            problems += "效果声明矛盾：只读命令不可能同时是不可逆修改"
        }

        schema.permissions.forEach { permission ->
            if (!JucsNames.isCapability(permission.capability)) {
                problems += "能力名不是点分小写标识：\"${permission.capability}\""
            }
            val scope = permission.scopeFrom
            if (scope != null) {
                val spec = schema.param(scope)
                if (spec == null) {
                    problems += "能力 ${permission.capability} 的作用域来源参数 --$scope 未在契约中声明"
                } else if (spec.kind != ParamKind.PATH && spec.kind != ParamKind.STRING) {
                    problems += "能力 ${permission.capability} 的作用域来源参数 --$scope 必须是 path 或 string 类型"
                }
            }
        }

        return problems
    }

    /** 判定默认值是否可作为整数处理。 */
    private fun isIntegral(value: Any): Boolean = value is Int || value is Long || value is Short || value is Byte
}
