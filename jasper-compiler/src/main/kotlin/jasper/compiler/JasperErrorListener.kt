package jasper.compiler

/*
 * ============================================================================
 * JasperErrorListener.kt —— ANTLR 语法错误监听器与诊断格式化（compiler 层）
 * ============================================================================
 *
 * 职责：
 *   注册到 ANTLR 生成的 [org.antlr.v4.runtime.Parser] 上，接收底层报出的
 *   syntaxError 事件，转成数据类 ParseError 并缓存；同时提供把错误渲染成
 *   「带源码行与插入符 ^」友好文本的工具方法。
 *
 * 所属层次：
 *   compiler 编排层（诊断/错误恢复子模块），依赖 ANTLR runtime 与本包的
 *   JasperError；被 JasperCompiler 在 createParser 中挂载。
 *
 * 与谁协作：
 *   - JasperCompiler.createParser：removeErrorListeners 后 addErrorListener(本类)，
 *     并把 parser.errorHandler 设为 JasperErrorStrategy；
 *   - JasperCompiler.parseSourceStrict：读取本类收集的 errors，转成 CompileError.Syntax。
 *
 * 对外契约：
 *   - errors 按 ANTLR 触发顺序累积，只增不减；每次解析应使用新的监听器实例
 *     （否则会跨次解析串味）；
 *   - syntaxError 永不抛出——错误恢复依赖它保持安静地记录。
 *
 * 扩展点 / 已知限制：
 *   - categorizeError 是**基于消息文本的启发式**归类，只覆盖英文消息里的少量
 *     关键词；ANTLR 默认消息一旦改文案，归类就会退化到默认的 SYNTAX，因此它
 *     只用于展示，不应作为可靠的语义判据；
 *   - column 采用 ANTLR 的 0-based 列号，输出时只给用户看，不参与定位逻辑。
 * ----------------------------------------------------------------------------
 */

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token

/**
 * ANTLR 语法错误监听器：把解析器报出的语法错误收集为 [ParseError] 列表。
 * 什么时候用：只在 JasperCompiler.createParser 构造解析器时创建并挂载；
 * 每次解析都需要一个全新实例，避免错误列表跨次累积。
 * 失败模式：本类不抛异常；即使源码行取不到（越界/非 CharStream），也只是
 * sourceLine 为 null，事件仍会被记录。
 */
class JasperErrorListener : BaseErrorListener() {
    /** 按触发顺序累积的原始语法错误；调用方解析结束后读取。 */
    val errors = mutableListOf<ParseError>()

    /**
     * ANTLR 回调：每检测到一个语法错误就调用一次。
     * 前置条件：由 ANTLR runtime 调用，recognizer 通常就是当前 Parser。
     * 边界：取源码行时对行号做越界保护；任何取行异常都被吞掉并记 null，
     * 保证错误记录本身绝不失败（否则会打断错误恢复流程）。
     */
    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?
    ) {
        // 尽可能取回出错那一行的原文：ANTLR 的 inputStream 是 CharStream 时可按
        // 1-based 行号切出；否则（含词法阶段的异常）放弃源码行，只留位置与消息。
        val sourceLine = try {
            val input = recognizer?.inputStream
            if (input is org.antlr.v4.runtime.CharStream) {
                val inputStr = input.toString()
                val lines = inputStr.lines()
                if (line > 0 && line <= lines.size) {
                    lines[line - 1]
                } else null
            } else null
        } catch (ex: Exception) { null }

        val category = categorizeError(msg)

        errors.add(ParseError(
            line = line,
            column = charPositionInLine,
            message = msg ?: "Unknown syntax error",
            sourceLine = sourceLine,
            category = category
        ))
    }

    /**
     * 依据 ANTLR 错误消息文本做粗粒度归类。
     * 为什么这么做：ANTLR 的 syntaxError 只给字符串，没有结构化错误种类；这里
     * 用关键词猜测，属于**启发式**，仅用于展示与分组。
     * 局限：消息文案一变、或换语言/换版本，匹配即失效并退回默认 SYNTAX；
     * 因此消费方不应把本结果当作可靠的语义分类。
     */
    private fun categorizeError(msg: String?): ErrorCategory {
        val message = msg ?: return ErrorCategory.SYNTAX
        return when {
            // 「type ... mismatch / expect」这类组合更接近类型错误，先于单关键词判断，
            // 避免被后面的门类抢走。
            message.contains("type", ignoreCase = true) &&
                (message.contains("mismatch", ignoreCase = true) ||
                 message.contains("expect", ignoreCase = true)) -> ErrorCategory.TYPE
            message.contains("type mismat", ignoreCase = true) ||
            message.contains("incompatible type", ignoreCase = true) ||
            message.contains("cannot be applied", ignoreCase = true) ||
            message.contains("not a function", ignoreCase = true) -> ErrorCategory.TYPE
            message.contains("semantic", ignoreCase = true) ||
            message.contains("undefined", ignoreCase = true) ||
            message.contains("already defined", ignoreCase = true) ||
            message.contains("unresolved", ignoreCase = true) -> ErrorCategory.SEMANTIC
            // 兜底：绝大多数 ANTLR 原生消息都是语法层面，默认归 SYNTAX。
            else -> ErrorCategory.SYNTAX
        }
    }

    /**
     * 把一条错误渲染成多行友好文本：标题行 + 源码行 + 指向列位置的插入符。
     * 什么时候用：CLI 或测试想显示带上下文的错误时。
     * 为什么带 (line, col, msg) 参数：允许渲染未记录在本实例里的临时错误；
     * 若能在缓存中按 (line, col) 找到记录，则复用其分类与源码行。
     * 边界：源码行缺失时只输出标题；列号会被夹到不超过源码行长度，避免 ^ 越界。
     */
    fun formatErrorMessage(line: Int, col: Int, msg: String): String {
        // 优先复用已记录错误的分类/源码行，未命中再现场归类（0-based 列参与匹配）。
        val error = errors.find { it.line == line && it.column == col }
        val category = error?.category ?: categorizeError(msg)
        val sourceLine = error?.sourceLine
        val sb = StringBuilder()
        sb.append("Error at line $line, column $col: $category - $msg")
        if (sourceLine != null) {
            sb.appendLine()
            sb.append("  $line | ${sourceLine.trimEnd()}")
            sb.appendLine()
            // 插入符前置空格数受源码行长度约束，防止 col 越界导致对齐跑偏。
            sb.append("  $line | ${" ".repeat(col.coerceAtMost(sourceLine.length))}^")
        }
        return sb.toString()
    }

    /**
     * 渲染本实例收集到的全部错误，块间以空行分隔。
     * 前置条件：无（errors 为空时返回空串）。
     */
    fun formatErrors(): String {
        return errors.joinToString("\n\n") { formatErrorMessage(it.line, it.column, it.message) }
    }
}
