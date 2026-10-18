// ============================================================================
// OperatorPrecedenceTest —— 二元运算符优先级的「真编译 + 真加载 + 真执行」回归
// ============================================================================
// 职责：锁住文法 `binaryExpression` 的优先级语义（权威文法 main @ e77f426：
//       每个二元分支的左右操作数都递归为 binaryExpression，用直接左递归恢复优先级）。
//
// 为什么需要这一层：优先级是「编译产物行为」而非纯语法形态——文法解析对了，若 AST
//   构建器把二元运算展平或接错左右子树，运行期数值仍会错。因此这里每条用例都走完整
//   链路（ANTLR 解析 → AST → 类型检查 → 字节码 → JVM 加载 → 反射调用），断言最终数值，
//   而不只是断言「能解析」。
//
// 与谁协作：与 JvmBackendTest（后端运行时）、GrammarRegressionTest（文法决策钉子）
//   互补，专注于「优先级 / 结合性」这一变化点。
//
// 临时文件位置：只写仓库内的 build/tmp/operator-precedence（不写 /tmp 或仓库外路径），
//   用例结束即递归删除。测试进程无需访问 JVM 外的任何资源。
// ============================================================================

package jasper

import jasper.ast.JasBinaryOp
import jasper.ast.JasExpression
import jasper.ast.JasIntLiteral
import jasper.ast.JasperAstBuilder
import jasper.compiler.JasperCompiler
import jasper.parser.JasperLexer
import jasper.parser.JasperParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class OperatorPrecedenceTest {

    // ------------------------------------------------------------------
    // 临时目录：固定在仓库内，避免污染系统 /tmp，也不越界到项目外。
    // ------------------------------------------------------------------

    /**
     * 在仓库内 `build/tmp/operator-precedence` 下建一个唯一临时目录。
     *
     * 前置条件：测试的 cwd 是仓库根（Gradle test 任务默认如此）。
     * 失败模式：若目录无法创建会抛 IOException，测试即失败——这是期望行为，
     *   宁可显式失败也不要写到不可预期的位置。
     */
    private fun createRepoTempDir(prefix: String): Path {
        val base = Paths.get("build", "tmp", "operator-precedence")
        Files.createDirectories(base)
        return Files.createTempDirectory(base, prefix)
    }

    // ------------------------------------------------------------------
    // 运行期断言：把一段表达式包成 `main`，编译后加载并反射调用取回结果。
    // ------------------------------------------------------------------

    /**
     * 把 `expr` 作为 `main(): <T>` 的函数体编译执行并断言返回值。
     *
     * 做什么：根据期望值的类型选择返回类型（Boolean → Bool，其余 → int32），
     *         `return <expr>;` 后编译、加载 `test.main`、调用静态 `main` 并比对。
     * 何时用：优先级/结合性用例的快速回归；每条断言都覆盖完整编译执行链路。
     * 前置条件：`expr` 必须是合法 Jasper 表达式，且其结果类型与 [expected] 匹配。
     * 失败模式：编译失败或返回类型不符会以带上下文的断言失败暴露（而非静默通过），
     *           便于定位是文法、AST 还是后端的问题。
     */
    private fun evalMain(expr: String, expected: Any?) {
        val retType = if (expected is Boolean) "Bool" else "int32"
        val source = "package test;\n" +
            "public function main(): $retType {\n" +
            "    return $expr;\n" +
            "}\n"

        val outputDir = createRepoTempDir("prec")
        try {
            val result = JasperCompiler().compileToDir(source, outputDir)
            assertTrue(
                result is JasperCompiler.CompileResult.Success,
                "compile failed for `$expr`: $result"
            )
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("test.main")
            val method = clazz.getMethod("main")
            val actual = method.invoke(null)
            assertEquals(expected, actual, "`$expr` should evaluate to $expected but got $actual")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 解析层断言：直接对 ANTLR 语法树构建 AST，检查运算符嵌套形状。
    // 与字节码无关，用来把「文法/构建器的树形」与「后端数值」解耦验证。
    // ------------------------------------------------------------------

    /**
     * 只对一段表达式做解析并构建 AST（不编译、不执行）。
     *
     * 前置条件：`src` 是单个合法表达式文本。
     * 失败模式：解析或构建失败会抛异常，测试失败。
     * 为什么单独保留：数值断言依赖后端一致，AST 断言直接锁住「谁是顶层运算符」，
     *   若未来后端数值巧合相同，仍能凭树形发现优先级回归。
     */
    private fun parseExpressionAst(src: String): JasExpression {
        val lexer = JasperLexer(CharStreams.fromString(src))
        val parser = JasperParser(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        val ctx = parser.expression()
        return JasperAstBuilder().visit(ctx) as JasExpression
    }

    // ==================================================================
    // (1) 乘法优先于加法
    // ==================================================================

    @Test
    fun multiplicationBindsTighterThanAddition() {
        evalMain("1 + 2 * 3", 7)
    }

    // ==================================================================
    // (2) 括号覆盖默认优先级
    // ==================================================================

    @Test
    fun parenthesesOverridePrecedence() {
        evalMain("(1 + 2) * 3", 9)
    }

    // ==================================================================
    // (3) 加法/乘法与相等比较组合出的布尔结果
    // ==================================================================

    @Test
    fun arithmeticBeforeEquality_isTrue() {
        evalMain("1 + 2 * 3 == 7", true)
    }

    @Test
    fun arithmeticBeforeEquality_wrongValueIsFalse() {
        evalMain("1 + 2 * 3 == 9", false)
    }

    // ==================================================================
    // (4) 按位与 `&` 优先于按位或 `|`
    // ==================================================================

    @Test
    fun bitAndBindsTighterThanBitOr() {
        // 4 | (1 & 0) = 4 | 0 = 4；若被展平为 (4|1)&0 则为 0。
        evalMain("4 | 1 & 0", 4)
    }

    // ==================================================================
    // (5) 加法优先于移位 `<<`
    // ==================================================================

    @Test
    fun additionBindsTighterThanShift() {
        // 1 << (2 + 1) = 1 << 3 = 8；若被展平为 (1<<2)+1 则为 5。
        evalMain("1 << 2 + 1", 8)
    }

    // ==================================================================
    // (6) 关系/相等结果作为逻辑与 `&&` 的操作数（不加括号）
    // ==================================================================

    @Test
    fun comparisonsAsAndOperands_withoutParens() {
        evalMain("1 + 1 == 2 && 2 + 2 == 4", true)
    }

    // ==================================================================
    // (7) 加法优先于关系比较 `<`
    // ==================================================================

    @Test
    fun additionBindsTighterThanRelational() {
        // (1 + 2) < 4 → 3 < 4 → true
        evalMain("1 + 2 < 4", true)
    }

    // ==================================================================
    // (8) 减法左结合：10 - 3 - 2 = 5（而非 10 - (3 - 2) = 9）
    // ==================================================================

    @Test
    fun subtractionIsLeftAssociative() {
        evalMain("10 - 3 - 2", 5)
    }

    // ==================================================================
    // (9) 解析树形状：`2 + 3 * 4` 顶层是 `+`，右子节点是 `*`
    // ==================================================================

    @Test
    fun parseTree_topNodeIsPlus_rightChildIsStar() {
        val ast = parseExpressionAst("2 + 3 * 4")
        assertTrue(ast is JasBinaryOp, "顶层应为 JasBinaryOp，实际为 ${ast::class.simpleName}")
        assertEquals("+", ast.op, "顶层运算符应为 +（加法低于乘法，故加法在树顶）")
        assertEquals(JasIntLiteral(2L, "2"), ast.left, "左子节点应为字面量 2")

        val right = ast.right
        assertTrue(right is JasBinaryOp, "右子节点应为 JasBinaryOp(*)，实际为 ${right::class.simpleName}")
        assertEquals("*", right.op, "右子节点运算符应为 *")
        assertEquals(JasIntLiteral(3L, "3"), right.left, "`*` 的左操作数应为 3")
        assertEquals(JasIntLiteral(4L, "4"), right.right, "`*` 的右操作数应为 4")
    }
}
