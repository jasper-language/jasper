package jasper.compiler

import jasper.parser.JasperLexer
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token

/**
 * 源文件的顶层语法模式。
 *
 * `Strict` 是默认模式，只接受正式模块结构；`Script` 是显式脚本模式，
 * 由 `.jass` 扩展名或显式编译 API 选择。模式不通过源码内容推断。
 */
enum class SourceMode {
    Strict,
    Script
}

/** `.jass` 是脚本；其余（包括 `.jas`）默认严格。 */
fun modeForFilename(filename: String): SourceMode =
    if (filename.endsWith(".jass", ignoreCase = true)) SourceMode.Script else SourceMode.Strict

/**
 * 一个已切分的顶层条目。
 *
 * [text] 包含该条目之前的空白与注释，因此重组脚本时不会丢注释；
 * [line]/[column] 指向首个语法 token，用于严格模式拒绝裸语句时给出准确位置。
 */
internal data class TopLevelSourceItem(
    val text: String,
    val isDeclaration: Boolean,
    val line: Int,
    val column: Int
)

/**
 * 源文件的顶层布局。模式已经由调用方确定；这里只负责结构切分，不据内容切换模式。
 */
internal data class SourceLayout(
    val header: String,
    val items: List<TopLevelSourceItem>,
    val trailing: String
) {
    val hasExecutableStatements: Boolean = items.any { !it.isDeclaration }
    val firstExecutable: TopLevelSourceItem? = items.firstOrNull { !it.isDeclaration }

    /** 将脚本顶层语句规范化成一个隐式 `main()`，声明仍保留为文件级声明。 */
    fun asScriptSource(original: String): String {
        if (!hasExecutableStatements) return original

        val declarations = items.filter { it.isDeclaration }.joinToString(separator = "") { it.text }
        val statements = items.filter { !it.isDeclaration }.joinToString(separator = "") { it.text }
        return buildString {
            append(header)
            if (declarations.isNotBlank()) {
                ensureLineBreak()
                append(declarations)
            }
            ensureLineBreak()
            append("function main(): void {\n")
            append(statements)
            if (statements.isNotEmpty() && !statements.endsWith('\n')) append('\n')
            append(trailing)
            if (trailing.isNotEmpty() && !trailing.endsWith('\n')) append('\n')
            append("}\n")
        }
    }

    private fun StringBuilder.ensureLineBreak() {
        if (isNotEmpty() && last() != '\n') append('\n')
    }
}

/**
 * 按顶层 token 切分 package/import 头、声明与可执行语句。
 *
 * 该扫描器不尝试解析表达式，只使用 lexer token、括号深度和顶层分号/闭合花括号
 * 确定条目边界；最终语法与语义仍由正式 parser/checker 决定。
 */
internal fun analyzeSourceLayout(source: String): SourceLayout {
    val lexer = JasperLexer(CharStreams.fromString(source)).apply {
        removeErrorListeners()
    }
    val tokenStream = CommonTokenStream(lexer)
    tokenStream.fill()
    val tokens: List<Token> = tokenStream.tokens.mapNotNull { token ->
        token?.takeIf { it.type != Token.EOF && it.channel == Token.DEFAULT_CHANNEL }
    }

    var headerTokenIndex = 0
    var headerEnd = 0
    if (tokens.isNotEmpty() && tokens[0].type == JasperLexer.Package) {
        headerTokenIndex = afterSemicolon(tokens, 0)
        headerEnd = endOffset(tokens, headerTokenIndex - 1)
    }
    while (headerTokenIndex < tokens.size) {
        val token = tokens[headerTokenIndex]
        val isDeclarationImport = token.type == JasperLexer.Import
        val isSoftFromImport = token.text == "from" && containsImportBeforeSemicolon(tokens, headerTokenIndex)
        if (!isDeclarationImport && !isSoftFromImport) break
        headerTokenIndex = afterSemicolon(tokens, headerTokenIndex)
        headerEnd = endOffset(tokens, headerTokenIndex - 1)
    }

    val items = mutableListOf<TopLevelSourceItem>()
    var leadingStart = headerEnd
    var index = headerTokenIndex
    while (index < tokens.size) {
        val itemTokens = tokens.subList(index, tokens.size)
        val kind = classifyItem(itemTokens)
        val endIndex = findItemEnd(tokens, index, kind)
        val finalIndex = endIndex ?: (tokens.size - 1)
        val itemEnd = endOffset(tokens, finalIndex)
        val firstToken = tokens[index]
        items += TopLevelSourceItem(
            text = source.substring(leadingStart, itemEnd),
            isDeclaration = kind == ItemKind.Declaration,
            line = firstToken.line,
            column = firstToken.charPositionInLine
        )
        leadingStart = itemEnd
        index = finalIndex + 1
    }

    return SourceLayout(
        header = source.substring(0, headerEnd),
        items = items,
        trailing = source.substring(leadingStart)
    )
}

private enum class ItemKind {
    Declaration,
    BlockStatement,
    SemicolonStatement
}

private val declarationModifiers = setOf(
    JasperLexer.Public,
    JasperLexer.Private,
    JasperLexer.Protected,
    JasperLexer.Abstract,
    JasperLexer.Static,
    JasperLexer.Final
)

private val declarationStarters = setOf(
    JasperLexer.Function,
    JasperLexer.Class,
    JasperLexer.Interface,
    JasperLexer.Enum
)

private val blockStatementStarters = setOf(
    JasperLexer.If,
    JasperLexer.While,
    JasperLexer.Do,
    JasperLexer.For,
    JasperLexer.Switch,
    JasperLexer.Try,
    JasperLexer.Defer,
    JasperLexer.Lock,
    JasperLexer.LBRACE
)

private fun classifyItem(tokens: List<Token>): ItemKind {
    var index = 0
    while (index < tokens.size && tokens[index].type in declarationModifiers) index++
    if (index >= tokens.size) return ItemKind.SemicolonStatement

    val type = tokens[index].type
    return when {
        type == JasperLexer.AT || type in declarationStarters -> ItemKind.Declaration
        type in blockStatementStarters -> ItemKind.BlockStatement
        else -> ItemKind.SemicolonStatement
    }
}

/**
 * 返回条目的最后一个 token 索引。
 *
 * 声明和块语句可在顶层闭合花括号处结束；普通语句以分号结束。`else`、`catch`、
 * `finally`、`do ... while` 和 G3 循环后置块属于同一个顶层条目，不能被提前切开。
 */
private fun findItemEnd(tokens: List<Token>, startIndex: Int, kind: ItemKind): Int? {
    var parenDepth = 0
    var bracketDepth = 0
    var braceDepth = 0
    var sawTopLevelBrace = false
    val startType = tokens[startIndex].type

    for (index in startIndex until tokens.size) {
        val token = tokens[index]
        when (token.type) {
            JasperLexer.LPAREN -> parenDepth++
            JasperLexer.RPAREN -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
            JasperLexer.LBRACK -> bracketDepth++
            JasperLexer.RBRACK -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
            JasperLexer.LBRACE -> {
                braceDepth++
                if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 1) {
                    sawTopLevelBrace = true
                }
            }
            JasperLexer.RBRACE -> {
                braceDepth = (braceDepth - 1).coerceAtLeast(0)
                if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 && sawTopLevelBrace) {
                    val next = tokens.getOrNull(index + 1)
                    if (!continuesAfterBrace(startType, next)) return index
                }
            }
            JasperLexer.SEMI -> {
                if (parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                    when (kind) {
                        ItemKind.SemicolonStatement -> return index
                        ItemKind.BlockStatement -> {
                            val next = tokens.getOrNull(index + 1)
                            if (!continuesAfterBrace(startType, next)) return index
                        }
                        ItemKind.Declaration -> {
                            if (!sawTopLevelBrace) return index
                        }
                    }
                }
            }
        }
    }
    return null
}

private fun continuesAfterBrace(startType: Int, next: Token?): Boolean {
    if (next == null) return false
    return when (next.type) {
        JasperLexer.Else, JasperLexer.Catch, JasperLexer.Finally -> true
        JasperLexer.While -> startType == JasperLexer.Do
        else -> next.text in setOf("onComplete", "onBreak", "onZeroRuns")
    }
}

private fun afterSemicolon(tokens: List<Token>, startIndex: Int): Int {
    var index = startIndex
    while (index < tokens.size) {
        if (tokens[index].type == JasperLexer.SEMI) return index + 1
        index++
    }
    return tokens.size
}

private fun containsImportBeforeSemicolon(tokens: List<Token>, startIndex: Int): Boolean {
    var index = startIndex + 1
    while (index < tokens.size) {
        when (tokens[index].type) {
            JasperLexer.Import -> return true
            JasperLexer.SEMI -> return false
        }
        index++
    }
    return false
}

private fun endOffset(tokens: List<Token>, index: Int): Int =
    if (index < 0 || index >= tokens.size) 0 else tokens[index].stopIndex + 1
