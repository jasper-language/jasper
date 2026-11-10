package jasper.ast

/**
 * 基本类型名的唯一事实来源（Q149，LANG-PRIMITIVE-NAMES-01）。
 *
 * 做什么：把文法 `primitiveType` 接受的全部写法归一成规范名，并区分「当前 JVM 后端已实现」与
 *   「规范已列出、后端尚未实现」两类。AST 构造时归一，此后检查器、翻译器只见规范名。
 * 为什么集中：修复前检查器、TypeMapper、平台描述符表各有一张名字表，互相不一致——`int8`
 *   过了检查器却在运行时 NoClassDefFoundError，`float` 与 `float32` 被当成不同类型。
 * 别名（docs/03 §3.2）：`int` → `int32`，`uint` → `uint32`，`float` → `float32`。
 * 已知与规范的差异（另入队，不在此改）：规范定 `byte` 为无符号 `uint8`、`char` 为 32 位
 *   Unicode scalar（`char32`），当前后端把 `byte` 当有符号 8 位、`char` 当 UTF-16 码元。
 */
object PrimitiveTypes {
    private val aliases = mapOf("int" to "int32", "uint" to "uint32", "float" to "float32")

    /** 当前 JVM 后端已实现的规范名。 */
    val supported: Set<String> = setOf("int8", "int16", "int32", "int64", "float32", "float64", "bool", "byte", "char")

    /** 规范已列出、当前后端未实现的名字：用了就报诊断，不能静默映射成别的宽度。 */
    val unsupported: Set<String> = setOf(
        "int128", "uint8", "uint16", "uint32", "uint64", "uint128", "char8", "char16", "char32", "float128"
    )

    /** 文法写法 → 规范名（大小写按文法关键字，原样小写处理）。 */
    fun canonical(name: String): String = name.lowercase().let { aliases[it] ?: it }
}
