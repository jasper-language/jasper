// ============================================================================
// ConformanceCase.kt —— 一致性用例的头部解析与数据模型
// ----------------------------------------------------------------------------
// 职责：把一个 `.jas` 一致性用例文件解析成后端无关的 ConformanceCase：
//   校验 spec / kind / expect 三个头部字段，并验证 spec 指向的真实文档文件与
//   小节标题确实存在。任何格式问题都以带文件名的 ConformanceFormatException
//   失败，绝不静默猜测。
// 边界：本文件只读文件与文档，不编译、不执行源码，也不了解具体后端。
// English: Parses conformance case headers and validates spec references.
// ============================================================================

package jasper.conformance

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 用例类型，对应文件名后缀与头部 `kind:` 字段。
 *
 * 做什么：区分「应当成功执行」「应当被拒绝」「边界但应成功」三类断言方向。
 * 什么时候用：解析头部时由字符串转换得到，运行器据此选择期望比较方式。
 * 失败模式：未知字符串不会映射到本枚举，解析器会给出带文件名的格式错误。
 * 为什么这样设计：把断言方向收敛成封闭枚举，新增类型时编译器会提示所有分支。
 */
enum class ConformanceKind(val headerValue: String) {
    /** 正例：规范允许，应成功编译并以指定值退出。 */
    POS("pos"),

    /** 拒绝例：规范禁止，编译必须失败且诊断包含指定片段。 */
    REJ("rej"),

    /** 边界例：规范边界，应成功编译并以指定值退出。 */
    EDGE("edge");

    companion object {
        /**
         * 把头部 `kind:` 的文本转成枚举。
         *
         * 前置条件：调用方已完成去空白；传入值可能为任意用户输入。
         * 失败模式：返回 null 表示未知类型，由解析器补充文件名后报告。
         */
        fun fromHeader(value: String): ConformanceKind? =
            entries.firstOrNull { it.headerValue == value }
    }
}

/**
 * 用例的期望结果，与具体后端无关。
 *
 * 做什么：`pos`/`edge` 表达 main 的退出值，`rej` 表达编译拒绝的诊断片段。
 * 什么时候用：解析头部 `expect:` 时构造，运行器比较实际结果时使用。
 * 失败模式：本身是纯数据；非法文本在构造前就由解析器拒绝。
 * 为什么这样设计：把「运行结果」和「拒绝诊断」拆成两种显式形态，避免用魔法值
 *   同时表示退出码和错误文本。
 */
sealed class ConformanceExpectation {
    /** 期望程序成功执行，main 返回 [code]。 */
    data class Exit(val code: Int) : ConformanceExpectation()

    /** 期望编译被拒绝，且至少一条诊断包含 [diagnosticFragment]。 */
    data class Reject(val diagnosticFragment: String) : ConformanceExpectation()
}

/**
 * 一个已解析的一致性用例。
 *
 * 字段含义：
 * - [path]：用例文件路径，用于错误定位与测试显示；
 * - [name]：相对仓库根的稳定名称，作为后端输出目录/诊断的标识；
 * - [spec]：原始规范引用文本，便于失败信息直接回显；
 * - [kind]：用例类型；
 * - [expectation]：期望结果；
 * - [source]：完整 Jasper 源文本（含头部注释）。
 *
 * 前置条件：只能由 [ConformanceCaseParser.parse] 构造，以保证头部已校验。
 * 为什么这样设计：运行器只依赖这份不可变数据，不再次读文件，避免解析与执行
 *   看到不同版本的内容。
 */
data class ConformanceCase(
    val path: Path,
    val name: String,
    val spec: String,
    val kind: ConformanceKind,
    val expectation: ConformanceExpectation,
    val source: String
)

/**
 * 一致性用例格式错误。
 *
 * 做什么：携带出错文件与具体原因，保证任何格式问题都能定位到文件。
 * 什么时候用：头部缺字段、字段值非法、spec 文件或小节不存在时抛出。
 * 前置条件：无。
 * 失败模式：作为 [IllegalArgumentException] 的子类，由测试框架直接报告为失败。
 * 为什么不用普通字符串异常：调用方与自检需要稳定地判断异常类型并读取路径。
 */
class ConformanceFormatException(
    val casePath: Path,
    detail: String
) : IllegalArgumentException("一致性用例格式错误: $casePath: $detail")

/**
 * 仓库内固定位置的定位器。
 *
 * 做什么：从当前工作目录向上寻找同时含 `settings.gradle.kts`、`docs/`、
 *   `conformance/` 的目录，作为仓库根；再提供 docs 与 conformance 的绝对路径。
 * 什么时候用：测试运行时（Gradle 可能以模块目录或仓库根为工作目录）。
 * 前置条件：调用时仓库结构完整；[start] 必须是绝对路径。
 * 失败模式：找不到根目录时抛 [IllegalStateException]，不猜测相对路径。
 * 为什么这样设计：把「测试工作目录因 Gradle 配置而变」这一变化点集中到一处，
 *   其余代码只依赖稳定的仓库根，避免到处写 `../`。
 */
object ConformanceLayout {
    /**
     * 从 [start] 向上定位仓库根。
     *
     * 失败模式：到达文件系统根仍未命中时抛异常，并在消息中回显起点。
     */
    fun repositoryRoot(start: Path = Paths.get("").toAbsolutePath()): Path {
        var current: Path? = start.toAbsolutePath().normalize()
        while (current != null) {
            val looksLikeRoot = Files.isRegularFile(current.resolve("settings.gradle.kts")) &&
                Files.isDirectory(current.resolve("docs")) &&
                Files.isDirectory(current.resolve("conformance"))
            if (looksLikeRoot) return current
            current = current.parent
        }
        throw IllegalStateException(
            "无法从 $start 向上定位仓库根（需要同时存在 settings.gradle.kts、docs/、conformance/）"
        )
    }

    /** 返回用例目录。前置条件：[repositoryRoot] 已成功定位。 */
    fun conformanceRoot(repositoryRoot: Path): Path = repositoryRoot.resolve("conformance")

    /** 返回规范文档目录。前置条件：[repositoryRoot] 已成功定位。 */
    fun docsRoot(repositoryRoot: Path): Path = repositoryRoot.resolve("docs")
}

/**
 * 一致性用例头部解析器。
 *
 * 做什么：读取文件开头的 `//` 注释块，提取并校验 spec / kind / expect，
 *   再验证 spec 指向的文档文件与小节标题真实存在。
 * 什么时候用：一致性套件发现每个 `.jas` 文件后、交给后端执行前。
 * 前置条件：文件存在且以 UTF-8 编码；[repositoryRoot] 是有效仓库根。
 * 失败模式：统一抛 [ConformanceFormatException]，消息含完整文件路径与原因。
 * 为什么用对象而不是顶层散函数：解析规则（字段集合、校验顺序）需要作为一个整体
 *   被复用和测试，对象给出了明确的命名空间与单入口。
 */
object ConformanceCaseParser {
    private val recognizedKeys = setOf("spec", "kind", "expect")
    private val exitPattern = Regex("""^exit\s+(-?\d+)$""")
    private val rejectPattern = Regex("""^reject\s+(\S.*)$""")
    private val headingPattern = Regex("""^#{1,6}\s+(.+?)\s*$""")

    /**
     * 解析一个用例文件。
     *
     * 前置条件：[casePath] 指向真实文件，且位于 [repositoryRoot] 之下。
     * 失败模式：文件不可读、头部字段缺失/重复/非法、spec 目标不存在时均抛
     *   [ConformanceFormatException]；不会返回部分构造的对象。
     */
    fun parse(
        casePath: Path,
        repositoryRoot: Path = ConformanceLayout.repositoryRoot()
    ): ConformanceCase {
        val absolutePath = casePath.toAbsolutePath().normalize()
        if (!Files.isRegularFile(absolutePath)) {
            throw ConformanceFormatException(absolutePath, "用例文件不存在或不是普通文件")
        }
        val lines = try {
            Files.readAllLines(absolutePath, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw ConformanceFormatException(absolutePath, "无法读取用例文件: ${e.message}")
        }

        val fields = linkedMapOf<String, String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (!trimmed.startsWith("//")) break
            val body = trimmed.removePrefix("//").trim()
            val separator = body.indexOf(':')
            if (separator <= 0) continue
            val key = body.substring(0, separator).trim()
            if (key !in recognizedKeys) continue
            val value = body.substring(separator + 1).trim()
            if (fields.put(key, value) != null) {
                throw ConformanceFormatException(absolutePath, "头部字段 '$key' 重复声明")
            }
        }

        val missing = recognizedKeys.filter { it !in fields }
        if (missing.isNotEmpty()) {
            throw ConformanceFormatException(
                absolutePath,
                "缺少头部字段: ${missing.joinToString(", ")}"
            )
        }

        val spec = fields.getValue("spec")
        validateSpec(spec, repositoryRoot, absolutePath)

        val kindText = fields.getValue("kind")
        val kind = ConformanceKind.fromHeader(kindText)
            ?: throw ConformanceFormatException(
                absolutePath,
                "未知 kind '$kindText'，只允许 pos / rej / edge"
            )

        val expectation = parseExpectation(fields.getValue("expect"), absolutePath)
        val consistent = when (kind) {
            ConformanceKind.REJ -> expectation is ConformanceExpectation.Reject
            ConformanceKind.POS, ConformanceKind.EDGE -> expectation is ConformanceExpectation.Exit
        }
        if (!consistent) {
            val required = if (kind == ConformanceKind.REJ) "reject <诊断片段>" else "exit <整数>"
            throw ConformanceFormatException(
                absolutePath,
                "kind '$kindText' 与 expect 不匹配，应使用 '$required'"
            )
        }

        val name = runCatching { repositoryRoot.relativize(absolutePath).toString() }
            .getOrElse { absolutePath.toString() }
        return ConformanceCase(
            path = absolutePath,
            name = name,
            spec = spec,
            kind = kind,
            expectation = expectation,
            source = lines.joinToString("\n", postfix = "\n")
        )
    }

    /**
     * 校验 `spec: <文档文件名>#<小节标题>`。
     *
     * 边界：文件名只允许 docs 目录内的直接文件，拒绝路径分隔符与 `..`，
     *   防止用例引用仓库外内容；小节标题按文档中 `#` 到 `######` 的文本精确匹配。
     */
    private fun validateSpec(spec: String, repositoryRoot: Path, casePath: Path) {
        val parts = spec.split("#", limit = 2)
        if (parts.size != 2) {
            throw ConformanceFormatException(
                casePath,
                "spec 必须写成 '<文件名>.md#<小节标题>'，实际为 '$spec'"
            )
        }
        val fileName = parts[0].trim()
        val section = parts[1].trim()
        if (fileName.isEmpty() || section.isEmpty()) {
            throw ConformanceFormatException(casePath, "spec 的文件名或小节标题为空: '$spec'")
        }
        if (fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) {
            throw ConformanceFormatException(casePath, "spec 文件名不得包含路径: '$fileName'")
        }
        val doc = ConformanceLayout.docsRoot(repositoryRoot).resolve(fileName)
        if (!Files.isRegularFile(doc)) {
            throw ConformanceFormatException(casePath, "spec 指向的规范文件不存在: '$fileName'")
        }
        val headings = try {
            Files.readAllLines(doc, StandardCharsets.UTF_8).mapNotNull { line ->
                headingPattern.find(line)?.groupValues?.get(1)?.trim()
            }
        } catch (e: Exception) {
            throw ConformanceFormatException(casePath, "无法读取规范文件 '$fileName': ${e.message}")
        }
        if (section !in headings) {
            throw ConformanceFormatException(
                casePath,
                "spec 指向的小节不存在: '$fileName' 中没有标题 '$section'"
            )
        }
    }

    /**
     * 解析 `expect:` 的两种语法。
     *
     * 失败模式：既不是 `exit <整数>` 也不是 `reject <非空片段>` 时抛格式错误。
     */
    private fun parseExpectation(text: String, casePath: Path): ConformanceExpectation {
        exitPattern.matchEntire(text)?.let { match ->
            val code = match.groupValues[1].toIntOrNull()
                ?: throw ConformanceFormatException(casePath, "exit 值超出整数范围: '$text'")
            return ConformanceExpectation.Exit(code)
        }
        rejectPattern.matchEntire(text)?.let { match ->
            return ConformanceExpectation.Reject(match.groupValues[1].trim())
        }
        throw ConformanceFormatException(
            casePath,
            "expect 必须是 'exit <整数>' 或 'reject <非空诊断片段>'，实际为 '$text'"
        )
    }
}
