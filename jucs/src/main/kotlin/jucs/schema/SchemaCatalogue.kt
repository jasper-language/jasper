package jucs.schema

/*
 * ============================================================================
 * SchemaCatalogue.kt —— 注册表级 Schema 目录（schema 层）
 * ============================================================================
 *
 * 职责：
 *   输出"全局 `--schema`"文档：列出所有已注册命令的完整契约。
 *
 * 为什么需要：工具发现不能只靠帮助文本。AI/IDE 需要一次拿到全部命令的
 * 结构化描述，才能在不执行任何命令的情况下建立工具目录。
 *
 * English summary:
 *   The registry-level schema catalogue used by the global `--schema` option.
 */

import jucs.render.Json

/**
 * 全局 Schema 目录投影。
 * English: registry-level schema catalogue.
 */
object SchemaCatalogue {

    /**
     * 生成目录文档。
     * 结构：`{jucsVersion, commands: [{id, publisher, version, title, effects, ...}]}`。
     * 每个命令的 `arguments` 与单命令 `--schema` 完全一致。
     * English: emit the catalogue document.
     */
    fun of(schemas: List<CommandSchema>): String {
        val commands = schemas.sortedBy { it.id }.map { SchemaJson.document(it) }
        return Json.toText(
            linkedMapOf(
                "jucsVersion" to OfficialVocabulary.VERSION,
                "commands" to commands,
            ),
        )
    }
}
