package jasper.compiler

/*
 * ============================================================================
 * JasperCompiler.kt —— 编译器前端编排入口（compiler 编排层，层次最高）
 * ============================================================================
 *
 * 职责：
 *   把「源码文本」变成「可写盘的 IR / class 文件」，并串起所有阶段。核心是
 *   compileToPipeline：预处理 → 解析 → 注解处理 → 符号表 → 类型检查 → IR 翻译；
 *   出错则聚合成 CompileException。另有多文件 compileAll、写盘 compileToDir 系列、
 *   compileAllToDir、以及 CLI 的 main()。
 *
 * 所属层次：
 *   compiler 编排层（层次最高），可向下依赖 checker / annotations / translator /
 *   stdlib / ast（以及 ANTLR 生成的 parser）。这是全仓库唯一允许「向上聚合」的
 *   位置；反向依赖（下层引用本文件）一律禁止。
 *
 * 与谁协作：
 *   - jasper.parser（ANTLR 生成）：createParser 挂上 JasperErrorListener 与
 *     JasperErrorStrategy 后取解析树；
 *   - annotations.AnnotationProcessor：在类型检查前合成成员签名与注解索引；
 *   - SymbolTable：收集声明、注册内建（注册/收集顺序影响 last-writer-wins）；
 *   - checker.TypeChecker：类型检查；
 *   - translator.JasperToIrTranslator：AST → Kotlin IR；
 *   - compiler.JvmIrBackend：IR → JVM class 字节码并写盘。
 *
 * 对外契约：
 *   - 公开入口（compile / compileScript / compileToDir* / compileAll*）**不抛异常**，
 *     统一返回 CompileResult（Success 或 Error）；异常只出现在内部阶段；
 *   - compileToPipeline 成功返回 CompilePipeline，失败抛 CompileException（携带
 *     多阶段聚合后的全部错误）；
 *   - main(args) 为 CLI：`jasperc <source-file> [-d <output-dir>]`，失败以退出码 1 结束。
 *
 * 扩展点 / 已知限制：
 *   - 预处理（preprocessSource / preprocessPower）用正则脱糖，是**已知的脆弱点**：
 *     正则会扫描包括字符串/注释在内的整段文本，可能误伤（详见各函数注释）；
 *   - 解析采用「两段式」：严格解析成功且能得到非空 AST 则直接用；否则用
 *     wrapLegacyTopLevelMembers 把裸顶层成员包进合成类再试一次，仍失败才放弃；
 *   - 多文件共享一个 SymbolTable，同名声明按 last-writer-wins 覆盖；
 *   - 本文件是全仓库依赖汇聚点，任何下层改动都可能在此暴露。
 * ----------------------------------------------------------------------------
 */

import jasper.annotations.AnnotationIndex
import jasper.annotations.AnnotationProcessor
import jasper.ast.JasperAstBuilder
import jasper.checker.TypeChecker
import jasper.parser.JasperLexer
import jasper.parser.JasperParser
import jasper.stdlib.StdLib
import jasper.translator.JasperToIrTranslator
import jasper.translator.TranslationResult
import jasper.translator.TypeMapper
import org.antlr.v4.runtime.CharStreams
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import jasper.compiler.JasperErrorListener
import jasper.compiler.JasperErrorStrategy

/**
 * Preprocesses source text for syntactic sugar that the parser cannot handle natively.
 * Note: `++`/`--` are now handled directly in the grammar.
 *
 * 中文说明：在交给 ANTLR 之前，对解析器无法原生处理的语法糖做**文本级**脱糖。
 * 什么时候用：只在 compileToPipeline/compileAll 内部、解析之前调用（经由
 * preprocessPower 组合）。
 * 为什么用正则：语法糖形态有限且无需完整语法树，正则最省事；但代价是它会把
 * 字符串/注释内容也一并改写——**已知限制**，对含这类字面量的源码可能误伤。
 * 边界：所有替换都是「贪心地就地改写」，不做作用域判断；返回脱糖后的新字符串。
 */
private fun preprocessSource(source: String): String {
    // 复用的标识符片段：`[a-zA-Z_$][a-zA-Z0-9_$]*`。
    val ident = "[a-zA-Z_\\$][a-zA-Z0-9_\\$]*"
    var result = source
    // Handle `expr as Type` → `cast expr as Type` (the grammar requires `cast` keyword)
    // 中文：把函数调用后的 `as Type` 补上 `cast` 关键字（语法要求显式 cast）。
    result = result.replace(Regex("""(\w[\w.]*\s*\([^)]*\))\s+as\s+($ident)""")) { m ->
        "cast ${m.groupValues[1]} as ${m.groupValues[2]}"
    }
    // 中文：把 `new T[] { ... }` 的数组字面量改写成语法支持的 `new T[] [ ... ]` 形式。
    result = result.replace(
        Regex("""new\s+($ident)\s*(\[\s*])\s*\{([^}]*)}""")
    ) { m ->
        "new ${m.groupValues[1]}${m.groupValues[2]} [${m.groupValues[3]}]"
    }
    // 中文：把 `func(args): RetType {` 匿名函数改写成 `lambda (args) {`。
    // 边界：负向环视 `(?<![A-Za-z0-9_$])` 避免命中 `myfunc(` 之类标识符后缀。
    result = result.replace(
        Regex("""(?<![A-Za-z0-9_$])func\s*\(([^)]*)\)\s*:\s*[\w.\[\]?]+\s*\{""")
    ) { m ->
        "lambda (${m.groupValues[1]}) {"
    }
    // 中文：把块体箭头 `x => { ... }` 改写成 `lambda (x) { ... }`。
    result = result.replace(Regex("""=\s*(\([^)]*\)|$ident)\s*=>\s*\{""")) { m ->
        "= lambda ${normalizeLambdaParams(m.groupValues[1])} {"
    }
    // 中文：把表达式体箭头 `x => expr` 改写成 `lambda (x) { return expr; }`。
    // 边界：`[^;\n]+` 只吃到分号/换行为止，避免吞掉后续语句。
    result = result.replace(Regex("""=\s*(\([^)]*\)|$ident)\s*=>\s*([^;\n]+)""")) { m ->
        "= lambda ${normalizeLambdaParams(m.groupValues[1])} { return ${m.groupValues[2].trim()}; }"
    }
    // 中文：把 `match (` 关键字改写成后端/语法使用的 `switch (`。
    result = result.replace(Regex("""\bmatch\s*\(""")) { "switch (" }
    // 中文：把通配分支 `case _ =>` 改写成 `default =>`。
    result = result.replace(Regex("""\bcase\s+_\s*=>""")) { "default =>" }
    return result
}

/**
 * 规范化 lambda 形参：`x` → `(x)`，已是 `(a, b)` 的保持不变。
 * 为什么需要：上游改写统一产出 `lambda (...)` 形式，此处补括号保证一致。
 * 边界：只判断是否以 `(` 开头，不校验括号是否配对（输入来自受控的正则捕获）。
 */
private fun normalizeLambdaParams(params: String): String {
    return if (params.startsWith("(")) params else "($params)"
}

/**
 * 编译器前端编排器：对外暴露一组「不抛异常、返回 CompileResult」的编译入口。
 * 什么时候用：把它当作整个前端的门面（facade）——CLI（main）、构建脚本或测试
 * 都从这里发起编译。
 * 为什么设计成无状态类：实例不持有跨次编译状态，所有中间件（SymbolTable、
 * AnnotationProcessor 等）都在方法内按需创建，避免并发/复用污染。
 * 失败模式：公开方法自身不抛异常；内部 CompileException 会被转成 Error，
 * 其它异常被兜底成 CompileError.Internal。
 */
class JasperCompiler {

    /**
     * 流水线的中间产物快照：源文本 + 最终 AST + IR 翻译结果 + 注解索引。
     * 什么时候用：需要检查/复用中间结果（而非仅判断成败）时，例如测试断言或
     * 增量编译；compileToDirWithIr 也从这里取 IR 与 typeMapper。
     * 契约：三者在同一次成功编译中相互对应；annotationIndex 缺省为空索引。
     */
    data class CompilePipeline(
        val source: String,
        val ast: jasper.ast.JasSourceFile,
        val ir: jasper.translator.TranslationResult,
        val annotationIndex: AnnotationIndex = AnnotationIndex.EMPTY
    )

    /**
     * parseSource 的内部结果：还原后的 AST、实际参与解析的文本（已包装/已预处理）
     * 与解析期错误。
     * 为什么单独区分 parsedText 与 source：两段式解析可能解析的是被包装过的文本，
     * 而 ParsedSource 记录的是真正被解析的那份，便于排查。
     * 可见性：private，仅编译器内部使用，不属于对外 API。
     */
    private data class ParsedSource(
        val ast: jasper.ast.JasSourceFile,
        val parsedText: String,
        val errors: List<CompileError> = emptyList()
    )

    /**
     * 单文件编译入口（脚本语义）：等价于 [compileScript]，是面向调用方的主入口。
     * 什么时候用：编译一段源码但不需落盘时。
     * 前置条件：source 为完整文本；没有顶层声明时会被自动包进合成 main 函数。
     * 失败模式：所有阶段错误都被聚合成 [CompileResult.Error]，本方法不抛异常。
     */
    fun compile(source: String): CompileResult {
        return compileScript(source)
    }

    /**
     * 以「脚本」方式编译单文件：若探测到没有顶层声明（fun/function/class/package/import
     * 等），就把整段源码包进 `function main() { ... }` 再交给流水线。
     * 什么时候用：让 `jasperc foo.j` 可以直接执行一堆裸语句，降低脚本写法门槛。
     * 边界：顶层声明探测使用 hasTopLevelDeclarations 的多行正则，属启发式；只要出现
     * 任一关键字行就按「有顶层声明」处理，不再包裹。
     * 失败模式：捕获 [CompileException] → Error；其它异常兜底为 [CompileError.Internal]。
     */
    fun compileScript(source: String): CompileResult {
        return try {
            // 无顶层声明时包一层合成 main，让裸语句成为函数体；否则原样编译。
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
            // 兜底：非 CompileException 一律视为编译器内部错误，保证对外不抛栈。
            CompileResult.Error(listOf(CompileError.Internal(e.message ?: "Unknown error", e)))
        }
    }

    /**
     * 启发式判断源码是否含顶层声明：多行匹配行首（可有前导空白）是否为
     * fun/function/class/interface/enum/annotation/package/import 关键字。
     * 什么时候用：compileScript 决定是否包裹合成 main 时。
     * 边界：只认行首关键字，注释里的同名字样可能造成误判；不解析字符串。
     */
    private fun hasTopLevelDeclarations(source: String): Boolean {
        return Regex(
            """^\s*(fun\s+|function\s+|class\s+|interface\s+|enum\s+|annotation\s+|package\s+|import\s+)""",
            RegexOption.MULTILINE
        ).containsMatchIn(source)
    }

    /**
     * 多文件编译入口：编译「文件名 -> 源码」的一组输入，只做校验不落盘。
     * 什么时候用：需要校验整个工程而不产出 class 时。
     * 内部委托 [compileAll]；成功摘要统计所有文件的声明数与 IR 文件数。
     * 失败模式：聚合错误到 [CompileResult.Error]，本方法不抛异常。
     * 命名说明：与单文件 [compile] 构成重载（参数类型不同）。
     */
    fun compile(sources: Map<String, String>): CompileResult {
        return try {
            val pipelines = compileAll(sources)
            // 汇总所有文件的声明数与 IR 文件数，仅用于成功提示文本。
            val totalDecls = pipelines.values.sumOf { it.ast.declarations.size }
            val totalIrFiles = pipelines.values.sumOf { it.ir.irFiles.size }
            CompileResult.Success("Compilation succeeded: $totalDecls declarations, $totalIrFiles IR file(s)")
        } catch (e: CompileException) {
            CompileResult.Error(e.errors)
        } catch (e: Exception) {
            CompileResult.Error(listOf(CompileError.Internal(e.message ?: "Unknown error", e)))
        }
    }

    /**
     * 单文件编译并写盘：等价于 [compileToDirWithIr]，是落盘场景的主入口。
     * 什么时候用：CLI 或需要产出 .class 时。
     * 前置条件：outputDir 应由调用方保证存在/可写。
     * 失败模式：同 [compileToDirWithIr]。
     */
    fun compileToDir(source: String, outputDir: Path): CompileResult {
        return compileToDirWithIr(source, outputDir)
    }

    /**
     * 单文件编译 + IR→JVM 字节码写盘的实际实现。
     * 流程：compileToPipeline 得到 IR；取 [TranslationResult.typeMapper]（为 null 则新建
     * 空 [TypeMapper]）；交给 [JvmIrBackend.generate] 输出到 outputDir。
     * 为什么 typeMapper 可缺省：它由 translator 在需要时填充；缺省时新建空映射只为
     * 让后端可运行，不保证所有类型映射完整。
     * 失败模式：编译错误 → [CompileResult.Error]；IO/后端异常 → [CompileError.Internal] 兜底。
     */
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

    /**
     * 多文件编译并写盘：先 [compileAll] 得到各文件 pipeline，再把它们的
     * [TranslationResult] 合并成一个整体交给后端一次性 generate。
     * 合并策略（last-writer-wins）：按遍历顺序对各 Map 依次 putAll，后写覆盖先写；
     * interfaceDeclNames / staticFieldSymbols 这类集合取并集。
     * 为什么必须合并：跨文件的类/接口/符号必须处于同一后端上下文中才能正确解析引用。
     * 边界：typeMapper 取第一个 pipeline 的（缺失则新建空）；IR 文件按各 pipeline 顺序拼接。
     * 失败模式：同其它公开入口，聚合错误不抛异常。
     */
    fun compileAllToDir(sources: Map<String, String>, outputDir: Path): CompileResult {
        return try {
            val results = compileAll(sources)
            val allIrFiles = mutableListOf<IrFile>()
            val mergedCallNames = mutableMapOf<IrSimpleFunctionSymbol, String>()
            val mergedCtorTypes = mutableMapOf<IrConstructorSymbol, String>()
            val mergedSuperClasses = mutableMapOf<String, String>()
            val mergedInterfaces = mutableMapOf<String, List<String>>()
            val mergedIfaceDeclNames = mutableSetOf<String>()
            val mergedStaticFields = mutableSetOf<IrFieldSymbol>()

            // 逐个文件把 IR 与各类索引汇入「合并容器」。
            for ((_, pipeline) in results) {
                val tr = pipeline.ir
                allIrFiles.addAll(tr.irFiles)
                // Map 类索引用 putAll（后写覆盖先写，即 last-writer-wins）。
                mergedCallNames.putAll(tr.callNames)
                mergedCtorTypes.putAll(tr.constructorTypes)
                mergedSuperClasses.putAll(tr.classSuperclassNames)
                mergedInterfaces.putAll(tr.classInterfaceNames)
                // 集合类索引用 addAll（去重并集，不涉及覆盖语义）。
                mergedIfaceDeclNames.addAll(tr.interfaceDeclNames)
                mergedStaticFields.addAll(tr.staticFieldSymbols)
            }

            // 后端只需要一个 TypeMapper；取第一个成功 pipeline 的，缺失则新建空实例。
            val typeMapper = results.values.firstOrNull()?.ir?.typeMapper ?: TypeMapper()
            val mergedResult = TranslationResult(
                irFiles = allIrFiles,
                callNames = mergedCallNames,
                constructorTypes = mergedCtorTypes,
                classSuperclassNames = mergedSuperClasses,
                classInterfaceNames = mergedInterfaces,
                interfaceDeclNames = mergedIfaceDeclNames,
                staticFieldSymbols = mergedStaticFields
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

    /**
     * Compiles the standard library together with [sources] and writes the
     * resulting classes to [outputDir]. The stdlib sources are inserted first so
     * user declarations win the translator's last-writer-wins member indexes.
     *
     * 中文说明：把标准库源码与用户源码合并后编译并落盘。
     * 什么时候用：需要让标准库与用户代码一起产出 class 时。
     * 为什么标准库先放入 merged：合并顺序决定 last-writer-wins，用户声明放在后面
     * 才能覆盖同名标准库成员（参见 translator 的成员索引规则）。
     * 失败模式：委托 [compileAllToDir]，不抛异常，统一返回 CompileResult。
     */
    fun compileAllWithStdlib(sources: Map<String, String>, outputDir: Path): CompileResult {
        // 用 LinkedHashMap 保证顺序：先标准库、后用户源码（用户覆盖标准库）。
        val merged = LinkedHashMap<String, String>()
        merged.putAll(StdLib.sources())
        merged.putAll(sources)
        return compileAllToDir(merged, outputDir)
    }

    /**
     * 单源码的完整流水线：预处理 → 解析 → 注解处理 → 符号表 → 类型检查 → IR 翻译。
     * 什么时候用：需要拿到中间产物 [CompilePipeline]（AST/IR/注解索引）而非只判断
     * 成功失败时；也是 [compile]/[compileScript] 的底层实现。
     * 前置条件：source 已完成顶层包装（由 [compileScript] 负责）。
     * 失败模式：任一阶段有错即把所有错误聚合成 [CompileException] 抛出。
     * 说明：本重载每次新建 SymbolTable 并注册内建；多文件场景请用 [compileAll]。
     */
    fun compileToPipeline(source: String): CompilePipeline {
        // 阶段一：文本脱糖（先 ** 再其它语法糖，顺序见 preprocessPower）。
        val processed = preprocessPower(preprocessSource(source))
        // 阶段二：两段式解析，失败会在此抛 CompileException。
        val parsed = parseSource(processed)

        // Annotation processing phase: synthesize member signatures before type checking.
        // 阶段三：注解处理，在类型检查前合成 @Getter/@Setter 等成员签名。
        val annotated = AnnotationProcessor().process(parsed.ast)
        val ast = annotated.ast

        // 阶段四：符号表（含内建），并把解析/注解/符号错误收集到同一列表。
        val allErrors = mutableListOf<CompileError>()
        allErrors.addAll(parsed.errors)
        allErrors.addAll(annotated.errors.map { ae ->
            CompileError.Annotation(ae.message, ae.line, ae.column)
        })

        val symbolTable = SymbolTable()
        symbolTable.registerBuiltins()
        symbolTable.collectDeclarations(ast)
        allErrors.addAll(symbolTable.errors.map { se ->
            CompileError.Symbol(se.message, se.name, se.line, se.column)
        })

        // 阶段五：类型检查；类型错误可能不带节点位置，故 line/column 以 0 兜底。
        val typeErrors = TypeChecker(symbolTable).check(ast)
        allErrors.addAll(typeErrors.map { te ->
            CompileError.Type(te.message, line = te.node?.sourceLine ?: 0, column = te.node?.sourceColumn ?: 0)
        })

        // 聚合后再抛：让调用方一次性看到全部阶段的错误，而不是在第一个错误处中断。
        if (allErrors.isNotEmpty()) {
            throw CompileException(allErrors)
        }

        // 阶段六：AST → IR 翻译（仅在无错误时执行）。
        val translator = JasperToIrTranslator()
        val ir = translator.translate(ast)

        return CompilePipeline(parsed.parsedText, ast, ir, annotated.index)
    }

    /**
     * 多文件编译核心：共享一个 [SymbolTable] 收集所有声明，再逐文件类型检查，最后
     * 一次 translateAll，最终把每个文件映射回各自的 [CompilePipeline]。
     * 为什么先收全部声明再检查：跨文件引用要求所有符号在类型检查前就位。
     * last-writer-wins：符号表按遍历 sources 的顺序注册，后注册的同名声明覆盖先前。
     * 失败模式：聚合所有文件的解析/注解/符号/类型错误，非空即抛 [CompileException]。
     * 边界：返回 Map 的 key 即输入文件名；IR 与解析结果靠 zip 按同序对齐。
     */
    fun compileAll(sources: Map<String, String>): Map<String, CompilePipeline> {
        val allErrors = mutableListOf<CompileError>()
        // 共享符号表：先注册内建，随后逐文件 collectDeclarations 累加（顺序影响覆盖）。
        val symbolTable = SymbolTable()
        symbolTable.registerBuiltins()
        val parsed = mutableMapOf<String, jasper.ast.JasSourceFile>()
        val annotationIndexes = mutableMapOf<String, AnnotationIndex>()

        // 第一轮：逐文件预处理 + 解析 + 注解处理，并收集声明进共享符号表。
        for ((filename, source) in sources) {
            val processed = preprocessPower(preprocessSource(source))
            val result = parseSource(processed)
            // Annotation processing phase: synthesize member signatures before type checking.
            val annotated = AnnotationProcessor().process(result.ast)
            parsed[filename] = annotated.ast
            annotationIndexes[filename] = annotated.index
            allErrors.addAll(result.errors)
            allErrors.addAll(annotated.errors.map { ae ->
                CompileError.Annotation(ae.message, ae.line, ae.column)
            })
            symbolTable.collectDeclarations(annotated.ast)
        }

        allErrors.addAll(symbolTable.errors.map { se ->
            CompileError.Symbol(se.message, se.name, se.line, se.column)
        })

        // 第二轮：所有声明就位后，逐文件做类型检查（复用同一符号表）。
        for ((filename, ast) in parsed) {
            val typeErrors = TypeChecker(symbolTable).check(ast)
            allErrors.addAll(typeErrors.map { te ->
                CompileError.Type(te.message, line = te.node?.sourceLine ?: 0, column = te.node?.sourceColumn ?: 0)
            })
        }

        if (allErrors.isNotEmpty()) {
            throw CompileException(allErrors)
        }

        // 第三轮：一次性翻译全部文件，再把结果按文件切回各自的 TranslationResult。
        val translator = JasperToIrTranslator()
        val translated = translator.translateAll(parsed.values.toList(), symbolTable)
        val pipelines = mutableMapOf<String, CompilePipeline>()
        // parsed.entries 与 translated.irFiles 同序（translateAll 按传入列表顺序产出），
        // 因此可用 zip 一一对应。
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
                interfaceDeclNames = translated.interfaceDeclNames,
                staticFieldSymbols = translated.staticFieldSymbols
            )
            pipelines[filename] = CompilePipeline(sources[filename]!!, ast, ir, annotationIndexes[filename] ?: AnnotationIndex.EMPTY)
        }
        return pipelines
    }

    /**
     * 复用外部 [SymbolTable] 的单源流水线：用于调用方需要跨多次调用共享符号
     * （例如增量或分片编译）的场景。
     * 与单参重载的区别：本重载**不**注册内建、不新建符号表，collectDeclarations
     * 的声明直接并入传入的表，因此调用方需自行保证内建已注册。
     * 失败模式：聚合错误后抛 [CompileException]；成功返回 [CompilePipeline]。
     */
    fun compileToPipeline(source: String, symbolTable: SymbolTable): CompilePipeline {
        // 复用调用方传入的符号表（不注册内建、不新建），声明直接并入。
        val processed = preprocessPower(preprocessSource(source))
        val parsed = parseSource(processed)
        // Annotation processing phase: synthesize member signatures before type checking.
        // 注解处理：在类型检查前合成成员签名。
        val annotated = AnnotationProcessor().process(parsed.ast)
        val ast = annotated.ast

        val allErrors = mutableListOf<CompileError>()
        allErrors.addAll(parsed.errors)
        allErrors.addAll(annotated.errors.map { ae ->
            CompileError.Annotation(ae.message, ae.line, ae.column)
        })

        // 声明并入外部符号表；冲突/重复由 SymbolTable 记为错误。
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

        return CompilePipeline(parsed.parsedText, ast, ir, annotated.index)
    }

    /**
     * 两段式解析：
     *   第一段：严格解析；若解析树非空、无语法错误，且能构造出**非空** AST，直接返回。
     *   第二段：仅当第一段拿不到可用 AST 时，用 [wrapLegacyTopLevelMembers] 把裸顶层
     *     成员包进合成类再解析，成功后用 [unwrapLegacyTopLevelMembers] 还原。
     * 为什么两段：兼容历史/脚本语法（顶层裸语句），同时不牺牲严格解析的成功路径。
     * 失败模式：两段都失败时抛 [CompileException]；若监听器没给出错误则补一条兜底 Syntax。
     * 边界：第一段即便 parseTree 非空也可能是「不完整树」，buildSourceFile 抛异常会被
     *   吞掉并落入第二段（见 try/catch）。
     */
    private fun parseSource(source: String): ParsedSource {
        // 第一段：严格解析。
        val (firstParseTree, firstErrors) = parseSourceStrict(source)
        if (firstParseTree != null && firstErrors.isEmpty()) {
            try {
                val ast = JasperAstBuilder().buildSourceFile(firstParseTree)
                // 只有拿到非空声明列表才认为第一段真正成功，否则仍需尝试兼容包装。
                if (ast.declarations.isNotEmpty()) {
                    return ParsedSource(ast, source, firstErrors)
                }
            } catch (_: Exception) {
                // Incomplete parse tree, fall through to legacy wrapping
                // 中文：解析树不完整（构树失败），继续走下面的兼容包装路径。
            }
        }

        // 第二段：仅在确实被包装（内容有变化）时才重试，避免无效的二次解析。
        val wrapped = wrapLegacyTopLevelMembers(source)
        if (wrapped != source) {
            val (secondParseTree, secondErrors) = parseSourceStrict(wrapped)
            if (secondParseTree != null) {
                try {
                    // 解析成功后必须还原合成类，把成员提升回文件级。
                    val ast = unwrapLegacyTopLevelMembers(JasperAstBuilder().buildSourceFile(secondParseTree))
                    return ParsedSource(ast, source, secondErrors)
                } catch (_: Exception) {
                    // 构树失败：优先回报第二段错误，无错误则补兜底 Syntax。
                    throw CompileException(secondErrors.ifEmpty {
                        listOf(CompileError.Syntax("Failed to parse source (no recovery possible)"))
                    })
                }
            }
            // 连解析树都没有：同样回报第二段错误或兜底 Syntax。
            throw CompileException(secondErrors.ifEmpty {
                listOf(CompileError.Syntax("Failed to parse source (no recovery possible)"))
            })
        }

        // 未被包装（无法兼容包装）：回报第一段错误或兜底 Syntax。
        throw CompileException(firstErrors.ifEmpty {
            listOf(CompileError.Syntax("Failed to parse source (no recovery possible)"))
        })
    }

    /**
     * 严格单次解析：构造解析器、执行 sourceFile()，把监听器收集的错误转成
     * [CompileError.Syntax]（消息带「Syntax error at line..., column...」前缀）。
     * 返回 (解析树或 null, 错误列表)；解析器自身抛异常时视为解析失败（树为 null）。
     * 注意：结构化 line/column 与消息里的位置同源，便于统一格式化输出。
     */
    private fun parseSourceStrict(source: String): Pair<JasperParser.SourceFileContext?, List<CompileError>> {
        val (parser, errorListener) = createParser(source)
        // 解析器抛异常（极端输入）时按「无解析树」处理，交由上层用错误列表兜底。
        val parseTree = try {
            parser.sourceFile()
        } catch (e: Exception) {
            null
        }

        // 把 ANTLR 的 ParseError 提升为结构化 CompileError.Syntax。
        val syntaxErrors = errorListener.errors.map { pe ->
            CompileError.Syntax(
                message = "Syntax error at line ${pe.line}, column ${pe.column}: ${pe.message}",
                line = pe.line,
                column = pe.column
            )
        }

        return parseTree to syntaxErrors
    }

    /**
     * 把「包声明 + 导入 + 裸顶层成员」包进一个合成类 `__JasperCompatTopLevel`。
     * 目的：让只写顶层语句、没有外层类的旧式源码也能被语法解析（见 parseSource 第二段）。
     * 实现：用一条正则把源码切成 header（package/import 行）与 body（其余），在 header
     * 之后插入类声明、把 body 放进类体再闭合。
     * 边界：正则整串不匹配时原样返回；调用方据此（wrapped != source）判断是否真的包装了。
     * 前置条件：仅用于解析兼容，最终由 [unwrapLegacyTopLevelMembers] 还原。
     */
    private fun wrapLegacyTopLevelMembers(source: String): String {
        // (?s) 让 `.` 跨行；group1=package 行，group2=import/from 行，group3=其余 body。
        val match = Regex("""(?s)^(\s*package\b[^;]*;\s*)?((?:\s*(?:import|from)\b[^;]*;\s*)*)(.*)$""")
            .matchEntire(source)
            ?: return source
        val header = (match.groupValues[1] + match.groupValues[2])
        val body = match.groupValues[3]
        return header + "public class __JasperCompatTopLevel {\n" + body + "\n}"
    }

    /**
     * [wrapLegacyTopLevelMembers] 的逆操作：若 AST 恰好只有一个名为
     * `__JasperCompatTopLevel` 的类，则把它的成员提升为文件级声明。
     * 边界：形状不符时原样返回；用 singleOrNull 保证只有唯一合成类时才还原，
     * 避免误伤用户自己定义的同名类。
     */
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
     *
     * 中文说明：把幂运算语法糖脱糖成对 `__jasper_pow__(a, b)` 的普通调用。
     * 为什么需要：语法/后端没有原生的 `**`，统一降级为函数调用最省事。
     * 顺序要求：必须先处理 `**=` 再处理 `**`，否则 `**=` 会被 `**` 规则部分匹配，
     * 破坏赋值语义（见下方注释）。
     * 边界：与 preprocessSource 一样是纯文本替换，可能误伤字符串/注释。
     */
    private fun preprocessPower(source: String): String {
        // Handle `**=` first (must come before `**` to avoid partial match)
        // 中文：先处理复合赋值 `a **= b` → `a = __jasper_pow__(a, b)`。
        val step1 = source.replace(Regex("""((?:\w+|\)|\]))\s*\*\*=\s*((?:\w+|\(|\())""")) { m ->
            "${m.groupValues[1]} = __jasper_pow__(${m.groupValues[1]}, ${m.groupValues[2]})"
        }
        // Handle `**` binary operator
        // 中文：再处理二元幂 `a ** b` → `__jasper_pow__(a, b)`。
        return step1.replace(Regex("""((?:\w+|\)|\]))\s*\*\*\s*((?:\w+|\(|\())""")) { m ->
            "__jasper_pow__(${m.groupValues[1]}, ${m.groupValues[2]})"
        }
    }

    /**
     * 组装 ANTLR 解析器：CharStream → JasperLexer → CommonTokenStream → JasperParser，
     * 并把默认错误监听器换成 [JasperErrorListener]、错误策略设为 [JasperErrorStrategy]。
     * 为什么 removeErrorListeners：默认监听器会把错误直接打到 stderr，必须先移除，
     * 改由我们自己的监听器静默收集，保证输出可控。
     * 返回 (parser, listener)；调用方从 listener 取错误列表。
     */
    private fun createParser(source: String): Pair<JasperParser, JasperErrorListener> {
        val input = CharStreams.fromString(source)
        val lexer = JasperLexer(input)
        val tokens = org.antlr.v4.runtime.CommonTokenStream(lexer)
        val parser = JasperParser(tokens)
        val errorListener = JasperErrorListener()
        // 换掉默认监听器（会污染 stderr），改用自定义监听器收集。
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)
        // 用自定义恢复策略：出错后同步到分号/右花括号，尽量继续解析以汇集更多错误。
        parser.errorHandler = JasperErrorStrategy()
        return Pair(parser, errorListener)
    }

    /**
     * 对外统一的编译结果：密封成「成功」与「失败」两支，调用方可用 when 穷尽处理。
     * 为什么不用「空错误列表表示成功」：显式两支能让调用方无法忽略失败分支。
     * 变化点：将来若需要「部分成功」等语义，在此新增子类即可。
     */
    sealed class CompileResult {
        /**
         * 编译成功：message 为人读摘要（声明数 / IR 文件数 / 输出目录等），不含副作用细节。
         */
        data class Success(val message: String) : CompileResult()
        /**
         * 编译失败：errors 为聚合后的全部诊断；message 是把每条诊断格式化拼接后的
         * 多行文本，便于日志/CLI 直接打印。
         */
        data class Error(val errors: List<CompileError>) : CompileResult() {
            val message: String get() = "Compilation failed:\n${errors.joinToString("\n") { formatError(it) }}"
        }
    }
}

/**
 * CLI 入口：`jasperc <source-file> [-d <output-dir>]`。
 * 用法：第一个参数是源文件；可选 `-d <dir>` 指定输出目录，缺省为当前目录 `.`。
 * 失败模式：无参数、源文件不存在或编译失败，均打印到 stderr 并以退出码 1 结束；
 * 成功打印摘要并以默认退出码 0 结束。
 * 为什么直接 System.exit：作为命令行进程顶层入口，需要把成败反映到退出码。
 */
fun main(args: Array<String>) {
    // 参数校验：至少一个源文件路径。
    if (args.isEmpty()) {
        System.err.println("Usage: jasperc <source-file> [-d <output-dir>]")
        System.exit(1)
    }

    val sourceFile = Paths.get(args[0])
    // 源文件不存在直接失败；此处不做存在性之外的可读性检查。
    if (!sourceFile.toFile().exists()) {
        System.err.println("Error: source file not found: $sourceFile")
        System.exit(1)
    }

    // 仅在形如 `-d <dir>`（且参数够 3 个）时使用指定目录，否则输出到当前目录。
    val outputDir = if (args.size >= 3 && args[1] == "-d") {
        Paths.get(args[2])
    } else {
        Paths.get(".")
    }

    val source = String(Files.readAllBytes(sourceFile))
    val compiler = JasperCompiler()
    val result = compiler.compileToDir(source, outputDir)

    // 统一分派：成功打印到 stdout；失败打印到 stderr 并以退出码 1 结束。
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
