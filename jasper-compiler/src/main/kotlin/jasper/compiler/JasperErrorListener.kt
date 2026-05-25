package jasper.compiler

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token

class JasperErrorListener : BaseErrorListener() {
    val errors = mutableListOf<ParseError>()

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?
    ) {
        val sourceLine = try {
            val input = recognizer?.inputStream
            if (input is org.antlr.v4.runtime.CharStream) {
                val inputStr = input.toString()
                val lines = inputStr.lines()
                if (line > 0 && line <= lines.size) {
                    lines[line - 1]
                } else null
            } else null
        } catch (ex: Exception) { null }

        val category = categorizeError(msg)

        errors.add(ParseError(
            line = line,
            column = charPositionInLine,
            message = msg ?: "Unknown syntax error",
            sourceLine = sourceLine,
            category = category
        ))
    }

    private fun categorizeError(msg: String?): ErrorCategory {
        val message = msg ?: return ErrorCategory.SYNTAX
        return when {
            message.contains("type", ignoreCase = true) &&
                (message.contains("mismatch", ignoreCase = true) ||
                 message.contains("expect", ignoreCase = true)) -> ErrorCategory.TYPE
            message.contains("type mismat", ignoreCase = true) ||
            message.contains("incompatible type", ignoreCase = true) ||
            message.contains("cannot be applied", ignoreCase = true) ||
            message.contains("not a function", ignoreCase = true) -> ErrorCategory.TYPE
            message.contains("semantic", ignoreCase = true) ||
            message.contains("undefined", ignoreCase = true) ||
            message.contains("already defined", ignoreCase = true) ||
            message.contains("unresolved", ignoreCase = true) -> ErrorCategory.SEMANTIC
            else -> ErrorCategory.SYNTAX
        }
    }

    fun formatErrorMessage(line: Int, col: Int, msg: String): String {
        val error = errors.find { it.line == line && it.column == col }
        val category = error?.category ?: categorizeError(msg)
        val sourceLine = error?.sourceLine
        val sb = StringBuilder()
        sb.append("Error at line $line, column $col: $category - $msg")
        if (sourceLine != null) {
            sb.appendLine()
            sb.append("  $line | ${sourceLine.trimEnd()}")
            sb.appendLine()
            sb.append("  $line | ${" ".repeat(col.coerceAtMost(sourceLine.length))}^")
        }
        return sb.toString()
    }

    fun formatErrors(): String {
        return errors.joinToString("\n\n") { formatErrorMessage(it.line, it.column, it.message) }
    }
}
