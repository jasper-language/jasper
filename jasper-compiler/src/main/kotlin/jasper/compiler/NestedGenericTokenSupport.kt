// ============================================================================
// NestedGenericTokenSupport.kt —— 嵌套泛型 `>>` / `>>>` 的 token 流拆分
// ============================================================================
//
// 职责（只做这件事）：
//   在「类型实参语境」里，把词法器产出的 `>>`（RSHIFT）拆成一个额外的 `>`（GT）、
//   把 `>>>`（URSHIFT）拆成两个额外的 GT，使 `List<List<int32>>`、
//   `Map<string, List<int32>>`、`A<B<C<int32>>>` 等连写能按既有文法解析；
//   在非类型语境里移位 token 原样保留（`a >> b`、`a >>> b` 语义不变）。
//
// 为什么需要（问题背景）：
//   词法器按「长规则优先」把连续尖括号合并成移位 token（见 JasperLexer.g4 注释
//   第 2 条：避免 >> 被拆成 > >），而 parser 的 `typeArguments : '<' typeArgument
//   ... '>'` 期望独立的 `>`（GT）。用户裁决：支持 `>>` 连写，且**不得**改 .g4。
//   于是只能在 Kotlin 侧的「token 流 / 解析装配」上做拆分。
//
// 所属层次：
//   compiler 编排层（解析装配子模块）。仅被 JasperCompiler.createParser 使用。
//
// 依赖方向（单向，禁止环）：
//   本文件 -> jasper.parser（ANTLR 生成物，不参与分层）与 antlr4-runtime，以及同包
//   的 JasperErrorListener / JasperErrorStrategy。不反向依赖任何下层业务模块。
//
// 为什么用「解析器当判定器」而不是 parser 语境标志（重要设计决策）：
//   最初尝试路线 1「parser 子类在 enterRule(typeArguments) 时置标志、token 源据此惰性
//   拆分」。实测失败：本文法里 `variableDeclarationTail` 等规则的前瞻**跨越整个类型
//   注解**（要看到 `;` 才能决定是 `binding ';'` 还是 `binding '=' inc`），而 ANTLR 的
//   adaptivePredict 在**模拟**中直接 `LA(k)` 拉取 token，此时并未真正进入 typeArguments
//   帧（模拟不调用 enterRule）。于是 `>>` 在标志还来不及置位时就被拉进缓冲，拆分失效。
//   故改用「先用普通 token 解析一次；若错误落在移位 token 上，就把它拆开重解析；
//   反复直到成功或无新的可拆 token」——以解析器自身作语法判定，不做任何文本启发式，
//   既不会把 `a >> b` 拆坏，也能精确定位真正需要拆的 `>>`。
//
// 变化点隔离：
//   「移位 token 还是两个 GT」这一处词法/语法矛盾，全部收敛在本文件：
//     - [syntheticGt]      —— 合成 `>` token（保留行列/字符区间，供报错与 AST 定位）；
//     - [expandShiftTokens]—— 依据「要拆哪些 token」把 base token 列表展开成新列表；
//     - [createNestedGenericAwareParser] —— 迭代判定循环 + 最终解析器装配。
//   不改 .g4，也不把拆分逻辑散落到编译器其它地方。
//
// 已知边界：
//   - 只处理 RSHIFT/URSHIFT；`>>=`/`>>>=`（RSHIFT_ASSIGN/URSHIFT_ASSIGN）不可能出现在
//     类型实参位置，故意不动；
//   - 迭代次数上界 = 源码里移位 token 的个数（每轮至少多拆一个，必然终止）；
//   - 拆分只在「某轮解析把错误指向某个移位 token」时发生，属保守方向：宁可少拆报错，
//     也绝不把真正的移位拆坏。
// ----------------------------------------------------------------------------

package jasper.compiler

import jasper.parser.JasperLexer
import jasper.parser.JasperParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenSource

/**
 * 组装一个「装配好错误监听器与恢复策略」的解析器；`source` 被完整预词法化为 token。
 *
 * 做什么：把 [tokens] 包进 [CommonTokenStream] 与 [NestedGenericParser] 无关的普通
 *   [JasperParser]，移除默认监听器、挂 [JasperErrorListener]、设 [JasperErrorStrategy]。
 * 什么时候用：本文件的判定循环每轮调用；也用于「无候选移位 token」的快速路径。
 * 前置条件：tokens 覆盖完整输入（含 EOF）。
 * 失败模式：不抛异常（构造期）。
 * 为什么这样设计：把「装配解析器」与「决定是否拆分」解耦，循环每轮都能拿到干净的
 *   解析器与错误列表，互不串味。
 */
private fun assembleParser(tokens: TokenSource): Pair<JasperParser, JasperErrorListener> {
    val tokenStream = CommonTokenStream(tokens)
    val parser = JasperParser(tokenStream)
    val errorListener = JasperErrorListener()
    // 换掉默认监听器（会污染 stderr），改用自定义监听器收集。
    parser.removeErrorListeners()
    parser.addErrorListener(errorListener)
    // 用自定义恢复策略：出错后同步到分号/右花括号，尽量继续解析以汇集更多错误。
    parser.errorHandler = JasperErrorStrategy()
    return parser to errorListener
}

/**
 * 依据源移位 token 造第 [offset] 个 GT 合成 token。
 *
 * 做什么：复制源 token 的行号、列号与字符区间，产出文本为 `>` 的 GT token。
 * 什么时候用：[expandShiftTokens] 展开 `>>`/`>>>` 时。
 * 前置条件：source 是 RSHIFT（`>>`）或 URSHIFT（`>>>`）token。
 * 失败模式：不抛异常。源位置为 -1（EOF/合成场景）时不改写字符区间，交给 ANTLR 默认值。
 * 为什么复制位置：让拆出的每个 `>` 带正确位置，保证编译诊断指向用户实际写下的那个字符。
 */
private fun syntheticGt(source: Token, offset: Int): CommonToken {
    val gt = CommonToken(JasperParser.GT, ">")
    gt.line = source.line
    gt.charPositionInLine = source.charPositionInLine + offset
    val start = source.startIndex
    if (start >= 0) {
        gt.startIndex = start + offset
        gt.stopIndex = start + offset
    }
    gt.channel = source.channel
    return gt
}

/**
 * 把 base token 列表展开为「按需拆分后的」token 列表。
 *
 * 做什么：对每个类型为 RSHIFT/URSHIFT 且字符起点命中 [splitStartIndices] 的 token，
 *   替换成 2 个（RSHIFT）或 3 个（URSHIFT）GT；其它 token 原样保留。
 * 什么时候用：判定循环每轮构造待解析 token 列表时。
 * 前置条件：[baseTokens] 为一次完整词法扫描的结果（含 EOF）。
 * 失败模式：不抛异常。
 * 为什么用「字符起点（startIndex）」作为键：它在各轮 token 重排后保持稳定，避免依赖
 *   会被重新编号的 tokenIndex；且合成 GT 类型为 GT（非 RSHIFT），不会与待拆候选混淆。
 */
private fun expandShiftTokens(baseTokens: List<Token>, splitStartIndices: Set<Int>): List<Token> {
    val expanded = ArrayList<Token>(baseTokens.size)
    for (token in baseTokens) {
        val isShift = token.type == JasperParser.RSHIFT || token.type == JasperParser.URSHIFT
        if (isShift && token.startIndex in splitStartIndices) {
            val count = if (token.type == JasperParser.URSHIFT) 3 else 2
            for (offset in 0 until count) {
                expanded.add(syntheticGt(token, offset))
            }
        } else {
            expanded.add(token)
        }
    }
    return expanded
}

/**
 * 对源码做一次完整词法扫描，返回含 EOF 的 token 列表。
 *
 * 做什么：CharStream → [JasperLexer] → [CommonTokenStream]，`fill()` 后取全部 token。
 * 什么时候用：[createNestedGenericAwareParser] 的入口。
 * 前置条件：source 为完整源文本。
 * 失败模式：纯词法，不抛异常（非法字符会产生词法错误 token）。
 * 为什么先扫描：候选移位 token 的行列与字符位置需要先确定，才能做「错误 → 候选」匹配。
 */
private fun lexAll(source: String): List<Token> {
    val lexer = JasperLexer(CharStreams.fromString(source))
    val stream = CommonTokenStream(lexer)
    stream.fill()
    return stream.tokens
}

/**
 * 创建「支持嵌套泛型 `>>` / `>>>` 连写」的解析器。
 *
 * 做什么：先词法扫描找出所有移位 token；若没有候选，直接按普通方式装配解析器。若有候选，
 *   则**以解析器为语法判定器**做「先全拆、再裁剪」的搜索：
 *     1) 把所有候选移位 token 都拆成 GT 先解析一次（这一步必然让真正的类型实参可解析，
 *        但也会把真移位 `a >> b` 拆坏，产生新的语法错误）；
 *     2) 逐个尝试「撤回某个拆分」：若撤回后语法错误数不增加（`<=` 当前最优，且更偏向少拆），
 *        就保留撤回——这样真正的类型实参拆分得以保留，而真移位会被还原；
 *     3) 反复裁剪到无法更好为止，取错误数最少、拆分最少的配置。
 *   最后返回按该配置装配的干净解析器，交给调用方 `sourceFile()`。
 * 什么时候用：JasperCompiler.createParser 的唯一实现；所有源码解析都经过它。
 * 前置条件：source 为完整源文本。
 * 失败模式：不抛异常。解析错误由返回的 [JasperErrorListener] 收集，与既有解析失败行为一致；
 *   若某配置导致解析器抛异常，视作「最差」配置而不被采纳。
 * 为什么这样设计（为什么不是「错误定位到哪个 token 就拆哪个」）：
 *   实测同一 `Map<string, List<int32>>` 在局部变量里错误指向 `>>`、在形参/返回类型里却指向
 *   内侧的 `<`（ANTLR 预测在不同上下文提前失败）。只按错误行列匹配候选会漏拆。改成「全拆 +
 *   裁剪」后不依赖错误落点，用「错误数是否下降」客观判定每个拆分是否有用，既不会把真移位
 *   拆坏（拆坏会新增错误、被裁掉），也能覆盖任意嵌套深度与上下文。
 * 性能：无 `>>`/`>>>` 的源码走快速路径（一次词法 + 一次装配，零额外解析）；含候选但本就能
 *   正常解析的源码，做一次基线解析后即返回（不改动任何 token）；只有「基线解析报错」的源码
 *   才进入「全拆起点 + 逐候选裁剪」搜索，实测每轮首个可裁候选即被接受，总解析次数接近线性。
 */
internal fun createNestedGenericAwareParser(source: String): Pair<JasperParser, JasperErrorListener> {
    // 先做一次与泛型无关、但同属「文法不接受、又不能改 .g4」的 token 规范化：
    // `new dict<K,V>()` 的构造位置需要把 Dict 关键字视作标识符。该改写只命中
    // `new` 之后的位置，不影响 `dict{...}` 字面量与类型位置（见
    // DictConstructorTokenSupport 的模块说明）。
    val baseTokens = rewriteDictConstructorKeywords(lexAll(source))
    val candidateStartIndices = baseTokens
        .filter { it.type == JasperParser.RSHIFT || it.type == JasperParser.URSHIFT }
        .map { it.startIndex }
        .toSet()

    // 快速路径：没有可拆 token，行为与普通装配完全一致。
    if (candidateStartIndices.isEmpty()) {
        return assembleParser(ListTokenSource(baseTokens))
    }

    // 不拆分的基线错误数：最终结果绝不比它更差（安全兜底，保证本修复不会帮倒忙）。
    // 若源码本来就能正常解析（无嵌套泛型，或 `>>` 本就是移位），直接按原样返回，
    // 既不进入搜索、也绝不改动任何 token —— 这是「非类型语境移位语义不变」的硬保证。
    val baseErrorCount = countSyntaxErrors(baseTokens)
    if (baseErrorCount == 0) {
        return assembleParser(ListTokenSource(baseTokens))
    }

    // 第 1 步：全拆一次，作为搜索起点。
    var chosen: MutableSet<Int> = candidateStartIndices.toMutableSet()
    var bestErrorCount = countSyntaxErrors(expandShiftTokens(baseTokens, chosen))

    // 第 2 步：裁剪。撤回一个拆分后若错误数不增加（`<=`，更偏向少拆），就保留撤回。
    // 每轮至少减少一个拆分点，故必然终止。
    var improved = true
    while (improved && chosen.isNotEmpty()) {
        improved = false
        for (candidate in chosen.toList()) {
            val trial = chosen.toMutableSet().apply { remove(candidate) }
            val trialErrorCount = countSyntaxErrors(expandShiftTokens(baseTokens, trial))
            if (trialErrorCount <= bestErrorCount) {
                chosen = trial
                bestErrorCount = trialErrorCount
                improved = true
                break
            }
        }
    }

    // 安全兜底：若搜索得到的配置还不如「完全不拆」，一律退回不拆。
    if (bestErrorCount > baseErrorCount) {
        chosen = mutableSetOf()
    }

    return assembleParser(ListTokenSource(expandShiftTokens(baseTokens, chosen)))
}

/**
 * 用给定 token 列表跑一次解析，返回语法错误条数；解析器抛异常时返回 [Int.MAX_VALUE]。
 *
 * 做什么：装配一次性解析器并调用 `sourceFile()`，只统计 [JasperErrorListener] 收集的错误数。
 * 什么时候用：[createNestedGenericAwareParser] 的搜索循环里评估每个候选拆分配置。
 * 前置条件：tokens 覆盖完整输入（含 EOF）。
 * 失败模式：不抛异常；极端输入导致解析器抛异常时以最大错误数返回，使该配置不被采纳。
 * 为什么以错误数为判据：错误数是与上下文无关的客观指标，避免依赖不稳定的错误落点。
 */
private fun countSyntaxErrors(tokens: List<Token>): Int {
    val (parser, listener) = assembleParser(ListTokenSource(tokens))
    return try {
        parser.sourceFile()
        listener.errors.size
    } catch (_: Exception) {
        Int.MAX_VALUE
    }
}
