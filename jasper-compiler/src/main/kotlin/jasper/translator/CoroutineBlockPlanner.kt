// ============================================================================
// CoroutineBlockPlanner.kt —— 协程帧的前向基本块规划（S4-C 第十片）
// ============================================================================
// 职责：
//   把一个已通过类型检查的受限函数体切成「只向前跳转」的基本块序列：每个真实
//   await（局部初始化或对局部 `=` 赋值的整个右侧）结束当前块并开启一个恢复块；
//   if/else 结束当前块并开启两个分支块，分支未 return 的出口汇合到新块；return
//   结束一条路径。生成帧用 `__phase` 记录下一块编号，按编号顺序排列的 `if` 段
//   即可在一次运行中顺序走完不挂起的部分。
//
// 所属层次：
//   translator 层（AST -> 规划数据）。只依赖 ast；不依赖 checker / compiler /
//   backend，由 LinearCoroutineEmitter 消费，也可由测试直接调用。
//
// 对外契约：
//   - plan(...) 是纯函数，不修改输入 AST；成功时返回完整块表，失败时只返回首个
//     拒绝原因，绝不返回半边计划；
//   - 块编号按分配顺序递增，且每个出口目标编号严格大于所在块编号（前向不变量），
//     规划器在返回前自检，违反即抛 IllegalStateException（编译器内部错误）。
//
// 边界（本片刻意不做）：
//   - 循环、try/catch/finally、defer、throw、match、break/continue 一律拒绝；
//   - await 只能是局部初始化或局部赋值的整个右侧，不能出现在条件、return 值或
//     任何子表达式中；
//   - 每条路径必须以 return 结束；return 之后的语句视为不可达并拒绝。
// ============================================================================

package jasper.translator

import jasper.ast.*

/**
 * 基本块出口。
 *
 * 做什么：描述一个块执行完普通赋值之后去哪里。
 * 为什么是 sealed：发射器必须穷举每种出口；新增出口种类时编译期即可发现漏处理。
 */
sealed class CoroutineBlockExit {
    /** 无条件前进到 [target] 块（分支汇合）。 */
    data class Goto(val target: Int) : CoroutineBlockExit()

    /** 按 [condition] 前进到 [whenTrue] 或 [whenFalse] 块。 */
    data class Branch(val condition: JasExpression, val whenTrue: Int, val whenFalse: Int) : CoroutineBlockExit()

    /** 挂起：注册 [await] 操作数的完成回调；恢复后从 [resume] 块开始，并把结果写入 [binding]。 */
    data class Suspend(val await: JasUnaryOp, val binding: String, val resume: Int) : CoroutineBlockExit()

    /** 以 [value] 作为成功结果结束整个帧。 */
    data class Finish(val value: JasExpression) : CoroutineBlockExit()
}

/**
 * 一个基本块。
 *
 * [resumeBinding] 非空表示这是某个挂起点的恢复块：入口先消费完成快照（取消/失败
 * 直接发布终态并结束），成功值写入该局部；[assignments] 是按源码顺序的局部写入；
 * [exit] 是唯一出口。
 */
data class CoroutineBlock(
    val id: Int,
    val resumeBinding: String?,
    val assignments: List<Pair<String, JasExpression>>,
    val exit: CoroutineBlockExit
)

/**
 * 规划结果。
 *
 * [blocks] 按编号排列（编号即下标）；[locals] 是函数体内声明的局部名与类型，
 * 按源码出现顺序；[reason] 非空表示不支持，此时其余字段为空。
 */
data class CoroutineBlockPlan(
    val blocks: List<CoroutineBlock>,
    val locals: List<Pair<String, JasType>>,
    val reason: String? = null
) {
    /** 挂起点数量；发射器要求至少一个，否则函数不是由直接 await 挂起。 */
    val suspensionCount: Int get() = blocks.count { it.exit is CoroutineBlockExit.Suspend }
}

/**
 * 前向基本块规划器（无状态单例）。
 *
 * 做什么：按上文契约切块；同时收集局部声明、校验类型与 await 位置。
 * 什么时候用：LinearCoroutineEmitter.unsupportedReason / emit。
 * 前置条件：函数已完成类型检查，真实 std.Future await 的 awaitResultType 已回填；
 *   兼容模式的普通值 await（awaitResultType 为 null）不视为挂起点。
 * 失败模式：不支持形态返回带原因的计划；内部不变量破坏抛 IllegalStateException。
 * 为什么只做前向跳转：没有循环时控制流图天然无环，按分配顺序编号即可让顺序排列
 *   的段在一次运行中走完；循环需要回边与重入检查，留给下一片单独建模验证。
 */
object CoroutineBlockPlanner {
    /** 本片允许的局部 / 参数值类型；帧字段直接使用该类型。 */
    private val INT = JasPrimitiveType("int32")
    private val BOOL = JasPrimitiveType("bool")

    /** 规划期间的拒绝；只在本对象内部抛出并收敛为 [CoroutineBlockPlan.reason]。 */
    private class Rejected(message: String) : RuntimeException(message)

    /** 正在构造、尚未确定出口的块。 */
    private class OpenBlock(val id: Int, val resumeBinding: String?) {
        val assignments = mutableListOf<Pair<String, JasExpression>>()
    }

    /**
     * 规划函数体。
     *
     * [slotTypes] 是参数名到类型的映射（由发射器提供并已校验）；局部声明在此基础上
     * 继续登记，名字必须全函数唯一（分支内局部也会成为帧字段）。
     */
    fun plan(function: JasFunction, slotTypes: Map<String, JasType>): CoroutineBlockPlan {
        val statements = function.body?.statements
            ?: return CoroutineBlockPlan(emptyList(), emptyList(), "function body is required")
        val state = State(slotTypes)
        return try {
            val tail = state.lowerSequence(statements, state.open(null))
            if (tail != null) throw Rejected("every path must end with return")
            val blocks = state.closed.values.sortedBy { it.id }
            check(blocks.map { it.id } == blocks.indices.toList()) { "coroutine block ids are not dense" }
            blocks.forEach { block ->
                targets(block.exit).forEach { target ->
                    check(target > block.id) { "coroutine block ${block.id} jumps backwards to $target" }
                }
            }
            CoroutineBlockPlan(blocks, state.locals.toList())
        } catch (rejected: Rejected) {
            CoroutineBlockPlan(emptyList(), emptyList(), rejected.message)
        }
    }

    /** 出口引用的块编号；供前向不变量检查与测试使用。 */
    fun targets(exit: CoroutineBlockExit): List<Int> = when (exit) {
        is CoroutineBlockExit.Goto -> listOf(exit.target)
        is CoroutineBlockExit.Branch -> listOf(exit.whenTrue, exit.whenFalse)
        is CoroutineBlockExit.Suspend -> listOf(exit.resume)
        is CoroutineBlockExit.Finish -> emptyList()
    }

    /** 一次规划的可变状态；与单例分离，保证并发编译互不干扰。 */
    private class State(parameters: Map<String, JasType>) {
        val types = LinkedHashMap(parameters)
        val locals = LinkedHashMap<String, JasType>()
        val closed = LinkedHashMap<Int, CoroutineBlock>()
        private var nextId = 0

        /** 分配新块；编号按分配顺序递增，是前向不变量的来源。 */
        fun open(resumeBinding: String?) = OpenBlock(nextId++, resumeBinding)

        /** 封闭块并登记出口。 */
        fun close(block: OpenBlock, exit: CoroutineBlockExit) {
            closed[block.id] = CoroutineBlock(block.id, block.resumeBinding, block.assignments.toList(), exit)
        }

        /** 降级语句序列；返回序列结束时仍开放的块，所有路径都已 return 时返回 null。 */
        fun lowerSequence(statements: List<JasStatement>, start: OpenBlock): OpenBlock? {
            var current: OpenBlock? = start
            for (statement in statements) {
                val block = current ?: throw Rejected("unreachable statement after return")
                current = lowerStatement(statement, block)
            }
            return current
        }

        /** 降级单条语句。 */
        private fun lowerStatement(statement: JasStatement, block: OpenBlock): OpenBlock? = when (statement) {
            is JasVariableStatement -> {
                val initializer = statement.initializer
                    ?: throw Rejected("local '${statement.name}' must be initialized")
                if (statement.name in types) throw Rejected("parameter and local names must be unique")
                val await = directAwait(initializer)
                val type = if (await != null) {
                    if (statement.type != null && statement.type != INT) throw Rejected("await binding type must be int32")
                    INT
                } else {
                    statement.type.takeIf { it == INT || it == BOOL }
                        ?: throw Rejected("only explicitly typed int32 or bool local declarations are supported")
                }
                types[statement.name] = type
                locals[statement.name] = type
                write(block, statement.name, initializer, await)
            }
            is JasExpressionStatement -> {
                val assignment = statement.expression as? JasAssignment
                    ?: throw Rejected("only assignments to locals may appear as statements")
                val target = assignment.target as? JasIdentifier
                if (assignment.op != "=" || target == null || target.name !in locals) {
                    throw Rejected("only plain '=' assignments to locals are supported")
                }
                val await = directAwait(assignment.value)
                if (await != null && locals.getValue(target.name) != INT) {
                    throw Rejected("await binding type must be int32")
                }
                write(block, target.name, assignment.value, await)
            }
            is JasIf -> lowerIf(statement, block)
            is JasReturn -> {
                val value = statement.value ?: throw Rejected("return must carry an int32 value")
                if (directAwait(value) != null) throw Rejected("return await is not supported")
                rejectNestedAwait(value)
                close(block, CoroutineBlockExit.Finish(value))
                null
            }
            is JasBlock -> lowerSequence(statement.statements, block)
            else -> throw Rejected("unsupported control flow node ${statement.javaClass.simpleName}")
        }

        /** 局部写入：直接 await 时挂起并开启恢复块，否则追加普通赋值。 */
        private fun write(block: OpenBlock, name: String, value: JasExpression, await: JasUnaryOp?): OpenBlock {
            if (await == null) {
                rejectNestedAwait(value)
                block.assignments += name to value
                return block
            }
            if (await.awaitResultType != INT) throw Rejected("await result type must be int32")
            rejectNestedAwait(await.operand)
            val resume = open(name)
            close(block, CoroutineBlockExit.Suspend(await, name, resume.id))
            return resume
        }

        /** if/else：条件在当前块求值；两条分支各自成块，未 return 的出口汇合到新块。 */
        private fun lowerIf(statement: JasIf, block: OpenBlock): OpenBlock? {
            rejectNestedAwait(statement.condition)
            val whenTrue = open(null)
            val trueEnd = lowerSequence(statement.thenBody.statements, whenTrue)
            val whenFalse = open(null)
            // 不能写成 `elseBody?.let { ... } ?: whenFalse`：else 全部 return 时序列结果为 null，
            // elvis 会把它误当成「没有 else」而接上汇合边。
            val elseBody = statement.elseBody
            val falseEnd = if (elseBody == null) whenFalse else lowerSequence(elseBody.statements, whenFalse)
            close(block, CoroutineBlockExit.Branch(statement.condition, whenTrue.id, whenFalse.id))
            if (trueEnd == null && falseEnd == null) return null
            val join = open(null)
            trueEnd?.let { close(it, CoroutineBlockExit.Goto(join.id)) }
            falseEnd?.let { close(it, CoroutineBlockExit.Goto(join.id)) }
            return join
        }
    }

    /** 表达式本身是否为真实挂起 await（兼容模式普通值 await 不算）。 */
    private fun directAwait(expression: JasExpression): JasUnaryOp? =
        (expression as? JasUnaryOp)?.takeIf { it.op == "await" && it.awaitResultType != null }

    /** 子表达式中出现真实 await 一律拒绝：当前只在语句边界切块。 */
    private fun rejectNestedAwait(expression: JasExpression?) {
        val children: List<JasExpression?> = when (expression) {
            null -> return
            is JasUnaryOp -> {
                if (directAwait(expression) != null) throw Rejected("await must be the whole value of a local write")
                listOf(expression.operand)
            }
            is JasBinaryOp -> listOf(expression.left, expression.right)
            is JasCall -> listOf(expression.target) + expression.args
            is JasPropertyAccess -> listOf(expression.target)
            is JasCastExpr -> listOf(expression.expression)
            is JasArrayAccess -> listOf(expression.target, expression.index)
            is JasNew -> expression.args
            is JasAssignment -> listOf(expression.target, expression.value)
            else -> emptyList()
        }
        children.forEach(::rejectNestedAwait)
    }
}
