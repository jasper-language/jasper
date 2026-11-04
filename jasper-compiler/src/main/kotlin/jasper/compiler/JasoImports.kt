// ============================================================================
// JasoImports.kt —— 把源码 import 解析到模块路径上的 `.jaso`（compiler 编排层，JB-L3）
// ============================================================================
// 职责：
//   - JasoModuleFile：结构读取一个 Managed 文件，只取编译要用的东西：身份、jasper.import、class 外形；
//   - JasoSignatureParser：jasper.import 的类型语法 → AST 类型（JasoExports.render 的逆）；
//   - JasoImportResolver：模块路径 + 各文件 import → 接口声明（只进符号表，不产 class）与诊断。
//
// 所属层次：compiler 编排层，只依赖 ast 层；由 JasperCompiler.compileAll 调用。
//
// 对外契约：
//   - 这里不是 L1：摘要、签名、平台、能力授予都在装载时由 std.jaso 判定。编译期只保证
//     「按这份接口生成的调用，能被这个文件的运行时链接检查接受」。
//   - 类型含义来自 jasper.import；jasper.import 版本 1 表达不了的两件事（接口还是类、超类型）
//     取自同一文件的 class 字节，不扩展语法。
//   - 坏文件一律诊断，不跳过：跳过会让后面的「找不到模块」指错原因。
//
// 已知限制（都报诊断或写在 docs/jaso.md）：顶层函数不能取别名、不能与别的函数重名；不支持
//   按需导入模块包；只有 `--emit jaso` 走模块路径。
// ----------------------------------------------------------------------------

package jasper.compiler

import jasper.ast.JasAnyType
import jasper.ast.JasArrayType
import jasper.ast.JasBlock
import jasper.ast.JasBytesType
import jasper.ast.JasClass
import jasper.ast.JasConstructor
import jasper.ast.JasDeclaration
import jasper.ast.JasFunction
import jasper.ast.JasImport
import jasper.ast.JasInterface
import jasper.ast.JasNamedType
import jasper.ast.JasNullableType
import jasper.ast.JasParameter
import jasper.ast.JasPrimitiveType
import jasper.ast.JasRegexType
import jasper.ast.JasSourceFile
import jasper.ast.JasStringType
import jasper.ast.JasType
import jasper.ast.JasUnitType
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** 一个 `.jaso` 不能当模块接口用：[message] 说明哪里不对。 */
class JasoModuleFormatException(message: String) : RuntimeException(message)

/** 一个方法在 class 字节里的外形：名字 + 形参个数 → 是否抽象。 */
internal data class JasoMethodShape(val name: String, val parameterCount: Int, val isAbstract: Boolean)

/** 一个 class 的外形：jasper.import 版本 1 写不出来的部分。 */
internal class JasoClassShape(
    val isInterface: Boolean,
    val isAbstract: Boolean,
    val superName: String?,
    val interfaces: List<String>,
    val methods: List<JasoMethodShape>
)

/** jasper.import 的一条 fn：[owner] 是持有者全限定名，[member] 去掉了重载后缀。 */
internal class JasoDeclaredFn(
    val symbol: String,
    val owner: String,
    val member: String,
    val parameters: List<JasParameter>,
    val result: JasType,
    val isInstance: Boolean
)

/**
 * jasper.import 的类型与签名语法 → AST。
 *
 * 做什么：解析 `fn` 行的符号之后部分与单个类型。
 * 什么时候用：[JasoModuleFile.read]。
 * 前置条件：无；输入是外部文件内容。
 * 失败模式：抛 [JasoModuleFormatException]。
 * 为什么嵌套有界：规范限 8 层，递归因此不会被恶意文件撑爆栈。
 */
internal class JasoSignatureParser(private val text: String) {
    private var at = 0

    private fun fail(what: String): Nothing = throw JasoModuleFormatException("$what in '$text'")

    private fun skipSpaces() {
        while (at < text.length && text[at] == ' ') at++
    }

    private fun peek(c: Char): Boolean {
        skipSpaces()
        return at < text.length && text[at] == c
    }

    private fun expect(c: Char) {
        if (!peek(c)) fail("expected '$c'")
        at++
    }

    private fun identifier(): String {
        skipSpaces()
        val start = at
        while (at < text.length && (text[at].isAsciiLetterOrDigit() || text[at] == '_')) at++
        if (at == start || text[start].isDigit()) fail("expected a name")
        return text.substring(start, at)
    }

    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun dottedName(): String {
        val out = StringBuilder(identifier())
        while (at < text.length && text[at] == '.') {
            at++
            out.append('.').append(identifier())
        }
        return out.toString()
    }

    /** 解析一个类型；`own` 与 `borrow` 只对原生模块有意义，托管接口里出现就是不能导入的形态。 */
    fun type(depth: Int = 0): JasType {
        if (depth > MAX_DEPTH) fail("type nesting deeper than $MAX_DEPTH")
        val name = dottedName()
        if (name == "own" || name == "borrow") fail("ownership word '$name' is not usable from managed code")
        val args = mutableListOf<JasType>()
        if (peek('<')) {
            at++
            args += type(depth + 1)
            while (peek(',')) {
                at++
                args += type(depth + 1)
            }
            expect('>')
        }
        val base: JasType = when {
            name == "Array" -> args.singleOrNull()?.let { JasArrayType(it) } ?: fail("Array takes one type argument")
            '.' in name -> JasNamedType(name, args)
            args.isNotEmpty() -> fail("built-in type '$name' takes no type arguments")
            else -> builtin(name) ?: fail("unknown built-in type '$name'")
        }
        if (peek('?')) {
            at++
            return JasNullableType(base)
        }
        return base
    }

    private fun builtin(name: String): JasType? = when (name) {
        in PRIMITIVES -> JasPrimitiveType(name)
        "string" -> JasStringType
        "bytes" -> JasBytesType
        "regex" -> JasRegexType
        "void" -> JasUnitType
        "any" -> JasAnyType
        else -> null
    }

    /** 解析 `(a: T, b: U): R`，返回形参（含 this）与返回类型。 */
    fun signature(): Pair<List<JasParameter>, JasType> {
        expect('(')
        val parameters = mutableListOf<JasParameter>()
        if (!peek(')')) {
            while (true) {
                val name = identifier()
                expect(':')
                parameters += JasParameter(name, type())
                if (!peek(',')) break
                at++
            }
        }
        expect(')')
        expect(':')
        val result = type()
        skipSpaces()
        if (at != text.length) fail("trailing text")
        return parameters to result
    }

    /** 整段文本就是一个类型。 */
    fun wholeType(): JasType {
        val t = type()
        skipSpaces()
        if (at != text.length) fail("trailing text")
        return t
    }

    private companion object {
        const val MAX_DEPTH = 8
        val PRIMITIVES = setOf(
            "bool", "byte", "char", "int8", "int16", "int32", "int64",
            "uint8", "uint16", "uint32", "uint64", "float32", "float64"
        )
    }
}

/**
 * 一个 Managed `.jaso` 的编译期视图。
 *
 * 做什么：读目录、清单身份、jasper.import、代码段里的 class 外形。
 * 什么时候用：[JasoImportResolver] 扫描模块路径时。
 * 前置条件：无；字节是不可信输入。
 * 失败模式：[read] 抛 [JasoModuleFormatException]，不抛别的异常。
 * 为什么不复用 std.jaso：它是 Jasper 写的，编译器要先编译它才能用，每次带模块路径的编译都
 *   多编一遍；两者的一致性由差分测试看住（JasoImportTest）。
 */
internal class JasoModuleFile private constructor(
    val path: Path,
    val name: String,
    val version: Triple<Long, Long, Long>,
    val packageName: String,
    val types: List<String>,
    val capabilities: List<String>,
    val functions: List<JasoDeclaredFn>,
    val classes: Map<String, JasoClassShape>
) {
    val versionText: String get() = "${version.first}.${version.second}.${version.third}"

    /** 持有者是 `type` 行里的类型时是成员，否则是顶层函数（落在同名类里）。 */
    fun topLevelFunctions(): List<JasoDeclaredFn> =
        functions.filter { it.owner.substringAfterLast('.') !in types }

    companion object {
        private val MAGIC = byteArrayOf(0x89.toByte(), 'J'.code.toByte(), 'A'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 0x0D, 0x0A, 0x1A)
        private const val FORMAT = 1
        private const val KIND_MANAGED = 2
        private const val HEADER_SIZE = 80
        private const val SECTION_SIZE = 32
        private const val MAX_SECTIONS = 4096
        private const val SECTION_MANIFEST = 1
        private const val SECTION_EXT = 8
        private const val SECTION_CODE = 9
        private const val IMPORT_VERSION = 1
        private const val MAX_IMPORT_BYTES = 65536
        private const val MAX_IMPORT_LINES = 1024
        private const val MAX_FILE_BYTES = 256L * 1024 * 1024
        private const val MAX_CLASS_BYTES = 64 * 1024 * 1024
        private val DOTTED = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
        private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val SYMBOL = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+(\\$[0-9a-f]{8})?")
        private val VERSION = Regex("([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})")

        private fun bad(what: String): Nothing = throw JasoModuleFormatException(what)

        /** 读磁盘上的文件；大小先查，不把超大文件读进内存。 */
        fun read(path: Path): JasoModuleFile {
            val size = try {
                Files.size(path)
            } catch (e: java.io.IOException) {
                bad("cannot read: ${e.message}")
            }
            if (size > MAX_FILE_BYTES) bad("larger than $MAX_FILE_BYTES bytes")
            val bytes = try {
                Files.readAllBytes(path)
            } catch (e: java.io.IOException) {
                bad("cannot read: ${e.message}")
            }
            return parse(path, bytes)
        }

        /** 解析字节；所有越界都走 [JasoModuleFormatException]。 */
        fun parse(path: Path, bytes: ByteArray): JasoModuleFile {
            if (bytes.size < HEADER_SIZE) bad("shorter than the file header")
            if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) bad("not a .jaso file")
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val format = b.getShort(8).toInt() and 0xFFFF
            if (format != FORMAT) bad("format $format is not supported, this compiler reads format $FORMAT")
            val kind = b.getShort(10).toInt() and 0xFFFF
            if (kind != KIND_MANAGED) bad("not a Managed module (kind $kind): only Managed modules can be imported")
            val count = b.getInt(20)
            val dir = b.getInt(24)
            if (count < 0 || count > MAX_SECTIONS || dir < 0) bad("bad section directory")
            if (dir.toLong() + count.toLong() * SECTION_SIZE > bytes.size) bad("section directory is out of bounds")

            var manifest: String? = null
            var import: String? = null
            var code: ByteArray? = null
            for (i in 0 until count) {
                val e = dir + SECTION_SIZE * i
                val offset = b.getInt(e + 8)
                val length = b.getInt(e + 12)
                if (offset < 0 || length < 0 || offset.toLong() + length.toLong() > bytes.size) bad("section $i is out of bounds")
                when (b.getInt(e)) {
                    SECTION_MANIFEST -> {
                        if (manifest != null) bad("two manifests")
                        manifest = utf8(bytes, offset, length, "manifest")
                    }
                    SECTION_CODE -> {
                        if (code != null) bad("two code sections: only single-image modules can be imported")
                        if (length < 8) bad("code section is too short")
                        code = bytes.copyOfRange(offset + 8, offset + length)
                    }
                    SECTION_EXT -> extension(bytes, b, offset, length)?.let {
                        if (import != null) bad("two jasper.import extensions")
                        import = it
                    }
                }
            }
            val manifestText = manifest ?: bad("no manifest")
            val importText = import ?: bad("no jasper.import extension, so it has no typed interface")
            val zip = code ?: bad("no code section")

            val keys = HashMap<String, String>()
            for (line in manifestText.split('\n')) {
                val eq = line.indexOf('=')
                if (eq > 0) keys.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim())
            }
            val name = keys["core.name"] ?: bad("manifest has no core.name")
            val versionText = keys["core.version"] ?: bad("manifest has no core.version")
            val v = VERSION.matchEntire(versionText) ?: bad("bad core.version '$versionText'")
            val version = Triple(v.groupValues[1].toLong(), v.groupValues[2].toLong(), v.groupValues[3].toLong())

            var pkg: String? = null
            val types = mutableListOf<String>()
            val caps = mutableListOf<String>()
            val fnLines = mutableListOf<Pair<String, String>>()
            var effective = 0
            for (raw in importText.split('\n')) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (++effective > MAX_IMPORT_LINES) bad("jasper.import has more than $MAX_IMPORT_LINES lines")
                val space = line.indexOf(' ')
                if (space < 0) bad("bad jasper.import line '$line'")
                val rest = line.substring(space + 1).trim()
                when (line.substring(0, space)) {
                    "package" -> {
                        if (pkg != null) bad("two package lines in jasper.import")
                        if (!DOTTED.matches(rest)) bad("bad package '$rest'")
                        pkg = rest
                    }
                    "type" -> {
                        if (!IDENT.matches(rest)) bad("bad type name '$rest'")
                        types += rest
                    }
                    "cap" -> caps += rest
                    "fn" -> {
                        val open = rest.indexOf('(')
                        if (open <= 0) bad("bad fn line '$line'")
                        fnLines += rest.substring(0, open).trim() to rest.substring(open)
                    }
                    else -> bad("bad jasper.import line '$line'")
                }
            }
            val packageName = pkg ?: bad("jasper.import has no package line")
            val functions = fnLines.map { (symbol, signature) -> declared(packageName, symbol, signature) }
            return JasoModuleFile(path, name, version, packageName, types, caps, functions, classShapes(zip))
        }

        private fun declared(pkg: String, symbol: String, signature: String): JasoDeclaredFn {
            if (!SYMBOL.matches(symbol)) bad("bad fn symbol '$symbol'")
            val plain = symbol.substringBefore('$')
            val owner = plain.substringBeforeLast('.')
            if (!owner.startsWith("$pkg.") || '.' in owner.removePrefix("$pkg.")) bad("fn '$symbol' is not in package '$pkg'")
            val (parameters, result) = JasoSignatureParser(signature).signature()
            val instance = parameters.firstOrNull()?.name == "this"
            return JasoDeclaredFn(symbol, owner, plain.substringAfterLast('.'), if (instance) parameters.drop(1) else parameters, result, instance)
        }

        private fun utf8(bytes: ByteArray, offset: Int, length: Int, what: String): String {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                bad("$what is not UTF-8")
            }
        }

        /** 扩展段是 jasper.import 时返回内容，别的扩展返回 null：关键标志由装载时判定。 */
        private fun extension(bytes: ByteArray, b: ByteBuffer, offset: Int, length: Int): String? {
            if (length < 8) bad("extension section is too short")
            val version = b.getInt(offset)
            val nameLength = b.getShort(offset + 4).toInt() and 0xFFFF
            val head = (8 + nameLength + 7) / 8 * 8
            if (head > length) bad("extension section is too short")
            val name = String(bytes, offset + 8, nameLength, StandardCharsets.US_ASCII)
            if (name != "jasper.import") return null
            if (version != IMPORT_VERSION) bad("jasper.import version $version is not supported")
            if (length - head > MAX_IMPORT_BYTES) bad("jasper.import is larger than $MAX_IMPORT_BYTES bytes")
            return utf8(bytes, offset + head, length - head, "jasper.import")
        }

        /** 代码段只存储不压缩（SPEC 第 8 节），所以读取量不会超过文件本身。 */
        private fun classShapes(zip: ByteArray): Map<String, JasoClassShape> {
            val out = HashMap<String, JasoClassShape>()
            try {
                ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                    while (true) {
                        val entry = z.nextEntry ?: break
                        if (entry.method != ZipEntry.STORED) bad("class archive entry '${entry.name}' is compressed")
                        if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                        val content = z.readBytes()
                        if (content.size > MAX_CLASS_BYTES) bad("class '${entry.name}' is too large")
                        out[entry.name.removeSuffix(".class").replace('/', '.')] = shapeOf(content, entry.name)
                    }
                }
            } catch (e: java.io.IOException) {
                bad("bad class archive: ${e.message}")
            } catch (e: IllegalArgumentException) {
                // ZipInputStream 对非 UTF-8 的项名抛这个，不是 IOException。
                bad("bad class archive: ${e.message}")
            }
            return out
        }

        private fun shapeOf(content: ByteArray, name: String): JasoClassShape {
            var access = 0
            var superName: String? = null
            var interfaces: List<String> = emptyList()
            val methods = mutableListOf<JasoMethodShape>()
            try {
                // 只要外形：跳过方法体与调试信息。坏 class 让 ASM 抛各种运行时异常，一律转诊断。
                ClassReader(content).accept(object : ClassVisitor(Opcodes.ASM9) {
                    /** 记下访问标志与超类型。 */
                    override fun visit(version: Int, acc: Int, n: String?, sig: String?, sup: String?, ifs: Array<out String>?) {
                        access = acc
                        superName = sup?.replace('/', '.')
                        interfaces = ifs?.map { it.replace('/', '.') } ?: emptyList()
                    }

                    /** 记下方法外形；不进方法体。 */
                    override fun visitMethod(acc: Int, n: String, d: String, sig: String?, ex: Array<out String>?): MethodVisitor? {
                        methods += JasoMethodShape(n, org.jetbrains.org.objectweb.asm.Type.getArgumentTypes(d).size, acc and Opcodes.ACC_ABSTRACT != 0)
                        return null
                    }
                }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            } catch (e: RuntimeException) {
                bad("class '$name' cannot be read: ${e.message}")
            }
            return JasoClassShape(
                access and Opcodes.ACC_INTERFACE != 0,
                access and Opcodes.ACC_ABSTRACT != 0,
                superName, interfaces, methods
            )
        }
    }
}

/**
 * 版本区间判定：与 std.jaso.JasoVersionRange 同义，差分测试看住（JasoImportTest）。
 *
 * 做什么：`>=1.0,<2.0` 每个比较式都满足才算满足；省略 patch 按 0。
 * 什么时候用：核对 `--depends` 与模块路径上找到的版本。
 * 前置条件：区间已通过 [JasoManifestText.validate]。
 * 失败模式：无。
 */
internal fun jasoRangeContains(range: String, version: Triple<Long, Long, Long>): Boolean = range.split(',').all { raw ->
    val c = raw.trim()
    val op = c.takeWhile { it in "<>=" }
    val parts = c.drop(op.length).split('.').map { it.toLong() }
    val bound = Triple(parts[0], parts[1], parts.getOrElse(2) { 0L })
    val order = compareValuesBy(version, bound, { it.first }, { it.second }, { it.third })
    when (op) {
        ">=" -> order >= 0
        ">" -> order > 0
        "<=" -> order <= 0
        "<" -> order < 0
        else -> order == 0
    }
}

/**
 * 一次编译的模块上下文。
 *
 * @property modulePath 目录（其中的 `*.jaso`，不递归）或单个文件。
 * @property moduleName 正在编译的模块：路径上同名的文件是它自己的旧产物，不参与解析。
 * @property depends 声明的依赖，每项 `名字` 或 `名字@区间`。
 * @property requires 本模块请求的能力。
 */
data class JasoModuleContext(
    val modulePath: List<Path>,
    val moduleName: String,
    val depends: List<String>,
    val requires: List<String>
)

/**
 * 解析结果。
 *
 * @property headers 接口声明：进符号表与翻译器的成员索引，不做类型检查、不产 class。
 * @property headerFunctions 导入的顶层函数：简单名 → 全限定名，类型检查器据此按文件判可见性。
 */
internal class JasoImportPlan(
    val headers: List<JasSourceFile>,
    val headerFunctions: Map<String, String>,
    val errors: List<CompileError>
)

/**
 * 模块路径上的 import 解析。
 *
 * 做什么：扫描路径，把每条 import 定位到模块，核对依赖声明、版本、能力，再把涉及的模块接口还原成声明。
 * 什么时候用：[JasperCompiler.compileAll] 解析完全部源文件之后、类型检查之前。
 * 前置条件：[localPackages] 含本次编译的全部源文件的包（含标准库）。
 * 失败模式：不抛异常；问题进入 [JasoImportPlan.errors]。
 * 为什么依赖必须声明：运行时只让模块看见它声明的依赖，没声明的 import 能过编译却链接不上。
 * 为什么只还原被导入的顶层函数：函数表按简单名全局共享，还原没人要的函数只会制造重名。
 */
internal class JasoImportResolver(
    private val context: JasoModuleContext,
    private val localPackages: Set<String>,
    private val functionTaken: (String) -> Boolean
) {
    private val errors = mutableListOf<CompileError>()
    private val byPackage = HashMap<String, MutableList<JasoModuleFile>>()
    private val byName = HashMap<String, MutableList<JasoModuleFile>>()

    private fun error(message: String, name: String, at: JasDeclaration? = null) {
        errors += CompileError.Symbol(message, name, at?.sourceLine ?: 0, at?.sourceColumn ?: 0)
    }

    /** 解析全部文件的 import。 */
    fun resolve(files: Collection<JasSourceFile>): JasoImportPlan {
        scan()
        val declared = context.depends.associate { it.substringBefore('@') to it.substringAfter('@', "") }
        checkDependencies(declared)
        val imported = LinkedHashMap<JasoModuleFile, MutableSet<String>>()
        val functions = LinkedHashMap<String, String>()
        for (file in files) {
            for (decl in file.imports) {
                if (decl is JasImport) resolveImport(decl, imported, functions)
            }
        }
        val closure = closureOf(imported.keys)
        for (module in closure) {
            if (module.name !in declared) {
                val why = if (module in imported) "is imported" else "appears in the interface of an imported module"
                error("Module '${module.name}' $why but is not declared: add --depends ${module.name}", module.name)
            }
        }
        for (module in imported.keys) {
            val missing = module.capabilities.filter { it !in context.requires }
            if (missing.isNotEmpty()) {
                error(
                    "Module '${module.name}' needs capabilities ${missing.joinToString()}, which this module does not request: add --requires ${missing.first()}",
                    module.name
                )
            }
        }
        val headers = if (errors.isEmpty()) closure.map { header(it, imported[it].orEmpty(), closure) } else emptyList()
        return JasoImportPlan(headers, functions, errors)
    }

    private fun scan() {
        for (entry in context.modulePath) {
            val found: List<Path> = when {
                Files.isDirectory(entry) -> Files.newDirectoryStream(entry, "*.jaso").use { it.toList() }.sortedBy { it.toString() }
                Files.isRegularFile(entry) -> listOf(entry)
                else -> {
                    error("Module path entry '$entry' does not exist", entry.toString())
                    emptyList()
                }
            }
            for (path in found) {
                val module = try {
                    JasoModuleFile.read(path)
                } catch (e: JasoModuleFormatException) {
                    error("Module file '$path' cannot be used: ${e.message}", path.toString())
                    continue
                }
                if (module.name == context.moduleName) continue
                byName.getOrPut(module.name) { mutableListOf() } += module
                byPackage.getOrPut(module.packageName) { mutableListOf() } += module
            }
        }
    }

    private fun checkDependencies(declared: Map<String, String>) {
        for ((name, range) in declared) {
            val found = byName[name].orEmpty()
            when {
                found.isEmpty() -> error("Dependency '$name' is not on the module path", name)
                found.size > 1 -> error("Dependency '$name' is on the module path more than once: ${found.joinToString { it.path.toString() }}", name)
                range.isNotEmpty() && !jasoRangeContains(range, found[0].version) ->
                    error("Dependency '$name@$range' does not accept version ${found[0].versionText} at ${found[0].path}", name)
                found[0].packageName in localPackages ->
                    error("Dependency '$name' defines package '${found[0].packageName}', which this module also defines", name)
            }
        }
    }

    private fun isPlatform(qualified: String): Boolean =
        PLATFORM_ROOTS.any { qualified == it || qualified.startsWith("$it.") }

    private fun resolveImport(
        decl: JasImport,
        imported: MutableMap<JasoModuleFile, MutableSet<String>>,
        functions: MutableMap<String, String>
    ) {
        val source = decl.fromSource
        if (source != null && source.startsWith("\"")) return
        val onDemand = decl.isOnDemand
        val pkg: String
        val name: String
        when {
            source != null -> { pkg = source; name = decl.name }
            onDemand -> { pkg = decl.name.removeSuffix(".*"); name = "*" }
            else -> { pkg = decl.name.substringBeforeLast('.', ""); name = decl.name.substringAfterLast('.') }
        }
        val qualified = if (pkg.isEmpty()) name else "$pkg.$name"
        if (pkg in localPackages || isPlatform(pkg)) return
        val modules = byPackage[pkg].orEmpty()
        if (modules.isEmpty()) {
            // 本次编译里的嵌套类型（import a.Outer.Inner）不是模块问题。
            if (localPackages.any { pkg.startsWith("$it.") }) return
            error("No module on the module path provides package '$pkg' (imported as '$qualified')", qualified, decl)
            return
        }
        if (modules.size > 1) {
            error("Package '$pkg' is provided by several modules: ${modules.joinToString { "${it.name} at ${it.path}" }}", qualified, decl)
            return
        }
        val module = modules[0]
        if (onDemand) {
            error("Import names from module '${module.name}' one by one: 'import $pkg.*' cannot be resolved against a module", qualified, decl)
            return
        }
        val names = imported.getOrPut(module) { linkedSetOf() }
        if (name in module.types) return
        if (module.topLevelFunctions().none { it.member == name }) {
            error("Module '${module.name}' does not export '$name' (package '$pkg')", qualified, decl)
            return
        }
        if (decl.alias != null) {
            error("Function '$qualified' cannot be imported under another name yet", qualified, decl)
            return
        }
        val existing = functions[name]
        when {
            existing == qualified -> Unit
            existing != null -> error("Functions '$existing' and '$qualified' share the name '$name'; import only one of them", qualified, decl)
            functionTaken(name) -> error("Imported function '$qualified' has the same name as another function in this compilation", qualified, decl)
            else -> functions[name] = qualified
        }
        names += name
    }

    /** 被导入模块 + 其接口（签名与超类型）提到的模块，传递闭包。 */
    private fun closureOf(roots: Collection<JasoModuleFile>): List<JasoModuleFile> {
        val seen = LinkedHashSet<JasoModuleFile>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val module = pending.removeFirst()
            if (!seen.add(module)) continue
            for (qualified in mentionedTypes(module)) {
                val pkg = qualified.substringBeforeLast('.', "")
                if (pkg == module.packageName || isPlatform(qualified) || pkg in localPackages) continue
                val owners = byPackage[pkg].orEmpty()
                val owner = owners.singleOrNull()
                if (owner == null) {
                    error("The interface of module '${module.name}' mentions '$qualified', and ${if (owners.isEmpty()) "no module on the module path provides" else "several modules provide"} package '$pkg'", qualified)
                } else if (qualified.substringAfterLast('.') !in owner.types) {
                    error("The interface of module '${module.name}' mentions '$qualified', which module '${owner.name}' does not export", qualified)
                } else {
                    pending.addLast(owner)
                }
            }
        }
        return seen.toList()
    }

    private fun mentionedTypes(module: JasoModuleFile): Set<String> {
        val out = linkedSetOf<String>()
        /** 收集一个类型里出现的具名类型，含类型实参。 */
        fun visit(t: JasType?) {
            when (t) {
                is JasNamedType -> {
                    out += t.name
                    t.typeArguments.forEach(::visit)
                }
                is JasNullableType -> visit(t.inner)
                is JasArrayType -> visit(t.inner)
                else -> Unit
            }
        }
        for (f in module.functions) {
            f.parameters.forEach { visit(it.type) }
            visit(f.result)
        }
        for (type in module.types) {
            val shape = module.classes["${module.packageName}.$type"] ?: continue
            visibleSupers(module, shape).forEach { out += it }
        }
        return out
    }

    /**
     * 一个 class 的可见超类型：接口里没有的中间类型跳过，继续往上找。
     * 为什么要走穿：`public class A extends Hidden`、`Hidden extends Shape` 时 A 仍是 Shape。
     */
    private fun visibleSupers(module: JasoModuleFile, shape: JasoClassShape): List<String> {
        val out = linkedSetOf<String>()
        val seen = hashSetOf<String>()
        /** 接口上有的就收下；中间的隐藏类型继续往上走。 */
        fun walk(name: String?) {
            if (name == null || name == "java.lang.Object" || !seen.add(name)) return
            val hidden = name.substringBeforeLast('.', "") == module.packageName && name.substringAfterLast('.') !in module.types
            if (!hidden) {
                out += name
                return
            }
            val inner = module.classes[name] ?: return
            walk(inner.superName)
            inner.interfaces.forEach(::walk)
        }
        walk(shape.superName)
        shape.interfaces.forEach(::walk)
        return out.toList()
    }

    /** 把模块接口还原成一份声明；[importedNames] 决定带上哪些顶层函数。 */
    private fun header(module: JasoModuleFile, importedNames: Set<String>, closure: List<JasoModuleFile>): JasSourceFile {
        val declarations = mutableListOf<JasDeclaration>()
        for (type in module.types) {
            val qualified = "${module.packageName}.$type"
            val shape = module.classes[qualified]
            if (shape == null) {
                error("Module '${module.name}' declares type '$qualified' but has no class for it", qualified)
                continue
            }
            declarations += typeDeclaration(module, type, shape, closure)
        }
        for (f in module.topLevelFunctions()) {
            if (f.member in importedNames) {
                declarations += JasFunction(f.member, f.parameters, f.result, JasBlock(emptyList()), listOf("public"))
            }
        }
        return JasSourceFile(module.packageName, declarations, emptyList())
    }

    private fun typeDeclaration(module: JasoModuleFile, type: String, shape: JasoClassShape, closure: List<JasoModuleFile>): JasDeclaration {
        val owner = "${module.packageName}.$type"
        val declared = module.functions.filter { it.owner == owner }
        val members = declared.filter { it.member != "new" }.map { f ->
            val isAbstract = shape.methods.any { it.name == f.member && it.parameterCount == f.parameters.size && it.isAbstract }
            val modifiers = mutableListOf<Any>("public")
            if (!f.isInstance) modifiers += "static"
            if (isAbstract) modifiers += "abstract"
            // 抽象方法没有方法体；具体方法的空块只是占位：接口声明不做检查、不产 class。
            JasFunction(f.member, f.parameters, f.result, if (isAbstract) null else JasBlock(emptyList()), modifiers)
        }
        val supers = visibleSupers(module, shape).filter { known(it, closure) }
        val superClasses = supers.filter { !isInterfaceType(it, module, closure) }
        val superInterfaces = supers.filter { isInterfaceType(it, module, closure) }
        if (shape.isInterface) {
            return JasInterface(type, listOf("public"), emptyList(), supers.map { JasNamedType(it) }, members)
        }
        val constructors = declared.filter { it.member == "new" }.map { f ->
            JasConstructor(listOf("public"), f.parameters, null, JasBlock(emptyList()))
        }.ifEmpty {
            // 接口里没有构造器：class 字节里也许有，但不属于接口，不能让默认构造器顶替。
            listOf(JasConstructor(listOf("private"), emptyList(), null, JasBlock(emptyList())))
        }
        val modifiers = mutableListOf<Any>("public")
        if (shape.isAbstract) modifiers += "abstract"
        return JasClass(type, modifiers, emptyList(), superClasses.firstOrNull()?.let { JasNamedType(it) }, superInterfaces.map { JasNamedType(it) }, members, constructors)
    }

    private fun known(qualified: String, closure: List<JasoModuleFile>): Boolean {
        if (isPlatform(qualified)) return true
        return closure.any { it.packageName == qualified.substringBeforeLast('.', "") && qualified.substringAfterLast('.') in it.types }
    }

    private fun isInterfaceType(qualified: String, module: JasoModuleFile, closure: List<JasoModuleFile>): Boolean {
        val pkg = qualified.substringBeforeLast('.', "")
        val shape = (closure.firstOrNull { it.packageName == pkg } ?: module).classes[qualified]
        if (shape != null) return shape.isInterface
        return try {
            Class.forName(qualified, false, javaClass.classLoader).isInterface
        } catch (e: ClassNotFoundException) {
            false
        } catch (e: LinkageError) {
            false
        }
    }

    private companion object {
        val PLATFORM_ROOTS = listOf("java", "javax", "kotlin", "std", "jasper")
    }
}
