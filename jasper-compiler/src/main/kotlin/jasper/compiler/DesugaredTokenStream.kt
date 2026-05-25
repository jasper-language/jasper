package jasper.compiler

import jasper.parser.JasperLexer
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenSource

class DesugaredTokenStream(tokenSource: TokenSource) : CommonTokenStream(tokenSource) {

    init {
        fill()
        val expanded = buildExpandedTokens()
        tokens.clear()
        tokens.addAll(expanded)
        p = 0
    }

    private fun buildExpandedTokens(): List<Token> {
        val result = mutableListOf<Token>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            when (tok.type) {
                JasperLexer.PLUS_PLUS -> handlePlusPlus(i, result) { i++ }
                JasperLexer.MINUS_MINUS -> handleMinusMinus(i, result) { i++ }
                JasperLexer.Get, JasperLexer.Set -> {
                    val prev = tokens.getOrNull(i - 1)
                    val isPropertyAccessor = prev?.type == JasperLexer.LBRACE ||
                            prev?.type == JasperLexer.Get || prev?.type == JasperLexer.Set ||
                            prev?.type == JasperLexer.SEMI
                    if (isPropertyAccessor) {
                        result.add(tok)
                    } else {
                        result.add(synth(JasperLexer.Identifier, tok.text, tok))
                    }
                }
                else -> result.add(tok)
            }
            i++
        }
        return result
    }

    private fun handlePlusPlus(i: Int, target: MutableList<Token>, skipNext: () -> Unit) {
        val tok = tokens[i]
        val prev = tokens.getOrNull(i - 1)
        val next = tokens.getOrNull(i + 1)

        when {
            next != null && isIdentifier(next) && prev?.let { !isIdentifier(it) } != false -> {
                target.add(synth(JasperLexer.LPAREN, "(", tok))
                target.add(next)
                target.add(synth(JasperLexer.PLUS_ASSIGN, "+=", tok))
                target.add(synth(JasperLexer.DEC_LITERAL, "1", tok))
                target.add(synth(JasperLexer.RPAREN, ")", tok))
                skipNext()
            }
            prev != null && isIdentifier(prev) -> {
                if (target.isNotEmpty() && target.last().type == JasperLexer.Identifier) {
                    target.removeAt(target.size - 1)
                }
                target.add(synth(JasperLexer.LPAREN, "(", tok))
                target.add(synth(JasperLexer.LPAREN, "(", tok))
                target.add(prev)
                target.add(synth(JasperLexer.PLUS_ASSIGN, "+=", tok))
                target.add(synth(JasperLexer.DEC_LITERAL, "1", tok))
                target.add(synth(JasperLexer.RPAREN, ")", tok))
                target.add(synth(JasperLexer.MINUS, "-", tok))
                target.add(synth(JasperLexer.DEC_LITERAL, "1", tok))
                target.add(synth(JasperLexer.RPAREN, ")", tok))
            }
            else -> target.add(tok)
        }
    }

    private fun handleMinusMinus(i: Int, target: MutableList<Token>, skipNext: () -> Unit) {
        val tok = tokens[i]
        val prev = tokens.getOrNull(i - 1)
        val next = tokens.getOrNull(i + 1)

        when {
            next != null && isIdentifier(next) && prev?.let { !isIdentifier(it) } != false -> {
                target.add(synth(JasperLexer.LPAREN, "(", tok))
                target.add(next)
                target.add(synth(JasperLexer.MINUS_ASSIGN, "-=", tok))
                target.add(synth(JasperLexer.DEC_LITERAL, "1", tok))
                target.add(synth(JasperLexer.RPAREN, ")", tok))
                skipNext()
            }
            prev != null && isIdentifier(prev) -> {
                if (target.isNotEmpty() && target.last().type == JasperLexer.Identifier) {
                    target.removeAt(target.size - 1)
                }
                target.add(synth(JasperLexer.LPAREN, "(", tok))
                target.add(synth(JasperLexer.LPAREN, "(", tok))
                target.add(prev)
                target.add(synth(JasperLexer.MINUS_ASSIGN, "-=", tok))
                target.add(synth(JasperLexer.DEC_LITERAL, "1", tok))
                target.add(synth(JasperLexer.RPAREN, ")", tok))
                target.add(synth(JasperLexer.PLUS, "+", tok))
                target.add(synth(JasperLexer.DEC_LITERAL, "1", tok))
                target.add(synth(JasperLexer.RPAREN, ")", tok))
            }
            else -> target.add(tok)
        }
    }

    private fun isIdentifier(tok: Token): Boolean = tok.type == JasperLexer.Identifier

    private fun synth(type: Int, text: String, pos: Token): Token {
        val t = CommonToken(type, text)
        t.line = pos.line
        t.charPositionInLine = pos.charPositionInLine
        t.channel = Token.DEFAULT_CHANNEL
        return t
    }
}
