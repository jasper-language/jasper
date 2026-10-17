package jasper.compiler

/*
 * ============================================================================
 * JasperErrorStrategy.kt —— ANTLR 语法错误恢复策略（compiler 层，错误恢复子模块）
 * ============================================================================
 *
 * 职责：
 *   替换 ANTLR 默认的错误恢复策略，规定「解析出错后如何继续扫描 token」：
 *   丢弃错误 token 直到遇到分号或右花括号这类语句/块边界，从而尽可能恢复出
 *   解析树、让后续的阶段能收集到更多错误，而不是在第一个语法错误处崩溃。
 *
 * 所属层次：
 *   compiler 编排层（与 JasperErrorListener 同属诊断/错误恢复子模块）。
 *   依赖 ANTLR runtime 与生成的 JasperParser 词法常量，被 JasperCompiler 挂载。
 *
 * 与谁协作：
 *   - JasperCompiler.createParser：new 出实例并赋给 parser.errorHandler；
 *   - JasperErrorListener：恢复期间抛出的错误由监听器记录，两者配合完成
 *     「恢复 → 记录 → 继续」的闭环；
 *   - 测试（GrammarRegressionTest / DocExamplesParseTest）也直接使用本策略。
 *
 * 对外契约：
 *   - 覆写 recover 后错误恢复不依赖 ANTLR 的默认同步集，改为固定同步到
 *     SEMI / RBRACE；不改变正确输入的解析结果，只影响出错后的恢复点。
 *
 * 扩展点 / 已知限制：
 *   - 「固定同步到 SEMI / RBRACE」对以花括号/分号分界的语法够用，但对更复杂的
 *     嵌套结构可能恢复得不精确（可能吞掉过多 token），属于有意为之的简单策略；
 *   - 若语法新增其它可作同步点的终结符（如 `}` 之外的块结束符），需同步修改此处；
 *   - recoverInline 目前完全委托父类，未做定制。
 * ----------------------------------------------------------------------------
 */

import jasper.parser.JasperParser
import org.antlr.v4.runtime.DefaultErrorStrategy
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Token

/**
 * 自定义错误恢复策略：把 ANTLR 默认的「按跟随集同步」简化为「同步到语句边界」。
 * 什么时候用：只在 JasperCompiler.createParser 里挂到 Parser 上；也可在测试中
 * 手动赋值给 parser.errorHandler。
 * 为什么这样设计：Jasper 是类 C/Java 的花括号语言，出错后跳到最近的 `;` 或 `}`
 * 通常能重新对齐到合法的语句/块起点，实现简单且足以支撑「一次编译报多个错」的
 * 目标；代价是恢复精度不如完整同步集。
 * 失败模式：本类自身不抛异常；若输入在出错后不再出现 `;`/`}`，循环会一直
 * consume 到 EOF 自然结束（受 while 条件保护，不会死循环）。
 */
class JasperErrorStrategy : DefaultErrorStrategy() {
    /**
     * 覆写：发生识别异常时，跳过 token 直到遇见分号或右花括号。
     * 前置条件：由 ANTLR runtime 在 recover 流程中调用。
     * 边界：遇到 EOF 即停（不消费 EOF）；命中的 SEMI/RBRACE 会被消费掉，
     * 以便解析器可以从这个边界之后重新尝试匹配。
     * 注意：这里刻意不调用 super.recover，完全接管恢复行为。
     */
    override fun recover(p: Parser, e: RecognitionException) {
        // 线性向前丢弃 token，直到撞上语句/块边界或输入耗尽。
        while (p.currentToken.type != Token.EOF) {
            val tokenType = p.currentToken.type
            // 命中可恢复边界：消费掉它并返回，让解析器从下一 token 继续。
            if (tokenType == JasperParser.SEMI || tokenType == JasperParser.RBRACE) {
                p.consume()
                return
            }
            p.consume()
        }
    }

    /**
     * 覆写：内联恢复（单个 token 缺失/多余时的就地恢复）。
     * 目前完全委托父类默认行为，未做定制；之所以显式覆写，是为了留出
     * 将来定制 Jasper 专属内联恢复的扩展点，同时保持当前行为不变。
     */
    override fun recoverInline(p: Parser): Token {
        return super.recoverInline(p)
    }

    /**
     * 把解析器当前「期望的 token 集合」展开为普通 Set<Int>，便于诊断/测试比对。
     * 什么时候用：调试或断言解析器在某状态下期望哪些终结符。
     * 为什么需要：ANTLR 返回的是 IntervalSet，不直接提供 Set 语义；这里手工展开。
     * 边界：getExpectedTokens() 返回 null 时返回空集合（表示无期望信息）。
     */
    fun getExpectedTokenSet(p: Parser): Set<Int> {
        val intervalSet = p.getExpectedTokens() ?: return emptySet()
        val result = mutableSetOf<Int>()
        // IntervalSet 在 ANTLR 4.9 未提供简单的 toSet，这里按 size()/get(i) 逐个取出。
        val n = intervalSet.size()
        var i = 0
        while (i < n) {
            result.add(intervalSet.get(i))
            i++
        }
        return result
    }
}
