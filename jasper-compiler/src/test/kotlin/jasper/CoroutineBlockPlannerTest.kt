// ============================================================================
// CoroutineBlockPlannerTest.kt —— S4-C 第十至十七片基本块规划器的契约与属性测试
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
//   允许回边，但只允许回到规划器登记的循环头；第十二片另有一组随机程序含 do-while 与三段式 for
//   （continue 前进到条件块 / 步进块）；第十六片另有一组随机程序给循环随机加标签，break /
//   continue 随机跳到任一外层循环；第十七片另有一组随机程序含 try/finally，清理体调用 `mark(k)`
//   记录轨迹，输入中的哨兵值表示挂起点失败：块状态机按 `$region` 展开清理（与发射器相同的算法），
//   两套语义比对结果（含失败）与清理轨迹。
// ============================================================================

package jasper

import jasper.ast.*
import jasper.translator.CoroutineBlockExit
import jasper.translator.CoroutineBlockPlan
import jasper.translator.CoroutineBlockPlanner
import jasper.translator.CoroutineTryRegion
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 被调函数 `mix(a: int32, b: int32): int32` 的声明；只用于回填 resolvedFunction，模拟检查器的解析结果。 */
private val MIX = JasFunction(
    "mix",
    listOf(JasParameter("a", JasPrimitiveType("int32")), JasParameter("b", JasPrimitiveType("int32"))),
    JasPrimitiveType("int32"),
    JasBlock(emptyList()),
    listOf("public", "static")
)

/** 检查器已解析的源码静态调用 `mix(a, b)`。 */
private fun mixCall(a: JasExpression, b: JasExpression, target: JasExpression = JasIdentifier("mix")) =
    JasCall(target, listOf(a, b)).also { it.resolvedFunction = MIX }

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
            // while / for 的循环头求值条件（条件含 await 时先在循环头挂起，恢复块里再分支）；
            // do-while 的循环头是空块，直接进入紧随其后的循环体。
            val exit = plan.blocks[header].exit
            assertTrue(exit is CoroutineBlockExit.Branch || exit is CoroutineBlockExit.Suspend ||
                exit == CoroutineBlockExit.Goto(header + 1),
                "$context: header $header is neither a branch, a condition suspension nor an empty do-while entry: $exit")
            assertTrue(plan.blocks[header].resumeBinding == null && plan.blocks[header].assignments.isEmpty(),
                "$context: header $header carries work")
        }
        // catch 处理块没有前驱边，只由异常路由进入，与入口块一起作为可达性的根。
        val roots = listOf(0) + plan.tryRegions.flatMap { region -> region.handlers.map { it.entry } }
        roots.forEach { entry ->
            assertTrue(plan.blocks[entry].resumeBinding == null, "$context: root $entry is a resume block")
        }
        val reached = roots.toMutableSet()
        val work = ArrayDeque(roots)
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
     * 做什么：三段式 for 与 do-while 的块形状。for：多绑定初始化段（AST 构建器包成 JasBlock）
     *   在入口块执行，continue 与循环体末尾前进到步进块，步进块（`i++` 改写为 `i = i + 1`）回到
     *   循环头；do-while：循环头是空块直接进入循环体，continue 前进到循环体之后的条件块，条件块
     *   回到循环头或前进到出口块。
     * 失败模式：continue 跳过步进 / 条件、初始化段被放进循环头、自增未改写或出口接错。
     */
    @Test
    fun `for and do-while plan step and condition blocks after the body`() {
        val forPlan = CoroutineBlockPlanner.plan(function(
            JasForStatement(
                block(decl("i", lit(0)), decl("total", lit(0))),
                JasBinaryOp(lit(4), ">", id("i")),
                JasPostfixIncDec(id("i"), "++"),
                block(
                    JasIf(id("flag"), block(JasContinueStatement(null)), null),
                    set("total", await("input"))
                )
            ),
            JasReturn(lit(0))
        ), parameters)
        assertWellFormed(forPlan, "for")
        assertEquals(listOf("i" to int, "total" to int), forPlan.locals)
        assertEquals(listOf("i", "total"), forPlan.blocks[0].assignments.map { it.first })
        val forHeader = forPlan.loopHeaders.single()
        assertEquals(CoroutineBlockExit.Goto(forHeader), forPlan.blocks[0].exit)
        val step = forPlan.blocks.single { block ->
            block.exit == CoroutineBlockExit.Goto(forHeader) && block.id != 0
        }
        assertEquals("i", step.assignments.single().first)
        assertEquals(JasBinaryOp(id("i"), "+", JasIntLiteral(1, "1")), step.assignments.single().second)
        val intoStep = forPlan.blocks.filter { it.exit == CoroutineBlockExit.Goto(step.id) }
        assertEquals(2, intoStep.size, "continue and body end must both reach the step block: $intoStep")

        val doPlan = CoroutineBlockPlanner.plan(function(
            decl("n", lit(0)),
            JasDoWhile(block(
                set("n", await("input")),
                JasIf(id("flag"), block(JasContinueStatement(null)), null),
                set("n", JasBinaryOp(id("n"), "+", lit(1)))
            ), JasBinaryOp(lit(3), ">", id("n"))),
            JasReturn(id("n"))
        ), parameters)
        assertWellFormed(doPlan, "do-while")
        val doHeader = doPlan.loopHeaders.single()
        assertEquals(CoroutineBlockExit.Goto(doHeader + 1), doPlan.blocks[doHeader].exit)
        val condition = doPlan.blocks.single { block ->
            (block.exit as? CoroutineBlockExit.Branch)?.whenTrue == doHeader
        }
        assertTrue(condition.id > doHeader + 1, "condition block must follow the body")
        val exit = doPlan.blocks[(condition.exit as CoroutineBlockExit.Branch).whenFalse]
        assertTrue(exit.exit is CoroutineBlockExit.Finish, "do-while exit: ${exit.exit}")
        assertEquals(2, doPlan.blocks.count { it.exit == CoroutineBlockExit.Goto(condition.id) })
    }

    /**
     * 做什么：仍未支持的形态必须给出具体原因，而不是返回部分计划。
     * 失败模式：for-in / 循环后置块 / 步进段非局部写入 / 非 int32 自增 / 带标签或循环外的 break、continue /
     *   转换中的 await / 更早有副作用运算之后、或操作数写局部的短路右操作数 await / 未解析调用、局部接收者调用、安全调用的实参中的 await /
     *   可能抛出或有副作用的运算（含先于 await 的调用实参）之后的 await /
     *   await 操作数内的 await 或局部写入 /
     *   return 或 break 之后的不可达语句 /
     *   缺 return 路径 / 重名 / 未写类型的非 await 局部被静默接受。
     */
    /**
     * 做什么：`label outer: while (flag) { while (flag) { v = await; if (flag) continue outer;
     *   break outer; } }`：continue outer 是回到外层循环头的回边，break outer 前进到外层出口块，
     *   内层循环的出口块没有任何入边以外的跳转；相邻的两个循环可以复用同一标签。
     * 失败模式：带标签跳转接到内层循环、外层出口未分配在内层之后或同名兄弟标签被误拒。
     */
    @Test
    fun `labeled break and continue target the enclosing loop`() {
        val inner = JasWhile(id("flag"), block(
            set("v", await("input")),
            JasIf(id("flag"), block(JasContinueStatement("outer")), null),
            JasBreakStatement("outer")))
        val fn = function(decl("v", lit(0)),
            JasLabeledStatement("outer", JasWhile(id("flag"), block(inner))),
            JasLabeledStatement("outer", JasDoWhile(block(JasBreakStatement("outer")), id("flag"))),
            JasReturn(id("v")))
        val plan = CoroutineBlockPlanner.plan(fn, parameters)
        assertWellFormed(plan, "labeled")
        // 外层循环头是第一个循环头，内层循环头是第二个。
        val (outerHeader, innerHeader) = plan.loopHeaders.sorted()
        val outerExit = (plan.blocks[outerHeader].exit as CoroutineBlockExit.Branch).whenFalse
        val innerExit = (plan.blocks[innerHeader].exit as CoroutineBlockExit.Branch).whenFalse
        assertTrue(outerExit > innerExit, "outer exit must be allocated after the inner loop")
        val gotos = plan.blocks.mapNotNull { block -> (block.exit as? CoroutineBlockExit.Goto)?.let { block.id to it.target } }
        // 挂起恢复块之后的 if：then 分支 continue outer（回到外层循环头），汇合块 break outer（到外层出口）。
        val resume = plan.blocks.single { it.resumeBinding == "v" }.id
        assertTrue(gotos.any { (from, to) -> from > resume && to == outerHeader }, "continue outer must jump to the outer header: $gotos")
        assertTrue(gotos.any { (from, to) -> from > resume && to == outerExit }, "break outer must jump to the outer exit: $gotos")
        assertTrue(gotos.none { (from, to) -> from > resume && to == innerHeader }, "labeled jumps must not reach the inner header")
        // 内层出口只由内层条件为假进入，再无条件回到外层循环头。
        assertEquals(CoroutineBlockExit.Goto(outerHeader), plan.blocks[innerExit].exit)
    }

    @Test
    fun `unsupported shapes are rejected with reasons`() {
        val cases = mapOf(
            "for-in" to function(decl("v", lit(0)), JasForInStatement("x", id("base"), block(set("v", await("input")))), JasReturn(id("v"))),
            "for-suffix" to function(decl("v", lit(0)),
                JasForStatement(null, id("flag"), null, block(set("v", await("input"))), LoopSuffix(onBreak = block())), JasReturn(id("v"))),
            "for-call-update" to function(decl("v", lit(0)),
                JasForStatement(null, id("flag"), JasCall(id("g"), emptyList()), block(set("v", await("input")))), JasReturn(id("v"))),
            "increment-bool" to function(decl("b", JasBoolLiteral(true), bool), JasExpressionStatement(JasPostfixIncDec(id("b"), "++")),
                JasReturn(lit(0))),
            "loop-suffix" to function(decl("v", lit(0)),
                JasWhile(id("flag"), block(set("v", await("input"))), LoopSuffix(onBreak = block())), JasReturn(id("v"))),
            // 短路左操作数被提前到分支前求值：同一表达式中更早的除法尚未求值时拒绝。
            "short-circuit-after-division" to function(JasIf(JasBinaryOp(
                JasBinaryOp(JasBinaryOp(id("base"), "/", lit(2)), ">", lit(0)), "==",
                JasBinaryOp(id("flag"), "&&", JasBinaryOp(await("input"), ">", lit(0)))),
                block(JasReturn(lit(1))), null), JasReturn(lit(0))),
            "short-circuit-operand-writes-local" to function(decl("v", lit(0)), JasIf(JasBinaryOp(id("flag"), "||",
                JasBinaryOp(await("input"), ">", JasAssignment(id("v"), lit(1)))), block(JasReturn(lit(1))), null), JasReturn(id("v"))),
            "unresolved-call-argument-await" to function(JasReturn(JasCall(id("g"), listOf(await("input"))))),
            "local-receiver-call-argument-await" to function(
                JasReturn(mixCall(await("input"), lit(0), JasPropertyAccess(id("base"), "mix")))),
            "safe-call-argument-await" to function(JasReturn(
                JasCall(JasPropertyAccess(id("Owner"), "mix"), listOf(await("input"), lit(0)), isSafe = true)
                    .also { it.resolvedFunction = MIX })),
            "await-after-earlier-call-argument" to function(JasReturn(mixCall(mixCall(lit(1), lit(2)), await("input")))),
            "await-after-call-with-awaited-argument" to function(
                JasReturn(JasBinaryOp(mixCall(await("input"), lit(1)), "+", await("input")))),
            "cast-await" to function(JasReturn(JasCastExpr(await("input"), int))),
            "await-after-call" to function(JasReturn(JasBinaryOp(JasCall(id("g"), emptyList()), "+", await("input")))),
            "await-after-division" to function(JasReturn(JasBinaryOp(JasBinaryOp(id("base"), "/", lit(2)), "+", await("input")))),
            "await-after-awaited-division" to function(
                JasReturn(JasBinaryOp(JasBinaryOp(await("input"), "%", id("base")), "+", await("input")))),
            "await-in-await-operand" to function(
                JasReturn(JasUnaryOp("await", JasCall(id("g"), listOf(await("input")))).also { it.awaitResultType = int })),
            "await-operand-writes-local" to function(decl("v", lit(0)),
                JasReturn(JasBinaryOp(id("v"), "+", JasUnaryOp("await", JasCall(id("g"), listOf(JasAssignment(id("v"), lit(1)))))
                    .also { it.awaitResultType = int }))),
            "unknown-label" to function(decl("v", await("input")), JasWhile(id("flag"), block(JasBreakStatement("outer"))), JasReturn(id("v"))),
            // 内层循环的标签不是外层可见的跳转目标。
            "inner-label-from-outside" to function(decl("v", await("input")), JasWhile(id("flag"), block(
                JasLabeledStatement("inner", JasWhile(id("flag"), block(JasBreakStatement(null)))),
                JasContinueStatement("inner"))), JasReturn(id("v"))),
            "shadowed-label" to function(decl("v", await("input")), JasLabeledStatement("outer", JasWhile(id("flag"), block(
                JasLabeledStatement("outer", JasWhile(id("flag"), block(JasBreakStatement("outer"))))))), JasReturn(id("v"))),
            "labeled-for-in" to function(decl("v", lit(0)),
                JasLabeledStatement("outer", JasForInStatement("x", id("base"), block(set("v", await("input"))))), JasReturn(id("v"))),
            "break-outside-loop" to function(decl("v", await("input")), JasBreakStatement(null), JasReturn(id("v"))),
            "continue-outside-loop" to function(decl("v", await("input")), JasContinueStatement(null), JasReturn(id("v"))),
            "after-break" to function(decl("v", await("input")),
                JasWhile(id("flag"), block(JasBreakStatement(null), set("v", lit(1)))), JasReturn(id("v"))),
            "unreachable" to function(JasReturn(lit(1)), decl("v", lit(0))),
            "missing-return" to function(decl("v", await("input")), JasIf(id("flag"), block(JasReturn(id("v"))), null)),
            "duplicate" to function(decl("base", await("input")), JasReturn(id("base"))),
            "branch-duplicate" to function(
                JasIf(id("flag"), block(decl("v", await("input")), JasReturn(id("v"))), block(decl("v", lit(1)), JasReturn(id("v"))))
            ),
            "untyped-local" to function(decl("v", lit(1), null), JasReturn(id("v"))),
            // 第二十片起 throw 受支持：原「throw」拒绝用例移到 `throw ends the path ...`，断言其规划为 Throw 出口。
            // 捕获参数无法成为帧字段：读取、同名局部声明与嵌套 catch 遮蔽都拒绝；带类型实参的捕获类型拒绝。
            "catch-parameter-read" to function(decl("v", lit(0)), JasTry(block(set("v", await("input"))),
                listOf(JasCatchClause(JasParameter("e", JasNamedType("Error")),
                    block(JasExpressionStatement(JasCall(JasPropertyAccess(id("e"), "message"), emptyList()))))), null),
                JasReturn(id("v"))),
            "catch-parameter-shadows-local" to function(decl("v", lit(0)), JasTry(block(set("v", await("input"))),
                listOf(JasCatchClause(JasParameter("v", JasNamedType("Error")), block(JasReturn(id("v"))))), null),
                JasReturn(id("v"))),
            "catch-generic-type" to function(decl("v", lit(0)), JasTry(block(set("v", await("input"))),
                listOf(JasCatchClause(JasParameter("e", JasNamedType("Box", listOf(int))), block())), null), JasReturn(id("v"))),
            "try-without-catch-or-finally" to function(decl("v", lit(0)), JasTry(block(set("v", await("input"))), emptyList(), null),
                JasReturn(id("v"))),
            "await-in-finally-after-catch" to function(decl("v", lit(0)), JasTry(block(set("v", await("input"))),
                listOf(JasCatchClause(JasParameter("e", JasNamedType("Error")), block())), block(set("v", await("input")))),
                JasReturn(id("v"))),
            "await-in-finally" to function(decl("v", lit(0)), JasTry(block(set("v", lit(1))), emptyList(),
                block(set("v", await("input")))), JasReturn(id("v"))),
            "await-in-finally-condition" to function(decl("v", await("input")), JasTry(block(set("v", lit(1))), emptyList(),
                block(JasIf(JasBinaryOp(await("input"), ">", lit(0)), block(), null))), JasReturn(id("v"))),
            "return-in-finally" to function(decl("v", await("input")), JasTry(block(set("v", lit(1))), emptyList(),
                block(JasReturn(lit(2)))), JasReturn(id("v"))),
            "break-out-of-finally" to function(decl("v", await("input")), JasWhile(id("flag"), block(
                JasTry(block(set("v", lit(1))), emptyList(), block(JasBreakStatement(null))))), JasReturn(id("v"))),
            "labeled-break-in-finally-loop" to function(decl("v", await("input")), JasLabeledStatement("outer", JasWhile(id("flag"), block(
                JasTry(block(set("v", lit(1))), emptyList(), block(JasWhile(id("flag"), block(JasBreakStatement("outer")))))))),
                JasReturn(id("v"))),
            "try-in-finally" to function(decl("v", await("input")), JasTry(block(set("v", lit(1))), emptyList(),
                block(JasTry(block(), emptyList(), block()))), JasReturn(id("v"))),
            "for-in-finally" to function(decl("v", await("input")), JasTry(block(set("v", lit(1))), emptyList(),
                block(JasForStatement(null, id("flag"), null, block()))), JasReturn(id("v"))),
            "finally-duplicate-local" to function(decl("v", await("input")), JasTry(block(decl("w", lit(1))), emptyList(),
                block(decl("w", lit(2)))), JasReturn(id("v"))),
            // defer 体与 finally 体同为同步清理（第十九片）：挂起、return、跳出清理、defer 中再 defer 拒绝。
            "await-in-defer" to function(decl("v", lit(0)), JasDefer(block(set("v", await("input")))), JasReturn(id("v"))),
            "return-in-defer" to function(decl("v", await("input")), JasDefer(block(JasReturn(lit(2)))), JasReturn(id("v"))),
            "break-out-of-defer" to function(decl("v", await("input")), JasWhile(id("flag"), block(
                JasDefer(block(JasBreakStatement(null))), set("v", lit(1)))), JasReturn(id("v"))),
            "defer-in-defer" to function(decl("v", await("input")), JasDefer(block(JasDefer(block()))), JasReturn(id("v"))),
            "defer-in-finally" to function(decl("v", await("input")), JasTry(block(set("v", lit(1))), emptyList(),
                block(JasDefer(block()))), JasReturn(id("v"))),
            // 作用域结束后的路径仍要以 return 结束：defer 不改变「每条路径以 return 结束」的要求。
            "defer-missing-return" to function(decl("v", await("input")), JasDefer(block(set("v", lit(1))))),
            // throw（第二十片）：清理体中抛出、处理体重新抛出捕获参数、构造实参中的 await、throw 之后的语句拒绝。
            "throw-in-finally" to function(decl("v", await("input")), JasTry(block(set("v", lit(1))), emptyList(),
                block(JasThrow(JasNew(JasNamedType("Error"), emptyList())))), JasReturn(id("v"))),
            "throw-in-defer" to function(decl("v", await("input")),
                JasDefer(block(JasThrow(JasNew(JasNamedType("Error"), emptyList())))), JasReturn(id("v"))),
            "rethrow-catch-parameter" to function(decl("v", lit(0)), JasTry(block(set("v", await("input"))), listOf(
                JasCatchClause(JasParameter("e", JasNamedType("Error")), block(JasThrow(id("e"))))), null), JasReturn(id("v"))),
            "await-in-thrown-constructor" to function(decl("v", await("input")),
                JasThrow(JasNew(JasNamedType("Error"), listOf(await("input"))))),
            "statement-after-throw" to function(decl("v", await("input")),
                JasThrow(JasNew(JasNamedType("Error"), emptyList())), JasReturn(id("v")))
        )
        for ((name, fn) in cases) {
            val plan = CoroutineBlockPlanner.plan(fn, parameters)
            assertNotNull(plan.reason, "$name unexpectedly planned: ${plan.blocks}")
            assertTrue(plan.blocks.isEmpty(), "$name returned a partial plan")
        }
    }

    // ---------------------------------------------------------------- 属性测试

    /** 挂起点失败的输入哨兵（正常输入都在 [-6, 6]）。 */
    private val FAIL = 99

    /** 挂起点被取消的输入哨兵（第十八片）。 */
    private val CANCEL = 98

    /**
     * 直接解释器中挂起点失败沿调用栈传播的信号；[cancelled] 区分 FutureCancelledError 与
     * FutureFailureError（按 Future.get() 语义）。
     */
    private class AwaitFailed(val cancelled: Boolean = false, val thrownType: String? = null) :
        RuntimeException(null, null, false, false)


    /** 循环控制在直接解释器中的跳出信号。 */
    private class LoopJump(val isBreak: Boolean, val label: String?) : RuntimeException(null, null, false, false)

    /** 源码 AST 直接解释器：await 按出现顺序从 inputs 取值；break/continue 用信号跳出。 */
    private class AstInterpreter(val inputs: Iterator<Int>) {
        val env = mutableMapOf<String, Int>()
        var consumed = 0
        /** 清理体中 `mark(k)` 的调用轨迹。 */
        val trace = mutableListOf<Int>()
        /** 已执行的源码 throw 次数（第二十片统计用）。 */
        var throwsExecuted = 0

        fun eval(expression: JasExpression): Int = when (expression) {
            is JasIntLiteral -> expression.value.toInt()
            is JasIdentifier -> env.getValue(expression.name)
            is JasUnaryOp -> {
                consumed++
                inputs.next().also { if (it == 99 || it == 98) throw AwaitFailed(cancelled = it == 98) }
            }
            // `mark(k)` 只记录轨迹；其余被调函数是 mix(a, b) = 3a - b：实参自左向右求值，不可交换，顺序错位即失配。
            is JasCall -> if ((expression.target as? JasIdentifier)?.name == "mark") {
                trace += eval(expression.args.single()); 0
            } else expression.args.map(::eval).let { (a, b) -> 3 * a - b }
            is JasBinaryOp -> when (expression.op) {
                // 短路：左操作数决定结果时右操作数（及其中的 await）不求值。
                "&&" -> if (eval(expression.left) != 0 && eval(expression.right) != 0) 1 else 0
                "||" -> if (eval(expression.left) != 0 || eval(expression.right) != 0) 1 else 0
                "+" -> eval(expression.left) + eval(expression.right)
                "-" -> eval(expression.left) - eval(expression.right)
                ">" -> if (eval(expression.left) > eval(expression.right)) 1 else 0
                else -> error(expression.op)
            }
            else -> error(expression.javaClass.simpleName)
        }

        /** 局部写入或 int32 自增 / 自减。 */
        fun write(expression: JasExpression) {
            when (expression) {
                is JasAssignment -> env[(expression.target as JasIdentifier).name] = eval(expression.value)
                is JasPostfixIncDec -> {
                    val name = (expression.target as JasIdentifier).name
                    env[name] = env.getValue(name) + if (expression.op == "++") 1 else -1
                }
                is JasCall -> eval(expression)
                else -> error(expression.javaClass.simpleName)
            }
        }

        /**
         * 运行一个循环；[label] 是包住它的标签。跳转信号的标签为空（最内层）或等于 [label]
         * 时由本循环处理，否则继续向外传播给同名的外层循环。返回 null 表示循环正常结束。
         */
        fun runLoop(statement: JasStatement, label: String?): Int? {
            fun mine(jump: LoopJump) = jump.label == null || jump.label == label
            when (statement) {
                is JasDoWhile -> do {
                    val returned = try {
                        run(statement.body.statements)
                    } catch (jump: LoopJump) {
                        if (!mine(jump)) throw jump
                        if (jump.isBreak) break else continue
                    }
                    if (returned != null) return returned
                } while (eval(statement.condition) != 0)
                is JasForStatement -> {
                    statement.init?.let { run(listOf(it)) }
                    while (statement.condition?.let { eval(it) != 0 } != false) {
                        val returned = try {
                            run(statement.body.statements)
                        } catch (jump: LoopJump) {
                            if (!mine(jump)) throw jump
                            if (jump.isBreak) break else null
                        }
                        if (returned != null) return returned
                        statement.update?.let(::write)
                    }
                }
                is JasWhile -> while (eval(statement.condition) != 0) {
                    val returned = try {
                        run(statement.body.statements)
                    } catch (jump: LoopJump) {
                        if (!mine(jump)) throw jump
                        if (jump.isBreak) break else continue
                    }
                    if (returned != null) return returned
                }
                else -> error(statement.javaClass.simpleName)
            }
            return null
        }

        /**
         * 返回 null 表示序列正常结束（未 return）。每次调用是一个作用域：其中的 defer 体登记到本层，
         * 离开本层时（正常结束、return、跳转信号或失败）按后进先出执行——直接按规范语义解释，
         * 不经过「改写为 try/finally」的降级。
         */
        fun run(statements: List<JasStatement>): Int? {
            val deferred = mutableListOf<JasBlock>()
            try {
                return runScope(statements, deferred)
            } finally {
                deferred.asReversed().forEach { check(run(it.statements) == null) { "defer returned" } }
            }
        }

        private fun runScope(statements: List<JasStatement>, deferred: MutableList<JasBlock>): Int? {
            for (statement in statements) {
                when (statement) {
                    is JasDefer -> deferred += statement.body
                    is JasVariableStatement -> env[statement.name] = eval(requireNotNull(statement.initializer))
                    is JasExpressionStatement -> write(statement.expression)
                    is JasDoWhile, is JasForStatement, is JasWhile -> runLoop(statement, null)?.let { return it }
                    is JasLabeledStatement -> runLoop(statement.statement, statement.label)?.let { return it }
                    is JasIf -> {
                        val taken = if (eval(statement.condition) != 0) statement.thenBody else statement.elseBody
                        taken?.let { run(it.statements) }?.let { return it }
                    }
                    is JasBreakStatement -> throw LoopJump(true, statement.label)
                    is JasContinueStatement -> throw LoopJump(false, statement.label)
                    is JasReturn -> return eval(requireNotNull(statement.value))
                    // 源码 throw 与挂起点失败同一条信号，携带所写的错误类型。
                    is JasThrow -> {
                        throwsExecuted++
                        throw AwaitFailed(thrownType = ((statement.expression as JasNew).type as JasNamedType).name)
                    }
                    // Kotlin 的 try/catch/finally 与源码同义：返回值先求出，跳转与失败信号在清理之后继续
                    // 传播；只有挂起点失败按捕获类型进入 catch，循环跳转信号直接穿过。
                    is JasTry -> try {
                        try {
                            run(statement.body.statements)
                        } catch (failure: AwaitFailed) {
                            val handler = statement.catches.firstOrNull { catches(it.parameter.type as JasNamedType, failure) }
                                ?: throw failure
                            run(handler.body.statements)
                        }
                    } finally {
                        statement.finallyBody?.let { check(run(it.statements) == null) { "cleanup returned" } }
                    }?.let { return it }
                    is JasBlock -> run(statement.statements)?.let { return it }
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
        val (result, suspensions, _) = runBlocksUnwinding(plan, inputs, base)
        return requireNotNull(result) { "block machine failed without failure inputs" } to suspensions
    }

    /**
     * 带清理区域的块状态机解释器：块的 cleanups 在出口前执行（先把 `$region` 退回外层）；
     * 恢复块消费到失败哨兵时，按 `$region` 以编号从大到小展开清理（与发射器生成的展开代码
     * 同一算法），结果为 null。返回 (结果, 挂起点数, 清理轨迹)。
     */
    private fun runBlocksUnwinding(plan: CoroutineBlockPlan, inputs: Iterator<Int>, base: Int): Triple<Int?, Int, List<Int>> {
        val env = mutableMapOf("base" to base)
        val ast = AstInterpreter(inputs).also { it.env.putAll(env) }
        val regions = plan.tryRegions.associateBy(CoroutineTryRegion::id)
        fun cleanup(region: CoroutineTryRegion) {
            ast.env[CoroutineBlockPlanner.REGION_LOCAL] = region.parent
            check(ast.run(region.cleanup.statements) == null) { "cleanup returned" }
        }
        /**
         * 与发射器的异常路由同一算法：按编号从大到小沿 `$region` 走一趟，finally 区域执行清理，catch
         * 区域先退回外层区域再按源码顺序匹配；返回命中的处理块入口，未命中为 null。
         */
        fun route(failure: AwaitFailed): Int? {
            var handler: Int? = null
            plan.tryRegions.sortedByDescending { it.id }.forEach { region ->
                if (handler == null && ast.env[CoroutineBlockPlanner.REGION_LOCAL] == region.id) {
                    cleanup(region)
                    handler = region.handlers.firstOrNull { catches(it.type, failure) }?.entry
                }
            }
            return handler
        }
        var phase = 0
        var suspensions = 0
        var steps = 0
        while (true) {
            check(++steps < 100_000) { "block machine did not terminate" }
            val block = plan.blocks[phase]
            val binding = block.resumeBinding
            if (binding != null) {
                val value = inputs.next()
                suspensions++
                if (value == FAIL || value == CANCEL) {
                    phase = route(AwaitFailed(cancelled = value == CANCEL)) ?: return Triple(null, suspensions, ast.trace)
                    continue
                }
                ast.env[binding] = value
            }
            block.assignments.forEach { (name, value) -> ast.env[name] = ast.eval(value) }
            block.cleanups.forEach { cleanup(regions.getValue(it)) }
            val next = when (val exit = block.exit) {
                is CoroutineBlockExit.Goto -> exit.target
                is CoroutineBlockExit.Branch -> if (ast.eval(exit.condition) != 0) exit.whenTrue else exit.whenFalse
                is CoroutineBlockExit.Suspend -> exit.resume
                is CoroutineBlockExit.Finish -> return Triple(ast.eval(exit.value), suspensions, ast.trace)
                // 段内抛出：块上不登记清理，离开区域与 catch 匹配全部经异常路由。
                is CoroutineBlockExit.Throw -> {
                    check(block.cleanups.isEmpty()) { "throw block ${block.id} carries cleanups" }
                    val type = ((exit.value as JasNew).type as JasNamedType).name
                    route(AwaitFailed(thrownType = type)) ?: return Triple(null, suspensions, ast.trace)
                }
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
    private class Generator(
        val random: Random,
        val extendedLoops: Boolean = false,
        val nestedAwaits: Boolean = false,
        val callAwaits: Boolean = false,
        val shortCircuits: Boolean = false,
        val labels: Boolean = false,
        val tries: Boolean = false,
        val catches: Boolean = false,
        val defers: Boolean = false,
        val throws: Boolean = false
    ) {
        private var counter = 0
        private val counters = mutableSetOf<String>()

        /** nestedAwaits 时叶子有 1/4 是 await，可落在条件、return 值与 `+` / `>` 的任一侧。 */
        fun expression(scope: List<String>, allowCall: Boolean = true): JasExpression {
            val leaf = {
                if (nestedAwaits && random.nextInt(4) == 0) awaitNode()
                else if (scope.isNotEmpty() && random.nextBoolean()) JasIdentifier(scope.random(random))
                else JasIntLiteral(random.nextLong(-5, 6), "")
            }
            // nestedAwaits 时也用不可交换的 `-`：两侧各有 await 时，提升若颠倒先后，消费的输入随之错位。
            val op = if (nestedAwaits && random.nextBoolean()) "-" else "+"
            // callAwaits 时调用只放在表达式最后求值的位置（整个表达式或右操作数，嵌套调用作最后一个
            // 实参），使每个随机程序都落在支持集内：调用之后同一表达式里不再有 await。
            if (callAwaits && allowCall && random.nextInt(3) == 0) {
                fun call(depth: Int): JasExpression = mixCall(leaf(), if (depth > 0 && random.nextBoolean()) call(depth - 1) else leaf())
                return if (random.nextBoolean()) call(1) else JasBinaryOp(leaf(), op, call(1))
            }
            return if (random.nextInt(3) == 0) JasBinaryOp(leaf(), op, leaf()) else leaf()
        }

        /**
         * shortCircuits 时三分之二的条件改写为 `c && d` / `c || d`（可再嵌套一层），d 是任意比较，其中
         * 可含 await 与调用；循环条件只用 `&&`，计数器比较仍在其中一侧，保证至多 k 趟。
         */
        fun combine(condition: JasExpression, scope: List<String>, allowOr: Boolean, depth: Int = 1): JasExpression {
            if (!shortCircuits || random.nextInt(3) == 0) return condition
            val comparison = JasBinaryOp(expression(scope), ">", JasIntLiteral(random.nextLong(-3, 4), ""))
            val other = if (depth > 0) combine(comparison, scope, allowOr, depth - 1) else comparison
            val op = if (allowOr && random.nextBoolean()) "||" else "&&"
            return if (random.nextBoolean()) JasBinaryOp(condition, op, other) else JasBinaryOp(other, op, condition)
        }

        fun awaitNode() = JasUnaryOp("await", JasIdentifier("input")).also { it.awaitResultType = JasPrimitiveType("int32") }

        fun markCall(code: Int) = JasExpressionStatement(JasCall(JasIdentifier("mark"), listOf(JasIntLiteral(code.toLong(), ""))))

        /**
         * 生成语句序列；mustReturn 时以 return 结束；inLoop 时可能以 break/continue 结束。
         * [enclosing] 是由内到外的外层循环标签（无标签为 null），labels 时跳转随机选一层带标签的
         * 外层循环。返回 (语句, 是否所有路径都已终止)。
         */
        fun sequence(
            depth: Int,
            outer: List<String>,
            mustReturn: Boolean,
            inLoop: Boolean = false,
            enclosing: List<String?> = emptyList()
        ): Pair<List<JasStatement>, Boolean> {
            val scope = outer.toMutableList()
            val statements = mutableListOf<JasStatement>()
            repeat(random.nextInt(0, 4)) {
                // labels 时循环体内额外偏向再开一层循环，使「内层循环跳到外层标签」有足够的嵌套可用。
                val choice = when {
                    defers && random.nextInt(4) == 0 -> 8
                    tries && depth > 0 && random.nextInt(3) == 0 -> 7
                    labels && inLoop && depth > 0 && random.nextInt(3) == 0 -> 5
                    else -> random.nextInt(if (depth > 0) 7 else 4)
                }
                when (choice) {
                    8 -> {
                        // defer：清理体与 try/finally 同形（记轨迹、可改写外层局部、可带条件分支），在所在
                        // 序列（块）结束时执行；同一序列可有多个 defer。
                        val code = counter++
                        val cleanup = mutableListOf<JasStatement>(markCall(code))
                        val writable = scope.filter { it != "base" && it !in counters }
                        if (writable.isNotEmpty() && random.nextBoolean()) {
                            cleanup += JasExpressionStatement(JasAssignment(JasIdentifier(writable.random(random)), expression(scope)))
                        }
                        if (random.nextInt(3) == 0) {
                            cleanup += JasIf(JasBinaryOp(expression(scope), ">", JasIntLiteral(0, "0")),
                                JasBlock(listOf(markCall(-code))), null)
                        }
                        statements += JasDefer(JasBlock(cleanup))
                    }
                    7 -> {
                        // try 体可以 return / break / continue 跨出区域；清理体先记轨迹，再随机改写外层
                        // 局部（正常路径上可观察）、可带一个条件分支。
                        val (body, bodyTerminates) = sequence(depth - 1, scope, random.nextInt(4) == 0, inLoop, enclosing)
                        val code = counter++
                        val cleanup = mutableListOf<JasStatement>(markCall(code))
                        val writable = scope.filter { it != "base" && it !in counters }
                        if (writable.isNotEmpty() && random.nextBoolean()) {
                            cleanup += JasExpressionStatement(JasAssignment(JasIdentifier(writable.random(random)), expression(scope)))
                        }
                        if (random.nextInt(3) == 0) {
                            cleanup += JasIf(JasBinaryOp(expression(scope), ">", JasIntLiteral(0, "0")),
                                JasBlock(listOf(markCall(-code))), null)
                        }
                        // catches 时三分之二的 try 带 1～2 个 catch（捕获类型随机，处理体可挂起、跳转与
                        // return），其中一半省略 finally。
                        val handlers = if (!catches || random.nextInt(3) == 0) emptyList() else List(random.nextInt(1, 3)) {
                            val type = listOf("FutureFailureError", "FutureCancelledError", "Error").random(random)
                            val (handlerBody, handlerTerminates) =
                                sequence(depth - 1, scope, random.nextInt(4) == 0, inLoop, enclosing)
                            JasCatchClause(JasParameter("e${counter++}", JasNamedType(type)), JasBlock(handlerBody)) to
                                handlerTerminates
                        }
                        val finallyBody = if (handlers.isNotEmpty() && random.nextBoolean()) null else JasBlock(cleanup)
                        statements += JasTry(JasBlock(body), handlers.map { it.first }, finallyBody)
                        if (bodyTerminates && handlers.all { it.second }) return statements to true
                    }
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
                        // labels 时四分之三的循环带标签；标签名全程唯一，兄弟循环也不复用，便于从 AST 文本定位。
                        val label = if (labels && random.nextInt(4) != 0) "L${counter++}" else null
                        val (body, bodyTerminates) =
                            sequence(depth - 1, bodyScope, random.nextInt(5) == 0, inLoop = true, enclosing = listOf(label) + enclosing)
                        val wrap = { loop: JasStatement -> if (label == null) loop else JasLabeledStatement(label, loop) }
                        val limit = JasIntLiteral(random.nextLong(0, 4), "")
                        // 循环条件里的 await 每趟都重新挂起；输入在 [-6, 6]，计数器每趟自增，仍至多 10 趟。
                        val bound = if (nestedAwaits && random.nextInt(3) == 0) JasBinaryOp(limit, "+", awaitNode()) else limit
                        val condition = combine(JasBinaryOp(bound, ">", JasIdentifier(loopCounter)), scope, allowOr = false)
                        statements += wrap(when (if (extendedLoops) random.nextInt(3) else 0) {
                            0 -> JasWhile(condition, JasBlock(head + body))
                            // do-while：计数器在循环体首句自增，continue 前进到条件块也不会死循环。
                            1 -> JasDoWhile(JasBlock(head + body), condition).also {
                                // 循环体至少执行一趟：所有路径都 return 且没有跳到本循环的 break /
                                // continue 时循环不会正常结束，其后语句不可达，序列就此结束。
                                if (bodyTerminates && !jumpsToThisLoop(body, label)) {
                                    statements += wrap(it)
                                    return statements to true
                                }
                            }
                            // for：计数器改由步进段自增（一半用 `++`，一半用 `=`），continue 前进到
                            // 步进块；计数器的 0 声明移进初始化段。
                            else -> {
                                statements.removeAt(statements.lastIndex)
                                val init = JasVariableStatement(loopCounter, JasPrimitiveType("int32"), JasIntLiteral(0, "0"))
                                val update = if (random.nextBoolean()) JasPostfixIncDec(JasIdentifier(loopCounter), "++")
                                    else JasAssignment(JasIdentifier(loopCounter),
                                        JasBinaryOp(JasIdentifier(loopCounter), "+", JasIntLiteral(1, "1")))
                                JasForStatement(init, condition, update, JasBlock(head.drop(1) + body))
                            }
                        })
                    }
                    else -> {
                        // 右侧是任意表达式时左侧不放调用：调用之后同一表达式里的 await 不在支持集内。
                        val rightIsExpression = nestedAwaits && random.nextBoolean()
                        val left = expression(scope, allowCall = !rightIsExpression)
                        val condition = combine(JasBinaryOp(left, ">",
                            if (rightIsExpression) expression(scope) else JasIntLiteral(random.nextLong(-3, 4), "")), scope, allowOr = true)
                        val (thenBody, thenReturns) = sequence(depth - 1, scope, random.nextInt(3) == 0, inLoop, enclosing)
                        val withElse = random.nextBoolean()
                        val (elseBody, elseReturns) =
                            if (withElse) sequence(depth - 1, scope, random.nextInt(3) == 0, inLoop, enclosing)
                            else emptyList<JasStatement>() to false
                        statements += JasIf(condition, JasBlock(thenBody), if (withElse) JasBlock(elseBody) else null)
                        if (thenReturns && elseReturns) return statements to true
                    }
                }
            }
            if (mustReturn && throws && random.nextInt(3) == 0) {
                // throws 时三分之一的必终止位置改为抛出随机类型的错误（可被同类型或 Error 的 catch 捕获）。
                val type = listOf("FutureFailureError", "FutureCancelledError", "Error").random(random)
                statements += JasThrow(JasNew(JasNamedType(type), emptyList()))
                return statements to true
            }
            if (mustReturn) {
                statements += JasReturn(if (random.nextInt(4) == 0) JasIntLiteral(random.nextLong(-5, 6), "") else expression(scope))
                return statements to true
            }
            // labels 时跳转更频繁，且优先跳到更外层的带标签循环，保证跨层跳转占足够比例。
            if (inLoop && random.nextInt(if (labels) 2 else 3) == 0) {
                val named = enclosing.filterNotNull()
                val outerNamed = enclosing.drop(1).filterNotNull()
                val target = when {
                    outerNamed.isNotEmpty() && random.nextInt(3) != 0 -> outerNamed.random(random)
                    named.isNotEmpty() && random.nextBoolean() -> named.random(random)
                    else -> null
                }
                statements += if (random.nextBoolean()) JasBreakStatement(target) else JasContinueStatement(target)
                return statements to true
            }
            return statements to false
        }
    }

    /**
     * 做什么：固定种子 20261031 生成 400 个含 while / do-while / 三段式 for 的随机程序，
     *   与上一测试同样比对两套语义；另要求 do-while 与 for 各自出现足够多次。
     * 失败模式：打印种子、用例与程序 AST。
     */
    /**
     * 做什么：固定种子 20261107 生成 400 个随机程序：在第十二片的 while / do-while / for 形态上
     *   四分之三的循环带标签，break / continue 多数跳到随机一层带标签的外层循环；规划必须成功且结构
     *   良好，4 组输入下比对两套语义的返回值与挂起点数；另要求跨层跳转足够多。
     * 失败模式：打印种子、用例与程序 AST。
     */
    @Test
    fun `seeded labeled jumps match direct AST semantics`() {
        val seed = 20261107
        val random = Random(seed)
        var crossLevel = 0
        var suspendingLoops = 0
        repeat(400) { index ->
            val (body, _) = Generator(random, extendedLoops = true, labels = true).sequence(3, listOf("base"), mustReturn = true)
            val fn = JasFunction("f", listOf(JasParameter("input", futureInt), JasParameter("base", int)), int,
                JasBlock(body), listOf("public", "static"))
            val context = "seed=$seed case=$index body=$body"
            if (crossesLoops(body)) crossLevel++
            val plan = CoroutineBlockPlanner.plan(fn, mapOf("input" to futureInt, "base" to int))
            assertWellFormed(plan, context)
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
        assertTrue(crossLevel > 40, "generator too weak: only $crossLevel programs jump across loop levels")
        assertTrue(suspendingLoops > 50, "generator too weak: only $suspendingLoops programs suspend inside a loop")
    }

    /** 程序中是否有从内层循环跳到外层带标签循环的 break / continue。 */
    private fun crossesLoops(statements: List<JasStatement>): Boolean = statements.any { statement ->
        when (statement) {
            is JasLabeledStatement -> loopBody(statement.statement).let { body ->
                innerLoops(body).any { jumpsToThisLoop(loopBody(it), statement.label, nested = true) } || crossesLoops(body)
            }
            is JasWhile, is JasDoWhile, is JasForStatement -> crossesLoops(loopBody(statement))
            is JasIf -> crossesLoops(statement.thenBody.statements) || crossesLoops(statement.elseBody?.statements.orEmpty())
            else -> false
        }
    }

    /** 序列中（穿过 if 与标签）直接出现的循环，不再深入它们的循环体。 */
    private fun innerLoops(statements: List<JasStatement>): List<JasStatement> = statements.flatMap { statement ->
        when (statement) {
            is JasLabeledStatement -> listOf(statement.statement)
            is JasWhile, is JasDoWhile, is JasForStatement -> listOf(statement)
            is JasIf -> innerLoops(statement.thenBody.statements) + innerLoops(statement.elseBody?.statements.orEmpty())
            else -> emptyList()
        }
    }

    private fun loopBody(loop: JasStatement): List<JasStatement> = when (loop) {
        is JasWhile -> loop.body.statements
        is JasDoWhile -> loop.body.statements
        is JasForStatement -> loop.body.statements
        else -> error(loop.javaClass.simpleName)
    }

    @Test
    fun `seeded do-while and for block machines match direct AST semantics`() {
        val seed = 20261031
        val random = Random(seed)
        var doWhilePrograms = 0
        var forPrograms = 0
        var suspendingLoops = 0
        repeat(400) { index ->
            val (body, _) = Generator(random, extendedLoops = true).sequence(3, listOf("base"), mustReturn = true)
            val fn = JasFunction("f", listOf(JasParameter("input", futureInt), JasParameter("base", int)), int,
                JasBlock(body), listOf("public", "static"))
            val context = "seed=$seed case=$index body=$body"
            val text = body.toString()
            if ("JasDoWhile" in text) doWhilePrograms++
            if ("JasForStatement" in text) forPrograms++
            val plan = CoroutineBlockPlanner.plan(fn, mapOf("input" to futureInt, "base" to int))
            assertWellFormed(plan, context)
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
        assertTrue(doWhilePrograms > 60, "generator too weak: only $doWhilePrograms do-while programs")
        assertTrue(forPrograms > 60, "generator too weak: only $forPrograms for programs")
        assertTrue(suspendingLoops > 50, "generator too weak: only $suspendingLoops programs suspend inside a loop")
    }

    private companion object {
        /**
         * 失败对 catch 可见的错误类型名：源码 throw 的是所写类型，挂起点失败 / 取消按 Future.get() 语义
         * 是 FutureFailureError / FutureCancelledError；三者都是 Error 的子类。
         */
        fun errorType(failure: AwaitFailed) =
            failure.thrownType ?: if (failure.cancelled) "FutureCancelledError" else "FutureFailureError"

        /** catch 子句的捕获类型是否接住 [failure]：Error 是所有错误的共同父类。 */
        fun catches(type: JasNamedType, failure: AwaitFailed) = when (type.name) {
            "Error" -> true
            "FutureCancelledError", "FutureFailureError" -> errorType(failure) == type.name
            else -> error(type.name)
        }

        /**
         * 语句序列中是否有以本层循环（标签 [label]）为目标的 break / continue：本层的无标签跳转
         * 穿过 if、不进入内层循环；带本层标签的跳转可以在任意深度的内层循环里。
         */
        fun jumpsToThisLoop(statements: List<JasStatement>, label: String?, nested: Boolean = false): Boolean =
            statements.any { statement ->
                when (statement) {
                    is JasBreakStatement -> if (statement.label == null) !nested else statement.label == label
                    is JasContinueStatement -> if (statement.label == null) !nested else statement.label == label
                    is JasIf -> jumpsToThisLoop(statement.thenBody.statements, label, nested) ||
                        (statement.elseBody?.let { jumpsToThisLoop(it.statements, label, nested) } ?: false)
                    is JasLabeledStatement -> jumpsToThisLoop(listOf(statement.statement), label, nested)
                    is JasTry -> jumpsToThisLoop(statement.body.statements, label, nested) ||
                        statement.catches.any { jumpsToThisLoop(it.body.statements, label, nested) }
                    is JasWhile -> jumpsToThisLoop(statement.body.statements, label, nested = true)
                    is JasDoWhile -> jumpsToThisLoop(statement.body.statements, label, nested = true)
                    is JasForStatement -> jumpsToThisLoop(statement.body.statements, label, nested = true)
                    else -> false
                }
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

    /**
     * 做什么：条件中的 await 在条件所在块挂起，恢复块再分支；while 的循环头直接挂起，
     *   回边回到循环头后再次挂起；return 值中的 await 先求值再 Finish 读临时局部；
     *   `base + await` 的 base 读取留在 await 之后（无副作用、局部只由本帧写入）。
     * 失败模式：分支在挂起前求值、回边跳过条件挂起、临时局部未登记为帧局部。
     */
    @Test
    fun `awaits in conditions and return values are hoisted into temporaries`() {
        val plan = CoroutineBlockPlanner.plan(function(
            decl("v", lit(0)),
            JasWhile(JasBinaryOp(await("input"), ">", id("v")), block(set("v", JasBinaryOp(id("v"), "+", lit(1))))),
            JasIf(JasUnaryOp("!", JasBinaryOp(await("input"), ">", lit(3))), block(JasReturn(lit(-1))), null),
            JasReturn(JasBinaryOp(id("base"), "+", await("input")))
        ), parameters)
        assertWellFormed(plan, "hoisted")
        val temps = plan.locals.map { it.first }.filter { it.startsWith(CoroutineBlockPlanner.TEMP_PREFIX) }
        assertEquals(3, temps.size, "every await gets its own temporary: ${plan.locals}")
        assertTrue(plan.locals.filter { it.first in temps }.all { it.second == int })
        val header = plan.loopHeaders.single()
        val headerExit = plan.blocks[header].exit as CoroutineBlockExit.Suspend
        val resume = plan.blocks[headerExit.resume]
        assertEquals(headerExit.binding, resume.resumeBinding)
        val branch = resume.exit as CoroutineBlockExit.Branch
        assertEquals(JasBinaryOp(id(headerExit.binding), ">", id("v")), branch.condition)
        // 循环体末尾回到挂起的循环头，而不是恢复块。
        assertTrue(plan.blocks.any { it.exit == CoroutineBlockExit.Goto(header) && it.id > header })
        val finish = plan.blocks.map { it.exit }.filterIsInstance<CoroutineBlockExit.Finish>()
            .single { it.value is JasBinaryOp }
        assertEquals(JasBinaryOp(id("base"), "+", id(temps.last())), finish.value)
        assertEquals(3, plan.suspensionCount)

        // 循环体只经 break 离开的 do-while 从不求值条件：条件中的 await 不构成挂起点。
        val dead = CoroutineBlockPlanner.plan(function(
            decl("v", await("input")),
            JasDoWhile(block(JasBreakStatement(null)), JasBinaryOp(await("input"), ">", lit(0))),
            JasReturn(id("v"))
        ), parameters)
        assertWellFormed(dead, "dead do-while condition")
        assertEquals(1, dead.suspensionCount)
    }

    /**
     * 做什么：固定种子 20261101 生成 400 个随机程序，await 可出现在局部写入的子表达式、
     *   if 条件、return 值与 while / do-while / for 条件中；同样比对 AST 直接解释与块状态机
     *   的返回值和经过的挂起点数（AST 解释器按求值顺序从左到右消费输入，提升若改变了 await
     *   的先后即会失配）。另要求条件挂起与表达式内挂起都出现足够多次。
     * 失败模式：打印种子、用例与程序 AST。
     */
    @Test
    fun `seeded hoisted awaits match direct AST semantics`() {
        val seed = 20261101
        val random = Random(seed)
        var conditionSuspends = 0
        var temporaries = 0
        repeat(400) { index ->
            val (body, _) = Generator(random, extendedLoops = true, nestedAwaits = true)
                .sequence(3, listOf("base"), mustReturn = true)
            val fn = JasFunction("f", listOf(JasParameter("input", futureInt), JasParameter("base", int)), int,
                JasBlock(body), listOf("public", "static"))
            val context = "seed=$seed case=$index body=$body"
            val plan = CoroutineBlockPlanner.plan(fn, mapOf("input" to futureInt, "base" to int))
            assertWellFormed(plan, context)
            if (plan.loopHeaders.any { plan.blocks[it].exit is CoroutineBlockExit.Suspend }) conditionSuspends++
            if (plan.locals.any { it.first.startsWith(CoroutineBlockPlanner.TEMP_PREFIX) }) temporaries++
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
        assertTrue(conditionSuspends > 40, "generator too weak: only $conditionSuspends programs suspend in a loop header")
        assertTrue(temporaries > 150, "generator too weak: only $temporaries programs hoist an await")
    }

    /**
     * 做什么：调用实参中的 await 按实参顺序各自提升，调用留在原位、改读临时局部；先于 await
     *   的实参只能是可推迟的局部读取；类型名限定的静态调用与嵌套调用的最后一个实参同样提升。
     * 失败模式：实参提升顺序颠倒、调用被提前到挂起之前、丢失 resolvedFunction（发射后无法复查）。
     */
    @Test
    fun `awaits in static call arguments are hoisted in argument order`() {
        val plan = CoroutineBlockPlanner.plan(function(
            decl("v", mixCall(id("base"), await("input"))),
            JasReturn(mixCall(await("input"), mixCall(await("input"), id("v")), JasPropertyAccess(id("Owner"), "mix")))
        ), parameters)
        assertWellFormed(plan, "call arguments")
        assertEquals(3, plan.suspensionCount)
        val temps = plan.locals.map { it.first }.filter { it.startsWith(CoroutineBlockPlanner.TEMP_PREFIX) }
        assertEquals(3, temps.size, "every awaited argument gets its own temporary: ${plan.locals}")
        val write = plan.blocks.flatMap { it.assignments }.single { it.first == "v" }.second as JasCall
        assertEquals(listOf(id("base"), id(temps[0])), write.args)
        assertTrue(write.resolvedFunction === MIX, "resolved callee must survive the rewrite")
        val finish = plan.blocks.map { it.exit }.filterIsInstance<CoroutineBlockExit.Finish>().single().value as JasCall
        assertEquals(JasPropertyAccess(id("Owner"), "mix"), finish.target)
        assertEquals(id(temps[1]), finish.args[0])
        assertEquals(listOf(id(temps[2]), id("v")), (finish.args[1] as JasCall).args)
    }

    /** 块里规划器产出的全部表达式（局部写入右侧与出口表达式）。 */
    private fun blockExpressions(block: jasper.translator.CoroutineBlock): List<JasExpression> =
        block.assignments.map { it.second } + when (val exit = block.exit) {
            is CoroutineBlockExit.Branch -> listOf(exit.condition)
            is CoroutineBlockExit.Finish -> listOf(exit.value)
            else -> emptyList()
        }

    /** 表达式里是否有调用直接以提升出的临时局部为实参。 */
    private fun readsTempInCallArgument(expression: JasExpression): Boolean = when (expression) {
        is JasCall -> expression.args.any { (it as? JasIdentifier)?.name?.startsWith(CoroutineBlockPlanner.TEMP_PREFIX) == true } ||
            expression.args.any(::readsTempInCallArgument)
        is JasBinaryOp -> readsTempInCallArgument(expression.left) || readsTempInCallArgument(expression.right)
        is JasUnaryOp -> readsTempInCallArgument(expression.operand)
        else -> false
    }

    /**
     * 做什么：固定种子 20261103 生成 400 个随机程序，在第十三片的随机形态上再加入
     *   `mix(a, b) = 3a - b` 调用（实参可含 await，可嵌套在最后一个实参），比对 AST 直接解释与块状态机
     *   的返回值和挂起点数；实参若被颠倒、调用被提前，消费输入或结果即失配。
     * 失败模式：打印种子、用例与程序 AST。
     */
    @Test
    fun `seeded call-argument awaits match direct AST semantics`() {
        val seed = 20261103
        val random = Random(seed)
        var argumentAwaits = 0
        repeat(400) { index ->
            val (body, _) = Generator(random, extendedLoops = true, nestedAwaits = true, callAwaits = true)
                .sequence(3, listOf("base"), mustReturn = true)
            val fn = JasFunction("f", listOf(JasParameter("input", futureInt), JasParameter("base", int)), int,
                JasBlock(body), listOf("public", "static"))
            val context = "seed=$seed case=$index body=$body"
            val plan = CoroutineBlockPlanner.plan(fn, mapOf("input" to futureInt, "base" to int))
            assertWellFormed(plan, context)
            if (plan.blocks.any { block -> blockExpressions(block).any(::readsTempInCallArgument) }) argumentAwaits++
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
        assertTrue(argumentAwaits > 100, "generator too weak: only $argumentAwaits programs mix calls with hoisted awaits")
    }

    /**
     * 计划中短路运算的判定块：写入合成 bool 临时局部并按它分支的块（汇合块里读同一临时局部的
     * if / 循环分支不算）。
     */
    private fun shortCircuitDecisions(plan: CoroutineBlockPlan): List<CoroutineBlockExit.Branch> {
        val boolTemps = plan.locals.filter { it.first.startsWith(CoroutineBlockPlanner.TEMP_PREFIX) && it.second == bool }
            .map { it.first }.toSet()
        return plan.blocks.mapNotNull { block ->
            val exit = block.exit as? CoroutineBlockExit.Branch
            val name = (exit?.condition as? JasIdentifier)?.name
            exit?.takeIf { name in boolTemps && block.assignments.any { it.first == name } }
        }
    }

    /**
     * 做什么：`&&` / `||` 的右操作数含 await 时，左操作数写入合成 bool 临时局部并在当前块分支：
     *   `&&` 为真进入右侧块、为假直接到汇合块，`||` 相反；右侧块挂起后覆写同一临时局部再汇合；
     *   原条件改读该临时局部。循环头起步时先前进到新块，循环头本身不承载写入。
     * 失败模式：右操作数被无条件求值（多挂起一次）、`||` 分支方向接反、临时局部类型错、
     *   循环头被写入。
     */
    @Test
    fun `awaits in short-circuit right operands branch on a bool temporary`() {
        val plan = CoroutineBlockPlanner.plan(function(
            decl("v", lit(0)),
            JasIf(JasBinaryOp(JasBinaryOp(id("base"), ">", lit(0)), "&&", JasBinaryOp(await("input"), ">", lit(3))),
                block(set("v", lit(1))), null),
            JasWhile(JasBinaryOp(JasBinaryOp(id("v"), ">", lit(5)), "||", JasBinaryOp(await("input"), ">", id("v"))),
                block(set("v", JasBinaryOp(id("v"), "+", lit(1))))),
            JasReturn(id("v"))
        ), parameters)
        assertWellFormed(plan, "short circuit")
        assertEquals(2, plan.suspensionCount)
        val decisions = shortCircuitDecisions(plan)
        assertEquals(2, decisions.size, "one decision per short-circuit: ${plan.blocks}")
        val (andExit, orExit) = decisions
        val andDecision = plan.blocks.single { it.exit == andExit }
        val andTemp = (andExit.condition as JasIdentifier).name
        assertEquals(andTemp to JasBinaryOp(id("base"), ">", lit(0)), andDecision.assignments.last())
        // `&&`：为真进入挂起的右侧块，为假直接到汇合块；右侧恢复块覆写临时局部后汇合。
        val andRight = plan.blocks[andExit.whenTrue].exit as CoroutineBlockExit.Suspend
        val andResume = plan.blocks[andRight.resume]
        assertEquals(andTemp to JasBinaryOp(id(andRight.binding), ">", lit(3)), andResume.assignments.single())
        assertEquals(CoroutineBlockExit.Goto(andExit.whenFalse), andResume.exit)
        // 汇合块按临时局部进入 if 的两条分支。
        assertEquals(CoroutineBlockExit.Branch(id(andTemp), andExit.whenFalse + 1, andExit.whenFalse + 2),
            plan.blocks[andExit.whenFalse].exit)
        // `||`：为真直接到汇合块，为假进入右侧块；循环头不写临时局部，先前进到判定块。
        val header = plan.loopHeaders.single()
        val orDecision = plan.blocks.single { it.exit == orExit }
        assertEquals(CoroutineBlockExit.Goto(orDecision.id), plan.blocks[header].exit)
        val orRight = plan.blocks[orExit.whenFalse].exit as CoroutineBlockExit.Suspend
        assertEquals(CoroutineBlockExit.Goto(orExit.whenTrue), plan.blocks[orRight.resume].exit)
        assertTrue(orExit.whenTrue > orRight.resume)
        val loopBranch = plan.blocks[orExit.whenTrue].exit as CoroutineBlockExit.Branch
        assertEquals(id((orExit.condition as JasIdentifier).name), loopBranch.condition)
    }

    /**
     * 做什么：固定种子 20261105 生成 400 个随机程序，在第十四片随机形态上把 if 条件与循环条件
     *   随机改写为 `&&` / `||` 组合（可嵌套，右操作数可含 await 与调用），比对 AST 直接解释（短路求值）
     *   与块状态机的返回值和挂起点数；右操作数若被无条件求值，消费的输入即失配。
     * 失败模式：打印种子、用例与程序 AST。
     */
    @Test
    fun `seeded short-circuit awaits match direct AST semantics`() {
        val seed = 20261105
        val random = Random(seed)
        var shortCircuitPrograms = 0
        var nestedPrograms = 0
        repeat(400) { index ->
            val (body, _) = Generator(random, extendedLoops = true, nestedAwaits = true, callAwaits = true, shortCircuits = true)
                .sequence(3, listOf("base"), mustReturn = true)
            val fn = JasFunction("f", listOf(JasParameter("input", futureInt), JasParameter("base", int)), int,
                JasBlock(body), listOf("public", "static"))
            val context = "seed=$seed case=$index body=$body"
            val plan = CoroutineBlockPlanner.plan(fn, mapOf("input" to futureInt, "base" to int))
            assertWellFormed(plan, context)
            val decisions = shortCircuitDecisions(plan).size
            if (decisions > 0) shortCircuitPrograms++
            if (decisions > 1) nestedPrograms++
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
        assertTrue(shortCircuitPrograms > 100, "generator too weak: only $shortCircuitPrograms programs branch on a short-circuit")
        assertTrue(nestedPrograms > 40, "generator too weak: only $nestedPrograms programs with several short-circuits")
    }

    /**
     * 做什么：嵌套 try/finally 的块形状：进入 try 体的块写区域编号；内层 try 体中 await 之后的
     *   return 先把返回值存入临时局部，再按内、外顺序清理；循环内跨出内层区域的 break 只清理
     *   内层；try 体正常结束的块清理后前进到新块。
     * 失败模式：清理顺序颠倒、返回值在清理之后才求值、break 多清理了循环外的区域。
     */
    @Test
    fun `try finally blocks record regions and run cleanups before leaving`() {
        val plan = CoroutineBlockPlanner.plan(function(
            decl("v", lit(0)),
            JasTry(block(
                JasWhile(id("flag"), block(
                    JasTry(block(
                        set("v", await("input")),
                        JasIf(JasBinaryOp(id("v"), ">", lit(3)), block(JasReturn(id("v"))), null),
                        JasIf(JasBinaryOp(id("v"), ">", lit(1)), block(JasBreakStatement(null)), null)
                    ), emptyList(), block(set("v", JasBinaryOp(id("v"), "+", lit(10)))))
                )),
                set("v", JasBinaryOp(id("v"), "+", lit(1)))
            ), emptyList(), block(JasExpressionStatement(JasCall(id("mark"), listOf(lit(1)))))),
            JasReturn(id("v"))
        ), parameters)
        assertWellFormed(plan, "nested try")
        assertEquals(listOf(1 to 0, 2 to 1), plan.tryRegions.map { it.id to it.parent })
        assertTrue(CoroutineBlockPlanner.REGION_LOCAL to int in plan.locals)
        val entries = plan.blocks.flatMap { block ->
            block.assignments.filter { it.first == CoroutineBlockPlanner.REGION_LOCAL }.map { it.second }
        }
        assertEquals(listOf<JasExpression>(lit(1), lit(2)), entries)
        val finishes = plan.blocks.filter { it.exit is CoroutineBlockExit.Finish }
        // try 体内的 return：返回值存入临时局部，内层先清理；函数末尾的 return 不在区域内，不清理。
        val inner = finishes.single { it.cleanups.isNotEmpty() }
        assertEquals(listOf(2, 1), inner.cleanups)
        val temp = (inner.exit as CoroutineBlockExit.Finish).value as JasIdentifier
        assertTrue(temp.name.startsWith(CoroutineBlockPlanner.TEMP_PREFIX))
        assertEquals(temp.name to id("v"), inner.assignments.last())
        // break 与内层 try 体的正常结束只清理内层区域；外层 try 体正常结束只清理外层。
        val cleanupLists = plan.blocks.filter { it.exit !is CoroutineBlockExit.Finish }.map { it.cleanups }.filter { it.isNotEmpty() }
        assertEquals(listOf(listOf(2), listOf(2), listOf(1)), cleanupLists)
    }

    /** 是否有 try 体（任意深度）中的 return：它必然跨出区域并按由内到外执行清理。 */
    private fun returnsFromRegion(statements: List<JasStatement>, inTry: Boolean): Boolean = statements.any { statement ->
        when (statement) {
            is JasReturn -> inTry
            is JasTry -> returnsFromRegion(statement.body.statements, true)
            is JasIf -> returnsFromRegion(statement.thenBody.statements, inTry) ||
                returnsFromRegion(statement.elseBody?.statements.orEmpty(), inTry)
            is JasLabeledStatement -> returnsFromRegion(listOf(statement.statement), inTry)
            is JasWhile, is JasDoWhile, is JasForStatement -> returnsFromRegion(loopBody(statement), inTry)
            else -> false
        }
    }

    /**
     * 做什么：固定种子 20261109 生成 400 个随机程序：在第十二片循环形态上随机包 try/finally（可嵌套、
     *   可在循环内外，try 体可 return / break / continue 跨出区域），清理体调用 `mark(k)` 并可改写外层
     *   局部；每个程序 4 组输入，输入中约 1/12 是失败哨兵。比对两套语义的结果（失败为 null）、挂起点数
     *   与清理轨迹（顺序与次数）；另要求跨区域跳转、挂起点失败展开与嵌套区域都有足够数量。
     * 失败模式：打印种子、用例、输入与程序 AST。
     */
    @Test
    fun `seeded try finally block machines match direct AST semantics`() {
        val seed = 20261109
        val random = Random(seed)
        var nested = 0
        var unwound = 0
        var leavingJumps = 0
        repeat(400) { index ->
            val (body, _) = Generator(random, extendedLoops = true, tries = true).sequence(3, listOf("base"), mustReturn = true)
            val fn = JasFunction("f", listOf(JasParameter("input", futureInt), JasParameter("base", int)), int,
                JasBlock(body), listOf("public", "static"))
            val context = "seed=$seed case=$index body=$body"
            val plan = CoroutineBlockPlanner.plan(fn, mapOf("input" to futureInt, "base" to int))
            assertWellFormed(plan, context)
            if (plan.tryRegions.any { it.parent != 0 }) nested++
            if (returnsFromRegion(body, inTry = false)) leavingJumps++
            repeat(4) { round ->
                val base = random.nextInt(-4, 5)
                val inputs = List(4096) { if (random.nextInt(12) == 0) FAIL else random.nextInt(-6, 7) }
                val direct = AstInterpreter(inputs.iterator()).also { it.env["base"] = base }
                val expected = try {
                    requireNotNull(direct.run(body)) { "$context: generator produced a fall-through path" }
                } catch (_: AwaitFailed) {
                    null
                }
                val (actual, suspensions, trace) = runBlocksUnwinding(plan, inputs.iterator(), base)
                val detail = "$context round=$round base=$base inputs=${inputs.take(64)}"
                assertEquals(expected, actual, detail)
                assertEquals(direct.consumed, suspensions, "$detail suspension count")
                assertEquals(direct.trace, trace, "$detail cleanup trace")
                if (actual == null && trace.isNotEmpty()) unwound++
            }
        }
        assertTrue(nested > 40, "generator too weak: only $nested programs with nested regions")
        assertTrue(leavingJumps > 40, "generator too weak: only $leavingJumps programs return from inside a region")
        assertTrue(unwound > 40, "generator too weak: only $unwound failed rounds unwound cleanups")
    }

    /**
     * 做什么：`try { v = await input } catch (FutureCancelledError) { v = await input } catch (Error) {
     *   return 7 } finally { mark(1) }` 降级为外层 finally 区域（1）包住内层 catch 区域（2，父区域 1）；
     *   catch 区域没有清理语句，处理块按源码顺序登记、入口没有前驱边且不是恢复块；try 体正常结束
     *   只离开 catch 区域，处理块在 finally 区域内，处理块中的 return 先固定返回值再清理 finally 区域；
     *   try 体与第一个处理块的正常出口汇合后清理 finally 区域。
     * 失败模式：区域嵌套顺序颠倒、处理块落在 catch 区域内（处理体再失败会被自己捕获）、处理块中的
     *   return 漏掉 finally 清理。
     */
    @Test
    fun `try catch finally plans a catch region inside the cleanup region`() {
        val plan = CoroutineBlockPlanner.plan(function(
            decl("v", lit(0)),
            JasTry(block(set("v", await("input"))), listOf(
                JasCatchClause(JasParameter("e", JasNamedType("FutureCancelledError")), block(set("v", await("input")))),
                JasCatchClause(JasParameter("e", JasNamedType("Error")), block(JasReturn(lit(7))))
            ), block(JasExpressionStatement(JasCall(id("mark"), listOf(lit(1)))))),
            JasReturn(id("v"))
        ), parameters)
        assertWellFormed(plan, "try catch finally")
        assertEquals(listOf(1 to 0, 2 to 1), plan.tryRegions.map { it.id to it.parent })
        val (cleanupRegion, catchRegion) = plan.tryRegions
        assertTrue(cleanupRegion.handlers.isEmpty())
        assertTrue(catchRegion.cleanup.statements.isEmpty())
        assertEquals(listOf("FutureCancelledError", "Error"), catchRegion.handlers.map { it.type.name })
        val entries = catchRegion.handlers.map { it.entry }
        // 处理块入口没有前驱边。
        plan.blocks.forEach { block -> entries.forEach { assertTrue(it !in CoroutineBlockPlanner.targets(block.exit)) } }
        // try 体挂起点的恢复块正常结束：只离开 catch 区域。
        val bodyResume = plan.blocks.first { it.resumeBinding == "v" }
        assertEquals(listOf(2), bodyResume.cleanups)
        // 第一个处理块挂起后恢复，正常结束时已不在 catch 区域内，不再离开它。
        val handlerSuspend = plan.blocks[entries[0]].exit as CoroutineBlockExit.Suspend
        assertTrue(plan.blocks[handlerSuspend.resume].cleanups.isEmpty())
        // 第二个处理块的 return：字面量直接返回，只清理 finally 区域。
        val handlerReturn = plan.blocks[entries[1]]
        assertEquals(CoroutineBlockExit.Finish(lit(7)), handlerReturn.exit)
        assertEquals(listOf(1), handlerReturn.cleanups)
        // 汇合块清理 finally 区域后前进到函数末尾的 return。
        assertEquals(1, plan.blocks.count { it.cleanups == listOf(1) && it.exit is CoroutineBlockExit.Goto })
    }

    /** 程序中 catch 子句的数量（任意深度）。 */
    private fun catchCount(statements: List<JasStatement>): Int = statements.sumOf { statement ->
        when (statement) {
            is JasTry -> statement.catches.size + catchCount(statement.body.statements) +
                statement.catches.sumOf { catchCount(it.body.statements) } + catchCount(statement.finallyBody?.statements.orEmpty())
            is JasIf -> catchCount(statement.thenBody.statements) + catchCount(statement.elseBody?.statements.orEmpty())
            is JasLabeledStatement -> catchCount(listOf(statement.statement))
            is JasWhile, is JasDoWhile, is JasForStatement -> catchCount(loopBody(statement))
            else -> 0
        }
    }

    /**
     * 做什么：固定种子 20261111 生成 400 个随机程序：在第十七片的 try/finally 随机程序上，三分之二的 try
     *   带 1～2 个 catch（捕获类型在 FutureFailureError / FutureCancelledError / Error 中随机，处理体可挂起、
     *   跳转、return，一半省略 finally）；输入中各约 1/12 是失败哨兵与取消哨兵。比对源码 AST 直接解释（Kotlin
     *   try/catch/finally）与块状态机异常路由（与发射器同一算法）的结果（未捕获为 null）、挂起点数与清理
     *   轨迹；另要求被捕获后继续执行、未匹配穿过 catch 区域、处理块内挂起各超过 40 例。
     * 失败模式：打印种子、用例、输入与程序 AST。
     */
    @Test
    fun `seeded try catch block machines match direct AST semantics`() {
        val seed = 20261111
        val random = Random(seed)
        var caughtRounds = 0
        var passedThrough = 0
        var suspendingHandlers = 0
        var programsWithCatch = 0
        repeat(400) { index ->
            val (body, _) = Generator(random, extendedLoops = true, tries = true, catches = true)
                .sequence(3, listOf("base"), mustReturn = true)
            val fn = JasFunction("f", listOf(JasParameter("input", futureInt), JasParameter("base", int)), int,
                JasBlock(body), listOf("public", "static"))
            val context = "seed=$seed case=$index body=$body"
            val plan = CoroutineBlockPlanner.plan(fn, mapOf("input" to futureInt, "base" to int))
            assertWellFormed(plan, context)
            if (catchCount(body) > 0) programsWithCatch++
            val handlerEntries = plan.tryRegions.flatMap { region -> region.handlers.map { it.entry } }
            if (handlerEntries.any { entry -> plan.blocks[entry].exit is CoroutineBlockExit.Suspend }) suspendingHandlers++
            repeat(4) { round ->
                val base = random.nextInt(-4, 5)
                val inputs = List(4096) {
                    when (random.nextInt(12)) {
                        0 -> FAIL
                        1 -> CANCEL
                        else -> random.nextInt(-6, 7)
                    }
                }
                val direct = AstInterpreter(inputs.iterator()).also { it.env["base"] = base }
                val expected = try {
                    requireNotNull(direct.run(body)) { "$context: generator produced a fall-through path" }
                } catch (_: AwaitFailed) {
                    null
                }
                val (actual, suspensions, trace) = runBlocksUnwinding(plan, inputs.iterator(), base)
                val detail = "$context round=$round base=$base inputs=${inputs.take(64)}"
                assertEquals(expected, actual, detail)
                assertEquals(direct.consumed, suspensions, "$detail suspension count")
                assertEquals(direct.trace, trace, "$detail cleanup trace")
                val failures = inputs.take(direct.consumed).count { it == FAIL || it == CANCEL }
                if (actual != null && failures > 0) caughtRounds++
                if (actual == null && handlerEntries.isNotEmpty()) passedThrough++
            }
        }
        assertTrue(programsWithCatch > 150, "generator too weak: only $programsWithCatch programs with catch")
        assertTrue(caughtRounds > 40, "generator too weak: only $caughtRounds rounds recovered from a failure")
        assertTrue(passedThrough > 40, "generator too weak: only $passedThrough failures escaped past catch regions")
        assertTrue(suspendingHandlers > 40, "generator too weak: only $suspendingHandlers programs suspend in a handler entry")
    }

    /**
     * 做什么：同一块中的两个 defer 降级为两层嵌套清理区域（先声明的在外层），return 先固定返回值、
     *   再按后声明先执行的顺序清理两层；while 循环体中的 defer 在 continue 与循环体末尾各清理一次，
     *   不越过循环；块末尾的 defer（其后没有语句）降级为空 try 体，离开该块时清理。
     * 失败模式：defer 顺序颠倒（先声明的先执行）、defer 作用域扩大到整个函数（循环体内的 defer 在
     *   每趟结束时不执行）、块末尾的 defer 被丢弃。
     */
    @Test
    fun `defer lowers the rest of its block into a cleanup region`() {
        fun mark(code: Int) = JasExpressionStatement(JasCall(id("mark"), listOf(lit(code))))
        val plan = CoroutineBlockPlanner.plan(function(
            decl("v", lit(0)),
            JasIf(id("flag"), block(set("v", lit(5)), JasDefer(block(mark(4)))), null),
            JasDefer(block(mark(1))),
            JasDefer(block(mark(2))),
            JasWhile(id("flag"), block(
                JasDefer(block(mark(3))),
                set("v", await("input")),
                JasIf(JasBinaryOp(id("v"), ">", lit(1)), block(JasContinueStatement(null)), null),
                set("v", JasBinaryOp(id("v"), "+", lit(1)))
            )),
            JasReturn(id("v"))
        ), parameters)
        assertWellFormed(plan, "defer")
        fun code(region: CoroutineTryRegion) =
            ((region.cleanup.statements.single() as JasExpressionStatement).expression as JasCall).args.single()
        // 区域按进入顺序编号：if 块末尾的 defer（1）、函数体的两个 defer（2 包住 3）、循环体的 defer（4）。
        assertEquals(listOf(1 to 0, 2 to 0, 3 to 2, 4 to 3), plan.tryRegions.map { it.id to it.parent })
        assertEquals(listOf<JasExpression>(lit(4), lit(1), lit(2), lit(3)), plan.tryRegions.map(::code))
        assertTrue(plan.tryRegions.all { it.handlers.isEmpty() })
        // 函数末尾的 return：返回值先存入临时局部，后声明的 defer 先清理。
        val finish = plan.blocks.single { it.exit is CoroutineBlockExit.Finish }
        assertEquals(listOf(3, 2), finish.cleanups)
        val temp = (finish.exit as CoroutineBlockExit.Finish).value as JasIdentifier
        assertTrue(temp.name.startsWith(CoroutineBlockPlanner.TEMP_PREFIX))
        // if 块末尾的 defer 离开 then 块时清理一次；循环体的 defer 在 continue 与循环体末尾各清理一次。
        val others = plan.blocks.filter { it.exit !is CoroutineBlockExit.Finish }.map { it.cleanups }.filter { it.isNotEmpty() }
        assertEquals(listOf(listOf(1), listOf(4), listOf(4)), others)
    }

    /** 同一语句序列（块）中 defer 的最大个数（任意深度）。 */
    private fun maxDefersInBlock(statements: List<JasStatement>): Int {
        val here = statements.count { it is JasDefer }
        val nested = statements.maxOfOrNull { statement ->
            when (statement) {
                is JasTry -> maxOf(maxDefersInBlock(statement.body.statements),
                    statement.catches.maxOfOrNull { maxDefersInBlock(it.body.statements) } ?: 0)
                is JasIf -> maxOf(maxDefersInBlock(statement.thenBody.statements),
                    maxDefersInBlock(statement.elseBody?.statements.orEmpty()))
                is JasLabeledStatement -> maxDefersInBlock(listOf(statement.statement))
                is JasWhile, is JasDoWhile, is JasForStatement -> maxDefersInBlock(loopBody(statement))
                else -> 0
            }
        } ?: 0
        return maxOf(here, nested)
    }

    /** 是否有循环体（任意深度）中直接或经 if / try 含 defer：它在每趟结束时执行。 */
    private fun defersInLoop(statements: List<JasStatement>, inLoop: Boolean): Boolean = statements.any { statement ->
        when (statement) {
            is JasDefer -> inLoop
            is JasTry -> defersInLoop(statement.body.statements, inLoop) ||
                statement.catches.any { defersInLoop(it.body.statements, inLoop) }
            is JasIf -> defersInLoop(statement.thenBody.statements, inLoop) ||
                defersInLoop(statement.elseBody?.statements.orEmpty(), inLoop)
            is JasLabeledStatement -> defersInLoop(listOf(statement.statement), inLoop)
            is JasWhile, is JasDoWhile, is JasForStatement -> defersInLoop(loopBody(statement), true)
            else -> false
        }
    }

    /**
     * 做什么：固定种子 20261113 生成 400 个随机程序：在第十八片的 try/catch/finally 随机程序上，每个
     *   语句位置约 1/4 插入 defer（清理体记轨迹、可改写外层局部、可带条件分支；同块可多个、可在循环体 /
     *   分支 / try 体 / 处理块中）；输入中各约 1/12 是失败与取消哨兵。直接解释器按规范语义执行 defer
     *   （每个块是一个作用域，离开时后进先出，不经过 try/finally 改写），与块状态机比对结果、挂起点数与
     *   清理轨迹；另要求同块多个 defer、循环体内 defer、失败展开 defer 各有足够数量。
     * 失败模式：打印种子、用例、输入与程序 AST。
     */
    @Test
    fun `seeded defer block machines match direct AST semantics`() {
        val seed = 20261113
        val random = Random(seed)
        var multiple = 0
        var looping = 0
        var unwound = 0
        repeat(400) { index ->
            val (body, _) = Generator(random, extendedLoops = true, tries = true, catches = true, defers = true)
                .sequence(3, listOf("base"), mustReturn = true)
            val fn = JasFunction("f", listOf(JasParameter("input", futureInt), JasParameter("base", int)), int,
                JasBlock(body), listOf("public", "static"))
            val context = "seed=$seed case=$index body=$body"
            val plan = CoroutineBlockPlanner.plan(fn, mapOf("input" to futureInt, "base" to int))
            assertWellFormed(plan, context)
            if (maxDefersInBlock(body) >= 2) multiple++
            if (defersInLoop(body, inLoop = false)) looping++
            repeat(4) { round ->
                val base = random.nextInt(-4, 5)
                val inputs = List(4096) {
                    when (random.nextInt(12)) {
                        0 -> FAIL
                        1 -> CANCEL
                        else -> random.nextInt(-6, 7)
                    }
                }
                val direct = AstInterpreter(inputs.iterator()).also { it.env["base"] = base }
                val expected = try {
                    requireNotNull(direct.run(body)) { "$context: generator produced a fall-through path" }
                } catch (_: AwaitFailed) {
                    null
                }
                val (actual, suspensions, trace) = runBlocksUnwinding(plan, inputs.iterator(), base)
                val detail = "$context round=$round base=$base inputs=${inputs.take(64)}"
                assertEquals(expected, actual, detail)
                assertEquals(direct.consumed, suspensions, "$detail suspension count")
                assertEquals(direct.trace, trace, "$detail cleanup trace")
                if (actual == null && trace.isNotEmpty()) unwound++
            }
        }
        assertTrue(multiple > 40, "generator too weak: only $multiple programs with several defers in one block")
        assertTrue(looping > 40, "generator too weak: only $looping programs defer inside a loop body")
        assertTrue(unwound > 40, "generator too weak: only $unwound failed rounds unwound cleanups")
    }

    /**
     * 做什么：try/catch/finally 内的 throw 结束路径、块上不登记清理（离开区域经异常路由）；函数顶层 throw
     *   也是合法的路径终点（原拒绝用例 `throw input` 现规划为单块 Throw 出口，类型合法性由检查器负责）。
     * 失败模式：throw 块带清理（正常离开路径重复清理）、throw 后接出错误的后继、顶层 throw 仍被拒绝。
     */
    @Test
    fun `throw ends the path and leaves cleanup to the exception route`() {
        val thrown = JasNew(JasNamedType("Error"), emptyList())
        val plan = CoroutineBlockPlanner.plan(function(
            decl("v", await("input")),
            JasTry(block(
                JasIf(JasBinaryOp(id("v"), ">", lit(0)), block(JasThrow(thrown)), null),
                set("v", lit(1))
            ), listOf(JasCatchClause(JasParameter("e", JasNamedType("Error")), block(JasThrow(thrown)))),
                block(JasExpressionStatement(JasCall(id("mark"), listOf(lit(1)))))),
            JasThrow(thrown)
        ), parameters)
        assertWellFormed(plan, "throw")
        val throws = plan.blocks.filter { it.exit is CoroutineBlockExit.Throw }
        assertEquals(3, throws.size)
        throws.forEach { assertTrue(it.cleanups.isEmpty(), "throw block ${it.id} must not carry cleanups") }
        assertTrue(plan.blocks.none { it.exit is CoroutineBlockExit.Finish })
        // try 体正常结束（v 不大于 0）的块离开 catch 区域与 finally 区域后前进，与 throw 块不同。
        assertTrue(plan.blocks.any { it.cleanups.isNotEmpty() })
        val topLevel = CoroutineBlockPlanner.plan(function(JasThrow(id("input"))), parameters)
        assertNull(topLevel.reason)
        assertEquals(listOf<CoroutineBlockExit>(CoroutineBlockExit.Throw(id("input"))), topLevel.blocks.map { it.exit })
    }

    /**
     * 做什么：固定种子 20261119 生成 400 个随机程序：在第十九片的 try/catch/finally/defer 随机程序上，
     *   三分之一的必终止位置改为 `throw new T()`（T 在 FutureFailureError / FutureCancelledError / Error 中
     *   随机，可在 try 体、处理块、循环体、分支与 defer 作用域内）；输入中各约 1/12 是失败与取消哨兵。直接
     *   解释器按源码语义抛出，与块状态机异常路由比对结果（未捕获为 null）、挂起点数与清理轨迹；另要求
     *   抛出后被捕获并继续、抛出逃出全部区域（且执行过清理）各超过 40 例。
     * 失败模式：打印种子、用例、输入与程序 AST。
     */
    @Test
    fun `seeded throw block machines match direct AST semantics`() {
        val seed = 20261119
        val random = Random(seed)
        var caughtThrows = 0
        var escapedThrows = 0
        var throwPrograms = 0
        repeat(400) { index ->
            val (body, _) = Generator(random, extendedLoops = true, tries = true, catches = true, defers = true, throws = true)
                .sequence(3, listOf("base"), mustReturn = true)
            val fn = JasFunction("f", listOf(JasParameter("input", futureInt), JasParameter("base", int)), int,
                JasBlock(body), listOf("public", "static"))
            val context = "seed=$seed case=$index body=$body"
            val plan = CoroutineBlockPlanner.plan(fn, mapOf("input" to futureInt, "base" to int))
            assertWellFormed(plan, context)
            if (plan.blocks.any { it.exit is CoroutineBlockExit.Throw }) throwPrograms++
            repeat(4) { round ->
                val base = random.nextInt(-4, 5)
                val inputs = List(4096) {
                    when (random.nextInt(12)) {
                        0 -> FAIL
                        1 -> CANCEL
                        else -> random.nextInt(-6, 7)
                    }
                }
                val direct = AstInterpreter(inputs.iterator()).also { it.env["base"] = base }
                var escapedByThrow = false
                val expected = try {
                    requireNotNull(direct.run(body)) { "$context: generator produced a fall-through path" }
                } catch (failure: AwaitFailed) {
                    escapedByThrow = failure.thrownType != null
                    null
                }
                val (actual, suspensions, trace) = runBlocksUnwinding(plan, inputs.iterator(), base)
                val detail = "$context round=$round base=$base inputs=${inputs.take(64)}"
                assertEquals(expected, actual, detail)
                assertEquals(direct.consumed, suspensions, "$detail suspension count")
                assertEquals(direct.trace, trace, "$detail cleanup trace")
                if (actual != null && direct.throwsExecuted > 0) caughtThrows++
                if (escapedByThrow && trace.isNotEmpty()) escapedThrows++
            }
        }
        assertTrue(throwPrograms > 150, "generator too weak: only $throwPrograms programs with throw")
        assertTrue(caughtThrows > 40, "generator too weak: only $caughtThrows rounds caught a thrown error and continued")
        assertTrue(escapedThrows > 40, "generator too weak: only $escapedThrows thrown errors escaped after cleanups")
    }
}
