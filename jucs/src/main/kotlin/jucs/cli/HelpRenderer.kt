package jucs.cli

/*
 * ============================================================================
 * HelpRenderer.kt —— 帮助文本生成（cli 层）
 * ============================================================================
 *
 * 职责：
 *   从契约生成 `--help`、`--version` 与全局命令列表。
 *
 * 为什么必须从契约生成：
 *   手写帮助文本一定会与解析器漂移：新增参数忘了写帮助、改了默认值没改文档。
 *   这里所有内容都来自同一个 CommandSchema，因此不存在第二份事实。
 *
 * English summary:
 *   Generate help/version text from the command contract.
 */

import jucs.registry.CommandRegistry
import jucs.schema.CommandSchema
import jucs.schema.JucsDuration
import jucs.schema.OfficialVocabulary
import jucs.schema.ParamKind
import jucs.schema.ParamSpec

/**
 * 帮助文本生成器。
 * English: help text renderer.
 */
object HelpRenderer {

    /**
     * 单条命令的帮助。
     * English: help for one command.
     */
    fun command(schema: CommandSchema): String {
        val sink = StringBuilder()
        sink.append("用法 / Usage\n  ").append(usageLine(schema)).append('\n')
        sink.append('\n')
        sink.append("说明\n  ").append(schema.summaryZh).append('\n')
        sink.append("  ").append(schema.summaryEn).append('\n')
        if (schema.params.isNotEmpty()) {
            sink.append('\n')
            sink.append("业务参数\n")
            val width = schema.params.maxOf { DisplayWidths.of(paramLabel(it)) }
            schema.params.forEach { spec ->
                sink.append("  ").append(pad(paramLabel(spec), width)).append("  ")
                sink.append(if (spec.required) "必需  " else "可选  ")
                sink.append(spec.summaryZh)
                spec.defaultValue?.let { sink.append("（默认 ").append(defaultText(it)).append("）") }
                if (spec.deprecated != null) sink.append("【已弃用：").append(spec.deprecated).append("】")
                sink.append('\n')
            }
        } else {
            sink.append("\n业务参数\n  该命令没有业务参数\n")
        }
        sink.append("\n通用参数\n")
        OfficialVocabulary.IMPLICIT_PARAMS.forEach { (_, spec) ->
            sink.append("  ").append(paramLabel(spec)).append("  ").append(spec.summaryZh)
            spec.defaultValue?.let { sink.append("（默认 ").append(defaultText(it)).append("）") }
            sink.append('\n')
        }
        sink.append("\n元命令（只读，不触发任何副作用）\n")
        sink.append("  --help     显示本帮助\n")
        sink.append("  --version  显示命令身份与契约版本\n")
        sink.append("  --schema   输出 JSON Schema（Draft 2020-12）\n")
        sink.append("\n身份\n")
        sink.append("  命令 ID    ").append(schema.id).append('\n')
        sink.append("  发布者     ").append(schema.publisher).append('\n')
        sink.append("  契约版本   ").append(schema.version).append('\n')
        sink.append("  JUCS       ").append(OfficialVocabulary.VERSION).append('\n')
        sink.append("  输出类型   ").append(schema.outputType).append('\n')
        sink.append("  效果       ").append(effectText(schema)).append('\n')
        if (schema.permissions.isNotEmpty()) {
            sink.append("  权限       ")
            sink.append(
                schema.permissions.joinToString("；") { permission ->
                    permission.scopeFrom?.let { scope -> "${permission.capability}（作用域来自 --$scope）" }
                        ?: permission.capability
                },
            )
            sink.append('\n')
        }
        return sink.toString()
    }

    /**
     * 命令的 `--version` 输出。
     * English: `--version` payload for one command.
     */
    fun version(schema: CommandSchema): String =
        "${schema.id} ${schema.version} (JUCS ${OfficialVocabulary.VERSION}, publisher=${schema.publisher})\n"

    /**
     * 全局帮助：列出已注册的命令与用法约定。
     * English: global help listing registered commands.
     */
    fun global(registry: CommandRegistry): String {
        val sink = StringBuilder()
        sink.append("JUCS ").append(OfficialVocabulary.VERSION).append(" 原生命令入口\n")
        sink.append('\n')
        sink.append("用法 / Usage\n")
        sink.append("  <program> <resource> <action> [--参数 取值 | --开关 | --no-开关]...\n")
        sink.append('\n')
        sink.append("约定\n")
        sink.append("  - 业务参数必须具名，值为 --name value 形式；布尔用 --flag / --no-flag。\n")
        sink.append("  - 不支持短选项、缩写与模糊匹配；未知参数与重复单值参数一律拒绝。\n")
        sink.append("  - --help / --version / --schema 为只读元命令，先于业务动作处理。\n")
        sink.append("  - --output 取 table（人读）、json（单个对象）、ndjson（每行一个对象）。\n")
        sink.append("  - 数据只写 stdout，诊断只写 stderr。\n")
        sink.append('\n')
        sink.append("已注册命令\n")
        val all = registry.all()
        if (all.isEmpty()) {
            sink.append("  （无）\n")
        } else {
            val width = all.maxOf { it.schema.id.length }
            all.forEach { registration ->
                sink.append("  ").append(registration.schema.id.padEnd(width)).append("  ")
                sink.append(registration.schema.summaryZh).append('\n')
            }
        }
        sink.append('\n')
        sink.append("官方动词：").append(OfficialVocabulary.VERBS.keys.joinToString(" ")).append('\n')
        return sink.toString()
    }

    /** 用法行：命令 ID + 每个业务参数的简写。 */
    private fun usageLine(schema: CommandSchema): String {
        val sink = StringBuilder(schema.id.replace('.', ' '))
        schema.params.forEach { spec ->
            sink.append(' ')
            val token = when (spec.kind) {
                ParamKind.BOOL -> "--${spec.name}"
                ParamKind.ENUM -> "--${spec.name} <${spec.enumValues.joinToString("|")}>"
                ParamKind.INT -> "--${spec.name} <int>"
                ParamKind.DURATION -> "--${spec.name} <duration>"
                ParamKind.PATH -> "--${spec.name} <path>"
                ParamKind.STRING -> "--${spec.name} <string>"
            }
            if (!spec.required) sink.append('[').append(token).append(']') else sink.append(token)
        }
        return sink.toString()
    }

    /** 参数标签：`--name <kind>` 或 `--flag`。 */
    private fun paramLabel(spec: ParamSpec): String = when (spec.kind) {
        ParamKind.BOOL -> "--${spec.name} / --no-${spec.name}"
        ParamKind.ENUM -> "--${spec.name} <${spec.enumValues.joinToString("|")}>"
        ParamKind.INT -> "--${spec.name} <int>"
        ParamKind.DURATION -> "--${spec.name} <duration>"
        ParamKind.PATH -> "--${spec.name} <path>"
        ParamKind.STRING -> "--${spec.name} <string>"
    }

    /** 默认值的人读文本。 */
    private fun defaultText(value: Any?): String = when (value) {
        is JucsDuration -> value.toString()
        else -> value.toString()
    }

    /** 效果的人读摘要。 */
    private fun effectText(schema: CommandSchema): String {
        val parts = mutableListOf<String>()
        if (schema.effects.readOnly) parts += "只读"
        if (schema.effects.destructive) parts += "不可逆修改"
        if (!schema.effects.readOnly && !schema.effects.destructive) parts += "可逆修改"
        if (schema.effects.externalAccess) parts += "触及外部资源"
        return parts.joinToString("，")
    }

    /** 按显示宽度右补空格。 */
    private fun pad(text: String, width: Int): String =
        text + " ".repeat((width - DisplayWidths.of(text)).coerceAtLeast(0))
}

/**
 * 与渲染层同口径的显示宽度，避免 cli 与 render 互相依赖。
 * English: same width rule as the render layer, kept local to avoid a cycle.
 */
private object DisplayWidths {
    fun of(text: String): Int = jucs.render.DisplayWidth.of(text)
}
