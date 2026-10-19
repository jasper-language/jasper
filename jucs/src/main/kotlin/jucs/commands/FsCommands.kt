package jucs.commands

/*
 * ============================================================================
 * FsCommands.kt —— filesystem 域原生命令（commands 层）
 * ============================================================================
 *
 * 职责：
 *   提供 `fs file list`：真实枚举目录条目（只读）。
 *
 * 设计要点：
 *   1. **真实 IO**：直接使用 `java.nio.file`，不返回任何示例数据。
 *   2. **错误映射**：不存在 / 不是目录 / 无权限 / IO 失败分别映射到稳定错误码，
 *      而不是把异常文本直接抛给调用方。
 *   3. **确定性顺序**：按 `--sort` + `--order` 排序，未知字段直接拒绝；
 *      大小与时间未知的条目在升降序下都排在最后，避免"空值跑到最前"的意外。
 *   4. **符号链接不跟随**：使用 NOFOLLOW_LINKS 读取属性，避免把链接当成目标，
 *      也避免链接环路。坏链接仍会出现在结果里，kind=symlink、size/modified 为空。
 *
 * 已知限制（不得对外宣称已完成）：
 *   - 隐藏条目按"点号开头"判定，未覆盖 Windows 的 DOS 隐藏属性；
 *   - 没有分页游标，`--limit` 只是截断，不是可续页的游标；
 *   - 权限判断只到"宿主是否允许读"，没有 JUCS 能力作用域判定。
 *
 * English summary:
 *   The `fs file list` command with real NIO directory enumeration.
 */

import jucs.error.JucsCode
import jucs.error.JucsError
import jucs.error.JucsException
import jucs.registry.CommandHandler
import jucs.registry.CommandRegistry
import jucs.registry.RegistrationResult
import jucs.runtime.CommandCall
import jucs.runtime.CommandData
import jucs.runtime.CommandOutcome
import jucs.runtime.RequestContext
import jucs.schema.CommandSchema
import jucs.schema.Effects
import jucs.schema.OfficialVocabulary
import jucs.schema.ParamKind
import jucs.schema.ParamSpec
import jucs.schema.Permission
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * filesystem 域命令集合。
 * English: commands in the `fs` domain.
 */
object FsCommands {

    /** 允许的排序字段。 */
    private val SORT_FIELDS = listOf("name", "size", "modified")

    /** 命令契约：`fs file list`。 */
    val fileListSchema: CommandSchema = CommandSchema(
        id = "fs.file.list",
        publisher = "org.jasper.toolchain",
        version = "0.1.0",
        summaryZh = "列出目录中的条目（不跟随符号链接）",
        summaryEn = "List directory entries without following symbolic links.",
        params = listOf(
            ParamSpec(
                name = "path",
                kind = ParamKind.PATH,
                summaryZh = "要列举的目录路径",
                summaryEn = "Directory to enumerate.",
                required = true,
            ),
            // 官方参数字典只固定名字与类型；默认值属于命令自身语义，这里默认排除隐藏项。
            // The official dictionary fixes name and kind only; the default belongs to the command.
            OfficialVocabulary.officialParam("all")!!.copy(defaultValue = false),
            ParamSpec(
                name = "sort",
                kind = ParamKind.STRING,
                summaryZh = "排序字段：name / size / modified",
                summaryEn = "Sort field: name, size or modified.",
                defaultValue = "name",
            ),
            OfficialVocabulary.officialParam("order")!!,
            ParamSpec(
                name = "limit",
                kind = ParamKind.INT,
                summaryZh = "最多返回的条目数；0 表示不限制",
                summaryEn = "Maximum entries to return; 0 means unlimited.",
                defaultValue = 0L,
            ),
        ),
        outputType = "Stream<FileEntry>",
        effects = Effects(readOnly = true),
        permissions = listOf(Permission("filesystem.read", scopeFrom = "path")),
    )

    /** 处理器。 */
    val fileListHandler = CommandHandler { call, ctx -> listDirectory(call, ctx) }

    /** 注册本域全部命令。 */
    fun register(registry: CommandRegistry): RegistrationResult =
        registry.register(fileListSchema, fileListHandler)

    /**
     * 目录枚举实现。
     * English: the actual directory enumeration.
     */
    private fun listDirectory(call: CommandCall, ctx: RequestContext): CommandOutcome {
        val pathText = call.string("path") ?: throw invalid("缺少 --path", call, ctx)
        val includeAll = call.boolean("all") ?: false
        val sortField = call.string("sort") ?: "name"
        val descending = call.string("order") == "desc"
        val limit = call.integer("limit") ?: 0L

        if (sortField !in SORT_FIELDS) {
            throw JucsException(
                JucsError(
                    JucsCode.ARGUMENT_TYPE,
                    "排序字段必须是 ${SORT_FIELDS.joinToString("/")} 之一，实际为 \"$sortField\"",
                    ctx.requestId,
                    linkedMapOf("parameter" to "sort", "value" to sortField),
                ),
            )
        }
        if (limit < 0) {
            throw JucsException(
                JucsError(
                    JucsCode.ARGUMENT_TYPE,
                    "--limit 必须是非负整数",
                    ctx.requestId,
                    linkedMapOf("parameter" to "limit", "value" to limit),
                ),
            )
        }

        val directory = try {
            Path.of(pathText).toAbsolutePath().normalize()
        } catch (e: RuntimeException) {
            throw JucsException(
                JucsError(
                    JucsCode.ARGUMENT_TYPE,
                    "路径不合法：$pathText",
                    ctx.requestId,
                    linkedMapOf("parameter" to "path", "value" to pathText),
                ),
            )
        }

        if (!Files.exists(directory)) {
            throw JucsException(
                JucsError(
                    JucsCode.RESOURCE_NOT_FOUND,
                    "目录不存在：$directory",
                    ctx.requestId,
                    linkedMapOf("path" to directory.toString()),
                ),
            )
        }
        if (!Files.isDirectory(directory)) {
            throw JucsException(
                JucsError(
                    JucsCode.ARGUMENT_TYPE,
                    "路径不是目录：$directory",
                    ctx.requestId,
                    linkedMapOf("parameter" to "path", "path" to directory.toString()),
                ),
            )
        }

        val entries = mutableListOf<EntryRow>()
        try {
            Files.newDirectoryStream(directory).use { stream ->
                for (child in stream) {
                    val name = child.fileName?.toString() ?: continue
                    if (!includeAll && name.startsWith(".")) continue
                    entries += readEntry(child, name)
                }
            }
        } catch (e: AccessDeniedException) {
            throw JucsException(
                JucsError(
                    JucsCode.PERMISSION_DENIED,
                    "宿主拒绝读取目录：$directory",
                    ctx.requestId,
                    linkedMapOf("path" to directory.toString()),
                ),
            )
        } catch (e: NoSuchFileException) {
            throw JucsException(
                JucsError(
                    JucsCode.RESOURCE_NOT_FOUND,
                    "目录在枚举过程中消失：$directory",
                    ctx.requestId,
                    linkedMapOf("path" to directory.toString()),
                ),
            )
        } catch (e: IOException) {
            throw JucsException(
                JucsError(
                    JucsCode.RESOURCE_IO,
                    "读取目录失败：${e.message ?: e.javaClass.simpleName}",
                    ctx.requestId,
                    linkedMapOf("path" to directory.toString(), "io" to e.javaClass.name),
                ),
            )
        }

        entries.sortWith(comparator(sortField, descending))
        val limited = if (limit > 0 && entries.size > limit) entries.subList(0, limit.toInt()) else entries

        return CommandOutcome(
            data = CommandData.Records(
                columns = listOf("name", "kind", "size", "modified", "path"),
                rows = limited.map { listOf(it.name, it.kind, it.size, formatModified(it.modified), it.path) },
            ),
            warnings = if (limited.size < entries.size) {
                listOf("已按 --limit 截断：共 ${entries.size} 条，仅返回 ${limited.size} 条；该截断不是游标分页")
            } else {
                emptyList()
            },
        )
    }

    /** 读取单个条目的属性；坏链接不视为致命错误。 */
    private fun readEntry(child: Path, name: String): EntryRow {
        val attributes = try {
            Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            null
        }
        val kind = when {
            attributes == null -> "unknown"
            attributes.isSymbolicLink -> "symlink"
            attributes.isDirectory -> "directory"
            attributes.isRegularFile -> "file"
            else -> "other"
        }
        val size = if (attributes != null && attributes.isRegularFile) attributes.size() else null
        val modified = attributes?.lastModifiedTime()?.toMillis()
        return EntryRow(name, kind, size, modified, child.toAbsolutePath().normalize().toString())
    }

    /**
     * 构造排序比较器。
     * 未知大小/时间（null）在升序与降序下都排在最后，避免空值抢占首位。
     * English: comparator that always keeps unknown values last.
     */
    private fun comparator(field: String, descending: Boolean): Comparator<EntryRow> =
        Comparator { left, right ->
            val result = when (field) {
                "name" -> orient(left.name.compareTo(right.name), descending)
                "size" -> compareNullable(left.size, right.size, descending)
                else -> compareNullable(left.modified, right.modified, descending)
            }
            if (result != 0) result else left.name.compareTo(right.name)
        }

    /** 可空数值比较：null 永远排后；非空按方向比较。 */
    private fun compareNullable(left: Long?, right: Long?, descending: Boolean): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> orient(left.compareTo(right), descending)
    }

    /** 按方向调整比较结果。 */
    private fun orient(result: Int, descending: Boolean): Int = if (descending) -result else result

    /** 构造参数错误。 */
    /**
     * 修改时间按 ISO-8601 UTC 输出（如 `2026-10-19T11:39:09.384Z`）。
     * 为什么不用裸毫秒：表格里裸数字不可读；ISO 字符串同时可被人和机器直接使用，
     * 且时区明确（UTC），不会随运行环境漂移。
     * English: ISO-8601 UTC text; readable and unambiguous.
     */
    private fun formatModified(millis: Long?): String? =
        millis?.let { Instant.ofEpochMilli(it).toString() }

    /** 构造参数错误。 */
    private fun invalid(message: String, call: CommandCall, ctx: RequestContext): JucsException =
        JucsException(
            JucsError(
                JucsCode.ARGUMENT_MISSING,
                message,
                ctx.requestId,
                linkedMapOf("command" to call.id),
            ),
        )

    /** 一条目录条目。 */
    private data class EntryRow(
        val name: String,
        val kind: String,
        val size: Long?,
        val modified: Long?,
        val path: String,
    )
}
