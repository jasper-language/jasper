package jasper.compiler

import jasper.ast.JasAnnotationType
import jasper.ast.JasClass
import jasper.ast.JasDeclaration
import jasper.ast.JasEnum
import jasper.ast.JasFunction
import jasper.ast.JasInterface
import jasper.ast.JasProperty
import jasper.ast.JasSourceFile

/**
 * strict 选项（[CompileOptions.strict]）的文件布局检查：一文件一类型。
 *
 * 做什么：在不改文法的前提下，把编译器为脚本与旧写法做的放宽在 strict 下一律改成诊断。
 *   文法 `sourceFile` 本来只允许一个顶层类型；放宽来自两处——`.jass` 脚本规范化，
 *   以及 [JasperCompiler] 的兼容包装（多个顶层类型、裸顶层函数 / 字段）。
 * 什么时候用：strict 编译时，每个用户源文件解析前调 [beforeParse]，解析后调 [afterParse]。
 * 失败模式：只返回诊断，不抛异常；调用方把它们并入本阶段的错误一起报告。
 * 为什么按 AST 而不是按「是否走了兼容包装」判断：判据只看文件实际声明了什么，
 *   与解析器内部用了几段无关，将来去掉兼容包装时这里不用改。
 *
 * 诊断码（消息前缀，供工具与文档引用）：
 * - STRICT-001 顶层可执行语句或脚本单元；
 * - STRICT-002 多个顶层类型；
 * - STRICT-003 裸顶层函数 / 字段；
 * - STRICT-004 文件主名与顶层类型名不一致（只在文件名可知时检查）；
 * - STRICT-005 文件没有顶层类型。
 */
internal object StrictLayout {

    /**
     * 解析前检查：脚本单元、顶层可执行语句、空文件。
     * 这些形态要么进不了解析器（语句先被模块模式拒绝），要么解析器给不出可读原因（空文件）。
     */
    fun beforeParse(filename: String?, mode: SourceMode, source: String): List<CompileError> {
        val label = label(filename)
        val layout = analyzeSourceLayout(source)
        val first = layout.firstExecutable
        return when {
            first != null -> listOf(CompileError.Syntax(
                "[STRICT-001] $label: top-level statements are not allowed with --strict; move them into a function of the file's type",
                line = first.line, column = first.column
            ))
            mode == SourceMode.Script -> listOf(CompileError.Syntax(
                "[STRICT-001] $label: script units (.jass / compileScript) are not allowed with --strict",
                line = 1, column = 0
            ))
            layout.items.isEmpty() -> listOf(CompileError.Syntax(
                "[STRICT-005] $label: the file declares no type; with --strict every source file declares exactly one",
                line = 1, column = 0
            ))
            else -> emptyList()
        }
    }

    /** 解析后检查：顶层只能是恰好一个类型声明，且（文件名可知时）与文件主名同名。 */
    fun afterParse(filename: String?, ast: JasSourceFile): List<CompileError> {
        val label = label(filename)
        val errors = mutableListOf<CompileError>()
        val types = ast.declarations.filter(::isType)
        for (member in ast.declarations.filterNot(::isType)) {
            errors += CompileError.Syntax(
                "[STRICT-003] $label: top-level ${kind(member)} '${name(member)}' is not allowed with --strict; declare it inside the file's type",
                line = member.sourceLine, column = member.sourceColumn
            )
        }
        for (extra in types.drop(1)) {
            errors += CompileError.Syntax(
                "[STRICT-002] $label: second top-level type '${name(extra)}' (first is '${name(types.first())}'); with --strict each type goes in its own file",
                line = extra.sourceLine, column = extra.sourceColumn
            )
        }
        if (types.isEmpty()) {
            errors += CompileError.Syntax(
                "[STRICT-005] $label: the file declares no type; with --strict every source file declares exactly one",
                line = 1, column = 0
            )
        }
        val stem = filename?.let(::stem)
        val only = types.singleOrNull()
        if (stem != null && only != null && name(only) != stem) {
            errors += CompileError.Syntax(
                "[STRICT-004] $label: type '${name(only)}' must be declared in '${name(only)}${extension(filename)}' with --strict",
                line = only.sourceLine, column = only.sourceColumn
            )
        }
        return errors
    }

    private fun isType(d: JasDeclaration): Boolean =
        d is JasClass || d is JasInterface || d is JasEnum || d is JasAnnotationType

    private fun kind(d: JasDeclaration): String = when (d) {
        is JasFunction -> "function"
        is JasProperty -> "field"
        else -> "declaration"
    }

    private fun name(d: JasDeclaration): String = when (d) {
        is JasClass -> d.name
        is JasInterface -> d.name
        is JasEnum -> d.name
        is JasAnnotationType -> d.name
        is JasFunction -> d.name
        is JasProperty -> d.name
        else -> d.javaClass.simpleName
    }

    private fun label(filename: String?): String = if (filename == null) "source" else "'$filename'"

    /** 文件主名：去掉目录与最后一个扩展名；`a/b/Foo.jas` → `Foo`。 */
    private fun stem(filename: String): String =
        filename.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')

    private fun extension(filename: String): String {
        val base = filename.replace('\\', '/').substringAfterLast('/')
        return if ('.' in base) "." + base.substringAfterLast('.') else ""
    }
}
