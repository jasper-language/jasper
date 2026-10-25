// S4-C 编译期分段规划：只处理直线函数体，不发射字节码、不改变语言规则。
package jasper.translator

import jasper.ast.*

/** 一个挂起边界；liveLocals 是恢复后仍可能读取、且挂起前已经存在的局部绑定。 */
data class LinearSuspensionPoint(
    val statementIndex: Int,
    val expression: JasUnaryOp,
    val resultBinding: String?,
    val liveLocals: Set<String>,
    val resultType: JasType? = expression.awaitResultType
)

/** 分段结果或首个不支持的节点；失败时不返回部分计划，以免调用者漏掉控制流。 */
data class LinearSuspensionPlan(
    val points: List<LinearSuspensionPoint>,
    val unsupported: JasNode? = null
)

/**
 * 为 continuation 字段布局准备保守活跃局部集合。仅接受顶层变量声明、表达式语句和
 * return；挂起必须直接位于变量初始化或 return。真正挂起点由检查器传入谓词，不能
 * 把兼容模式的 await 普通值误当挂起。暂不处理分支、循环、lambda、清理或嵌套 await。
 * 本规划器尚未接入代码生成，unsupported 不等于永久语言限制。
 */
object LinearSuspensionPlanner {
    /** 规划直线体；先完整验证支持范围，再收集跨挂起活跃绑定，保持源码节点身份与位置。 */
    fun plan(
        function: JasFunction,
        isSuspending: (JasUnaryOp) -> Boolean = { it.awaitResultType != null }
    ): LinearSuspensionPlan {
        val statements = function.body?.statements ?: return LinearSuspensionPlan(emptyList())
        val references = mutableListOf<Set<String>>()
        val awaits = mutableListOf<Pair<JasUnaryOp, String?>?>()
        for (statement in statements) {
            val expression = when (statement) {
                is JasVariableStatement -> statement.initializer
                is JasExpressionStatement -> statement.expression
                is JasReturn -> statement.value
                else -> return LinearSuspensionPlan(emptyList(), statement)
            }
            val direct = (expression as? JasUnaryOp)?.takeIf { it.op == "await" && isSuspending(it) }
            // 只有声明初始化和 return 有明确的结果接收位置；语句型挂起留待后续。
            if (direct != null && statement is JasExpressionStatement) {
                return LinearSuspensionPlan(emptyList(), statement)
            }
            val names = linkedSetOf<String>()
            val unsupported = collect(expression, names, direct, isSuspending)
            if (unsupported != null) return LinearSuspensionPlan(emptyList(), unsupported)
            references += names
            awaits += direct?.let { it to (statement as? JasVariableStatement)?.name }
        }
        val available = function.parameters.mapTo(linkedSetOf()) { it.name }
        val points = mutableListOf<LinearSuspensionPoint>()
        for ((index, statement) in statements.withIndex()) {
            val boundary = awaits[index]
            if (boundary != null) {
                val usedAfter = references.drop(index + 1).flatten().toSet()
                points += LinearSuspensionPoint(index, boundary.first, boundary.second,
                    available.filterTo(linkedSetOf()) { it in usedAfter })
            }
            if (statement is JasVariableStatement) available += statement.name
        }
        return LinearSuspensionPlan(points)
    }

    /** 收集本片支持的纯表达式引用；未知节点明确返回，禁止默默漏掉其中的挂起或局部。 */
    private fun collect(
        expression: JasExpression?, names: MutableSet<String>, direct: JasUnaryOp?,
        isSuspending: (JasUnaryOp) -> Boolean
    ): JasNode? {
        val children: List<JasExpression?> = when (expression) {
            null -> emptyList()
            is JasIdentifier -> { names += expression.name; emptyList() }
            is JasIntLiteral, is JasFloatLiteral, is JasStringLiteral,
            is JasBoolLiteral, is JasCharLiteral, JasNullLiteral -> emptyList()
            is JasUnaryOp -> {
                if (expression.op == "await" && isSuspending(expression) && expression !== direct) return expression
                listOf(expression.operand)
            }
            is JasBinaryOp -> listOf(expression.left, expression.right)
            is JasCall -> listOf(expression.target) + expression.args
            is JasPropertyAccess -> listOf(expression.target)
            is JasArrayAccess -> listOf(expression.target, expression.index)
            is JasNew -> expression.args
            else -> return expression
        }
        for (child in children) {
            val unsupported = collect(child, names, null, isSuspending)
            if (unsupported != null) return unsupported
        }
        return null
    }
}
