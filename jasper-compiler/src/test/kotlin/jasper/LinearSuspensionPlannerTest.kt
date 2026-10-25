// S4-C 直线体布局规划测试：不将计划产出等同于编译器已生成可运行协程。
package jasper

import jasper.ast.*
import jasper.translator.LinearSuspensionPlanner
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 保存跨挂起仍需使用的局部，严格拒绝尚未支持的控制流与嵌套挂起。 */
class LinearSuspensionPlannerTest {
    /** 构建类型检查后的规划输入；此测试只验布局，类型正确性由前端测试覆盖。 */
    private fun function(statements: List<JasStatement>) = JasFunction(
        "sample", listOf(JasParameter("input", JasNamedType("Future", listOf(JasPrimitiveType("int32"))))),
        JasPrimitiveType("int32"), JasBlock(statements), emptyList())

    /** 一个等待结果并不属于进入该挂起点前就存在的局部值。 */
    @Test
    fun `save preexisting values but not await result or dead locals`() {
        val await = JasUnaryOp("await", JasIdentifier("input"))
        await.sourceLine = 7
        val plan = LinearSuspensionPlanner.plan(function(listOf(
            JasVariableStatement("live", null, JasIntLiteral(7, "7"), false),
            JasVariableStatement("dead", null, JasIntLiteral(9, "9"), false),
            JasVariableStatement("result", null, await, false),
            JasReturn(JasBinaryOp(JasIdentifier("live"), "+", JasIdentifier("result")))
        ))) { true }
        assertEquals(null, plan.unsupported)
        val point = plan.points.single()
        assertEquals(2, point.statementIndex)
        assertEquals("result", point.resultBinding)
        assertEquals(setOf("live"), point.liveLocals)
        assertSame(await, point.expression)
        assertEquals(7, point.expression.sourceLine)
    }

    /** 普通值 await 不触发分段；只使用检查器确认的挂起点。 */
    @Test
    fun `ordinary await remains a single synchronous segment`() {
        val plan = LinearSuspensionPlanner.plan(function(listOf(
            JasReturn(JasUnaryOp("await", JasIntLiteral(42, "42")))
        ))) { false }
        assertEquals(null, plan.unsupported)
        assertTrue(plan.points.isEmpty())
    }

    /** 两个结构相同的 await 仍是不同程序位置，各自使用进入该位置时的局部集合。 */
    @Test
    fun `multiple boundaries retain node identity and distinct frame layouts`() {
        val first = JasUnaryOp("await", JasIdentifier("input"))
        val second = JasUnaryOp("await", JasIdentifier("input"))
        val plan = LinearSuspensionPlanner.plan(function(listOf(
            JasVariableStatement("x", null, first, false),
            JasVariableStatement("y", null, second, false),
            JasReturn(JasBinaryOp(JasIdentifier("x"), "+", JasIdentifier("y")))
        ))) { true }
        assertEquals(null, plan.unsupported)
        assertEquals(2, plan.points.size)
        assertSame(first, plan.points[0].expression)
        assertSame(second, plan.points[1].expression)
        assertEquals(setOf("input"), plan.points[0].liveLocals)
        assertEquals(setOf("x"), plan.points[1].liveLocals)
    }

    /** 不支持的控制流不能留下看似可发射的部分计划；嵌套 await 同样明确拒绝。 */
    @Test
    fun `unsupported control flow and nested await fail without partial plans`() {
        val nested = JasUnaryOp("await", JasIdentifier("input"))
        val expression = JasBinaryOp(JasIntLiteral(1, "1"), "+", nested)
        val plan = LinearSuspensionPlanner.plan(function(listOf(JasReturn(expression)))) { true }
        assertSame(nested, plan.unsupported)
        assertTrue(plan.points.isEmpty())
        val branch = JasIf(JasBoolLiteral(true), JasBlock(emptyList()), null)
        val branched = LinearSuspensionPlanner.plan(function(listOf(branch))) { true }
        assertSame(branch, branched.unsupported)
        assertTrue(branched.points.isEmpty())
    }

    /** 固定种子选择跨段使用集合，逐一收缩为单引用再次检查，保留种子与反例集合。 */
    @Test
    fun `seeded live sets match cross-boundary references`() {
        val seed = 20261025
        val random = Random(seed)
        val names = (0..7).map { "local$it" }
        fun verify(used: Set<String>) {
            val declarations = names.map { JasVariableStatement(it, null, JasIntLiteral(0, "0"), false) }
            val await = JasUnaryOp("await", JasIdentifier("input"))
            val later = used.map { JasExpressionStatement(JasIdentifier(it)) }
            val plan = LinearSuspensionPlanner.plan(function(declarations +
                JasVariableStatement("result", null, await, false) + later + JasReturn(JasIdentifier("result")))) { true }
            assertEquals(used, plan.points.single().liveLocals, "seed=$seed references=$used")
        }
        repeat(64) {
            val used = names.filter { random.nextBoolean() }.toSet()
            try { verify(used) } catch (failure: AssertionError) {
                // 单引用反例优先；若只有组合触发，保留原始集合。
                used.forEach { verify(setOf(it)) }
                throw failure
            }
        }
    }
}
