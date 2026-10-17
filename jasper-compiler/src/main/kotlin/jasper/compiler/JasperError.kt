package jasper.compiler

/*
 * ============================================================================
 * JasperError.kt —— 编译器诊断数据模型（compiler 编排层，逻辑归 compiler 层）
 * ============================================================================
 *
 * 职责：
 *   定义整个 Jasper 编译器共享的「诊断数据模型」：错误分类枚举、解析期错误
 *   （ParseError / ParseResult）、贯穿各阶段的密封错误层次（CompileError），
 *   以及把一批错误打包成异常抛出的 CompileException 和统一格式化函数
 *   formatError。
 *
 * 所属层次：
 *   compiler 编排层。本文件只做「数据 + 格式化」，不依赖 parser / checker /
 *   translator，因此位于 compiler 层最底部，可被同层任意文件安全引用。
 *
 * 与谁协作：
 *   - JasperErrorListener：把 ANTLR 的 syntaxError 事件转成 ParseError；
 *   - JasperCompiler：把各阶段的错误归一到 CompileError 各子类，聚合后抛
 *     CompileException，并在公开入口映射成 CompileResult.Error；
 *   - checker / annotations 各自的错误类型（TypeError / SymbolError /
 *     AnnotationError）由 JasperCompiler 翻译成本文件的子类。
 *
 * 对外契约：
 *   - CompileError 是 sealed 层次：新增一类诊断必须显式新增子类，使用方的
 *     when 表达式会得到编译期穷尽性检查，避免漏处理（隔离的变化点）。
 *   - 每条错误都携带 message / category / line / column；line <= 0 表示无位置。
 *
 * 扩展点 / 已知限制：
 *   - 新增错误类别 = 新增 ErrorCategory 枚举值 + 新增 CompileError 子类，
 *     并同步其 category 赋值；
 *   - formatError 对 category 直接输出枚举名（英文），未做本地化；
 *   - 位置信息为 0 的错误（如 Internal）不打印行列。
 * ----------------------------------------------------------------------------
 */

/**
 * 诊断分类枚举：给下游（IDE、日志、测试断言）一个稳定的粗粒度标签。
 * 为什么用 enum 而不是 sealed/字符串：分类集合需要封闭、可穷尽、可直接比较，
 * 且不携带额外数据；携带数据的部分放在 [CompileError] 子类里。
 * 变更点：任何阶段引入新错误种类时，在此追加一个值并同步对应子类的 category。
 */
enum class ErrorCategory { SYNTAX, SEMANTIC, TYPE, SYMBOL, ANNOTATION, INTERNAL }

/**
 * 解析期诊断记录：面向 ANTLR 错误监听器的最小数据结构。
 * 与 [CompileError] 的区别：本类只是「原始事件 + 出错行上下文」，尚未进入密封
 * 错误层次；JasperCompiler 会在 parseSourceStrict 中把它转成 [CompileError.Syntax]。
 * 前置条件：line >= 1、column >= 0（1-based 行号、0-based 列号，与 ANTLR 约定一致）。
 * sourceLine 用于格式化时回显出错那一行源码，可能为 null（行号越界或输入不是
 * CharStream，例如词法阶段的异常）。
 */
data class ParseError(
    val line: Int,
    val column: Int,
    val message: String,
    val sourceLine: String? = null,
    val category: ErrorCategory = ErrorCategory.SYNTAX
)

/**
 * 通用解析结果包装：result 为 null 或 errors 非空都表示解析未成功。
 * 为什么同时保留两者：错误恢复后仍可能构造出局部 AST，调用方应据 errors 判断
 * 结果是否可信，而不是只看 result 是否为空。
 * 已知限制：当前主流水线不使用本类（JasperCompiler 内部走 parseSourceStrict 的
 * Pair 返回），它主要作为对外/测试 API 预留；扩展点在于将来若把解析抽成公共
 * 入口，可统一改用本类型。
 */
data class ParseResult<T>(val result: T?, val errors: List<ParseError> = emptyList())

/**
 * 编译器诊断的密封根类型：把「语法 / 符号 / 类型 / 注解 / 内部」五类错误统一成
 * 同一族，方便在一个 List 里聚合、统一格式化，再抛 [CompileException]。
 * 为什么用 sealed：错误种类封闭，使用者可用 when 穷尽处理；新增子类时所有需要
 * 更新的分支会在编译期暴露出来（隔离的变化点）。
 * 契约：message 必填；line <= 0 表示该错误没有可用的源码位置。
 */
sealed class CompileError {
    abstract val message: String
    abstract val category: ErrorCategory
    abstract val line: Int
    abstract val column: Int

    /**
     * 语法错误：来自 ANTLR 解析失败或 AST 构造失败。
     * 由 parseSourceStrict 依据错误监听器事件生成，line/column 直接取自 ANTLR，
     * 因此是原始报错位置（未做二次校正）。
     */
    data class Syntax(
        override val message: String,
        override val line: Int = 0,
        override val column: Int = 0
    ) : CompileError() {
        override val category: ErrorCategory = ErrorCategory.SYNTAX
    }

    /**
     * 符号错误：声明收集阶段的名称问题（重复定义、签名冲突等）。
     * name 记录相关符号名，供诊断文案与测试断言使用；无关联名称时为空串。
     */
    data class Symbol(
        override val message: String,
        val name: String = "",
        override val line: Int = 0,
        override val column: Int = 0
    ) : CompileError() {
        override val category: ErrorCategory = ErrorCategory.SYMBOL
    }

    /**
     * 类型错误：类型检查阶段产生。
     * 边界：当类型错误未绑定到具体 AST 节点时，line/column 会是 0，
     * 格式化时只输出类别与消息、不输出位置。
     */
    data class Type(
        override val message: String,
        override val line: Int = 0,
        override val column: Int = 0
    ) : CompileError() {
        override val category: ErrorCategory = ErrorCategory.TYPE
    }

    /**
     * 注解处理错误：例如 @Mapper 的目标属性无法映射。
     * 由 annotations 层的 AnnotationError 转换而来，位置信息可能为 0。
     */
    data class Annotation(
        override val message: String,
        override val line: Int = 0,
        override val column: Int = 0
    ) : CompileError() {
        override val category: ErrorCategory = ErrorCategory.ANNOTATION
    }

    /**
     * 内部错误：兜底的异常包装，表示「编译器自身出了意料之外的问题」。
     * 只要 JasperCompiler 捕获到非 [CompileException] 的异常，就用本类兜住，
     * 以保证对外始终返回结构化错误列表而不是把栈直接抛给调用方。
     * cause 保留原始异常，便于调试；line/column 恒为 0。
     */
    data class Internal(
        override val message: String,
        val cause: Throwable? = null
    ) : CompileError() {
        override val category: ErrorCategory = ErrorCategory.INTERNAL
        override val line: Int = 0
        override val column: Int = 0
    }
}

/**
 * 把一批 [CompileError] 打包成异常，用于在流水线内部「快速失败」、在边界统一处理。
 * 为什么用异常：各阶段函数可以保持干净的正常返回签名，错误用抛出中断；JasperCompiler
 * 的公开入口在 catch 中把它转成 [JasperCompiler.CompileResult.Error]。
 * message 是整批错误的格式化拼接（含 [类别] 与位置），便于日志与测试直接比对。
 * 前置条件：errors 非空（聚合阶段只在确实有错时抛出）。
 */
class CompileException(val errors: List<CompileError>) : RuntimeException(
    errors.joinToString("\n") { formatError(it) }
) {
    /** 单条错误的便捷构造：仅用于「只有一个错误」的场景。 */
    constructor(error: CompileError) : this(listOf(error))
}

/**
 * 把单条诊断格式化成一行：`  [类别] (行:列) 消息`；无位置（line <= 0）时省略位置括号。
 * 用 internal 而非 public：这是 compiler 内部统一的输出口径，不构成对外 API。
 */
internal fun formatError(err: CompileError): String {
    val loc = if (err.line > 0) " (${err.line}:${err.column})" else ""
    return "  [${err.category.name}]$loc ${err.message}"
}
