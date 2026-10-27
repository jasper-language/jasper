// ============================================================================
// CoroutineBlockPlannerTest.kt —— S4-C 第十、十一片基本块规划器的契约与属性测试
// ============================================================================
// 做什么：直接构造已「类型检查」的 AST（真实 await 节点回填 awaitResultType），
//   锁定块规划器的结构不变量、拒绝边界，并用固定种子随机程序比对两套独立语义：
//   源码 AST 的直接解释 vs 规划出的块状态机解释（逐块执行、挂起点消费输入）。
//
// 所属层次：translator 单元测试；不经过解析器、检查器或后端，秒级完成。
// 失败模式：任一断言失败都打印种子与用例编号；随机程序以 AST 文本形式给出。
// 为什么需要双解释器 oracle：块编号、汇合边与恢复绑定的错误不一定让编译失败，
//   只有把块状态机的执行结果与源码语义逐例对齐才能发现（例如 else 全部 return 时
//   误接汇合边）。第十一片起随机程序含 while / break / continue，块状态机解释器
//   允许回边，但只允许回到规划器登记的循环头。
// ============================================================================

package jasper

import jasper.ast.*
import jasper.translator.CoroutineBlockExit
import jasper.translator.CoroutineBlockPlan
import jasper.translator.CoroutineBlockPlanner
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 块规划器测试；所有 AST 都由下方小工具直接构造。 */
class CoroutineBlockPlannerTest {
    private val int = JasPrimitiveType("int32")
    private val bool = JasPrimitiveType("bool")
    private val futureInt = JasNamedType("std.Future", listOf(int))

    /** 真实挂起 await：回填结果类型，模拟检查器对 std.Future<int32> 的处理。 */
    private fun await(name: String) = JasUnaryOp("await", JasIdentifier(name)).also { it.awaitResultType = int }
    private fun id(name: String) = JasIdentifier(name)
    private fun lit(value: Int) = JasIntLiteral(value.toLong(), value.toString())
    private fun decl(name: String, value: JasExpression, type: JasType? = int) = JasVariableStatement(name, type, value)
    private fun set(name: String, value: JasExpression) = JasExpressionStatement(JasAssignment(id(name), value))
    private fun block(vararg statements: JasStatement) = JasBlock(statements.toList())

    /** 以参数 `input: Future<int32>`、`flag: bool`、`base: int32` 包装函数体。 */
    private fun function(vararg statements: JasStatement) = JasFunction(
        "f",
        listOf(JasParameter("input", futureInt), JasParameter("flag", bool), JasParameter("base", int)),
        int,
        JasBlock(statements.toList()),
        listOf("public", "static")
    )

    private val parameters get() = mapOf("input" to futureInt, "flag" to bool, "base" to int)

    /**
     * 结构不变量：编号稠密；出口要么前向，要么回到循环头（循环头只由前向边进入一次，
     * 出口是 Branch）；从 0 可达全部块。
     */
    private fun assertWellFormed(plan: CoroutineBlockPlan, context: String) {
        assertNull(plan.reason, context)
        assertEquals(plan.blocks.indices.toList(), plan.blocks.map { it.id }, context)
        plan.blocks.forEach { block ->
            CoroutineBlockPlanner.targets(block.exit).forEach {
                assertTrue(it < plan.blocks.size, "$context: block ${block.id} -> $it out of range")
                assertTrue(it > block.id || it in plan.loopHeaders, "$context: block ${block.id} -> $it backwards to non-header")
            }
        }
        plan.loopHeaders.forEach { header ->
            assertTrue(plan.blocks[header].exit is CoroutineBlockExit.Branch, "$context: header $header is not a branch")
            assertTrue(plan.blocks[header].resumeBinding == null && plan.blocks[header].assignments.isEmpty(),
                "$context: header $header carries work")
        }
        val reached = mutableSetOf(0)
        val work = ArrayDeque(listOf(0))
        while (work.isNotEmpty()) {
            CoroutineBlockPlanner.targets(plan.blocks[work.removeFirst()].exit).forEach { if (reached.add(it)) work += it }
        }
        assertEquals(plan.blocks.indices.toSet(), reached, "$context: unreachable blocks")
    }

    /**
     * 做什么：分支内挂起、else 全部 return 时，else 起始块必须直接 Finish，不能接汇合边；
     *   汇合块之后的恢复绑定指向 then 分支内的 await。
     * 失败模式：else 被误接到汇合块（实现中 elvis 回退的真实缺陷形态）。
     */
    @Test
    fun `else that always returns is not joined`() {
        val plan = CoroutineBlockPlanner.plan(function(
            decl("value", lit(0)),
            JasIf(id("flag"), block(set("value", await("input"))), block(JasReturn(lit(7)))),
            JasReturn(id("value"))
        ), parameters)
        assertWellFormed(plan, "else-returns")
        assertEquals(1, plan.suspensionCount)
        val branch = plan.blocks[0].exit as CoroutineBlockExit.Branch
        val elseStart = plan.blocks[branch.whenFalse]
        assertTrue(elseStart.exit is CoroutineBlockExit.Finish, "else block exit: ${elseStart.exit}")
        val suspend = plan.blocks[branch.whenTrue].exit as CoroutineBlockExit.Suspend
        assertEquals("value", plan.blocks[suspend.resume].resumeBinding)
        assertEquals(listOf("value" to int), plan.locals)
    }

    /**
     * 做什么：while 内挂起的块形状：入口前进到循环头，循环头 Branch 进入循环体或出口，
     *   循环体内 await 的恢复块在 continue 时回到循环头，break 前进到出口块；出口块在
     *   循环体之后分配，所以除回边外都是前向边。
     * 失败模式：回边指向循环头以外的块、break 接错块或循环头携带普通赋值。
     */
    @Test
    fun `while loop plans a header with back edges and a forward exit`() {
        val plan = CoroutineBlockPlanner.plan(function(
            decl("total", lit(0)),
            JasWhile(JasBinaryOp(id("total"), ">", lit(10)), block(
                decl("got", await("input")),
                JasIf(id("flag"), block(JasBreakStatement(null)), null),
                set("total", JasBinaryOp(id("total"), "+", id("got"))),
                JasContinueStatement(null)
            )),
            JasReturn(id("total"))
        ), parameters)
        assertWellFormed(plan, "while")
        assertEquals(1, plan.suspensionCount)
        assertEquals(1, plan.loopHeaders.size)
        val header = plan.loopHeaders.single()
        assertEquals(CoroutineBlockExit.Goto(header), plan.blocks[0].exit)
        val branch = plan.blocks[header].exit as CoroutineBlockExit.Branch
        val exit = plan.blocks[branch.whenFalse]
        assertTrue(exit.exit is CoroutineBlockExit.Finish, "loop exit: ${exit.exit}")
        assertTrue(plan.blocks.all { it.id < branch.whenFalse || it.id == branch.whenFalse },
            "loop exit must be allocated after the body")
        val backEdges = plan.blocks.filter { block -> CoroutineBlockPlanner.targets(block.exit).any { it <= block.id } }
        assertTrue(backEdges.isNotEmpty() && backEdges.all { it.exit == CoroutineBlockExit.Goto(header) }, "back edges: $backEdges")
        val breaks = plan.blocks.filter { it.exit == CoroutineBlockExit.Goto(branch.whenFalse) && it.id != header }
        assertEquals(1, breaks.size, "break edges: $breaks")
        assertEquals(listOf("total" to int, "got" to int), plan.locals)
    }

    /**
     * 做什么：仍未支持的形态必须给出具体原因，而不是返回部分计划。
     * 失败模式：do-while / for / 循环后置块 / 带标签或循环外的 break、continue /
     *   return await / 子表达式与循环条件中的 await / return 或 break 之后的不可达语句 /
     *   缺 return 路径 / 重名 / 未写类型的非 await 局部被静默接受。
     */
    @Test
    fun `unsupported shapes are rejected with reasons`() {
        val cases = mapOf(
            "do-while" to function(decl("v", lit(0)), JasDoWhile(block(set("v", await("input"))), id("flag")), JasReturn(id("v"))),
            "for" to function(decl("v", lit(0)), JasForStatement(null, id("flag"), null, block(set("v", await("input")))), JasReturn(id("v"))),
            "loop-suffix" to function(decl("v", lit(0)),
                JasWhile(id("flag"), block(set("v", await("input"))), LoopSuffix(onBreak = block())), JasReturn(id("v"))),
            "loop-condition-await" to function(decl("v", lit(0)),
                JasWhile(JasBinaryOp(await("input"), ">", lit(0)), block(set("v", lit(1)))), JasReturn(id("v"))),
            "labeled-break" to function(decl("v", await("input")), JasWhile(id("flag"), block(JasBreakStatement("outer"))), JasReturn(id("v"))),
            "break-outside-loop" to function(decl("v", await("input")), JasBreakStatement(null), JasReturn(id("v"))),
            "continue-outside-loop" to function(decl("v", await("input")), JasContinueStatement(null), JasReturn(id("v"))),
            "after-break" to function(decl("v", await("input")),
                JasWhile(id("flag"), block(JasBreakStatement(null), set("v", lit(1)))), JasReturn(id("v"))),
            "return-await" to function(JasReturn(await("input"))),
            "nested-await" to function(decl("v", JasBinaryOp(await("input"), "+", lit(1))), JasReturn(id("v"))),
            "condition-await" to function(JasIf(JasBinaryOp(await("input"), ">", lit(0)), block(JasReturn(lit(1))), null), JasReturn(lit(0))),
            "unreachable" to function(JasReturn(lit(1)), decl("v", lit(0))),
            "missing-return" to function(decl("v", await("input")), JasIf(id("flag"), block(JasReturn(id("v"))), null)),
            "duplicate" to function(decl("base", await("input")), JasReturn(id("base"))),
            "branch-duplicate" to function(
                JasIf(id("flag"), block(decl("v", await("input")), JasReturn(id("v"))), block(decl("v", lit(1)), JasReturn(id("v"))))
            ),
            "untyped-local" to function(decl("v", lit(1), null), JasReturn(id("v"))),
            "throw" to function(JasThrow(id("input")))
        )
        for ((name, fn) in cases) {
            val plan = CoroutineBlockPlanner.plan(fn, parameters)
            assertNotNull(plan.reason, "$name unexpectedly planned: ${plan.blocks}")
            assertTrue(plan.blocks.isEmpty(), "$name returned a partial plan")
        }
    }

    // ---------------------------------------------------------------- 属性测试

    /** 循环控制在直接解释器中的跳出信号。 */
    private class LoopJump(val isBreak: Boolean) : RuntimeException(null, null, false, false)

    /** 源码 AST 直接解释器：await 按出现顺序从 inputs 取值；break/continue 用信号跳出。 */
    private class AstInterpreter(val inputs: Iterator<Int>) {
        val env = mutableMapOf<String, Int>()
        var consumed = 0

        fun eval(expression: JasExpression): Int = when (expression) {
            is JasIntLiteral -> expression.value.toInt()
            is JasIdentifier -> env.getValue(expression.name)
            is JasUnaryOp -> { consumed++; inputs.next() }
            is JasBinaryOp -> when (expression.op) {
                "+" -> eval(expression.left) + eval(expression.right)
                ">" -> if (eval(expression.left) > eval(expression.right)) 1 else 0
                else -> error(expression.op)
            }
            else -> error(expression.javaClass.simpleName)
        }

        /** 返回 null 表示序列正常结束（未 return）。 */
        fun run(statements: List<JasStatement>): Int? {
            for (statement in statements) {
                when (statement) {
                    is JasVariableStatement -> env[statement.name] = eval(requireNotNull(statement.initializer))
                    is JasExpressionStatement -> {
                        val assignment = statement.expression as JasAssignment
                        env[(assignment.target as JasIdentifier).name] = eval(assignment.value)
                    }
                    is JasIf -> {
                        val taken = if (eval(statement.condition) != 0) statement.thenBody else statement.elseBody
                        taken?.let { run(it.statements) }?.let { return it }
                    }
                    is JasWhile -> while (eval(statement.condition) != 0) {
                        val returned = try {
                            run(statement.body.statements)
                        } catch (jump: LoopJump) {
                            if (jump.isBreak) break else continue
                        }
                        if (returned != null) return returned
                    }
                    is JasBreakStatement -> throw LoopJump(true)
                    is JasContinueStatement -> throw LoopJump(false)
                    is JasReturn -> return eval(requireNotNull(statement.value))
                    else -> error(statement.javaClass.simpleName)
                }
            }
            return null
        }
    }

    /**
     * 块状态机解释器：逐块执行，挂起点在恢复块入口消费输入；编号必须递增，除非回到
     * 循环头；步数有上限，回边接错导致的死循环直接失败。
     */
    private fun runBlocks(plan: CoroutineBlockPlan, inputs: Iterator<Int>, base: Int): Pair<Int, Int> {
        val env = mutableMapOf("base" to base)
        val ast = AstInterpreter(inputs).also { it.env.putAll(env) }
        var phase = 0
        var suspensions = 0
        var steps = 0
        while (true) {
            check(++steps < 100_000) { "block machine did not terminate" }
            val block = plan.blocks[phase]
            block.resumeBinding?.let { ast.env[it] = inputs.next(); suspensions++ }
            block.assignments.forEach { (name, value) -> ast.env[name] = ast.eval(value) }
            val next = when (val exit = block.exit) {
                is CoroutineBlockExit.Goto -> exit.target
                is CoroutineBlockExit.Branch -> if (ast.eval(exit.condition) != 0) exit.whenTrue else exit.whenFalse
                is CoroutineBlockExit.Suspend -> exit.resume
                is CoroutineBlockExit.Finish -> return ast.eval(exit.value) to suspensions
            }
            check(next > phase || next in plan.loopHeaders) { "phase jumped backwards to a non-header: $phase -> $next" }
            phase = next
        }
    }

    /**
     * 随机程序生成器：保证名字唯一、只引用作用域内已声明的局部。循环形如
     * `var cN: int32 = 0; while (cN < k) { cN = cN + 1; ... }`，计数器不作为随机赋值目标，
     * 因此每个循环至多 k 趟，continue 也不会跳过自增。
     */
    private class Generator(val random: Random) {
        private var counter = 0
        private val counters = mutableSetOf<String>()

        fun expression(scope: List<String>): JasExpression {
            val leaf = { if (scope.isNotEmpty() && random.nextBoolean()) JasIdentifier(scope.random(random)) else JasIntLiteral(random.nextLong(-5, 6), "") }
            return if (random.nextInt(3) == 0) JasBinaryOp(leaf(), "+", leaf()) else leaf()
        }

        fun awaitNode() = JasUnaryOp("await", JasIdentifier("input")).also { it.awaitResultType = JasPrimitiveType("int32") }

        /**
         * 生成语句序列；mustReturn 时以 return 结束；inLoop 时可能以 break/continue 结束。
         * 返回 (语句, 是否所有路径都已终止)。
         */
        fun sequence(depth: Int, outer: List<String>, mustReturn: Boolean, inLoop: Boolean = false): Pair<List<JasStatement>, Boolean> {
            val scope = outer.toMutableList()
            val statements = mutableListOf<JasStatement>()
            repeat(random.nextInt(0, 4)) {
                when (random.nextInt(if (depth > 0) 7 else 4)) {
                    0, 1 -> {
                        val name = "v${counter++}"
                        val value = if (random.nextInt(3) == 0) awaitNode() else expression(scope)
                        statements += JasVariableStatement(name, JasPrimitiveType("int32"), value)
                        scope += name
                    }
                    2, 3 -> if (scope.any { it != "base" && it !in counters }) {
                        val target = scope.filter { it != "base" && it !in counters }.random(random)
                        val value = if (random.nextInt(3) == 0) awaitNode() else expression(scope)
                        statements += JasExpressionStatement(JasAssignment(JasIdentifier(target), value))
                    }
                    5, 6 -> {
                        val loopCounter = "c${counter++}"
                        counters += loopCounter
                        statements += JasVariableStatement(loopCounter, JasPrimitiveType("int32"), JasIntLiteral(0, "0"))
                        scope += loopCounter
                        val increment = JasExpressionStatement(JasAssignment(JasIdentifier(loopCounter),
                            JasBinaryOp(JasIdentifier(loopCounter), "+", JasIntLiteral(1, "1"))))
                        // 一半循环体在自增后立即挂起，保证「循环内挂起」形态占足够比例。
                        val head = mutableListOf<JasStatement>(increment)
                        val bodyScope = scope.toMutableList()
                        if (random.nextBoolean()) {
                            val name = "v${counter++}"
                            head += JasVariableStatement(name, JasPrimitiveType("int32"), awaitNode())
                            bodyScope += name
                        }
                        val (body, _) = sequence(depth - 1, bodyScope, random.nextInt(5) == 0, inLoop = true)
                        val limit = JasIntLiteral(random.nextLong(0, 4), "")
                        statements += JasWhile(JasBinaryOp(limit, ">", JasIdentifier(loopCounter)), JasBlock(head + body))
                    }
                    else -> {
                        val condition = JasBinaryOp(expression(scope), ">", JasIntLiteral(random.nextLong(-3, 4), ""))
                        val (thenBody, thenReturns) = sequence(depth - 1, scope, random.nextInt(3) == 0, inLoop)
                        val withElse = random.nextBoolean()
                        val (elseBody, elseReturns) =
                            if (withElse) sequence(depth - 1, scope, random.nextInt(3) == 0, inLoop) else emptyList<JasStatement>() to false
                        statements += JasIf(condition, JasBlock(thenBody), if (withElse) JasBlock(elseBody) else null)
                        if (thenReturns && elseReturns) return statements to true
                    }
                }
            }
            if (mustReturn) {
                statements += JasReturn(if (random.nextInt(4) == 0) JasIntLiteral(random.nextLong(-5, 6), "") else expression(scope))
                return statements to true
            }
            if (inLoop && random.nextInt(3) == 0) {
                statements += if (random.nextBoolean()) JasBreakStatement(null) else JasContinueStatement(null)
                return statements to true
            }
            return statements to false
        }
    }

    /** 计划中是否有挂起点位于某个循环内（挂起块与某循环头互相可达）。 */
    private fun suspendsInsideLoop(plan: CoroutineBlockPlan): Boolean {
        fun reach(from: Int): Set<Int> {
            val seen = mutableSetOf<Int>()
            val work = ArrayDeque(CoroutineBlockPlanner.targets(plan.blocks[from].exit))
            while (work.isNotEmpty()) {
                val next = work.removeFirst()
                if (seen.add(next)) work += CoroutineBlockPlanner.targets(plan.blocks[next].exit)
            }
            return seen
        }
        return plan.blocks.filter { it.exit is CoroutineBlockExit.Suspend }.any { block ->
            plan.loopHeaders.any { header -> header in reach(block.id) && block.id in reach(header) }
        }
    }

    /**
     * 做什么：固定种子 20261029 生成 400 个随机分支/循环/挂起程序，规划必须成功且结构良好；
     *   每个程序再用 4 组随机 base 与 await 输入序列，比对 AST 语义与块状态机语义的返回值
     *   以及消费的 await 次数（= 实际经过的挂起点数）。
     * 失败模式：打印种子、用例与程序 AST；单函数即最小反例。
     */
    @Test
    fun `seeded block machines match direct AST semantics`() {
        val seed = 20261029
        val random = Random(seed)
        var suspendingPrograms = 0
        var loopPrograms = 0
        var suspendingLoops = 0
        repeat(400) { index ->
            val (body, _) = Generator(random).sequence(3, listOf("base"), mustReturn = true)
            val fn = JasFunction("f", listOf(JasParameter("input", futureInt), JasParameter("base", int)), int,
                JasBlock(body), listOf("public", "static"))
            val context = "seed=$seed case=$index body=$body"
            val plan = CoroutineBlockPlanner.plan(fn, mapOf("input" to futureInt, "base" to int))
            assertWellFormed(plan, context)
            if (plan.suspensionCount > 0) suspendingPrograms++
            if (plan.loopHeaders.isNotEmpty()) loopPrograms++
            if (suspendsInsideLoop(plan)) suspendingLoops++
            repeat(4) { round ->
                val base = random.nextInt(-4, 5)
                val inputs = List(4096) { random.nextInt(-6, 7) }
                val direct = AstInterpreter(inputs.iterator()).also { it.env["base"] = base }
                val expected = requireNotNull(direct.run(body)) { "$context: generator produced a fall-through path" }
                val (actual, suspensions) = runBlocks(plan, inputs.iterator(), base)
                assertEquals(expected, actual, "$context round=$round base=$base inputs=$inputs")
                assertEquals(direct.consumed, suspensions, "$context round=$round suspension count")
            }
        }
        assertTrue(suspendingPrograms > 100, "generator too weak: only $suspendingPrograms suspending programs")
        assertTrue(loopPrograms > 100, "generator too weak: only $loopPrograms programs with loops")
        assertTrue(suspendingLoops > 50, "generator too weak: only $suspendingLoops programs suspend inside a loop")
    }
}
