// ============================================================================
// CoroutineBlockPlanner.kt —— 协程帧的基本块规划（S4-C 第十至十二片）
// ============================================================================
// 职责：
//   把一个已通过类型检查的受限函数体切成基本块序列：每个真实 await（局部初始化
//   或对局部 `=` 赋值的整个右侧）结束当前块并开启一个恢复块；if/else 结束当前块
//   并开启两个分支块，分支未终止的出口汇合到新块；while 开启循环头块（求值条件），
//   循环体末尾与 continue 回到循环头，break 与条件为假前进到循环出口块；do-while
//   的循环头是不带工作的空块（直接进入循环体），条件块在循环体之后；三段式 for
//   先在当前块做初始化，continue 与循环体末尾前进到步进块，步进块回到循环头；
//   return 结束一条路径。生成帧用 `__phase` 记录下一块编号，按编号顺序排列的 `if` 段
//   包在分派循环里：前向跳转在同一趟内顺序走完，回到循环头时开启新一趟。
//
// 所属层次：
//   translator 层（AST -> 规划数据）。只依赖 ast；不依赖 checker / compiler /
//   backend，由 LinearCoroutineEmitter 消费，也可由测试直接调用。
//
// 对外契约：
//   - plan(...) 是纯函数，不修改输入 AST；成功时返回完整块表，失败时只返回首个
//     拒绝原因，绝不返回半边计划；
//   - 块编号按分配顺序递增；出口目标编号要么严格大于所在块（前向边），要么是某个
//     循环头（回边），规划器在返回前自检，违反即抛 IllegalStateException（编译器
//     内部错误）。
//
// 边界（本片刻意不做）：
//   - for-in、循环后置块、带标签的 break/continue、try/catch/finally、defer、
//     throw、match 一律拒绝；
//   - 三段式 for 的步进段只接受对局部的 `=` 赋值或 int32 局部的 `++` / `--`；
//   - await 只能是局部初始化或局部赋值的整个右侧，不能出现在条件、return 值或
//     任何子表达式中；
//   - 每条路径必须以 return 结束；return / break / continue 之后的语句视为不可达
//     并拒绝。
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
    /** 无条件跳到 [target] 块（分支汇合、进入循环头、循环体末尾 / continue 回边、break）。 */
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
 * 按源码出现顺序；[loopHeaders] 是 while / do-while / for 的循环头块编号，回边只能指向它们；
 * [reason] 非空表示不支持，此时其余字段为空。
 */
data class CoroutineBlockPlan(
    val blocks: List<CoroutineBlock>,
    val locals: List<Pair<String, JasType>>,
    val reason: String? = null,
    val loopHeaders: Set<Int> = emptySet()
) {
    /** 挂起点数量；发射器要求至少一个，否则函数不是由直接 await 挂起。 */
    val suspensionCount: Int get() = blocks.count { it.exit is CoroutineBlockExit.Suspend }
}

/**
 * 基本块规划器（无状态单例）。
 *
 * 做什么：按上文契约切块；同时收集局部声明、校验类型与 await 位置。
 * 什么时候用：LinearCoroutineEmitter.unsupportedReason / emit。
 * 前置条件：函数已完成类型检查，真实 std.Future await 的 awaitResultType 已回填；
 *   兼容模式的普通值 await（awaitResultType 为 null）不视为挂起点。
 * 失败模式：不支持形态返回带原因的计划；内部不变量破坏抛 IllegalStateException。
 * 为什么回边只能指向循环头：除循环外控制流图无环，按分配顺序编号即可让顺序排列
 *   的段在一趟内走完；回边集中到循环头，发射器只需在分派循环每趟开头做一次终态
 *   检查，就能让不挂起的循环在回边处响应作用域取消。
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
     * 正在降级的循环。
     *
     * [continueTarget] 非空时 continue 直接跳过去（while 的循环头）；为空时 continue 的
     * 目标块（do-while 的条件块、for 的步进块）要在循环体之后才分配，先把块记进
     * [continues]。break 的块同样等出口块分配后再封闭。
     */
    private class LoopContext(val continueTarget: Int?) {
        val breaks = mutableListOf<OpenBlock>()
        val continues = mutableListOf<OpenBlock>()
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
                    check(target > block.id || target in state.headers) {
                        "coroutine block ${block.id} jumps backwards to non-header $target"
                    }
                }
            }
            CoroutineBlockPlan(blocks, state.locals.toList(), loopHeaders = state.headers.toSet())
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
        val headers = linkedSetOf<Int>()
        private val loops = ArrayDeque<LoopContext>()
        private var nextId = 0

        /** 分配新块；编号按分配顺序递增，是前向不变量的来源。 */
        fun open(resumeBinding: String?) = OpenBlock(nextId++, resumeBinding)

        /** 封闭块并登记出口。 */
        fun close(block: OpenBlock, exit: CoroutineBlockExit) {
            closed[block.id] = CoroutineBlock(block.id, block.resumeBinding, block.assignments.toList(), exit)
        }

        /**
         * 降级语句序列；返回序列结束时仍开放的块，所有路径都已终止（return / break /
         * continue）时返回 null。
         */
        fun lowerSequence(statements: List<JasStatement>, start: OpenBlock): OpenBlock? {
            var current: OpenBlock? = start
            for (statement in statements) {
                val block = current ?: throw Rejected("unreachable statement after return, break or continue")
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
            is JasExpressionStatement -> when (val expression = statement.expression) {
                is JasAssignment -> lowerAssignment(expression, block)
                else -> lowerIncrement(expression, block)
            }
            is JasIf -> lowerIf(statement, block)
            is JasWhile -> lowerWhile(statement, block)
            is JasDoWhile -> lowerDoWhile(statement, block)
            is JasForStatement -> lowerFor(statement, block)
            is JasBreakStatement -> {
                val loop = innermostLoop(statement.label)
                loop.breaks += block
                null
            }
            is JasContinueStatement -> {
                val loop = innermostLoop(statement.label)
                val target = loop.continueTarget
                if (target != null) close(block, CoroutineBlockExit.Goto(target)) else loop.continues += block
                null
            }
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

        /** 对局部的 `=` 赋值；右侧为直接 await 时挂起。 */
        private fun lowerAssignment(assignment: JasAssignment, block: OpenBlock): OpenBlock {
            val target = assignment.target as? JasIdentifier
            if (assignment.op != "=" || target == null || target.name !in locals) {
                throw Rejected("only plain '=' assignments to locals are supported")
            }
            val await = directAwait(assignment.value)
            if (await != null && locals.getValue(target.name) != INT) {
                throw Rejected("await binding type must be int32")
            }
            return write(block, target.name, assignment.value, await)
        }

        /**
         * `i++` / `i--`：只接受 int32 局部，改写为 `i = i + 1` / `i = i - 1` 的普通赋值；
         * 生成帧随后经真实类型检查，改写结果与源码自增的 int32 回绕语义一致。
         */
        private fun lowerIncrement(expression: JasExpression, block: OpenBlock): OpenBlock {
            val increment = expression as? JasPostfixIncDec
                ?: throw Rejected("only assignments to locals may appear as statements")
            val target = increment.target as? JasIdentifier
            if (target == null || locals[target.name] != INT || increment.op !in setOf("++", "--")) {
                throw Rejected("only '++' / '--' on int32 locals are supported")
            }
            val op = if (increment.op == "++") "+" else "-"
            block.assignments += target.name to JasBinaryOp(JasIdentifier(target.name), op, JasIntLiteral(1, "1"))
            return block
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

        /**
         * while：当前块前进到新开的循环头；循环头求值条件，为真进入循环体，为假前进到
         * 出口块。循环体末尾与 continue 是回边（回到循环头），break 前进到出口块。出口块
         * 在循环体之后分配，所以除回边外的所有边仍是前向的。
         */
        private fun lowerWhile(statement: JasWhile, block: OpenBlock): OpenBlock {
            if (statement.suffix != null) throw Rejected("loop suffix blocks are not supported")
            rejectNestedAwait(statement.condition)
            val header = open(null)
            headers += header.id
            close(block, CoroutineBlockExit.Goto(header.id))
            val body = open(null)
            val loop = LoopContext(header.id)
            val bodyEnd = lowerLoopBody(statement.body, body, loop)
            bodyEnd?.let { close(it, CoroutineBlockExit.Goto(header.id)) }
            val exit = open(null)
            close(header, CoroutineBlockExit.Branch(statement.condition, body.id, exit.id))
            loop.breaks.forEach { close(it, CoroutineBlockExit.Goto(exit.id)) }
            return exit
        }

        /**
         * do-while：先开一个不带工作的循环头块（条件为真时的回边目标），无条件进入循环体；
         * 循环头与循环体首块分开，使所有循环头都只负责「每趟开始」这一件事。循环体末尾与
         * continue 前进到循环体之后分配的条件块，条件块按条件回到循环头或前进到出口块。
         * 没有任何路径到达条件块时（循环体每条路径都 return / break）不分配条件块；也没有
         * break 时整个循环不会正常结束，返回 null，其后的语句按不可达拒绝。
         */
        private fun lowerDoWhile(statement: JasDoWhile, block: OpenBlock): OpenBlock? {
            rejectNestedAwait(statement.condition)
            val header = open(null)
            headers += header.id
            close(block, CoroutineBlockExit.Goto(header.id))
            val body = open(null)
            close(header, CoroutineBlockExit.Goto(body.id))
            val loop = LoopContext(null)
            val bodyEnd = lowerLoopBody(statement.body, body, loop)
            val exitNeeded = bodyEnd != null || loop.continues.isNotEmpty() || loop.breaks.isNotEmpty()
            if (!exitNeeded) return null
            val conditionBlock = if (bodyEnd != null || loop.continues.isNotEmpty()) open(null) else null
            if (conditionBlock != null) {
                bodyEnd?.let { close(it, CoroutineBlockExit.Goto(conditionBlock.id)) }
                loop.continues.forEach { close(it, CoroutineBlockExit.Goto(conditionBlock.id)) }
            }
            val exit = open(null)
            conditionBlock?.let { close(it, CoroutineBlockExit.Branch(statement.condition, header.id, exit.id)) }
            loop.breaks.forEach { close(it, CoroutineBlockExit.Goto(exit.id)) }
            return exit
        }

        /**
         * 三段式 for：初始化在当前块按普通局部声明 / 赋值降级（可以含 await，只执行一次）；
         * 随后开循环头块求值条件（省略条件等价于 true，与 while (true) 一样保留出口块）；
         * 循环体末尾与 continue 前进到步进块，步进块执行步进段后回到循环头；break 前进到
         * 出口块。步进块与出口块都在循环体之后分配，除两条回边外所有边仍是前向的。
         */
        private fun lowerFor(statement: JasForStatement, block: OpenBlock): OpenBlock {
            if (statement.suffix != null) throw Rejected("loop suffix blocks are not supported")
            // 多个绑定的声明式初始化（`var a = 0, b = 0`）由 AST 构建器包成 JasBlock，逐条降级。
            val afterInit = when (val init = statement.init) {
                null -> block
                is JasVariableStatement, is JasExpressionStatement -> lowerStatement(init, block)
                is JasBlock -> {
                    if (init.statements.any { it !is JasVariableStatement }) {
                        throw Rejected("unsupported for initializer block")
                    }
                    lowerSequence(init.statements, block)
                }
                else -> throw Rejected("unsupported for initializer ${init.javaClass.simpleName}")
            } ?: throw Rejected("for initializer must not end the path")
            val condition = statement.condition ?: JasBoolLiteral(true)
            rejectNestedAwait(condition)
            val header = open(null)
            headers += header.id
            close(afterInit, CoroutineBlockExit.Goto(header.id))
            val body = open(null)
            val loop = LoopContext(null)
            val bodyEnd = lowerLoopBody(statement.body, body, loop)
            if (bodyEnd != null || loop.continues.isNotEmpty()) {
                val step = open(null)
                bodyEnd?.let { close(it, CoroutineBlockExit.Goto(step.id)) }
                loop.continues.forEach { close(it, CoroutineBlockExit.Goto(step.id)) }
                val update = statement.update
                val stepEnd = if (update == null) step else lowerStatement(JasExpressionStatement(update), step)
                    ?: throw Rejected("for update must not end the path")
                close(stepEnd, CoroutineBlockExit.Goto(header.id))
            }
            val exit = open(null)
            close(header, CoroutineBlockExit.Branch(condition, body.id, exit.id))
            loop.breaks.forEach { close(it, CoroutineBlockExit.Goto(exit.id)) }
            return exit
        }

        /** 在 [loop] 作用域内降级循环体；无论成败都弹出循环上下文。 */
        private fun lowerLoopBody(body: JasBlock, start: OpenBlock, loop: LoopContext): OpenBlock? {
            loops.addLast(loop)
            return try {
                lowerSequence(body.statements, start)
            } finally {
                loops.removeLast()
            }
        }

        /** break/continue 的目标循环；带标签或不在循环内时拒绝。 */
        private fun innermostLoop(label: String?): LoopContext {
            if (label != null) throw Rejected("labeled break/continue is not supported")
            return loops.lastOrNull() ?: throw Rejected("break/continue outside a loop")
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
