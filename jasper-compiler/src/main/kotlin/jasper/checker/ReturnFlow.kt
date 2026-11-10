package jasper.checker

import jasper.ast.*

/**
 * 「能否正常走完」分析（LANG-RETURN-PATHS-01）：非 void 函数的函数体不得正常走到末尾。
 *
 * 做什么：按语句结构保守地判断一条语句执行后能否落到其后继（Java「can complete normally」
 *   的子集）。return / throw 不能；条件恒为字面量 `true` 且没有跳出它的 break 的循环不能；
 *   其余按组合规则推导。
 * 什么时候用：[TypeChecker] 检查有函数体、声明了非 void 返回类型的函数时。
 * 为什么保守（宁可说「能走完」）：说「能」只会多要求一条 return；说「不能」而实际能走到末尾，
 *   后端就会在方法末尾生成没有返回值的返回指令（Q154 的 VerifyError）或静默返回垃圾值。
 *   因此只把字面量 `true` 当成恒真，不做常量折叠；switch 只在有 default、每个分支都不能走完、
 *   且没有跳出 switch 的 break 时才算不能走完。
 * 失败模式：纯函数，不抛异常。
 */
internal object ReturnFlow {

    /** 语句列表能否正常走完：任一语句不能走完，其后都不可达，整体不能走完。 */
    fun canComplete(statements: List<JasStatement>): Boolean = statements.all { canComplete(it, null) }

    private fun canComplete(stmt: JasStatement): Boolean = canComplete(stmt, null)

    private fun canComplete(stmt: JasStatement, label: String?): Boolean = when (stmt) {
        is JasReturn, is JasThrow -> false
        // break / continue 离开当前语句，但不是落到后继；循环是否因 break 走完由循环自己判断。
        is JasBreakStatement -> false
        is jasper.ast.JasContinueStatement -> false
        is JasBlock -> canComplete(stmt.statements)
        is JasIf -> stmt.elseBody == null || canComplete(stmt.thenBody) || canComplete(stmt.elseBody)
        is JasLabeledStatement -> canComplete(stmt.statement, stmt.label) || breaksTo(stmt.statement, stmt.label, nestedInBreakable = false)
        is JasWhile -> !isTrue(stmt.condition) || breaksOut(stmt.body, label)
        // 保守：条件不恒真就算能走完（不区分循环体是否每次都 return）。
        is JasDoWhile -> !isTrue(stmt.condition) || breaksOut(stmt.body, label)
        is JasForStatement -> (stmt.condition != null && !isTrue(stmt.condition)) || breaksOut(stmt.body, label)
        is JasForInStatement -> true
        is JasTry -> {
            val finallyCompletes = stmt.finallyBody?.let { canComplete(it) } ?: true
            finallyCompletes && (canComplete(stmt.body) || stmt.catches.any { canComplete(it.body) })
        }
        is JasSwitch -> {
            val hasDefault = stmt.cases.any { it.values.isEmpty() }
            !hasDefault || stmt.cases.any { canComplete(it.body) || breaksOut(it.body, label) }
        }
        is JasMatch -> {
            val exhaustive = stmt.cases.any { (it.pattern == null || it.pattern === jasper.ast.JasWildcardPattern) && it.guard == null }
            !exhaustive || stmt.cases.any { canComplete(it.body) || breaksOut(it.body, label) }
        }
        is JasLock -> canComplete(stmt.body)
        // defer 体在作用域结束时才执行，不影响当前位置之后的可达性。
        is JasDefer -> true
        else -> true
    }

    private fun isTrue(condition: JasExpression): Boolean = condition is JasBoolLiteral && condition.value

    /** [body] 里是否有跳出「本循环 / 本 switch」的 break：无标签且不在内层循环 / switch 里，或带本语句的标签。 */
    private fun breaksOut(body: JasStatement, label: String?): Boolean =
        breaksTo(body, label, nestedInBreakable = false)

    private fun breaksTo(stmt: JasStatement, label: String?, nestedInBreakable: Boolean): Boolean = when (stmt) {
        is JasBreakStatement -> if (stmt.label == null) !nestedInBreakable else stmt.label == label
        else -> children(stmt).any { (child, breakable) -> breaksTo(child, label, nestedInBreakable || breakable) }
    }

    /** 子语句，以及「进入该子语句时是否进入了新的无标签 break 目标」（只有循环体）。 */
    private fun children(stmt: JasStatement): List<Pair<JasStatement, Boolean>> = when (stmt) {
        is JasBlock -> stmt.statements.map { it to false }
        is JasIf -> listOfNotNull(stmt.thenBody to false, stmt.elseBody?.let { it to false })
        is JasLabeledStatement -> listOf(stmt.statement to false)
        is JasWhile -> listOf(stmt.body to true) + suffixBlocks(stmt.suffix)
        is JasDoWhile -> listOf(stmt.body to true)
        is JasForStatement -> listOf(stmt.body to true) + suffixBlocks(stmt.suffix)
        is JasForInStatement -> listOfNotNull(stmt.body to true, stmt.thenBody?.let { it to false }, stmt.elseBody?.let { it to false }) + suffixBlocks(stmt.suffix)
        is JasTry -> listOf(stmt.body to false) + stmt.catches.map { it.body to false } + listOfNotNull(stmt.finallyBody?.let { it to false })
        // switch 分支里的无标签 break 按「跳出外层」也计入：无论语言最终把它解释为跳出 switch 还是
        // 外层循环，都只会让分析偏向「能走完」，不会放过真正落到末尾的路径。
        is JasSwitch -> stmt.cases.map { it.body to false }
        is JasMatch -> stmt.cases.map { it.body to false }
        is JasLock -> listOf(stmt.body to false)
        is JasDefer -> listOf(stmt.body to false)
        else -> emptyList()
    }

    private fun suffixBlocks(suffix: jasper.ast.LoopSuffix?): List<Pair<JasStatement, Boolean>> =
        if (suffix == null) emptyList() else listOfNotNull(suffix.onComplete, suffix.onBreak, suffix.onZeroRuns).map { it to false }
}
