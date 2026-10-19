package jucs.schema

/*
 * ============================================================================
 * ParamSpec.kt —— 参数契约数据模型（schema 层）
 * ============================================================================
 *
 * 职责：
 *   描述"一个命令接受哪些参数、每个参数是什么类型、默认值、是否必填、是否可重复"。
 *   解析器、校验器、帮助生成器、JSON Schema 生成器全部从这里派生，不存在第二份定义。
 *
 * 边界：
 *   纯数据 + 自校验；不负责解析 argv，也不执行命令。
 *
 * English summary:
 *   Immutable parameter contract shared by the parser, validator, help and
 *   JSON-Schema generators.
 */

/**
 * 参数类型。
 * 为什么是封闭枚举：参数类型决定解析与校验行为，新增类型必须同时补齐解析、
 * 校验、帮助与 JSON Schema 四处，封闭集合能让遗漏在编译期暴露。
 * English: closed set of JUCS parameter kinds.
 */
enum class ParamKind {
    /** 任意 UTF-8 字符串。 */
    STRING,

    /** 非空路径字符串（不做文件系统存在性检查，那是命令处理器的职责）。 */
    PATH,

    /** 布尔开关：出现 `--flag` 为真，出现 `--no-flag` 为假。 */
    BOOL,

    /** 64 位十进制整数，允许负号。 */
    INT,

    /** 带单位时长，例如 `30s`、`500ms`、`2m`、`1h`。单位为必填。 */
    DURATION,

    /** 闭集字符串，合法取值由 [ParamSpec.enumValues] 给出。 */
    ENUM,
}

/**
 * 单个参数的契约。
 *
 * 不变量（由 [SchemaValidation] 强制）：
 * - [name] 是合法 kebab-case；
 * - [kind] 为 [ParamKind.ENUM] 时 [enumValues] 非空且元素唯一、均非空；
 * - [kind] 不为 ENUM 时 [enumValues] 必须为空；
 * - 可重复参数不能声明默认值（默认值与"出现多次"语义冲突）。
 *
 * English summary:
 *   One parameter contract; see the invariant list above.
 */
data class ParamSpec(
    /** 参数名，不含 `--` 前缀，例如 `path`、`all`、`dry-run`。 */
    val name: String,
    /** 参数类型。 */
    val kind: ParamKind,
    /** 中文说明，进入 `--help` 与 Schema 描述。 */
    val summaryZh: String,
    /** 英文一行摘要，供跨语言工具展示；不参与业务逻辑。 */
    val summaryEn: String,
    /** 是否必填。布尔参数声明为必填意味着调用方必须显式给出真或假。 */
    val required: Boolean = false,
    /** 是否可重复出现并保留出现顺序。 */
    val repeatable: Boolean = false,
    /** 默认值；类型必须与 [kind] 匹配，未给出时为 null。 */
    val defaultValue: Any? = null,
    /** [ParamKind.ENUM] 的合法取值，按声明顺序原样输出到帮助与 Schema。 */
    val enumValues: List<String> = emptyList(),
    /** 弃用说明；非 null 时该参数仍可用但会在 `--help` 中标记。 */
    val deprecated: String? = null,
)
