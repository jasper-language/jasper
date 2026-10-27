// ============================================================================
// SuspendingAwaitScanner.kt —— 编译器内部的真实挂起点只读扫描器
// ============================================================================
// 职责：
//   从 AST 中收集所有已经被类型检查器确认的真挂起 await（awaitResultType != null）。
//   用于协程降级阶段的两道护栏：定位不支持函数的挂起点，以及确认降级后源码里不再
//   残留任何未处理的真实 await。
//
// 所属层次：
//   compiler 编排层的辅助分析器，只依赖 ast。它不修改 AST、不做类型推断、不决定
//   支持范围；支持范围由 LinearCoroutineEmitter 负责。
//
// 为什么需要它：
//   直接 await 可能出现在函数、构造器、字段初始化器或 lambda 中，而挂起能力分析
//   只遍历函数声明。若只依赖函数级效果表，非函数位置的 await 会被静默透传给后端。
//   本扫描器宁可多报，也不让真实挂起丢失诊断。
//
// 边界：
//   - 只识别检查器已回填 awaitResultType 的 await；普通值 await 保持兼容，不报告；
//   - 不解析符号、不判断上下文可挂起性，因此扫描结果只作诊断/安全网，不冒充证明；
//   - AST 新增节点类型时必须同步扩展 when 分支，否则该分支内部不会被扫描。
// ============================================================================

package jasper.compiler

import jasper.ast.*

/** 只读收集真实挂起点；调用方按节点身份去重，不依赖源码位置。 */
internal object SuspendingAwaitScanner {
    /**
     * 做什么：返回整个源文件中所有真实挂起 await，按 AST 深度优先顺序排列。
     * 什么时候用：协程降级前后做残留检查。
     * 前置条件：源文件已完成类型检查并回填 awaitResultType。
     * 失败模式：不抛异常；未知节点类型不被猜测展开，属于已知覆盖边界。
     */
    fun scan(source: JasSourceFile): List<JasUnaryOp> {
        val found = mutableListOf<JasUnaryOp>()
        source.declarations.forEach { scanDeclaration(it, found) }
        return found
    }

    /**
     * 做什么：只扫描一个函数的形参默认值与函数体。
     * 什么时候用：降级阶段已判定某函数不支持，把它的挂起点登记为「已诊断」，
     *   避免安全网对同一节点重复报错。
     * 前置条件：函数属于当前已类型检查的 AST。
     * 失败模式：不抛异常；抽象/无体函数返回空列表。
     */
    fun scanFunction(function: JasFunction): List<JasUnaryOp> {
        val found = mutableListOf<JasUnaryOp>()
        function.parameters.forEach { scanExpression(it.defaultValue, found) }
        scanBlock(function.body, found)
        return found
    }

    /** 收集函数/类成员等声明中的表达式体。 */
    private fun scanDeclaration(declaration: JasDeclaration, found: MutableList<JasUnaryOp>) {
        when (declaration) {
            is JasFunction -> {
                declaration.parameters.forEach { scanExpression(it.defaultValue, found) }
                scanBlock(declaration.body, found)
            }
            is JasClass -> {
                declaration.members.forEach { scanDeclaration(it, found) }
                declaration.constructors.forEach { scanDeclaration(it, found) }
            }
            is JasInterface -> declaration.members.forEach { scanDeclaration(it, found) }
            is JasEnum -> {
                declaration.constants.forEach { constant -> constant.args.forEach { scanExpression(it, found) } }
                declaration.members.forEach { scanDeclaration(it, found) }
            }
            is JasConstructor -> {
                declaration.parameters.forEach { scanExpression(it.defaultValue, found) }
                declaration.delegateCall?.args?.forEach { scanExpression(it, found) }
                scanBlock(declaration.body, found)
            }
            is JasProperty -> {
                scanExpression(declaration.initializer, found)
                scanBlock(declaration.getter?.body, found)
                scanBlock(declaration.setter?.body, found)
            }
            is JasAnnotationType -> declaration.members.forEach { member ->
                scanExpression(member.defaultValue, found)
            }
            else -> Unit
        }
    }

    /** 收集块内语句中的挂起点。 */
    private fun scanBlock(block: JasBlock?, found: MutableList<JasUnaryOp>) {
        block?.statements?.forEach { scanStatement(it, found) }
    }

    /** 按语句结构递归；所有可能包含表达式的语句都要显式处理。 */
    private fun scanStatement(statement: JasStatement, found: MutableList<JasUnaryOp>) {
        when (statement) {
            is JasBlock -> scanBlock(statement, found)
            is JasVariableStatement -> scanExpression(statement.initializer, found)
            is JasExpressionStatement -> scanExpression(statement.expression, found)
            is JasIf -> {
                scanExpression(statement.condition, found)
                scanBlock(statement.thenBody, found)
                scanBlock(statement.elseBody, found)
            }
            is JasWhile -> {
                scanExpression(statement.condition, found)
                scanBlock(statement.body, found)
                scanSuffix(statement.suffix, found)
            }
            is JasDoWhile -> {
                scanBlock(statement.body, found)
                scanExpression(statement.condition, found)
            }
            is JasForStatement -> {
                statement.init?.let { scanStatement(it, found) }
                scanExpression(statement.condition, found)
                scanExpression(statement.update, found)
                scanBlock(statement.body, found)
                scanSuffix(statement.suffix, found)
            }
            is JasForInStatement -> {
                scanExpression(statement.iterable, found)
                scanBlock(statement.body, found)
                scanBlock(statement.thenBody, found)
                scanBlock(statement.elseBody, found)
                scanSuffix(statement.suffix, found)
            }
            is JasSwitch -> {
                scanExpression(statement.expression, found)
                statement.cases.forEach { case ->
                    case.values.forEach { scanExpression(it, found) }
                    scanBlock(case.body, found)
                }
            }
            is JasReturn -> scanExpression(statement.value, found)
            is JasBreakStatement, is JasContinueStatement -> Unit
            is JasThrow -> scanExpression(statement.expression, found)
            is JasTry -> {
                scanBlock(statement.body, found)
                statement.catches.forEach { clause ->
                    scanExpression(clause.parameter.defaultValue, found)
                    scanBlock(clause.body, found)
                }
                scanBlock(statement.finallyBody, found)
            }
            is JasDefer -> scanBlock(statement.body, found)
            is JasLock -> {
                scanExpression(statement.expression, found)
                scanBlock(statement.body, found)
            }
            is JasAssert -> {
                scanExpression(statement.condition, found)
                scanExpression(statement.message, found)
            }
            is JasYield -> scanExpression(statement.expression, found)
            is JasLabeledStatement -> scanStatement(statement.statement, found)
            is JasStatementIncrement -> scanExpression(statement.increment, found)
            is JasDestructuringDeclaration -> scanExpression(statement.initializer, found)
            is JasMatch -> {
                scanExpression(statement.expression, found)
                statement.cases.forEach { case ->
                    case.guard?.let { scanExpression(it, found) }
                    scanBlock(case.body, found)
                }
            }
            else -> Unit
        }
    }

    /** 循环后置块与普通块同构。 */
    private fun scanSuffix(suffix: LoopSuffix?, found: MutableList<JasUnaryOp>) {
        suffix?.onComplete?.let { scanBlock(it, found) }
        suffix?.onBreak?.let { scanBlock(it, found) }
        suffix?.onZeroRuns?.let { scanBlock(it, found) }
    }

    /** 所有表达式节点：遇到真实 await 先登记，再继续扫描操作数/子表达式。 */
    private fun scanExpression(expression: JasExpression?, found: MutableList<JasUnaryOp>) {
        when (expression) {
            null -> Unit
            is JasUnaryOp -> {
                if (expression.op == "await" && expression.awaitResultType != null) found += expression
                scanExpression(expression.operand, found)
            }
            is JasBinaryOp -> {
                scanExpression(expression.left, found)
                scanExpression(expression.right, found)
            }
            is JasCall -> {
                scanExpression(expression.target, found)
                expression.args.forEach { scanExpression(it, found) }
            }
            is JasPropertyAccess -> scanExpression(expression.target, found)
            is JasArrayAccess -> {
                scanExpression(expression.target, found)
                scanExpression(expression.index, found)
            }
            is JasNew -> expression.args.forEach { scanExpression(it, found) }
            is JasArrayCreation -> {
                expression.dims.forEach { scanExpression(it, found) }
                when (val init = expression.init) {
                    is JasArrayInitValues -> init.values.forEach { scanExpression(it, found) }
                    is JasArrayInitLength -> scanExpression(init.length, found)
                    null -> Unit
                }
            }
            is JasArrayLiteral -> expression.values.forEach { scanExpression(it, found) }
            is JasLambdaExpr -> scanBlock(expression.body, found)
            is JasMethodReference -> scanExpression(expression.target, found)
            is JasInstanceOfExpr -> scanExpression(expression.expression, found)
            is JasCastExpr -> scanExpression(expression.expression, found)
            is JasNullCoalescing -> {
                scanExpression(expression.left, found)
                scanExpression(expression.right, found)
            }
            is JasTernaryExpr -> {
                scanExpression(expression.condition, found)
                scanExpression(expression.thenExpr, found)
                scanExpression(expression.elseExpr, found)
            }
            is JasTupleExpr -> expression.elements.forEach { scanExpression(it, found) }
            is JasStringTemplate -> expression.parts.forEach { part ->
                if (part is JasTemplateExpr) scanExpression(part.expr, found)
            }
            is JasDictLiteral -> expression.entries.forEach { entry ->
                scanExpression(entry.key, found)
                scanExpression(entry.value, found)
            }
            is JasAssignment -> {
                scanExpression(expression.target, found)
                scanExpression(expression.value, found)
            }
            is JasAssignmentIncrement -> {
                scanExpression(expression.target, found)
                scanExpression(expression.increment, found)
            }
            is JasPostfixIncDec -> scanExpression(expression.target, found)
            is JasAnnotationLiteral -> expression.annotation.arguments.forEach { argument ->
                when (argument) {
                    is JasAnnotationNamedArg -> scanExpression(argument.value, found)
                    is JasAnnotationPositionalArg -> scanExpression(argument.value, found)
                }
            }
            is JasIdentifier, is JasIntLiteral, is JasFloatLiteral, is JasCharLiteral,
            is JasStringLiteral, is JasBoolLiteral, JasNullLiteral -> Unit
            else -> Unit
        }
    }
}
