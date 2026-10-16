package jasper

import jasper.compiler.JasperCompiler
import jasper.compiler.JasperErrorStrategy
import jasper.parser.JasperLexer
import jasper.parser.JasperParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Regression nails for language decisions that have already been finalized.
 *
 * These tests guard the *design*, not an implementation: grammar-level
 * rejections are asserted by invoking the ANTLR parser/lexer directly (so a
 * rejection for an unrelated reason cannot masquerade as a pass), while
 * semantic decisions are asserted by compiling and executing the result.
 *
 * Grammar authority: jasper-grammar @ 18e0e11 (do not edit .g4 here).
 */
class GrammarRegressionTest {

    // ------------------------------------------------------------------
    // Parser-level helpers
    // ------------------------------------------------------------------

    private data class ParseOutcome(
        val lexErrors: List<String>,
        val parserErrors: List<String>
    ) {
        val hasLexicalError: Boolean get() = lexErrors.isNotEmpty()
        val hasSyntaxError: Boolean get() = parserErrors.isNotEmpty()
        val isClean: Boolean get() = lexErrors.isEmpty() && parserErrors.isEmpty()

        fun describe(): String =
            "lex=${lexErrors.ifEmpty { listOf("<none>") }} parser=${parserErrors.ifEmpty { listOf("<none>") }}"
    }

    private fun parse(source: String): ParseOutcome {
        val lexErrors = mutableListOf<String>()
        val parserErrors = mutableListOf<String>()

        val lexer = JasperLexer(CharStreams.fromString(source))
        lexer.removeErrorListeners()
        lexer.addErrorListener(object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String?,
                e: RecognitionException?
            ) {
                lexErrors.add("line $line:$charPositionInLine $msg")
            }
        })

        val tokens = CommonTokenStream(lexer)
        val parser = JasperParser(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String?,
                e: RecognitionException?
            ) {
                parserErrors.add("line $line:$charPositionInLine $msg")
            }
        })
        parser.errorHandler = JasperErrorStrategy()

        try {
            parser.sourceFile()
        } catch (e: RecognitionException) {
            parserErrors.add("recovery failure: ${e.message}")
        }

        return ParseOutcome(lexErrors, parserErrors)
    }

    private fun assertParses(source: String) {
        val outcome = parse(source)
        assertTrue(outcome.isClean, "expected clean parse but got ${outcome.describe()}")
    }

    private fun assertSyntaxError(source: String) {
        val outcome = parse(source)
        assertTrue(outcome.hasSyntaxError,
            "expected a grammar (syntax) rejection but parser accepted: ${outcome.describe()}")
    }

    private fun assertLexicalError(source: String) {
        val outcome = parse(source)
        assertTrue(outcome.hasLexicalError,
            "expected a lexer rejection but lexer accepted: ${outcome.describe()}")
    }

    /**
     * The pure grammar only accepts a single top-level *type* declaration; the
     * compiler separately wraps top-level statements/functions. Parser-level
     * assertions therefore put statements inside a class body so that a clean
     * parse really means "the statements are grammatical".
     */
    private fun classWrapped(body: String): String {
        val indented = body.lines().joinToString("\n") { "        $it" }
        return "package test;\nclass A {\n    function main(): void {\n$indented\n    }\n}"
    }

    // ------------------------------------------------------------------
    // Semantic helpers (compile + execute)
    // ------------------------------------------------------------------

    private fun createRepoTempDir(prefix: String): Path {
        val base = Paths.get("build", "tmp", "grammar-regression")
        Files.createDirectories(base)
        return Files.createTempDirectory(base, prefix)
    }

    private fun compileAndInvoke(source: String, className: String, methodName: String, expected: Any?) {
        val outputDir = createRepoTempDir("grammar-regression")
        try {
            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            assertTrue(result is JasperCompiler.CompileResult.Success,
                "compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message ?: result}")
            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass(className)
            val method = clazz.getMethod(methodName)
            val actual = method.invoke(null)
            assertEquals(expected, actual)
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ==================================================================
    // (1) `(a) * b` must be multiplication, NOT a cast
    // ==================================================================

    @Test
    fun parenthesizedIdentifierStar_b_isMultiplicationNotCast() {
        compileAndInvoke(
            """
                package test;
                public function main(): int32 {
                    var a: int32 = 3;
                    var b: int32 = 4;
                    return (a) * b;
                }
            """.trimIndent(),
            "test.main", "main", expected = 12
        )
    }

    // ==================================================================
    // (2) `defer`/`atomic`/`lock`/`default` are not class modifiers
    // ==================================================================

    @Test
    fun modifierPosition_rejectsDeferOntoClass() {
        assertSyntaxError("package test;\ndefer class A {}")
    }

    @Test
    fun modifierPosition_rejectsAtomicOntoClass() {
        assertSyntaxError("package test;\natomic class A {}")
    }

    @Test
    fun modifierPosition_rejectsLockOntoClass() {
        assertSyntaxError("package test;\nlock class A {}")
    }

    @Test
    fun modifierPosition_rejectsDefaultOntoClass() {
        assertSyntaxError("package test;\ndefault class A {}")
    }

    // ==================================================================
    // (3) at most one postfix increment per statement
    // ==================================================================

    @Test
    fun statement_rejectsChainedIncrement_xPlusPlusPlusPlus() {
        assertSyntaxError(classWrapped("var x: int32 = 1;\n        x++++;"))
    }

    @Test
    fun statement_rejectsTwoIncrementsInsideExpression() {
        assertSyntaxError(classWrapped("var x: int32 = 1;\n        (x++) * x++;"))
    }

    @Test
    fun statement_rejectsIncrementInsideAddition() {
        assertSyntaxError(classWrapped("var x: int32 = 1;\n        x++ + 1;"))
    }

    @Test
    fun declaration_rejectsIncrementAcrossMultipleBindings() {
        assertSyntaxError(classWrapped("var a = x++, b = y++;"))
    }

    // ==================================================================
    // (4) unterminated string literal / bare quote must be a LEXICAL error
    // ==================================================================

    @Test
    fun unterminatedStringLiteral_isLexicalErrorNotIdentifier() {
        assertLexicalError(classWrapped("var s: string = \"abc;"))
    }

    @Test
    fun bareQuote_isLexicalErrorNotIdentifier() {
        assertLexicalError(classWrapped("var s: string = \";"))
    }

    // ==================================================================
    // (5) `var arr: int[] = [1,2,3];` parses
    // ==================================================================

    @Test
    fun typedArrayWithArrayInitializer_parses() {
        assertParses(classWrapped("var arr: int[] = [1,2,3];"))
    }

    // ==================================================================
    // (6) char literal parses and keeps its value
    // ==================================================================

    @Test
    fun charLiteral_parses() {
        assertParses(classWrapped("var c: char = 'A';"))
    }

    @Test
    fun charLiteral_parsesAndHoldsValue() {
        compileAndInvoke(
            """
                package test;
                public function main(): char {
                    var c: char = 'A';
                    return c;
                }
            """.trimIndent(),
            "test.main", "main", expected = 'A'
        )
    }

    // ==================================================================
    // (7) annotation with array argument parses
    // ==================================================================

    @Test
    fun annotationWithArrayArgument_parses() {
        assertParses(
            """
                package test;
                @SuppressWarnings({"unchecked"})
                class A {}
            """.trimIndent()
        )
    }

    // ==================================================================
    // (8) extends/implements only accept the restricted `namedType`
    // ==================================================================

    @Test
    fun extendsPosition_rejectsArrayType() {
        assertSyntaxError("package test;\nclass A extends B[] {}")
    }

    @Test
    fun implementsPosition_rejectsNullableType() {
        assertSyntaxError("package test;\nclass A implements C? {}")
    }

    // ==================================================================
    // (9) 4th legal postfix-increment position: declaration initializer
    // ==================================================================

    @Test
    fun declarationInitWithTypedIncrement_parses() {
        assertParses(classWrapped("var x: int32 = 1;\n        var y: int32 = x++;"))
    }

    @Test
    fun declarationInitWithInferredDecrement_parses() {
        assertParses(classWrapped("var x: int32 = 1;\n        var y = x--;"))
    }

    @Test
    fun declarationInitWithConstIncrement_parses() {
        assertParses(classWrapped("var x: int32 = 1;\n        const y = x++;"))
    }

    @Test
    fun declarationInitIncrement_bindsOldValueAndBumpsSource() {
        compileAndInvoke(
            """
                package test;
                public function main(): int32 {
                    var x: int32 = 5;
                    var y: int32 = x++;
                    return x * 10 + y;
                }
            """.trimIndent(),
            "test.main", "main", expected = 65
        )
    }

    @Test
    fun declarationInitDecrement_bindsOldValueAndDropsSource() {
        compileAndInvoke(
            """
                package test;
                public function main(): int32 {
                    var x: int32 = 5;
                    var y = x--;
                    return x * 10 + y;
                }
            """.trimIndent(),
            "test.main", "main", expected = 45
        )
    }

    @Test
    fun assignmentRHS_incrementYieldsOldValue() {
        compileAndInvoke(
            """
                package test;
                public function main(): int32 {
                    var x: int32 = 5;
                    x = x++;
                    return x;
                }
            """.trimIndent(),
            "test.main", "main", expected = 5
        )
    }
}
