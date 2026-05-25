package jasper.compiler

import jasper.parser.JasperParser
import org.antlr.v4.runtime.DefaultErrorStrategy
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Token

class JasperErrorStrategy : DefaultErrorStrategy() {
    override fun recover(p: Parser, e: RecognitionException) {
        while (p.currentToken.type != Token.EOF) {
            val tokenType = p.currentToken.type
            if (tokenType == JasperParser.SEMI || tokenType == JasperParser.RBRACE) {
                p.consume()
                return
            }
            p.consume()
        }
    }

    override fun recoverInline(p: Parser): Token {
        return super.recoverInline(p)
    }

    fun getExpectedTokenSet(p: Parser): Set<Int> {
        val intervalSet = p.getExpectedTokens() ?: return emptySet()
        val result = mutableSetOf<Int>()
        val n = intervalSet.size()
        var i = 0
        while (i < n) {
            result.add(intervalSet.get(i))
            i++
        }
        return result
    }
}
