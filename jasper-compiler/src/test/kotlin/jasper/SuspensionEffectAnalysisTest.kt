// S4-C 挂起能力传播：调用声明身份、递归不动点和未知边保守性。
package jasper

import jasper.ast.*
import jasper.checker.SuspensionEffect
import jasper.checker.SuspensionEffectAnalysis
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** 以已解析调用图为输入，独立可达性 oracle 验证传播，不改变源语言 ABI。 */
class SuspensionEffectAnalysisTest {
    private fun function(name: String, statements: List<JasStatement> = emptyList()) =
        JasFunction(name, emptyList(), JasPrimitiveType("int32"), JasBlock(statements), listOf("static"))

    private fun call(target: JasFunction) = JasExpressionStatement(
        JasCall(JasIdentifier(target.name), emptyList()).also { it.resolvedFunction = target })

    private fun await() = JasExpressionStatement(JasUnaryOp("await", JasIdentifier("future")).also {
        it.awaitResultType = JasPrimitiveType("int32")
    })

    /** 前向引用、递归环以及同名声明按真正解析结果传播，不按函数名猜测。 */
    @Test
    fun `resolved declaration identities distinguish same name functions`() {
        val leaf = function("same", listOf(await()))
        val synchronous = function("same")
        val caller = function("caller", listOf(call(leaf)))
        val otherCaller = function("other", listOf(call(synchronous)))
        val effects = SuspensionEffectAnalysis.analyze(listOf(caller, otherCaller, synchronous, leaf))
        assertEquals(SuspensionEffect.SUSPENDING, effects[caller])
        assertEquals(SuspensionEffect.SYNCHRONOUS, effects[otherCaller])
    }

    /** 不能证明的外部/动态调用保留 UNKNOWN，并向调用者传播。 */
    @Test
    fun `unknown edges are not treated as synchronous`() {
        val unresolved = function("dynamic", listOf(JasExpressionStatement(JasCall(JasIdentifier("unknown"), emptyList()))))
        val wrapper = function("wrapper", listOf(call(unresolved)))
        val outside = function("outside")
        val externalCaller = function("external", listOf(call(outside)))
        val effects = SuspensionEffectAnalysis.analyze(listOf(wrapper, unresolved, externalCaller))
        assertEquals(SuspensionEffect.UNKNOWN, effects[wrapper])
        assertEquals(SuspensionEffect.UNKNOWN, effects[externalCaller])
        assertEquals(SuspensionEffect.UNKNOWN, effects[outside])
    }

    /** 无挂起种子的闭合递归环为同步；有种子则整个可达调用链挂起。 */
    @Test
    fun `recursive cycles converge with and without suspension seeds`() {
        for (seeded in listOf(false, true)) {
            val aBody = mutableListOf<JasStatement>()
            val bBody = mutableListOf<JasStatement>()
            val a = function("a", aBody)
            val b = function("b", bBody)
            aBody += call(b)
            bBody += call(a)
            if (seeded) bBody += await()
            val effects = SuspensionEffectAnalysis.analyze(listOf(a, b))
            val expected = if (seeded) SuspensionEffect.SUSPENDING else SuspensionEffect.SYNCHRONOUS
            assertEquals(expected, effects[a])
            assertEquals(expected, effects[b])
        }
    }

    /** 固定种子生成闭合调用图，以逐根可达性检查不动点，并报告最小失败根。 */
    @Test
    fun `seeded graph inference matches suspension reachability`() {
        val seed = 20261025
        val random = Random(seed)
        repeat(64) { round ->
            val bodies = List(8) { mutableListOf<JasStatement>() }
            val functions = bodies.indices.map { function("fn$it", bodies[it]) }
            val seeds = bodies.indices.filter { random.nextInt(4) == 0 }.toSet()
            val edges = bodies.indices.map { bodies.indices.filter { random.nextInt(5) == 0 } }
            for (index in bodies.indices) {
                if (index in seeds) bodies[index] += await()
                for (target in edges[index]) bodies[index] += call(functions[target])
            }
            val effects = SuspensionEffectAnalysis.analyze(functions.shuffled(random))
            for (root in bodies.indices) {
                val seen = mutableSetOf<Int>()
                val pending = ArrayDeque<Int>()
                pending.add(root)
                while (pending.isNotEmpty()) {
                    val current = pending.removeFirst()
                    if (seen.add(current)) pending.addAll(edges[current])
                }
                val expected = if (seen.any { it in seeds }) SuspensionEffect.SUSPENDING else SuspensionEffect.SYNCHRONOUS
                assertEquals(expected, effects[functions[root]], "seed=$seed round=$round minimal-root=$root edges=$edges seeds=$seeds")
            }
        }
    }
}
