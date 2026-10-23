// ============================================================================
// DictConstructorTokenSupport.kt —— `new dict<K,V>()` 构造位置的 token 改写
// ============================================================================
//
// 职责（只做这件事）：
//   在词法 token 流里，把**紧跟在 `new` 之后**的 `dict` 关键字（ANTLR 的
//   JasperParser.Dict）改写为普通标识符 token（JasperParser.Identifier），
//   使 `new dict<string,int32>()` 能通过现有 `classInstanceCreationExpression`
//   规则（该规则要求 `New ... Identifier ...`）解析。其余任何位置的 `dict`
//   原样保留，因此 `dict{...}` 字面量与类型位置 `dict<string,int32>` 的语义
//   完全不变。
//
// 为什么需要：
//   `dict` 在 JasperLexer.g4 中是独立关键字（`Dict : 'dict';`），而构造表达式的
//   语法只接受 Identifier；任务书明确禁止修改 .g4。本文件用与
//   NestedGenericTokenSupport 相同的「只改 token 流、不改文法」策略，把矛盾
//   收敛在解析装配层，不把关键字 hack 扩散到 AST/checker。
//
// 所属层次：
//   compiler 编排层（解析装配子模块）。由 [createNestedGenericAwareParser]
//   在词法扫描后调用一次；不参与类型检查或代码生成。
//
// 依赖方向（单向、禁止环）：
//   本文件 -> jasper.parser（ANTLR 生成物）与 antlr4-runtime；不依赖 checker /
//   translator / backend。
//
// 变化点隔离：
//   「构造位置允许 dict、字面量位置仍要求 dict 关键字」这一处词法/语法矛盾，
//   全部收敛在 [rewriteDictConstructorKeywords] 一个函数里。将来若文法正式接纳
//   `new dict<...>`，删除本文件并去掉调用即可，不影响其它解析逻辑。
//
// 已知边界：
//   - 只改写 `new` 之后、中间允许隐藏通道（空白/注释）的 `dict`；
//   - 不改写 `dict` 作为类型名或字面量关键字出现的其它位置；
//   - token 的文本、行列号与字符区间保持原样，因此诊断仍指向用户写下的 `dict`；
//   - `new dict()`（无显式类型实参）同样被改写，交给 checker 按裸 `dict` 的宽松
//     规则处理；类型实参缺失不会被本文件补全。
// ----------------------------------------------------------------------------

package jasper.compiler

import jasper.parser.JasperParser
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.Token

/**
 * 把构造位置的 `dict` 关键字改写成标识符 token。
 *
 * 做什么：遍历一次 token 列表，若当前 token 是 [JasperParser.Dict]，且它前面
 *   最近的非隐藏通道 token 是 [JasperParser.New]，就把当前 token 的 type 改成
 *   [JasperParser.Identifier]；文本仍为 `dict`，位置信息不动。
 * 什么时候用：[createNestedGenericAwareParser] 词法扫描后、任何语法判定之前。
 * 前置条件：tokens 是完整词法扫描结果（含 EOF），且尚未被解析器消费。
 * 失败模式：不抛异常；非 [CommonToken] 的 token 直接跳过（ANTLR 生成器实际
 *   总是产出 CommonToken，跳过只是防御性兜底）。
 * 为什么原地修改而不是重建列表：只有 token 的 type 变化，文本/位置/list 顺序
 *   全部保留；重建会引入「位置复制是否正确」的额外风险，也没有收益。
 */
internal fun rewriteDictConstructorKeywords(tokens: List<Token>): List<Token> {
    var previousSignificant: Token? = null
    for (token in tokens) {
        if (token.type == JasperParser.Dict &&
            previousSignificant?.type == JasperParser.New &&
            token is CommonToken
        ) {
            // CommonToken.type 是可写属性；只改类型，保留文本与全部位置字段。
            token.type = JasperParser.Identifier
        }
        // 隐藏通道（空白、注释）不参与「前一个有效 token」判定。
        if (token.channel == Token.DEFAULT_CHANNEL) {
            previousSignificant = token
        }
    }
    return tokens
}
