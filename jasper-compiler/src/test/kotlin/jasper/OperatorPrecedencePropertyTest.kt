// ============================================================================
// OperatorPrecedencePropertyTest —— 二元运算符优先级 / 左结合的固定种子属性测试
// ============================================================================
// 职责：随机生成只含整数字面量与二元运算的表达式树，按优先级表以「最少括号」渲染成
//       源码，再经 ANTLR 解析 + AstBuilder 构建 AST，断言得到的树与生成的树完全相同。
//       English: fixed-seed property test — print a random binary tree with minimal
//       parentheses, parse it back, and require the identical AST.
//
// 为什么需要：OperatorPrecedenceTest 只钉住少数手写样例；本测试在 10 个优先级层、
//   全部符号运算符与任意嵌套深度上检查同一个可判定性质——「最少括号渲染 → 解析」是
//   恒等映射。文法的任一层次被调换、结合性被改成右结合、或构建器接错左右子树，都会
//   产生反例。
//
// 可复现与最小化：种子固定（SEED），失败消息给出种子、轮次、原始反例与最小化后的反例。
//   最小化为贪心收缩：反复尝试用子树替换整棵树、收缩左右子树、把字面量改为 0，
//   只要仍失败就接受，直到不能再缩。
//
// 边界：只覆盖符号形式的二元运算符（`and` / `or` 关键字形式与全角 `｜` 不在生成范围内）、
//   只用非负整数字面量（避免与一元负号交织）；不覆盖 `??`、赋值、三目、一元运算。
// ============================================================================

package jasper

import jasper.ast.JasBinaryOp
import jasper.ast.JasExpression
import jasper.ast.JasIntLiteral
import jasper.ast.JasperAstBuilder
import jasper.parser.JasperLexer
import jasper.parser.JasperParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class OperatorPrecedencePropertyTest {

    private companion object {
        /** 固定种子：失败可用同一源码无状态复现。 */
        const val SEED = 0x1A5F0
        /** 每次运行生成的表达式数。 */
        const val ROUNDS = 400
        /** 生成树的最大深度（根为 0）。 */
        const val MAX_DEPTH = 5

        /**
         * 优先级表：数字越大结合越紧，全部左结合；与文法 binaryExpression 的分支顺序一一对应。
         * English: precedence levels, higher binds tighter, all left-associative.
         */
        val PRECEDENCE: Map<String, Int> = mapOf(
            "*" to 10, "/" to 10, "%" to 10,
            "+" to 9, "-" to 9,
            "<<" to 8, ">>" to 8, ">>>" to 8,
            "<" to 7, ">" to 7, "<=" to 7, ">=" to 7,
            "==" to 6, "!=" to 6,
            "&" to 5,
            "^" to 4,
            "|" to 3,
            "&&" to 2,
            "||" to 1,
        )
        val OPERATORS: List<String> = PRECEDENCE.keys.toList()
    }

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

    /**
     * 生成一棵随机表达式树：叶子为 0–99 的整数字面量，内部节点为随机二元运算。
     * English: random tree of int literals and binary operators.
     */
    private fun generate(random: Random, depth: Int): JasExpression {
        if (depth >= MAX_DEPTH || random.nextInt(100) < 30) return literal(random.nextLong(0, 100))
        val op = OPERATORS[random.nextInt(OPERATORS.size)]
        return JasBinaryOp(generate(random, depth + 1), op, generate(random, depth + 1))
    }

    private fun literal(value: Long) = JasIntLiteral(value, value.toString())

    // ------------------------------------------------------------------
    // 渲染：最少括号
    // ------------------------------------------------------------------

    /**
     * 以最少括号渲染：左子树优先级低于父节点才加括号；右子树优先级不高于父节点就加括号
     * （左结合下 `a - (b - c)` 必须保留括号）。
     * English: minimal-parenthesis printer for left-associative operators.
     */
    private fun render(expr: JasExpression): String = when (expr) {
        is JasIntLiteral -> expr.raw
        is JasBinaryOp -> {
            val p = PRECEDENCE.getValue(expr.op)
            val left = render(expr.left).let { if (precedenceOf(expr.left) < p) "($it)" else it }
            val right = render(expr.right).let { if (precedenceOf(expr.right) <= p) "($it)" else it }
            "$left ${expr.op} $right"
        }
        else -> error("generator produced unexpected node ${expr::class.simpleName}")
    }

    private fun precedenceOf(expr: JasExpression): Int =
        if (expr is JasBinaryOp) PRECEDENCE.getValue(expr.op) else Int.MAX_VALUE

    // ------------------------------------------------------------------
    // 解析：语法错误与未消费的尾部记号都算失败
    // ------------------------------------------------------------------

    /**
     * 把一段表达式文本解析为 AST；任何语法错误或尾部残留都以异常报告。
     * English: parse a full expression; syntax errors and trailing tokens throw.
     */
    private fun parse(src: String): JasExpression {
        val throwing = object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int,
                charPositionInLine: Int, msg: String?, e: RecognitionException?,
            ) {
                throw IllegalStateException("syntax error at $line:$charPositionInLine: $msg")
            }
        }
        val lexer = JasperLexer(CharStreams.fromString(src))
        lexer.removeErrorListeners()
        lexer.addErrorListener(throwing)
        val tokens = CommonTokenStream(lexer)
        val parser = JasperParser(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(throwing)
        val ctx = parser.expression()
        check(tokens.LA(1) == Token.EOF) { "trailing input after expression: '${tokens.LT(1).text}'" }
        return JasperAstBuilder().visit(ctx) as JasExpression
    }

    /** 性质本身：成立返回 null，不成立返回原因。English: the property; null when it holds. */
    private fun violation(expr: JasExpression): String? {
        val src = render(expr)
        val parsed = try {
            parse(src)
        } catch (e: RuntimeException) {
            return "`$src` failed to parse: ${e.message}"
        }
        return if (parsed == expr) null else "`$src` parsed as $parsed, expected $expr"
    }

    // ------------------------------------------------------------------
    // 最小化
    // ------------------------------------------------------------------

    /** 一步收缩候选：子树本身、收缩一侧子树、字面量归零。English: one-step shrink candidates. */
    private fun shrinkCandidates(expr: JasExpression): Sequence<JasExpression> = sequence {
        when (expr) {
            is JasBinaryOp -> {
                yield(expr.left)
                yield(expr.right)
                for (l in shrinkCandidates(expr.left)) yield(JasBinaryOp(l, expr.op, expr.right))
                for (r in shrinkCandidates(expr.right)) yield(JasBinaryOp(expr.left, expr.op, r))
            }
            is JasIntLiteral -> if (expr.value != 0L) yield(literal(0))
            else -> {}
        }
    }

    /** 贪心最小化：只要候选仍违反性质就接受，直到没有更小的反例。English: greedy shrink. */
    private fun minimize(counterexample: JasExpression): JasExpression {
        var current = counterexample
        while (true) {
            current = shrinkCandidates(current).firstOrNull { violation(it) != null } ?: return current
        }
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `minimal parenthesis rendering parses back to the same tree`() {
        val random = Random(SEED)
        var binaryNodes = 0
        repeat(ROUNDS) { round ->
            val expr = generate(random, 0)
            binaryNodes += countBinary(expr)
            val reason = violation(expr)
            if (reason != null) {
                val small = minimize(expr)
                fail(
                    "precedence property violated (seed=$SEED, round=$round)\n" +
                        "  original: $reason\n" +
                        "  minimized: ${violation(small)}"
                )
            }
        }
        // 防止生成器退化为只产出叶子而使性质空洞成立。
        check(binaryNodes > ROUNDS) { "generator degenerated: only $binaryNodes binary nodes" }
    }

    @Test
    fun `renderer keeps parentheses that change the tree`() {
        // 渲染器自身的固定点：右侧同级必须加括号、左侧同级不得加括号。
        val leftNested = JasBinaryOp(JasBinaryOp(literal(10), "-", literal(3)), "-", literal(2))
        val rightNested = JasBinaryOp(literal(10), "-", JasBinaryOp(literal(3), "-", literal(2)))
        assertEquals("10 - 3 - 2", render(leftNested))
        assertEquals("10 - (3 - 2)", render(rightNested))
        assertEquals(null, violation(leftNested))
        assertEquals(null, violation(rightNested))
    }

    private fun countBinary(expr: JasExpression): Int =
        if (expr is JasBinaryOp) 1 + countBinary(expr.left) + countBinary(expr.right) else 0
}
