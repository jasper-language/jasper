// ============================================================================
// JasoModule.kt —— 把一次成功编译固化成 Managed `.jaso`（compiler 编排层，JB-L2）
// ============================================================================
// 职责：
//   - JasoExports：从源码 AST 固化导出（不靠反射），产出 core.exports 与 jasper.import 文本；
//     凡当前表达不了的公开形态一律诊断，不静默丢弃。
//   - JasoManifestText：校验模块元数据并写清单文本。
//   - JasoWriter：确定性地排布 format 1 文件字节（JVM class 归档 + 导出表 + 扩展段）。
//
// 所属层次：compiler 编排层，只依赖 ast 层；由 JasperCompiler.compileToJaso 调用。
//
// 对外契约（字节级规范是 jasper-os 仓库的 jaso/SPEC.md）：
//   - 同样的输入产出逐字节相同的文件：导出、类型、class 都排序，ZIP 时间戳固定；
//   - 目录顺序与参考实现的构建器相同：清单、平台、镜像索引、代码、导出、扩展；
//   - 只有写明 public 的声明才进入模块接口；JVM 层的可见性不算数。
//
// jasper.import 里类型的含义（规范把它留给 Jasper 层）：
//   - 带点的名字是全限定类型；不带点的是语言内建：int32 等基本类型、string、bytes、regex、void、any；
//   - 数组写 Array<T>（SPEC 的类型语法没有 []），可空写 T?；
//   - 第一个形参名为 this 的是实例方法，符号 <类>.new 是构造器；
//   - 顶层 public function jaso_init / jaso_start / jaso_stop(): int32 是生命周期导出，只进导出表；
//   - 托管对象按 GC 引用传递，没有所有权转移，所以不写 own / borrow。
//
// 已知限制（都报诊断，见 docs/jaso.md）：一个模块只能有一个包；泛型声明、嵌套类型、枚举、
//   注解类型、公开字段、可变参数、默认实参、函数 / 元组 / 通配 / 指针 / 引用类型暂不能出现在接口上。
//   不写签名：签名由打包工具完成。
// ----------------------------------------------------------------------------

package jasper.compiler

import jasper.ast.JasAnnotationType
import jasper.ast.JasAnyType
import jasper.ast.JasArrayType
import jasper.ast.JasBytesType
import jasper.ast.JasClass
import jasper.ast.JasConstructor
import jasper.ast.JasDeclaration
import jasper.ast.JasEnum
import jasper.ast.JasFunction
import jasper.ast.JasImport
import jasper.ast.JasInterface
import jasper.ast.JasNamedType
import jasper.ast.JasNode
import jasper.ast.JasNonNullType
import jasper.ast.JasNullableType
import jasper.ast.JasParameter
import jasper.ast.JasPrimitiveType
import jasper.ast.JasProperty
import jasper.ast.JasRegexType
import jasper.ast.JasSourceFile
import jasper.ast.JasStringType
import jasper.ast.JasType
import jasper.ast.JasUnitType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * 模块元数据：来自命令行，写进清单的 `core.*` 键。
 *
 * 做什么：承载一个模块的身份、运行时要求、依赖与能力请求。
 * 什么时候用：调用 [JasperCompiler.compileToJaso] 时传入。
 * 前置条件：无；取值由 [JasoManifestText.render] 校验。
 * 失败模式：无（纯数据）。
 *
 * @property runtime 版本区间；默认是当前运行时的 0.1 系列。
 * @property depends 依赖项，每项 `名字` 或 `名字@区间`。
 * @property requires 能力请求；权限是请求不是授权。
 * @property lifecycle `plugin`、`system` 或 null（规范默认 plugin）。
 */
data class JasoModuleOptions(
    val name: String,
    val version: String,
    val description: String? = null,
    val publisher: String? = null,
    val runtime: String = JasoManifestText.DEFAULT_RUNTIME,
    val depends: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val lifecycle: String? = null
)

/**
 * 固化后的模块接口。
 *
 * 做什么：承载导出符号表与 jasper.import 文本，两者逐项对应。
 * 什么时候用：[JasoExports.plan] 产出，[JasoWriter.assemble] 消费。
 * 前置条件：来自一次无诊断的 plan。
 * 失败模式：无（纯数据）。
 */
data class JasoInterface(val packageName: String, val exports: List<String>, val importText: String)

/**
 * 从源码 AST 固化模块接口。
 *
 * 做什么：遍历各文件的公开声明，得到导出符号与类型化签名。
 * 什么时候用：编译成功后、写文件前。
 * 前置条件：传入的是协程降级之前的 AST，生成帧不属于接口。
 * 失败模式：不抛异常；不能表达的公开形态进入 [Result.errors]，有错误时接口不可用。
 * 为什么报错而不跳过：静默少导出一个公开符号，要到别的模块编译时才暴露，且原因难查。
 */
object JasoExports {
    /** 符号与名字的上限：SPEC 第 7.2 节、第 9.4 节、第 11 节。 */
    private const val MAX_SYMBOL_BYTES = 128
    private const val MAX_MANIFEST_EXPORTS = 256
    private const val MAX_IMPORT_BYTES = 65536
    private const val MAX_IMPORT_LINES = 1024
    private const val MAX_TYPE_DEPTH = 8

    /** 生命周期导出：规范第 9.4 节。 */
    private val LIFECYCLE = setOf("jaso_init", "jaso_start", "jaso_stop")

    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val SYMBOL = Regex("[A-Za-z0-9_.$]+")
    private val DOTTED = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")

    /**
     * 固化结果：[errors] 非空时 [module] 为 null。
     */
    data class Result(val module: JasoInterface?, val errors: List<CompileError>)

    /** 一个导出：源码名（已含类名）、形参类型指纹、jasper.import 里符号之后的签名文本。 */
    private class Export(val base: String, val fingerprint: String, val signature: String, val at: JasNode)

    /**
     * 固化一个模块的接口。
     *
     * 做什么：校验「只有一个包」，收集导出，给重载加稳定后缀，排序后渲染。
     * 什么时候用：[JasperCompiler.compileToJaso] 在生成 class 之前调用。
     * 前置条件：[asts] 是本模块全部源文件，[requires] 已通过清单校验。
     * 失败模式：见 [Result]。
     */
    fun plan(asts: List<JasSourceFile>, requires: List<String>): Result {
        val errors = mutableListOf<CompileError>()
        val packages = asts.map { it.packageName.orEmpty() }.toSet()
        val pkg = packages.singleOrNull()
        if (pkg == null || pkg.isEmpty()) {
            val what = if (packages.size > 1) "several packages (${packages.sorted().joinToString()})" else "no package"
            errors += CompileError.Symbol("A .jaso module needs exactly one package, found $what", "package")
            return Result(null, errors)
        }
        if (!DOTTED.matches(pkg)) {
            errors += CompileError.Symbol("Package '$pkg' cannot be written in jasper.import", pkg)
            return Result(null, errors)
        }
        if (pkg == "std" || pkg.startsWith("std.")) {
            errors += CompileError.Symbol("A .jaso module cannot define package '$pkg': std belongs to the runtime", pkg)
            return Result(null, errors)
        }

        val moduleTypes = asts.flatMap { ast ->
            ast.declarations.mapNotNull { (it as? JasClass)?.name ?: (it as? JasInterface)?.name ?: (it as? JasEnum)?.name }
        }.toSet()
        val exportedTypes = sortedSetOf<String>()
        val exports = mutableListOf<Export>()
        for (ast in asts) {
            FileWalk(pkg, ast, moduleTypes, errors, exportedTypes, exports).walk()
        }
        // 接口上提到的本模块类型必须也是接口的一部分，否则导入方拿不到它。
        val hidden = (moduleTypes - exportedTypes).map { "$pkg.$it" }
        for (e in exports) {
            val leaked = hidden.firstOrNull { Regex("(^|[^A-Za-z0-9_.])" + Regex.escape(it) + "($|[^A-Za-z0-9_.])").containsMatchIn(e.signature) }
            if (leaked != null) {
                errors += located("Exported '${e.base}' mentions '$leaked', which is not public", e.base, e.at)
            }
        }

        val byBase = exports.groupBy { it.base }
        val symbols = sortedMapOf<String, Export>()
        for ((base, group) in byBase) {
            for (e in group) {
                // 重载的符号带形参指纹：与声明顺序无关；导入方靠签名解析，后缀对它不透明。
                val symbol = if (group.size == 1) base else base + "$" + sha256Hex(e.fingerprint).substring(0, 8)
                if (symbols.put(symbol, e) != null) {
                    errors += located("Two exports share the symbol '$symbol' (same parameter types?)", symbol, e.at)
                }
            }
        }
        for ((symbol, e) in symbols) {
            if (symbol.length > MAX_SYMBOL_BYTES || !SYMBOL.matches(symbol)) {
                errors += located("Export name '$symbol' is not 1..$MAX_SYMBOL_BYTES bytes of [A-Za-z0-9_.$]", symbol, e.at)
            }
        }
        if (symbols.size > MAX_MANIFEST_EXPORTS) {
            errors += CompileError.Symbol("A module can export at most $MAX_MANIFEST_EXPORTS functions, found ${symbols.size}", "exports")
        }
        if (errors.isNotEmpty()) return Result(null, errors)

        val lines = mutableListOf("package $pkg")
        exportedTypes.mapTo(lines) { "type $it" }
        requires.sorted().mapTo(lines) { "cap $it" }
        symbols.filterKeys { it !in LIFECYCLE }.mapTo(lines) { (symbol, e) -> "fn $symbol${e.signature}" }
        val text = lines.joinToString("\n", postfix = "\n")
        val size = text.toByteArray(StandardCharsets.UTF_8).size
        if (size > MAX_IMPORT_BYTES || lines.size > MAX_IMPORT_LINES) {
            errors += CompileError.Symbol(
                "jasper.import is limited to $MAX_IMPORT_BYTES bytes and $MAX_IMPORT_LINES lines, this module needs $size bytes and ${lines.size} lines",
                "exports"
            )
            return Result(null, errors)
        }
        return Result(JasoInterface(pkg, symbols.keys.toList(), text), errors)
    }

    private fun located(message: String, name: String, at: JasNode): CompileError =
        CompileError.Symbol(message, name, at.sourceLine, at.sourceColumn)

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun isPublic(modifiers: List<Any>): Boolean = "public" in modifiers

    /** 一个源文件的遍历：持有该文件的 import 表，因为类型名按文件解析。 */
    private class FileWalk(
        val pkg: String,
        val ast: JasSourceFile,
        val moduleTypes: Set<String>,
        val errors: MutableList<CompileError>,
        val exportedTypes: MutableSet<String>,
        val exports: MutableList<Export>
    ) {
        /** 显式 import：别名优先，否则取最后一段；按需导入（.*）解析不出名字，不进表。 */
        private val imports: Map<String, String> = ast.imports.filterIsInstance<JasImport>().filter { !it.isOnDemand }
            .associate { (it.alias ?: it.name.substringAfterLast('.')) to it.name }

        /** 遍历本文件的顶层声明：公开形态写入导出，不能表达的公开形态记诊断。 */
        fun walk() {
            for (decl in ast.declarations) {
                when (decl) {
                    is JasFunction -> when {
                        decl.name in LIFECYCLE -> lifecycle(decl)
                        // 顶层函数落在以函数名命名的类里，是它的静态方法（见 JvmIrBackend）。
                        isPublic(decl.modifiers) -> function(decl, "$pkg.${decl.name}.${decl.name}", null)
                    }
                    is JasClass -> if (isPublic(decl.modifiers)) classDecl(decl)
                    is JasInterface -> if (isPublic(decl.modifiers)) interfaceDecl(decl)
                    is JasEnum -> if (isPublic(decl.modifiers)) unsupported("public enum '${decl.name}'", decl.name, decl)
                    is JasAnnotationType -> if (isPublic(decl.modifiers)) unsupported("public annotation type '${decl.name}'", decl.name, decl)
                    is JasProperty -> if (isPublic(decl.modifiers)) unsupported("public top-level field '${decl.name}'", decl.name, decl)
                    else -> Unit
                }
            }
        }

        /**
         * 生命周期钩子：规范第 9.4 节把裸名 jaso_init / jaso_start / jaso_stop 排除在 fn 集合外，
         * 运行时按 `<包>.<名>.<名>()` 解析（见 JasoLinker.hook）。形状不对或忘了 public 都会让钩子
         * 静默不运行，所以报诊断。
         */
        private fun lifecycle(f: JasFunction) {
            val returnsInt = (f.returnType as? JasPrimitiveType)?.name == "int32"
            if (!isPublic(f.modifiers) || f.parameters.isNotEmpty() || f.typeParameters.isNotEmpty() || !returnsInt) {
                errors += located("Lifecycle function '${f.name}' must be declared 'public function ${f.name}(): int32'", f.name, f)
                return
            }
            exports += Export(f.name, "", "", f)
        }

        private fun unsupported(what: String, name: String, at: JasNode) {
            errors += located("$what cannot be exported from a .jaso module yet; remove 'public' or change it", name, at)
        }

        private fun typeName(name: String, generic: Boolean, at: JasNode): Boolean {
            if (generic) {
                unsupported("generic type '$name'", name, at)
                return false
            }
            if (!IDENT.matches(name)) {
                unsupported("type name '$name'", name, at)
                return false
            }
            exportedTypes += name
            return true
        }

        private fun classDecl(decl: JasClass) {
            if (!typeName(decl.name, decl.typeParameters.isNotEmpty(), decl)) return
            val owner = "$pkg.${decl.name}"
            val isAbstract = "abstract" in decl.modifiers
            if (!isAbstract) {
                if (decl.constructors.isEmpty()) {
                    exports += Export("$owner.new", "", "(): $owner", decl)
                }
                decl.constructors.filter { isPublic(it.modifiers) }.forEach { constructor(it, owner) }
            }
            members(decl.members, owner, interfaceMembers = false)
        }

        private fun interfaceDecl(decl: JasInterface) {
            if (!typeName(decl.name, decl.typeParameters.isNotEmpty(), decl)) return
            members(decl.members, "$pkg.${decl.name}", interfaceMembers = true)
        }

        private fun members(members: List<JasDeclaration>, owner: String, interfaceMembers: Boolean) {
            for (m in members) {
                when (m) {
                    is JasFunction -> if (interfaceMembers || isPublic(m.modifiers)) {
                        function(m, "$owner.${m.name}", if ("static" in m.modifiers) null else owner)
                    }
                    is JasProperty -> if (interfaceMembers || isPublic(m.modifiers)) unsupported("public field '$owner.${m.name}'", m.name, m)
                    is JasClass -> if (isPublic(m.modifiers)) unsupported("nested type '$owner.${m.name}'", m.name, m)
                    is JasInterface -> if (isPublic(m.modifiers)) unsupported("nested type '$owner.${m.name}'", m.name, m)
                    is JasEnum -> if (isPublic(m.modifiers)) unsupported("nested type '$owner.${m.name}'", m.name, m)
                    else -> Unit
                }
            }
        }

        private fun constructor(c: JasConstructor, owner: String) {
            val params = parameters(c.parameters, null, "$owner.new", c) ?: return
            exports += Export("$owner.new", params.second, "(${params.first}): $owner", c)
        }

        private fun function(f: JasFunction, base: String, self: String?) {
            if (f.typeParameters.isNotEmpty()) {
                unsupported("generic function '$base'", base, f)
                return
            }
            val params = parameters(f.parameters, self, base, f) ?: return
            // 省略返回类型就是 void：后端按 V 生成。
            val ret = if (f.returnType == null) "void" else render(f.returnType, 0, base, f) ?: return
            exports += Export(base, params.second, "(${params.first}): $ret", f)
        }

        /** 渲染形参表；返回（文本，类型指纹），不能表达时报诊断并返回 null。 */
        private fun parameters(list: List<JasParameter>, self: String?, base: String, at: JasNode): Pair<String, String>? {
            val parts = mutableListOf<String>()
            val types = mutableListOf<String>()
            if (self != null) parts += "this: $self"
            var ok = true
            for (p in list) {
                val type = p.type
                when {
                    p.vararg -> { unsupported("vararg parameter '${p.name}' of '$base'", p.name, at); ok = false }
                    p.defaultValue != null -> { unsupported("default value of parameter '${p.name}' of '$base'", p.name, at); ok = false }
                    !IDENT.matches(p.name) -> { unsupported("parameter name '${p.name}' of '$base'", p.name, at); ok = false }
                    type == null -> { unsupported("untyped parameter '${p.name}' of '$base'", p.name, at); ok = false }
                    else -> {
                        val text = render(type, 0, base, at)
                        if (text == null) ok = false else {
                            parts += "${p.name}: $text"
                            types += text
                        }
                    }
                }
            }
            return if (ok) parts.joinToString(", ") to types.joinToString(",") else null
        }

        /** 把 AST 类型写成 jasper.import 语法；不能表达时报诊断并返回 null。 */
        private fun render(type: JasType, depth: Int, base: String, at: JasNode): String? {
            if (depth > MAX_TYPE_DEPTH) {
                unsupported("type nesting deeper than $MAX_TYPE_DEPTH in '$base'", base, at)
                return null
            }
            return when (type) {
                is JasPrimitiveType -> type.name
                JasStringType -> "string"
                JasBytesType -> "bytes"
                JasRegexType -> "regex"
                JasUnitType -> "void"
                JasAnyType -> "any"
                is JasNullableType -> render(type.inner, depth, base, at)?.let { if (it.endsWith("?")) it else "$it?" }
                is JasNonNullType -> render(type.inner, depth, base, at)
                is JasArrayType -> render(type.inner, depth + 1, base, at)?.let { "Array<$it>" }
                is JasNamedType -> named(type, depth, base, at)
                else -> {
                    unsupported("type '${JasperManifest.renderType(type)}' in the signature of '$base'", base, at)
                    null
                }
            }
        }

        private fun named(type: JasNamedType, depth: Int, base: String, at: JasNode): String? {
            val name = type.name
            val qualified = when {
                name.contains('.') -> name
                imports.containsKey(name) -> imports.getValue(name)
                name in moduleTypes -> "$pkg.$name"
                else -> {
                    errors += located("Type '$name' in the signature of '$base' must be imported by name to be exported", name, at)
                    return null
                }
            }
            if (!DOTTED.matches(qualified)) {
                unsupported("type name '$qualified' in the signature of '$base'", base, at)
                return null
            }
            if (type.typeArguments.isEmpty()) return qualified
            val args = type.typeArguments.map { render(it, depth + 1, base, at) ?: return null }
            return "$qualified<${args.joinToString(",")}>"
        }
    }
}

/**
 * 校验模块元数据并写清单文本。
 *
 * 做什么：按 SPEC 第 11 节校验取值，产出 UTF-8 清单。
 * 什么时候用：写文件前；[JasoExports.plan] 需要校验过的能力表，所以先于它调用 [validate]。
 * 前置条件：无。
 * 失败模式：不抛异常；[validate] 返回诊断列表。
 * 为什么在写时校验：坏清单写进文件要到装载时才被拒绝，编译时就该告诉作者。
 */
object JasoManifestText {
    /** 当前运行时系列。 */
    const val DEFAULT_RUNTIME: String = ">=0.1,<0.2"

    /** Managed 镜像的 ABI 名：class 版本 52。 */
    const val ABI: String = "jvm.52"

    private const val MAX_LIST = 32
    private val NAME = Regex("[a-z0-9._-]{1,64}")
    private val CAPABILITY = Regex("[a-z0-9._]{1,64}")
    private val NUMBER = "[0-9]{1,9}"
    private val VERSION = Regex("$NUMBER\\.$NUMBER\\.$NUMBER")
    private val COMPARATOR = Regex("(>=|<=|>|<|=)$NUMBER\\.$NUMBER(\\.$NUMBER)?")

    private fun rangeOk(range: String): Boolean {
        val parts = range.split(',')
        return parts.size <= 4 && parts.all { COMPARATOR.matches(it) }
    }

    /**
     * 校验元数据。
     *
     * 做什么：逐键对照规范取值规则，返回全部问题。
     * 什么时候用：写文件前。
     * 前置条件：无。
     * 失败模式：不抛异常；空列表表示通过。
     */
    fun validate(o: JasoModuleOptions): List<CompileError> {
        val errors = mutableListOf<String>()
        if (!NAME.matches(o.name)) errors += "module name '${o.name}' is not 1..64 characters of [a-z0-9._-]"
        if (!VERSION.matches(o.version)) errors += "module version '${o.version}' is not major.minor.patch with 1..9 digits each"
        if (!rangeOk(o.runtime)) errors += "runtime range '${o.runtime}' is not 1..4 comparators such as >=0.1,<0.2"
        o.publisher?.let { if (!NAME.matches(it)) errors += "publisher '$it' is not 1..64 characters of [a-z0-9._-]" }
        o.description?.let {
            if (it.isBlank() || it != it.trim() || it.any { c -> c == '\n' || c == '\r' }) {
                errors += "description must be one line with no surrounding whitespace"
            }
        }
        o.lifecycle?.let { if (it != "plugin" && it != "system") errors += "lifecycle '$it' is neither plugin nor system" }
        if (o.requires.size > MAX_LIST) errors += "at most $MAX_LIST capabilities, found ${o.requires.size}"
        o.requires.filterNot { CAPABILITY.matches(it) }.forEach { errors += "capability '$it' is not 1..64 characters of [a-z0-9._]" }
        if (o.depends.size > MAX_LIST) errors += "at most $MAX_LIST dependencies, found ${o.depends.size}"
        val seen = mutableSetOf<String>()
        for (d in o.depends) {
            val name = d.substringBefore('@')
            val range = if ('@' in d) d.substringAfter('@') else null
            if (!NAME.matches(name) || (range != null && !rangeOk(range))) errors += "dependency '$d' is not name or name@range"
            if (!seen.add(name)) errors += "dependency '$name' is listed twice"
        }
        return errors.map { CompileError.Symbol(it, "module") }
    }

    /**
     * 写清单文本。
     *
     * 做什么：按固定键序渲染；导出表来自 [JasoExports]。
     * 什么时候用：[validate] 通过之后。
     * 前置条件：[o] 已通过校验。
     * 失败模式：无。
     */
    fun render(o: JasoModuleOptions, exports: List<String>): ByteArray {
        val lines = mutableListOf(
            "core.name = ${o.name}",
            "core.version = ${o.version}",
            "core.abi = $ABI",
            "core.stage = runtime",
            "core.runtime = ${o.runtime}"
        )
        o.lifecycle?.let { lines += "core.lifecycle = $it" }
        if (o.depends.isNotEmpty()) lines += "core.depends = ${o.depends.joinToString(", ")}"
        if (o.requires.isNotEmpty()) lines += "core.requires = ${o.requires.sorted().joinToString(",")}"
        if (exports.isNotEmpty()) lines += "core.exports = ${exports.joinToString(",")}"
        o.description?.let { lines += "core.description = $it" }
        o.publisher?.let { lines += "core.publisher = $it" }
        return lines.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8)
    }
}

/**
 * format 1 文件的字节排布。
 *
 * 做什么：把 class、清单、导出与 jasper.import 排成一个未签名的 Managed 文件。
 * 什么时候用：[JasperCompiler.compileToJaso] 的最后一步；测试也直接调用它构造样本。
 * 前置条件：清单与导出表一一对应（由 [JasoExports] 保证）。
 * 失败模式：超出格式上限抛 IllegalArgumentException，由调用方转成诊断。
 * 为什么手写 ZIP：要逐字节确定，而 java.util.zip 写 DOS 时间时取决于默认时区。
 */
object JasoWriter {
    private val MAGIC = byteArrayOf(0x89.toByte(), 'J'.code.toByte(), 'A'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 0x0D, 0x0A, 0x1A)
    private const val FORMAT = 1
    private const val KIND_MANAGED = 2
    private const val STAGE_RUNTIME = 0
    private const val HEADER_SIZE = 80
    private const val SECTION_SIZE = 32
    private const val DIGEST_OFFSET = 40
    private const val SECTION_MANIFEST = 1
    private const val SECTION_EXPORTS = 3
    private const val SECTION_PLATFORM = 6
    private const val SECTION_IMAGES = 7
    private const val SECTION_EXT = 8
    private const val SECTION_CODE = 9
    private const val TARGET_JVM = 2
    private const val IMAGE_MANAGED = 2
    private const val CODE_JVM_CLASSES = 1
    private const val EXPORT_MANAGED = 2
    private const val EXT_CRITICAL = 1
    private const val IMPORT_VERSION = 1
    private const val IMPORT_NAME = "jasper.import"
    private const val MAX_ZIP_ENTRIES = 65535

    private class Section(val kind: Int, val flags: Int, val body: ByteArray)

    private fun le(size: Int, fill: (ByteBuffer) -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also(fill).array()

    /**
     * 排布一个未签名的 Managed 文件。
     *
     * 做什么：目录顺序为清单、平台、镜像索引、代码、导出（无导出时省略）、jasper.import。
     * 什么时候用：见类说明。
     * 前置条件：[classes] 的键是 ZIP 内路径（`a/b/C.class`），至少一项。
     * 失败模式：class 过多或文件超出 2 GiB（JVM 单个数组上限）时抛 IllegalArgumentException。
     */
    fun assemble(manifest: ByteArray, classes: Map<String, ByteArray>, exports: List<String>, importText: String): ByteArray {
        val image = mutableListOf(Section(SECTION_CODE, 0, code(classes)))
        if (exports.isNotEmpty()) image += Section(SECTION_EXPORTS, 0, exportTable(exports))
        val sections = mutableListOf(
            Section(SECTION_MANIFEST, 0, manifest),
            Section(SECTION_PLATFORM, 0, platform()),
            Section(SECTION_IMAGES, 0, images(first = 3, count = image.size))
        )
        sections += image
        sections += Section(SECTION_EXT, EXT_CRITICAL, extension(importText.toByteArray(StandardCharsets.UTF_8)))
        return layout(sections)
    }

    /** 平台段：一条记录，isa 0、指针 0、浮点 0、目标 jvm、ABI jvm.52。 */
    private fun platform(): ByteArray = le(8 + 64) { b ->
        b.putInt(1).putInt(0)
        b.position(8 + 4)
        b.putShort(TARGET_JVM.toShort())
        b.position(8 + 40)
        b.put(JasoManifestText.ABI.toByteArray(StandardCharsets.US_ASCII))
    }

    /** 镜像索引：一项，引用平台记录 0，托管镜像的 image_size 为 0。 */
    private fun images(first: Int, count: Int): ByteArray = le(8 + 32) { b ->
        b.putInt(1).putInt(0)
        b.putShort(IMAGE_MANAGED.toShort()).putShort(0).putInt(first).putInt(count)
    }

    /** 代码段：format、保留、class 归档。 */
    private fun code(classes: Map<String, ByteArray>): ByteArray {
        val zip = storedZip(classes)
        return le(8 + zip.size) { b -> b.putInt(CODE_JVM_CLASSES).putInt(0).put(zip) }
    }

    /** 导出表：托管符号，value 恒为 0。 */
    private fun exportTable(exports: List<String>): ByteArray {
        val names = exports.map { it.toByteArray(StandardCharsets.US_ASCII) }
        return le(4 + 16 * names.size + names.sumOf { it.size }) { b ->
            b.putInt(names.size)
            var offset = 4 + 16 * names.size
            for (n in names) {
                b.putInt(offset).putInt(n.size).putInt(0).putInt(EXPORT_MANAGED)
                offset += n.size
            }
            names.forEach { b.put(it) }
        }
    }

    /** jasper.import 扩展段：版本、名字、补零到 8 字节对齐，再接内容。 */
    private fun extension(content: ByteArray): ByteArray {
        val name = IMPORT_NAME.toByteArray(StandardCharsets.US_ASCII)
        val head = (8 + name.size + 7) / 8 * 8
        return le(head + content.size) { b ->
            b.putInt(IMPORT_VERSION).putShort(name.size.toShort()).putShort(0).put(name)
            b.position(head)
            b.put(content)
        }
    }

    /** 头部 + 目录 + 各段内容，最后回填摘要。 */
    private fun layout(sections: List<Section>): ByteArray {
        val total = HEADER_SIZE.toLong() + SECTION_SIZE * sections.size + sections.sumOf { it.body.size.toLong() }
        require(total <= Int.MAX_VALUE) { "the module is too large for one .jaso file: $total bytes" }
        val b = ByteBuffer.allocate(total.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAGIC)
        b.putShort(FORMAT.toShort()).putShort(KIND_MANAGED.toShort()).putShort(0)
        b.put(STAGE_RUNTIME.toByte()).put(0)
        b.putInt(HEADER_SIZE).putInt(sections.size).putInt(HEADER_SIZE).putInt(total.toInt())
        b.position(HEADER_SIZE)
        var offset = HEADER_SIZE + SECTION_SIZE * sections.size
        for (s in sections) {
            b.putInt(s.kind).putInt(s.flags).putInt(offset).putInt(s.body.size).putInt(0).putInt(0).putLong(0)
            offset += s.body.size
        }
        sections.forEach { b.put(it.body) }
        val bytes = b.array()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        System.arraycopy(digest, 0, bytes, DIGEST_OFFSET, digest.size)
        return bytes
    }

    /**
     * 只存储不压缩的确定性 ZIP。
     *
     * 做什么：按路径排序写出，时间戳固定为 DOS 零点（1980-01-01）；路径含非 ASCII 时置 UTF-8 标志。
     * 什么时候用：写代码段；测试用它比对标准库 ZIP 读取器。
     * 前置条件：[entries] 至少一项。
     * 失败模式：项数或大小超出无 ZIP64 的上限时抛 IllegalArgumentException。
     */
    fun storedZip(entries: Map<String, ByteArray>): ByteArray {
        require(entries.isNotEmpty()) { "a class archive needs at least one class" }
        require(entries.size <= MAX_ZIP_ENTRIES) { "at most $MAX_ZIP_ENTRIES classes, found ${entries.size}" }
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        for ((path, data) in entries.toSortedMap()) {
            val name = path.toByteArray(StandardCharsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value.toInt()
            val flags = if (name.size == path.length) 0 else UTF8_FLAG
            val offset = out.size()
            out.write(le(30) { b ->
                b.putInt(LOCAL_MAGIC).putShort(ZIP_VERSION).putShort(flags.toShort()).putShort(0)
                b.putShort(0).putShort(DOS_DATE_ZERO).putInt(crc).putInt(data.size).putInt(data.size)
                b.putShort(name.size.toShort()).putShort(0)
            })
            out.write(name)
            out.write(data)
            central.write(le(46) { b ->
                b.putInt(CENTRAL_MAGIC).putShort(ZIP_VERSION).putShort(ZIP_VERSION).putShort(flags.toShort()).putShort(0)
                b.putShort(0).putShort(DOS_DATE_ZERO).putInt(crc).putInt(data.size).putInt(data.size)
                b.putShort(name.size.toShort())
                b.position(42)
                b.putInt(offset)
            })
            central.write(name)
        }
        val centralOffset = out.size()
        out.write(central.toByteArray())
        out.write(le(22) { b ->
            b.putInt(END_MAGIC).putShort(0).putShort(0)
            b.putShort(entries.size.toShort()).putShort(entries.size.toShort())
            b.putInt(central.size()).putInt(centralOffset)
        })
        return out.toByteArray()
    }

    private const val LOCAL_MAGIC = 0x04034b50
    private const val CENTRAL_MAGIC = 0x02014b50
    private const val END_MAGIC = 0x06054b50
    private const val ZIP_VERSION: Short = 10
    private const val UTF8_FLAG = 0x0800
    private const val DOS_DATE_ZERO: Short = 0x21
}
