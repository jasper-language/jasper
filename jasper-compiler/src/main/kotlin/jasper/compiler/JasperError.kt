package jasper.compiler

enum class ErrorCategory { SYNTAX, SEMANTIC, TYPE, SYMBOL, INTERNAL }

data class ParseError(
    val line: Int,
    val column: Int,
    val message: String,
    val sourceLine: String? = null,
    val category: ErrorCategory = ErrorCategory.SYNTAX
)

data class ParseResult<T>(val result: T?, val errors: List<ParseError> = emptyList())

sealed class CompileError {
    abstract val message: String
    abstract val category: ErrorCategory
    abstract val line: Int
    abstract val column: Int

    data class Syntax(
        override val message: String,
        override val line: Int = 0,
        override val column: Int = 0
    ) : CompileError() {
        override val category: ErrorCategory = ErrorCategory.SYNTAX
    }

    data class Symbol(
        override val message: String,
        val name: String = "",
        override val line: Int = 0,
        override val column: Int = 0
    ) : CompileError() {
        override val category: ErrorCategory = ErrorCategory.SYMBOL
    }

    data class Type(
        override val message: String,
        override val line: Int = 0,
        override val column: Int = 0
    ) : CompileError() {
        override val category: ErrorCategory = ErrorCategory.TYPE
    }

    data class Internal(
        override val message: String,
        val cause: Throwable? = null
    ) : CompileError() {
        override val category: ErrorCategory = ErrorCategory.INTERNAL
        override val line: Int = 0
        override val column: Int = 0
    }
}

class CompileException(val errors: List<CompileError>) : RuntimeException(
    errors.joinToString("\n") { formatError(it) }
) {
    constructor(error: CompileError) : this(listOf(error))
}

internal fun formatError(err: CompileError): String {
    val loc = if (err.line > 0) " (${err.line}:${err.column})" else ""
    return "  [${err.category.name}]$loc ${err.message}"
}
