// ============================================================================
// JasperManifest.kt —— 编译器 `jasper-manifest.json` 元数据写出器（compiler 编排层）
// ============================================================================
// 职责：
//   在一次成功编译（class 已写盘）之后，把源码中「声明性元信息」导出成
//   `<outputDir>/jasper-manifest.json`。当前只导出 `throws` 子句：
//   每个 class / interface 的方法与构造器各自列出其声明的异常类型。
//
// 所属层次：
//   compiler 编排层。只依赖 ast 层（Jas* 节点），因此处于依赖方向的上层→下层，
//   不产生任何反向依赖，也不被 ast/checker/translator 引用。
//
// 与谁协作：
//   - JasperCompiler.compileToDirWithIr（单文件）与 compileAllToDir（多文件）在
//     后端写盘成功后调用本文件的 write* 入口；
//   - 消费方是工具链 / AI / 模块消费者（IDE、文档生成、静态分析）。
//
// 对外契约：
//   - 输出 JSON，schema 恒为 "jasper-manifest/0"；
//   - `throws` 空时也要写出 `[]`（结构稳定，便于消费方无脑取值）；
//   - 写出失败（IOException 等）不吞：由调用方按既有 CompileError.Internal 兜底。
//
// 与 Managed `.jaso` ABI 的边界（重要）：
//   本文件产出的是**编译器侧 manifest JSON**，不是 OS 的 `.jaso` 二进制格式。
//   Managed `.jaso` 是联合规范、仍在演进（2026-10-18 用户裁决），因此此处
//   刻意不发明、不冻结任何二进制布局；待 Managed ABI 冻结后，再把本 JSON 的
//   schema 映射进 `.jaso` 的 manifest 节。
//
// 设计模式说明：
//   - 这里使用「递归遍历 + 不可变条目数据类」的简单结构。隔离的变化点是
//     「manifest 里要记哪些字段」：新增字段只需改这里的数据类与 JSON 渲染，
//     AST 与编译器流水线其余部分不受影响（反对为模式而模式的过度抽象）。
//
// 扩展点 / 已知限制：
//   - 目前只记 name/kind/methods/constructors/functions 与 throws；
//   - 方法只写简单名，不写描述符，因此重载会在同一 methods 数组里出现同名条目；
//   - 嵌套类型用 JVM 二进制风格的 `$` 连接（与 class 文件命名一致），但当前
//     语言写 `Outer.Inner` 源码引用时，二者映射由调用方自行对应；
//   - enum / annotation type 暂不导出（本任务只要求 class / interface）。
// ----------------------------------------------------------------------------

package jasper.compiler

import jasper.ast.JasClass
import jasper.ast.JasConstructor
import jasper.ast.JasDeclaration
import jasper.ast.JasFunction
import jasper.ast.JasInterface
import jasper.ast.JasSourceFile
import jasper.ast.JasType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 编译器 manifest（`jasper-manifest.json`）写出器。
 *
 * 做什么：把若干已成功编译的源文件 AST 归并成一份 JSON 元数据并写到编译输出目录，
 * 记录每个类型（class/interface）的方法 / 构造器及其 `throws` 声明。
 * 什么时候用：JasperCompiler 在 class 写盘成功后调用（单文件 [writeForFile]、
 * 多文件 [writeForFiles]）；也可独立调用 [render] 只取文本用于测试/预览。
 * 前置条件：传入的 AST 来自一次**成功**编译（错误流程不会走到这里）；
 * `outputDir` 应由调用方保证存在/可写（通常后端刚在里面写过 class）。
 * 失败模式：IO 异常不吞，向上抛出，由 JasperCompiler 的兜底分支转成
 * `CompileError.Internal` 返回 Error；本类不做静默降级。
 * 为什么设计成 object 且无状态：manifest 渲染是纯函数式的「AST 文本 → JSON 文本」，
 * 不持有跨次编译状态，避免并发/复用污染（与 JasperCompiler 无状态风格一致）。
 */
object JasperManifest {

    /** manifest 文件固定名（写在该编译输出目录根下）。 */
    const val FILE_NAME: String = "jasper-manifest.json"

    /** manifest schema 版本标识；消费方据此选择解析逻辑。 */
    const val SCHEMA: String = "jasper-manifest/0"

    /**
     * 单文件编译的 manifest 写入口。
     *
     * 做什么：把 [ast] 归并渲染后写入 `outputDir/jasper-manifest.json`（UTF-8，覆盖写）。
     * 什么时候用：`compileToDirWithIr` 写盘成功后。
     * 前置条件：outputDir 存在/可写。
     * 失败模式：IO 异常直接抛出（不静默）。
     */
    fun writeForFile(ast: JasSourceFile, outputDir: Path) {
        writeForFiles(listOf(ast), outputDir)
    }

    /**
     * 多文件编译的 manifest 写入口。
     *
     * 做什么：按 [asts] 的顺序归并所有类型/顶层函数，渲染后写入
     * `outputDir/jasper-manifest.json`（UTF-8，覆盖写）。
     * 什么时候用：`compileAllToDir` 合并写盘成功后。
     * 前置条件：asts 来自同一批成功编译；outputDir 存在/可写。
     * 失败模式：IO 异常直接抛出（不静默）。
     * 顺序语义：类型按「遍历顺序 + last-writer-wins」去重（与编译器合并语义一致），
     * 同名类型后出现者覆盖先出现者，但保留首次出现的位置。
     */
    fun writeForFiles(asts: List<JasSourceFile>, outputDir: Path) {
        val text = render(asts)
        Files.write(outputDir.resolve(FILE_NAME), text.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 把若干源文件 AST 渲染成 manifest JSON 文本（不落盘）。
     *
     * 做什么：收集每个文件的 class/interface（含嵌套类型）与顶层函数，输出稳定缩进的 JSON。
     * 什么时候用：测试里想直接比对文本、或调用方想自行决定写盘位置时。
     * 前置条件：asts 可空（则输出 types/functions 均为空数组）。
     * 失败模式：纯字符串拼接，不抛业务异常。
     */
    fun render(asts: List<JasSourceFile>): String {
        // LinkedHashMap：保持声明顺序，并对同名类型实现 last-writer-wins 覆盖。
        val types = LinkedHashMap<String, TypeEntry>()
        val functions = mutableListOf<MethodEntry>()
        for (ast in asts) {
            collectTypes(ast.packageName, ast.declarations, types)
            collectFunctions(ast.packageName, ast.declarations, functions)
        }
        return renderJson(types.values.toList(), functions)
    }

    /**
     * 把一个 AST 类型渲染成对人类可读、且与源码写法接近的类型名字符串。
     *
     * 做什么：覆盖具名类型（含泛型实参）、基本类型、数组、可空/非空、函数/元组/通配等，
     * 供 manifest 的 `throws` 字符串数组使用。
     * 什么时候用：收集 throws 列表时逐项转换。
     * 前置条件：type 非空；未知子类落到 `"unknown"` 兜底（不会抛异常）。
     * 失败模式：无；本函数只做展示性渲染，不承担类型等价判定。
     * 为什么不用后端描述符：manifest 面向人类/工具阅读，保留 `std.Error`、`A<B>[]`
     * 这类源码风格更直观，也与将来 `.jaso` manifest 节的内容语义一致。
     */
    fun renderType(type: JasType): String = when (type) {
        is jasper.ast.JasNamedType ->
            if (type.typeArguments.isEmpty()) type.name
            else "${type.name}<${type.typeArguments.joinToString(", ") { renderType(it) }}>"
        is jasper.ast.JasPrimitiveType -> type.name
        is jasper.ast.JasNullableType -> "${renderType(type.inner)}?"
        is jasper.ast.JasNonNullType -> "${renderType(type.inner)}!"
        is jasper.ast.JasArrayType -> "${renderType(type.inner)}[]"
        is jasper.ast.JasPointerType -> "${renderType(type.inner)}*"
        is jasper.ast.JasReferenceType -> "&${renderType(type.inner)}"
        is jasper.ast.JasFunctionType -> {
            val params = type.paramTypes.joinToString(", ") { renderType(it) }
            val tail = if (type.varargs) "..." else ""
            "($params$tail) -> ${renderType(type.returnType)}"
        }
        is jasper.ast.JasTupleType -> type.types.joinToString(", ", "(", ")") { renderType(it) }
        is jasper.ast.JasWildcardType -> {
            val bound = type.bound
            if (bound == null) "?"
            else if (type.extends) "? extends ${renderType(bound)}" else "? super ${renderType(bound)}"
        }
        jasper.ast.JasStringType -> "string"
        jasper.ast.JasBytesType -> "bytes"
        jasper.ast.JasRegexType -> "regex"
        jasper.ast.JasUnitType -> "void"
        jasper.ast.JasAnyType -> "any"
        else -> "unknown"
    }

    // ── 内部收集与渲染 ────────────────────────────────────────────────────

    /** 一个方法/构造器条目：名字 + 其 throws 类型（已渲染为字符串）。 */
    private data class MethodEntry(val name: String, val throwsTypes: List<String>)

    /** 一个类型条目：全限定名 + kind + 方法 + 构造器。 */
    private data class TypeEntry(
        val name: String, val kind: String,
        val methods: List<MethodEntry>, val constructors: List<MethodEntry>
    )

    /** 把包名与类型名拼成全限定名；无包名时返回裸名。 */
    private fun qualify(pkg: String?, name: String): String =
        if (pkg.isNullOrEmpty()) name else "$pkg.$name"

    /**
     * 递归收集一段声明里的 class/interface（含嵌套）。嵌套名用 `$` 连接外层简单名。
     *
     * 为什么递归：语法允许多层嵌套类型（MemberClass / MemberInterface），
     * 只记顶层会漏掉内层方法/构造器的 throws。变化点被收束在本函数内。
     */
    private fun collectTypes(
        pkg: String?, declarations: List<JasDeclaration>, out: MutableMap<String, TypeEntry>
    ) {
        for (decl in declarations) {
            when (decl) {
                is JasClass -> addType(pkg, "", decl.name, "class", decl.members, decl.constructors, out)
                is JasInterface -> addType(pkg, "", decl.name, "interface", decl.members, emptyList(), out)
                else -> Unit
            }
        }
    }

    /** 登记单个类型条目，并递归其成员里的嵌套类型。 */
    private fun addType(
        pkg: String?, outerPrefix: String, simpleName: String, kind: String,
        members: List<JasDeclaration>, constructors: List<JasConstructor>,
        out: MutableMap<String, TypeEntry>
    ) {
        val fq = qualify(pkg, outerPrefix + simpleName)
        val methods = members.filterIsInstance<JasFunction>().map { toMethodEntry(it.name, it.throwsTypes) }
        // 构造器统一用 JVM 习惯名 `<init>`，便于消费方与字节码对应。
        val ctors = constructors.map { toMethodEntry("<init>", it.throwsTypes) }
        out[fq] = TypeEntry(fq, kind, methods, ctors)

        // 嵌套类型：外层名 + `$` + 内层简单名（与 .class 文件命名一致）。
        val nestedPrefix = outerPrefix + simpleName + "$"
        for (member in members) {
            when (member) {
                is JasClass -> addType(pkg, nestedPrefix, member.name, "class", member.members, member.constructors, out)
                is JasInterface -> addType(pkg, nestedPrefix, member.name, "interface", member.members, emptyList(), out)
                else -> Unit
            }
        }
    }

    /** 把一个签名渲染成 MethodEntry（throws 逐项转字符串）。 */
    private fun toMethodEntry(name: String, throwsTypes: List<JasType>): MethodEntry =
        MethodEntry(name, throwsTypes.map { renderType(it) })

    /** 收集顶层函数（不含类成员）为 MethodEntry 列表。 */
    private fun collectFunctions(
        pkg: String?, declarations: List<JasDeclaration>, out: MutableList<MethodEntry>
    ) {
        for (decl in declarations) {
            if (decl is JasFunction) {
                out.add(toMethodEntry(qualify(pkg, decl.name), decl.throwsTypes))
            }
        }
    }

    /**
     * 生成最终 JSON 文本。
     *
     * 契约：字段顺序固定为 schema → types → functions；`throws` 恒为数组（空则 `[]`）。
     * 为什么手写而不引依赖：避免为一份小文档引入 JSON 库；同时显式控制转义与缩进，
     * 让测试可稳定断言片段。所有字符串都经 [escapeJson] 处理。
     */
    private fun renderJson(types: List<TypeEntry>, functions: List<MethodEntry>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": \"").append(escapeJson(SCHEMA)).append("\",\n")
        sb.append("  \"types\": ")
        appendTypesArray(sb, types)
        sb.append(",\n")
        sb.append("  \"functions\": ")
        appendMethodsArray(sb, functions, indent = "  ")
        sb.append("\n}\n")
        return sb.toString()
    }

    /** 渲染 types 数组；空数组直接写 `[]`。 */
    private fun appendTypesArray(sb: StringBuilder, types: List<TypeEntry>) {
        if (types.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append("[\n")
        types.forEachIndexed { i, t ->
            sb.append("    {\n")
            sb.append("      \"name\": \"").append(escapeJson(t.name)).append("\",\n")
            sb.append("      \"kind\": \"").append(escapeJson(t.kind)).append("\",\n")
            sb.append("      \"methods\": ")
            appendMethodsArray(sb, t.methods, indent = "      ")
            sb.append(",\n")
            sb.append("      \"constructors\": ")
            appendMethodsArray(sb, t.constructors, indent = "      ")
            sb.append("\n    }")
            if (i != types.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]")
    }

    /**
     * 渲染一组方法/构造器对象数组。
     *
     * indent 是当前数组左括号所处的缩进，用于把内部字段再缩进两格。
     */
    private fun appendMethodsArray(sb: StringBuilder, entries: List<MethodEntry>, indent: String) {
        if (entries.isEmpty()) {
            sb.append("[]")
            return
        }
        val inner = "$indent  "
        sb.append("[\n")
        entries.forEachIndexed { i, e ->
            sb.append(inner).append("{\n")
            sb.append(inner).append("  \"name\": \"").append(escapeJson(e.name)).append("\",\n")
            sb.append(inner).append("  \"throws\": ")
            appendStringArray(sb, e.throwsTypes)
            sb.append("\n")
            sb.append(inner).append("}")
            if (i != entries.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append(indent).append("]")
    }

    /** 渲染字符串数组；空数组写 `[]`。 */
    private fun appendStringArray(sb: StringBuilder, values: List<String>) {
        if (values.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append(values.joinToString(", ", "[", "]") { "\"${escapeJson(it)}\"" })
    }

    /**
     * JSON 字符串转义：处理 `"`、`\` 与控制字符（含 \b \f \n \r \t，其余 < 0x20 用 \uXXXX）。
     *
     * 为什么必须做：类型名/方法名虽多为标识符，但 manifest 属于对外契约，
     * 一旦出现未转义字符就会产出非法 JSON，消费方解析整体失败。
     */
    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
