package jasper.compiler

import jasper.ast.JasperAstBuilder
import jasper.checker.TypeChecker
import jasper.parser.JasperLexer
import jasper.parser.JasperParser
import jasper.translator.JasperToIrTranslator
import jasper.translator.TranslationResult
import jasper.translator.TypeMapper
import org.antlr.v4.runtime.CharStreams
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import jasper.compiler.JasperErrorListener
import jasper.compiler.JasperErrorStrategy

/**
 * Preprocesses source text for syntactic sugar that the parser cannot handle natively.
 * Note: `++`/`--` are now handled via token-stream desugaring in [DesugaredTokenStream].
 */
private fun preprocessSource(source: String): String {
    val ident = "[a-zA-Z_\\$][a-zA-Z0-9_\\$]*"
    var result = source
    // Handle `expr as Type` → `cast expr as Type` (the grammar requires `cast` keyword)
    result = result.replace(Regex("""(\w[\w.]*\s*\([^)]*\))\s+as\s+($ident)""")) { m ->
        "cast ${m.groupValues[1]} as ${m.groupValues[2]}"
    }
    result = result.replace(
        Regex("""new\s+($ident)\s*(\[\s*])\s*\{([^}]*)}""")
    ) { m ->
        "new ${m.groupValues[1]}${m.groupValues[2]} [${m.groupValues[3]}]"
    }
    result = result.replace(
        Regex("""(?<![A-Za-z0-9_$])func\s*\(([^)]*)\)\s*:\s*[\w.\[\]?]+\s*\{""")
    ) { m ->
        "lambda (${m.groupValues[1]}) {"
    }
    result = result.replace(Regex("""=\s*(\([^)]*\)|$ident)\s*=>\s*\{""")) { m ->
        "= lambda ${normalizeLambdaParams(m.groupValues[1])} {"
    }
    result = result.replace(Regex("""=\s*(\([^)]*\)|$ident)\s*=>\s*([^;\n]+)""")) { m ->
        "= lambda ${normalizeLambdaParams(m.groupValues[1])} { return ${m.groupValues[2].trim()}; }"
    }
    result = result.replace(Regex("""\bmatch\s*\(""")) { "switch (" }
    result = result.replace(Regex("""\bcase\s+_\s*=>""")) { "default =>" }
    return result
}

private fun normalizeLambdaParams(params: String): String {
    return if (params.startsWith("(")) params else "($params)"
}

class JasperCompiler {

    data class CompilePipeline(
        val source: String,
        val ast: jasper.ast.JasSourceFile,
        val ir: jasper.translator.TranslationResult
    )

    private data class ParsedSource(
        val ast: jasper.ast.JasSourceFile,
        val parsedText: String,
        val errors: List<CompileError> = emptyList()
    )

    fun compile(source: String): CompileResult {
        return compileScript(source)
    }

    fun compileScript(source: String): CompileResult {
        return try {
            val wrapped = if (!hasTopLevelDeclarations(source)) {
                "function main() {\n$source\n}"
            } else {
                source
            }
            val pipeline = compileToPipeline(wrapped)
            CompileResult.Success("Compilation succeeded: ${pipeline.ast.declarations.size} declarations, ${pipeline.ir.irFiles.size} IR file(s)")
        } catch (e: CompileException) {
            CompileResult.Error(e.errors)
        } catch (e: Exception) {
            CompileResult.Error(listOf(CompileError.Internal(e.message ?: "Unknown error", e)))
        }
    }

    private fun hasTopLevelDeclarations(source: String): Boolean {
        return Regex(
            """^\s*(fun\s+|function\s+|class\s+|interface\s+|enum\s+|annotation\s+|package\s+|import\s+)""",
            RegexOption.MULTILINE
        ).containsMatchIn(source)
    }

    fun compile(sources: Map<String, String>): CompileResult {
        return try {
            val pipelines = compileAll(sources)
            val totalDecls = pipelines.values.sumOf { it.ast.declarations.size }
            val totalIrFiles = pipelines.values.sumOf { it.ir.irFiles.size }
            CompileResult.Success("Compilation succeeded: $totalDecls declarations, $totalIrFiles IR file(s)")
        } catch (e: CompileException) {
            CompileResult.Error(e.errors)
        } catch (e: Exception) {
            CompileResult.Error(listOf(CompileError.Internal(e.message ?: "Unknown error", e)))
        }
    }

    fun compileToDir(source: String, outputDir: Path): CompileResult {
        return compileToDirWithIr(source, outputDir)
    }

    fun compileToDirWithIr(source: String, outputDir: Path): CompileResult {
        return try {
            val pipeline = compileToPipeline(source)

            val mapper = pipeline.ir.typeMapper ?: TypeMapper()
            val backend = JvmIrBackend(mapper)
            backend.generate(pipeline.ir, outputDir)

            CompileResult.Success("Compilation succeeded: ${pipeline.ast.declarations.size} declarations, classes written to $outputDir")
        } catch (e: CompileException) {
            CompileResult.Error(e.errors)
        } catch (e: Exception) {
            CompileResult.Error(listOf(CompileError.Internal(e.message ?: "Unknown error", e)))
        }
    }

    fun compileAllToDir(sources: Map<String, String>, outputDir: Path): CompileResult {
        return try {
            val results = compileAll(sources)
            val allIrFiles = mutableListOf<IrFile>()
            val mergedCallNames = mutableMapOf<IrSimpleFunctionSymbol, String>()
            val mergedCtorTypes = mutableMapOf<IrConstructorSymbol, String>()
            val mergedSuperClasses = mutableMapOf<String, String>()
            val mergedInterfaces = mutableMapOf<String, List<String>>()
            val mergedIfaceDeclNames = mutableSetOf<String>()

            for ((_, pipeline) in results) {
                val tr = pipeline.ir
                allIrFiles.addAll(tr.irFiles)
                mergedCallNames.putAll(tr.callNames)
                mergedCtorTypes.putAll(tr.constructorTypes)
                mergedSuperClasses.putAll(tr.classSuperclassNames)
                mergedInterfaces.putAll(tr.classInterfaceNames)
                mergedIfaceDeclNames.addAll(tr.interfaceDeclNames)
            }

            val typeMapper = results.values.firstOrNull()?.ir?.typeMapper ?: TypeMapper()
            val mergedResult = TranslationResult(
                irFiles = allIrFiles,
                callNames = mergedCallNames,
                constructorTypes = mergedCtorTypes,
                classSuperclassNames = mergedSuperClasses,
                classInterfaceNames = mergedInterfaces,
                interfaceDeclNames = mergedIfaceDeclNames
            )

            val backend = JvmIrBackend(typeMapper)
            backend.generate(mergedResult, outputDir)
            CompileResult.Success("Compilation succeeded: ${results.size} files, classes written to $outputDir")
        } catch (e: CompileException) {
            CompileResult.Error(e.errors)
        } catch (e: Exception) {
            CompileResult.Error(listOf(CompileError.Internal(e.message ?: "Unknown error", e)))
        }
    }

    fun compileToPipeline(source: String): CompilePipeline {
        val processed = preprocessPower(preprocessSource(source))
        val parsed = parseSource(processed)
        val ast = parsed.ast

        val allErrors = mutableListOf<CompileError>()
        allErrors.addAll(parsed.errors)

        val symbolTable = SymbolTable()
        symbolTable.registerBuiltins()
        symbolTable.collectDeclarations(ast)
        allErrors.addAll(symbolTable.errors.map { se ->
            CompileError.Symbol(se.message, se.name, se.line, se.column)
        })

        val typeErrors = TypeChecker(symbolTable).check(ast)
        allErrors.addAll(typeErrors.map { te ->
            CompileError.Type(te.message, line = te.node?.sourceLine ?: 0, column = te.node?.sourceColumn ?: 0)
        })

        if (allErrors.isNotEmpty()) {
            throw CompileException(allErrors)
        }

        val translator = JasperToIrTranslator()
        val ir = translator.translate(ast)

        return CompilePipeline(parsed.parsedText, ast, ir)
    }

    fun compileAll(sources: Map<String, String>): Map<String, CompilePipeline> {
        val allErrors = mutableListOf<CompileError>()
        val symbolTable = SymbolTable()
        symbolTable.registerBuiltins()
        val parsed = mutableMapOf<String, jasper.ast.JasSourceFile>()

        for ((filename, source) in sources) {
            val processed = preprocessPower(preprocessSource(source))
            val result = parseSource(processed)
            parsed[filename] = result.ast
            allErrors.addAll(result.errors)
            symbolTable.collectDeclarations(result.ast)
        }

        allErrors.addAll(symbolTable.errors.map { se ->
            CompileError.Symbol(se.message, se.name, se.line, se.column)
        })

        for ((filename, ast) in parsed) {
            val typeErrors = TypeChecker(symbolTable).check(ast)
            allErrors.addAll(typeErrors.map { te ->
                CompileError.Type(te.message, line = te.node?.sourceLine ?: 0, column = te.node?.sourceColumn ?: 0)
            })
        }

        if (allErrors.isNotEmpty()) {
            throw CompileException(allErrors)
        }

        val translator = JasperToIrTranslator()
        val translated = translator.translateAll(parsed.values.toList(), symbolTable)
        val pipelines = mutableMapOf<String, CompilePipeline>()
        for ((entry, irFile) in parsed.entries.zip(translated.irFiles)) {
            val filename = entry.key
            val ast = entry.value
            val ir = TranslationResult(
                irFiles = listOf(irFile),
                callNames = translated.callNames,
                constructorTypes = translated.constructorTypes,
                typeMapper = translated.typeMapper,
                functionDescriptors = translated.functionDescriptors,
                parameterDescriptors = translated.parameterDescriptors,
                classSuperclassNames = translated.classSuperclassNames,
                classInterfaceNames = translated.classInterfaceNames,
                interfaceDeclNames = translated.interfaceDeclNames
            )
            pipelines[filename] = CompilePipeline(sources[filename]!!, ast, ir)
        }
        return pipelines
    }

    fun compileToPipeline(source: String, symbolTable: SymbolTable): CompilePipeline {
        val processed = preprocessPower(preprocessSource(source))
        val parsed = parseSource(processed)
        val ast = parsed.ast

        val allErrors = mutableListOf<CompileError>()
        allErrors.addAll(parsed.errors)

        symbolTable.collectDeclarations(ast)
        allErrors.addAll(symbolTable.errors.map { se ->
            CompileError.Symbol(se.message, se.name, se.line, se.column)
        })

        val typeErrors = TypeChecker(symbolTable).check(ast)
        allErrors.addAll(typeErrors.map { te ->
            CompileError.Type(te.message, line = te.node?.sourceLine ?: 0, column = te.node?.sourceColumn ?: 0)
        })

        if (allErrors.isNotEmpty()) {
            throw CompileException(allErrors)
        }

        val translator = JasperToIrTranslator()
        val ir = translator.translate(ast)

        return CompilePipeline(parsed.parsedText, ast, ir)
    }

    private fun parseSource(source: String): ParsedSource {
        val (firstParseTree, firstErrors) = parseSourceStrict(source)
        if (firstParseTree != null && firstErrors.isEmpty()) {
            try {
                val ast = JasperAstBuilder().buildSourceFile(firstParseTree)
                if (ast.declarations.isNotEmpty()) {
                    return ParsedSource(ast, source, firstErrors)
                }
            } catch (_: Exception) {
                // Incomplete parse tree, fall through to legacy wrapping
            }
        }

        val wrapped = wrapLegacyTopLevelMembers(source)
        if (wrapped != source) {
            val (secondParseTree, secondErrors) = parseSourceStrict(wrapped)
            if (secondParseTree != null) {
                try {
                    val ast = unwrapLegacyTopLevelMembers(JasperAstBuilder().buildSourceFile(secondParseTree))
                    return ParsedSource(ast, source, secondErrors)
                } catch (_: Exception) {
                    throw CompileException(secondErrors.ifEmpty {
                        listOf(CompileError.Syntax("Failed to parse source (no recovery possible)"))
                    })
                }
            }
            throw CompileException(secondErrors.ifEmpty {
                listOf(CompileError.Syntax("Failed to parse source (no recovery possible)"))
            })
        }

        throw CompileException(firstErrors.ifEmpty {
            listOf(CompileError.Syntax("Failed to parse source (no recovery possible)"))
        })
    }

    private fun parseSourceStrict(source: String): Pair<JasperParser.SourceFileContext?, List<CompileError>> {
        val (parser, errorListener) = createParser(source)
        val parseTree = try {
            parser.sourceFile()
        } catch (e: Exception) {
            null
        }

        val syntaxErrors = errorListener.errors.map { pe ->
            CompileError.Syntax(
                message = "Syntax error at line ${pe.line}, column ${pe.column}: ${pe.message}",
                line = pe.line,
                column = pe.column
            )
        }

        return parseTree to syntaxErrors
    }

    private fun wrapLegacyTopLevelMembers(source: String): String {
        val match = Regex("""(?s)^(\s*package\b[^;]*;\s*)?((?:\s*(?:import|from)\b[^;]*;\s*)*)(.*)$""")
            .matchEntire(source)
            ?: return source
        val header = (match.groupValues[1] + match.groupValues[2])
        val body = match.groupValues[3]
        return header + "public class __JasperCompatTopLevel {\n" + body + "\n}"
    }

    private fun unwrapLegacyTopLevelMembers(ast: jasper.ast.JasSourceFile): jasper.ast.JasSourceFile {
        val wrapper = ast.declarations.singleOrNull() as? jasper.ast.JasClass
        if (wrapper?.name != "__JasperCompatTopLevel") {
            return ast
        }
        return ast.copy(declarations = wrapper.members)
    }

    /**
     * Preprocesses the source to desugar `**` (power) and `**=` (power assign)
     * into function calls that the parser can handle.
     *
     * - `a ** b` → `__jasper_pow__(a, b)`
     * - `a **= b` → `a = __jasper_pow__(a, b)`
     */
    private fun preprocessPower(source: String): String {
        // Handle `**=` first (must come before `**` to avoid partial match)
        val step1 = source.replace(Regex("""((?:\w+|\)|\]))\s*\*\*=\s*((?:\w+|\(|\())""")) { m ->
            "${m.groupValues[1]} = __jasper_pow__(${m.groupValues[1]}, ${m.groupValues[2]})"
        }
        // Handle `**` binary operator
        return step1.replace(Regex("""((?:\w+|\)|\]))\s*\*\*\s*((?:\w+|\(|\())""")) { m ->
            "__jasper_pow__(${m.groupValues[1]}, ${m.groupValues[2]})"
        }
    }

    private fun createParser(source: String): Pair<JasperParser, JasperErrorListener> {
        val input = CharStreams.fromString(source)
        val lexer = JasperLexer(input)
        val tokens = DesugaredTokenStream(lexer)
        val parser = JasperParser(tokens)
        val errorListener = JasperErrorListener()
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)
        parser.errorHandler = JasperErrorStrategy()
        return Pair(parser, errorListener)
    }

    sealed class CompileResult {
        data class Success(val message: String) : CompileResult()
        data class Error(val errors: List<CompileError>) : CompileResult() {
            val message: String get() = "Compilation failed:\n${errors.joinToString("\n") { formatError(it) }}"
        }
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: jasperc <source-file> [-d <output-dir>]")
        System.exit(1)
    }

    val sourceFile = Paths.get(args[0])
    if (!sourceFile.toFile().exists()) {
        System.err.println("Error: source file not found: $sourceFile")
        System.exit(1)
    }

    val outputDir = if (args.size >= 3 && args[1] == "-d") {
        Paths.get(args[2])
    } else {
        Paths.get(".")
    }

    val source = String(Files.readAllBytes(sourceFile))
    val compiler = JasperCompiler()
    val result = compiler.compileToDir(source, outputDir)

    when (result) {
        is JasperCompiler.CompileResult.Success -> {
            println("Compilation successful: ${result.message}")
        }
        is JasperCompiler.CompileResult.Error -> {
            System.err.println("Compilation failed: ${result.message}")
            System.exit(1)
        }
    }
}
