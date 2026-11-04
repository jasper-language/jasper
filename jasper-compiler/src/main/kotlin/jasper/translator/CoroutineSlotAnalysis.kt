// ============================================================================
// CoroutineSlotAnalysis.kt —— 协程帧槽位的明确初始化与活跃性分析
// ============================================================================
// 职责：
//   在 CoroutineBlockPlanner 的块表上回答两个问题，落实用户 2026-11-04 对帧引用槽的裁决：
//   ① 任何槽位的读取之前，每条路径上是否都已写入（「首次读取前完成初始化」）；
//   ② 引用槽在哪里死亡，发射器该在哪里清除（「槽位死亡后清除引用」）。
//
// 所属层次：
//   translator 层（规划数据 -> 分析结果）。纯函数，不修改输入；由规划器与发射器调用。
//
// 「死亡」的定义：
//   从该点出发，沿任何路径（含异常路由与展开）都不会再读取。这比词法作用域结束更早：
//   例如循环内最后一次读取之后，对象不再被帧持有。
//
// 异常边（两个分析的 soundness 都靠它）：
//   块内任何位置都可能抛出，挂起中的帧也可能被取消并展开，所以：
//   - 块所在区域链上的 finally 体提到的名字、catch 处理块入口活跃的名字，在整个块内都活跃；
//   - catch 处理块的入口状态取「进入区域时已写入的集合」，finally 体的检查同样取这个集合，
//     不取 try 体中途的状态。
//   finally 体只做保守处理：提到即活跃，不算定义。
// ============================================================================

package jasper.translator

import jasper.ast.*

/**
 * 发射器要插入的清除点。
 *
 * [onEntry]：块入口处清除（前驱持有、本块已不活跃）。
 * [beforeSuspend]：挂起块在操作数求值之后、注册回调之前清除。为什么单列：挂起是对象被长期持有的
 *   位置，恢复块入口再清就晚了。走完挂起边的前驱不计入后继的 [onEntry]。
 * 结束路径（return / throw / 取消）不在这里：帧回收时统一清除全部引用槽。
 */
data class CoroutineSlotDeaths(
    val onEntry: Map<Int, Set<String>>,
    val beforeSuspend: Map<Int, Set<String>>
)

/** 协程帧槽位分析（无状态单例）。 */
object CoroutineSlotAnalysis {
    /**
     * 表达式读取的名字。覆盖发射器 rewrite 接受的全部形态；其它形态 rewrite 自身会拒绝，这里不必识别。
     * 若两边将来漂移，读取检查（std.FrameSlot）把漏算的死亡报告成失败，而不是读到 null。
     */
    private fun reads(expression: JasExpression?, into: MutableSet<String> = linkedSetOf()): MutableSet<String> {
        when (expression) {
            is JasIdentifier -> into += expression.name
            is JasBinaryOp -> { reads(expression.left, into); reads(expression.right, into) }
            is JasUnaryOp -> reads(expression.operand, into)
            is JasPropertyAccess -> reads(expression.target, into)
            is JasCall -> { reads(expression.target, into); expression.args.forEach { reads(it, into) } }
            is JasCastExpr -> reads(expression.expression, into)
            is JasNullCoalescing -> { reads(expression.left, into); reads(expression.right, into) }
            is JasNew -> expression.args.forEach { reads(it, into) }
            is JasPostfixIncDec -> reads(expression.target, into)
            is JasAssignment -> reads(expression.value, into)
            else -> Unit
        }
        return into
    }

    /** 清理语句提到的全部名字（读取、写入目标与声明）；活跃性按「提到即活跃」保守处理。 */
    private fun cleanupNames(statement: JasStatement, into: MutableSet<String>) {
        when (statement) {
            is JasVariableStatement -> { into += statement.name; reads(statement.initializer, into) }
            is JasStatementIncrement -> reads(statement.increment, into)
            is JasExpressionStatement -> {
                val expression = statement.expression
                if (expression is JasAssignment) reads(expression.target, into)
                reads(expression, into)
            }
            is JasIf -> {
                reads(statement.condition, into)
                statement.thenBody.statements.forEach { cleanupNames(it, into) }
                statement.elseBody?.statements?.forEach { cleanupNames(it, into) }
            }
            is JasWhile -> {
                reads(statement.condition, into)
                statement.body.statements.forEach { cleanupNames(it, into) }
            }
            is JasBlock -> statement.statements.forEach { cleanupNames(it, into) }
            else -> Unit
        }
    }

    /** 出口读取的名字。 */
    private fun exitReads(exit: CoroutineBlockExit): Set<String> = when (exit) {
        is CoroutineBlockExit.Goto -> emptySet()
        is CoroutineBlockExit.Branch -> reads(exit.condition)
        is CoroutineBlockExit.Suspend -> reads(exit.await.operand)
        is CoroutineBlockExit.Finish -> reads(exit.value)
        is CoroutineBlockExit.Throw -> reads(exit.value)
    }

    /** [regions] 及其全部外层区域。 */
    private fun chain(regions: Set<Int>, plan: CoroutineBlockPlan): Set<Int> {
        val result = linkedSetOf<Int>()
        regions.forEach { start ->
            var region = start
            while (region != 0 && result.add(region)) region = plan.tryRegions[region - 1].parent
        }
        return result
    }

    /**
     * 明确初始化检查：返回第一个可能先读后写的名字，全部安全时返回 null。
     *
     * 做什么：前向 must 分析，入口集合是 [parameters]，合流取交集。catch 处理块与 finally 体从
     *   「进入区域时」的集合开始，理由见文件头。只看帧局部（[CoroutineBlockPlan.locals]）与参数，
     *   自由标识符（类型名、函数名）不是槽位。
     * 为什么放在类型检查之外再做一遍：检查器的作用域规则是词法的，帧把函数体切成块并加了异常边，
     *   这里在切块结果上验证，不依赖两者恰好一致。
     */
    fun uninitializedRead(plan: CoroutineBlockPlan, parameters: Set<String>): String? {
        val slots = parameters + plan.locals.map { it.first }
        val all = slots
        val entry = plan.blocks.associate { it.id to all }.toMutableMap()
        entry[0] = parameters
        val atRegion = plan.tryRegions.associate { it.id to all }.toMutableMap()
        val handlerRegion = plan.tryRegions.flatMap { region -> region.handlers.map { it.entry to region.id } }.toMap()
        val successors = plan.blocks.associate { it.id to CoroutineBlockPlanner.targets(it.exit) }
        val predecessors = plan.blocks.flatMap { block -> successors.getValue(block.id).map { it to block.id } }
            .groupBy({ it.first }, { it.second })

        /** 走一遍块的工作项，返回出口集合；[onRead] 收到每次读取时的已写入集合，[onRegion] 收到区域进入。 */
        fun walk(
            block: CoroutineBlock,
            onRead: (Set<String>, Set<String>) -> Unit = { _, _ -> },
            onRegion: (Int, Set<String>) -> Unit = { _, _ -> }
        ): Set<String> {
            val state = entry.getValue(block.id).toMutableSet()
            block.resumeBinding?.let { state += it }
            block.assignments.forEach { (name, value) ->
                onRead(reads(value), state)
                if (name == CoroutineBlockPlanner.REGION_LOCAL) onRegion((value as JasIntLiteral).value.toInt(), state.toSet())
                if (name != CoroutineBlockPlanner.EFFECT_TARGET) state += name
            }
            onRead(exitReads(block.exit), state)
            return state
        }

        var changed = true
        while (changed) {
            changed = false
            val regionEntry = atRegion.toMutableMap()
            plan.blocks.forEach { block ->
                val region = handlerRegion[block.id]
                val incoming = if (region != null) atRegion.getValue(region) else if (block.id == 0) parameters else {
                    // 没有前驱的块不可达（all 不会造成误拒）；只算已有状态的前驱。
                    predecessors[block.id].orEmpty().map { walk(plan.blocks[it]) }
                        .fold(all) { acc, next -> acc intersect next }
                }
                if (incoming != entry[block.id]) {
                    entry[block.id] = incoming
                    changed = true
                }
                walk(block, onRegion = { id, state -> regionEntry[id] = regionEntry.getValue(id) intersect state })
            }
            if (regionEntry != atRegion) {
                atRegion.putAll(regionEntry)
                changed = true
            }
        }

        var unsafe: String? = null
        /** 记录第一个不在 [state] 中的读取。 */
        fun check(names: Set<String>, state: Set<String>) {
            if (unsafe == null) unsafe = names.firstOrNull { it in slots && it !in state }
        }
        /** finally 体内部按顺序走：声明与赋值只在其后可见，分支合流取交集，循环体的写入不带出循环。 */
        fun checkCleanup(statements: List<JasStatement>, start: Set<String>): Set<String> {
            var state = start
            statements.forEach { statement ->
                when (statement) {
                    is JasVariableStatement -> { check(reads(statement.initializer), state); state = state + statement.name }
                    is JasStatementIncrement -> check(reads(statement.increment), state)
                    is JasExpressionStatement -> {
                        val expression = statement.expression
                        check(reads(expression), state)
                        ((expression as? JasAssignment)?.target as? JasIdentifier)?.let { state = state + it.name }
                    }
                    is JasIf -> {
                        check(reads(statement.condition), state)
                        val whenTrue = checkCleanup(statement.thenBody.statements, state)
                        val whenFalse = statement.elseBody?.let { checkCleanup(it.statements, state) } ?: state
                        state = whenTrue intersect whenFalse
                    }
                    is JasWhile -> {
                        check(reads(statement.condition), state)
                        checkCleanup(statement.body.statements, state)
                    }
                    is JasBlock -> state = checkCleanup(statement.statements, state)
                    else -> Unit
                }
            }
            return state
        }
        plan.blocks.forEach { block -> walk(block, onRead = ::check) }
        plan.tryRegions.forEach { region -> checkCleanup(region.cleanup.statements, atRegion.getValue(region.id)) }
        return unsafe
    }

    /**
     * 活跃性分析：返回 [tracked]（引用槽）的清除点。
     *
     * 做什么：后向 may 分析。块内活跃 = 按工作项倒序算出的入口活跃 ∪ 异常边活跃（文件头）；
     *   出口活跃 = 后继入口活跃之并。块「可能持有」= 入口活跃 ∪ 块内写入，第一块还要算上 [parameters]：
     *   构造器写入的参数从未被读取时不在任何活跃集里，同样要清除。
     * 为什么块上登记的离开清理不单独算：它们离开的区域都在块所在的区域链上，已计入异常边活跃。
     * 前置条件：[plan] 无拒绝原因。
     */
    fun deaths(plan: CoroutineBlockPlan, tracked: Set<String>, parameters: Set<String>): CoroutineSlotDeaths {
        val regionNames = plan.tryRegions.associate { region ->
            val names = linkedSetOf<String>()
            region.cleanup.statements.forEach { cleanupNames(it, names) }
            region.id to (names intersect tracked)
        }
        val live = plan.blocks.associate { it.id to emptySet<String>() }.toMutableMap()

        /** 异常边活跃：所在区域链上的 finally 名字与 catch 处理块入口活跃。 */
        fun exceptional(block: CoroutineBlock): Set<String> = chain(block.regions, plan).flatMap { id ->
            regionNames.getValue(id) + plan.tryRegions[id - 1].handlers.flatMap { live.getValue(it.entry) }
        }.toSet()

        /** 本块入口活跃：从出口活跃倒着走工作项，再并上异常边。 */
        fun liveIn(block: CoroutineBlock): Set<String> {
            val state = CoroutineBlockPlanner.targets(block.exit).flatMap { live.getValue(it) }.toMutableSet()
            state += exitReads(block.exit) intersect tracked
            block.assignments.asReversed().forEach { (name, value) ->
                if (name != CoroutineBlockPlanner.EFFECT_TARGET) state -= name
                state += reads(value) intersect tracked
            }
            block.resumeBinding?.let { state -= it }
            return state + exceptional(block)
        }

        var changed = true
        while (changed) {
            changed = false
            plan.blocks.asReversed().forEach { block ->
                val next = liveIn(block)
                if (next != live[block.id]) {
                    live[block.id] = next
                    changed = true
                }
            }
        }

        /** 块结束时可能持有的引用：入口活跃（挂起跨越的）加块内写入。 */
        fun held(block: CoroutineBlock): Set<String> =
            live.getValue(block.id) + (block.assignments.map { it.first } + listOfNotNull(block.resumeBinding)).filter { it in tracked } +
                if (block.id == 0) parameters intersect tracked else emptySet()

        val onEntry = linkedMapOf<Int, MutableSet<String>>()
        val beforeSuspend = linkedMapOf<Int, Set<String>>()
        plan.blocks.forEach { block ->
            val exit = block.exit
            if (exit is CoroutineBlockExit.Suspend) {
                val dead = held(block) - live.getValue(exit.resume)
                if (dead.isNotEmpty()) beforeSuspend[block.id] = dead
            } else {
                CoroutineBlockPlanner.targets(exit).forEach { target ->
                    val dead = held(block) - live.getValue(target)
                    if (dead.isNotEmpty()) onEntry.getOrPut(target) { linkedSetOf() } += dead
                }
            }
        }
        // catch 处理块没有前驱边：区域链内任何块都可能跳过来。
        plan.tryRegions.forEach { region ->
            region.handlers.forEach { handler ->
                plan.blocks.filter { region.id in chain(it.regions, plan) }.forEach { block ->
                    val dead = held(block) - live.getValue(handler.entry)
                    if (dead.isNotEmpty()) onEntry.getOrPut(handler.entry) { linkedSetOf() } += dead
                }
            }
        }
        return CoroutineSlotDeaths(onEntry, beforeSuspend)
    }
}
