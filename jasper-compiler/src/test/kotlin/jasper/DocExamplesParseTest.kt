package jasper

import jasper.compiler.JasperErrorStrategy
import jasper.parser.JasperLexer
import jasper.parser.JasperParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards that every ```jasper block in docs/ (except jaso.md) still parses under
 * the authoritative grammar. A block may parse either as a standalone source file
 * or when wrapped in a class/function body, because chapters often show fragments.
 *
 * Blocks that are *intentionally* non-grammatical (pure type/expression listings
 * and explicitly-marked illegal examples) are listed in [KNOWN_FRAGMENTS]. Any new
 * unparseable block, or a fragment that becomes parseable, fails this test so that
 * documentation drift is caught.
 */
class DocExamplesParseTest {

    private data class Block(val file: String, val index: Int, val code: String)

    private fun docsDir(): Path {
        val candidates = listOf(Paths.get("../docs"), Paths.get("docs"))
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("docs dir not found from ${Paths.get("").toAbsolutePath()}")
    }

    private fun extractBlocks(): List<Block> {
        val out = mutableListOf<Block>()
        Files.list(docsDir()).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".md") }
                .filter { it.fileName.toString() != "jaso.md" }
                .sorted()
                .forEach { p ->
                    val lines = Files.readAllLines(p)
                    var i = 0
                    var idx = 0
                    while (i < lines.size) {
                        if (lines[i].trim() == "```jasper") {
                            val sb = StringBuilder()
                            i++
                            while (i < lines.size && lines[i].trim() != "```") {
                                sb.appendLine(lines[i]); i++
                            }
                            out.add(Block(p.fileName.toString(), idx, sb.toString().trimEnd('\n')))
                            idx++
                        }
                        i++
                    }
                }
        }
        return out
    }

    private fun errors(source: String): List<String> {
        val errs = mutableListOf<String>()
        try {
            val lexer = JasperLexer(CharStreams.fromString(source))
            lexer.removeErrorListeners()
            lexer.addErrorListener(object : BaseErrorListener() {
                override fun syntaxError(r: Recognizer<*, *>?, o: Any?, l: Int, c: Int, m: String?, e: RecognitionException?) { errs.add("LEX:$m") }
            })
            val parser = JasperParser(CommonTokenStream(lexer))
            parser.removeErrorListeners()
            parser.addErrorListener(object : BaseErrorListener() {
                override fun syntaxError(r: Recognizer<*, *>?, o: Any?, l: Int, c: Int, m: String?, e: RecognitionException?) { errs.add("SYN:$m") }
            })
            parser.errorHandler = JasperErrorStrategy()
            parser.sourceFile()
        } catch (e: Exception) {
            errs.add("EXC:${e.message}")
        }
        return errs
    }

    private fun parses(source: String): Boolean = errors(source).isEmpty()

    private fun strategies(code: String): List<String> = listOf(
        code,
        "package test;\nclass A {\n$code\n}",
        "package test;\nclass A {\n    function f(): void {\n$code\n    }\n}",
        "package test;\n$code"
    )

    /** Strips `//` comments and blank lines so the allowlist survives comment edits. */
    private fun normalize(code: String): String = code.lineSequence()
        .map { it.substringBefore("//").trimEnd() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

    @Test
    fun allDocExamplesParseExceptKnownFragments() {
        val actual = extractBlocks()
            .filter { b -> strategies(b.code).none { parses(it) } }
            .groupingBy { normalize(it.code) }
            .eachCount()

        val expected = KNOWN_FRAGMENTS.groupingBy { it }.eachCount()
        assertEquals(expected, actual, "doc code blocks changed parse status; update docs or KNOWN_FRAGMENTS")

        Files.createDirectories(Paths.get("build", "tmp"))
        Files.write(
            Paths.get("build", "tmp", "doc-parse-report.txt"),
            buildString {
                appendLine("unparseable-count=${actual.values.sum()}")
                actual.keys.sorted().forEach { appendLine(it.replace("\n", " ⏎ ")) }
            }.toByteArray()
        )
    }

    private companion object {
        val KNOWN_FRAGMENTS = listOf(
            // 03/overview: pure type listings (not a compilable unit).
            "T\nT?\n*T\nT[]\nList<T>\n(A, B)",
            "T\nT?\n*T\nT[]\nList<T>\n(A, B)",
            // 03: explicitly unsupported type forms.
            "T!\nT??\n&T\n&&T\nraw T\nshared T",
            // 03: Map initialised by its dict implementation; `dict` is still a lexer keyword,
            // so `new dict<K, V>()` does not parse until the grammar admits it.
            "var names: List<string> = new List<string>();\nvar table: Map<string, int32> = new dict<string, int32>();",
            // 03: tuple/grouping illustration (third line marked 非法).
            "(a)\n(a, b)\n(a,)",
            // 04: binding syntax fragment.
            "name: Type",
            // 04: explicitly illegal multi-binding.
            "var (name, age) = userInfo();",
            // 05 §5.7: intentionally illegal — error types used as values (rejected by the checker).
            "function read(): (int32, Error) { ... }\nfunction read(): Error { ... }\nvar lastError: Error;\nfunction handle(e: Error): void { ... }\nvar box: Result<int32, Error>;",
            // 05 §5.7: anti-pattern fragment plus the throw/catch alternative; not a compilable unit.
            "var value, err = parse(text);\nif (err != null) { ... }\ntry {\n    var value = parse(text);\n} catch (e: RecoverableError) {\n    ...\n}",
            // 06: annotation fragment.
            "@A(1, 2, name = \"x\", ...args)",
            // 08: switch-case fragment.
            "case x if x > 0 => { }\ncase (x: int32, y: int32) => { }",
            // 09: primary-expression fragment.
            "42\n\"hello\"\ntrue\nthis\nsuper\nnew User()\n(a + b)",
            // 09: postfix-expression fragment.
            "fn(args)\nobj.field\nobj.method()\narr[i]\nmaybe?.field"
        )
    }
}
