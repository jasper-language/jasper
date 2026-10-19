package jucs.schema

/*
 * ============================================================================
 * OfficialVocabulary.kt —— 官方动词表与全生态参数字典（schema 层）
 * ============================================================================
 *
 * 职责：
 *   固定"整个 Jasper 生态共用的动词与参数语义"。任何命令都不得重新定义这些
 *   名字的含义，也不得用同义变体（show/info/rm）替代官方动词。
 *
 * 边界：
 *   只描述词汇与固定类型；具体命令声明哪些参数，由各自的 CommandSchema 决定。
 *
 * English summary:
 *   The official verb table and the ecosystem-wide parameter dictionary.
 */

/**
 * 官方词汇表。
 * English summary: official verbs and the shared parameter dictionary.
 */
object OfficialVocabulary {

    /** JUCS 规范版本，出现在每次 JSON 输出与 `--schema` 文档中。 */
    const val VERSION: String = "1.0"

    /**
     * 官方动词 → 中文语义。
     * 为什么用有序 Map：帮助输出需要稳定顺序，且注册表治理按语义分组。
     * English: official verb table with stable iteration order.
     */
    val VERBS: Map<String, String> = linkedMapOf(
        "list" to "返回集合",
        "get" to "按唯一标识读取单项",
        "search" to "按条件检索",
        "create" to "创建资源",
        "update" to "修改已有资源",
        "delete" to "删除资源（必须声明可恢复性）",
        "start" to "启动资源",
        "stop" to "停止资源",
        "restart" to "重启资源",
        "status" to "查询运行状态",
        "install" to "安装软件或可安装资源",
        "uninstall" to "卸载软件或可安装资源",
        "run" to "执行任务（同步或异步语义必须声明）",
    )

    /**
     * 通用参数：由运行时对**所有**命令隐式提供，命令 Schema 不得重复声明。
     * English: runtime-provided parameters, implicitly accepted by every command.
     */
    val IMPLICIT_PARAMS: Map<String, ParamSpec> = linkedMapOf(
        "output" to ParamSpec(
            name = "output",
            kind = ParamKind.ENUM,
            summaryZh = "输出格式：table（人读）、json（单个 JSON 对象）、ndjson（每行一个对象）",
            summaryEn = "Output format: table, json or ndjson.",
            enumValues = listOf("table", "json", "ndjson"),
            defaultValue = "table",
        ),
        "verbose" to ParamSpec(
            name = "verbose",
            kind = ParamKind.BOOL,
            summaryZh = "输出更多诊断信息到 stderr（不影响数据语义）",
            summaryEn = "Emit more diagnostics on stderr.",
            defaultValue = false,
        ),
        "quiet" to ParamSpec(
            name = "quiet",
            kind = ParamKind.BOOL,
            summaryZh = "抑制非必要诊断信息（不影响数据语义）",
            summaryEn = "Suppress non-essential diagnostics.",
            defaultValue = false,
        ),
    )

    /**
     * 保留的只读元命令参数：必须在执行业务动作之前处理，且绝不触发副作用。
     * English: read-only meta parameters handled before any business action.
     */
    val META_PARAMS: Set<String> = linkedSetOf("help", "version", "schema")

    /**
     * 全生态参数字典：参数名 → 固定契约。
     * 不变量：这里出现的参数，命令声明时必须使用完全相同的 kind 与枚举取值；
     * 命令不得把 `--limit` 声明成字符串，也不得把 `--output` 的取值改成别的集合。
     * English: ecosystem parameter dictionary with fixed kinds.
     */
    val PARAMS: Map<String, ParamSpec> = linkedMapOf(
        "name" to ParamSpec("name", ParamKind.STRING, "资源名称", "Resource name."),
        "id" to ParamSpec("id", ParamKind.STRING, "资源的稳定标识", "Stable resource identifier."),
        "path" to ParamSpec("path", ParamKind.PATH, "文件系统路径", "Filesystem path."),
        "all" to ParamSpec("all", ParamKind.BOOL, "包含被默认排除的条目（例如隐藏项）", "Include entries excluded by default."),
        "filter" to ParamSpec("filter", ParamKind.STRING, "过滤条件表达式", "Filter expression."),
        "sort" to ParamSpec("sort", ParamKind.STRING, "排序字段名（具体取值由命令定义）", "Sort field name (domain defined by the command)."),
        "order" to ParamSpec(
            "order", ParamKind.ENUM, "排序方向", "Sort direction.",
            enumValues = listOf("asc", "desc"), defaultValue = "asc",
        ),
        "limit" to ParamSpec("limit", ParamKind.INT, "返回条目数量上限（0 表示不限制）", "Maximum number of entries (0 means unlimited)."),
        "destination" to ParamSpec("destination", ParamKind.PATH, "输出目标路径", "Destination path."),
        "timeout" to ParamSpec("timeout", ParamKind.DURATION, "超时时长，必须带单位（如 30s）", "Timeout with an explicit unit, e.g. 30s."),
        "dry-run" to ParamSpec("dry-run", ParamKind.BOOL, "只生成可验证的执行预览，不提交变更", "Produce a verifiable plan without committing changes."),
        "force" to ParamSpec("force", ParamKind.BOOL, "允许契约中声明的保护例外（不能绕过授权）", "Allow declared safety exceptions (never bypasses authorization)."),
    )

    /**
     * 判定动词是否在官方表内。
     * English: true when the verb is part of the official table.
     */
    fun isOfficialVerb(verb: String): Boolean = VERBS.containsKey(verb)

    /**
     * 取官方参数的固定契约；不是官方参数时返回 null。
     * English: the official contract for a parameter name, or null.
     */
    fun officialParam(name: String): ParamSpec? = PARAMS[name]

    /**
     * 判定参数名是否由运行时隐式提供。
     * English: true when the parameter is provided implicitly by the runtime.
     */
    fun isImplicitParam(name: String): Boolean = IMPLICIT_PARAMS.containsKey(name)
}
