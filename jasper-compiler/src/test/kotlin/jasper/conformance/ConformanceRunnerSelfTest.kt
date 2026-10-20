// ============================================================================
// ConformanceRunnerSelfTest.kt —— 一致性运行器的自检
// ----------------------------------------------------------------------------
// 职责：用仓库内临时目录构造坏用例，验证运行器会拒绝缺失/非法头部，并且不会把
//   「rej 用例意外编译通过」或「pos 返回值不符」当成成功。
// 边界：临时用例只放在仓库 build/ 下，不写入 conformance/，测试结束即删除。
// English: Self-tests the conformance parser and outcome comparison.
// ============================================================================

package jasper.conformance

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 运行器自检套件。
 *
 * 做什么：直接驱动 [ConformanceCaseParser] 与 [conformanceFailure]，覆盖格式错误
 *   和「期望拒绝却通过」「期望退出值不符」两类关键误判。
 * 什么时候用：执行一致性测试时自动运行。
 * 前置条件：仓库根可定位；模块 build 目录可写。
 * 失败模式：任一自检断言失败即说明运行器可能把坏用例判成好用例。
 * 为什么需要自检：动态测试本身依赖运行器；若运行器把意外通过当成功，整套一致性
 *   测试会静默失去意义。
 */
class ConformanceRunnerSelfTest {
    private val repositoryRoot = ConformanceLayout.repositoryRoot()
    private val backend: ConformanceBackend = JvmConformanceBackend()

    /**
     * 缺少 `expect:` 时解析必须失败，且错误信息包含文件名。
     */
    @Test
    fun missingExpectIsRejectedWithFileName() {
        val content = header(
            spec = "02-字面量.md#2.1 整数字面量",
            kind = "pos",
            expect = null
        ) + mainReturning(0)
        assertParseFails("missing-expect", content, "缺少头部字段")
    }

    /**
     * 未知 `kind` 时解析必须失败，且错误信息包含文件名。
     */
    @Test
    fun unknownKindIsRejectedWithFileName() {
        val content = header(
            spec = "02-字面量.md#2.1 整数字面量",
            kind = "smoke",
            expect = "exit 0"
        ) + mainReturning(0)
        assertParseFails("unknown-kind", content, "未知 kind")
    }

    /**
     * `spec:` 指向不存在的文档文件时解析必须失败。
     */
    @Test
    fun missingSpecFileIsRejectedWithFileName() {
        val content = header(
            spec = "99-不存在的章节.md#1.1 不存在",
            kind = "pos",
            expect = "exit 0"
        ) + mainReturning(0)
        assertParseFails("missing-spec-file", content, "规范文件不存在")
    }

    /**
     * `spec:` 指向存在文件中不存在的小节时解析必须失败。
     */
    @Test
    fun missingSpecSectionIsRejectedWithFileName() {
        val content = header(
            spec = "02-字面量.md#2.99 不存在的小节",
            kind = "pos",
            expect = "exit 0"
        ) + mainReturning(0)
        assertParseFails("missing-spec-section", content, "小节不存在")
    }

    /**
     * 标记为 `rej` 但实际能编译通过的用例，必须被判为失败。
     */
    @Test
    fun rejectionCaseThatCompilesIsReportedAsFailure() {
        val content = header(
            spec = "10-语句与控制流.md#10.6 Return / Break / Continue",
            kind = "rej",
            expect = "reject Break outside loop or switch"
        ) + mainReturning(0)

        withTempCase("unexpected-pass", content) { path ->
            val case = ConformanceCaseParser.parse(path, repositoryRoot)
            val outcome = backend.compileAndRun(case.source, case.name)
            val failure = conformanceFailure(case, outcome)
            assertNotNull(failure, "rej 用例意外编译通过时运行器必须报告失败")
            assertTrue(
                failure.contains("意外编译通过"),
                "失败信息应明确说明意外编译通过，实际为: $failure"
            )
        }
    }

    /**
     * 标记为 `pos` 但返回值不符的用例，必须被判为失败。
     */
    @Test
    fun exitCodeMismatchIsReportedAsFailure() {
        val content = header(
            spec = "02-字面量.md#2.1 整数字面量",
            kind = "pos",
            expect = "exit 1"
        ) + mainReturning(2)

        withTempCase("exit-mismatch", content) { path ->
            val case = ConformanceCaseParser.parse(path, repositoryRoot)
            val outcome = backend.compileAndRun(case.source, case.name)
            val failure = conformanceFailure(case, outcome)
            assertNotNull(failure, "pos 用例返回值不符时运行器必须报告失败")
            assertTrue(failure.contains("期望 exit 1"), "失败信息应包含期望值，实际为: $failure")
            assertTrue(failure.contains("实际 exit 2"), "失败信息应包含实际值，实际为: $failure")
            assertFalse(failure.contains("意外编译通过"), "返回值不符不应被描述成意外通过")
        }
    }

    /**
     * 生成头部文本；[expect] 为 null 时故意省略该字段。
     */
    private fun header(spec: String, kind: String, expect: String?): String = buildString {
        appendLine("// spec: $spec")
        appendLine("// kind: $kind")
        if (expect != null) appendLine("// expect: $expect")
    }

    /** 生成一个返回固定整数的完整主函数源码。 */
    private fun mainReturning(value: Int): String = """
        package test;

        public function main(): int32 {
            return $value;
        }
    """.trimIndent() + "\n"

    /**
     * 断言解析失败，并确认错误消息包含文件名与指定原因片段。
     */
    private fun assertParseFails(name: String, content: String, expectedFragment: String) {
        withTempCase(name, content) { path ->
            val error = assertFailsWith<ConformanceFormatException> {
                ConformanceCaseParser.parse(path, repositoryRoot)
            }
            val message = error.message.orEmpty()
            assertTrue(
                message.contains(path.fileName.toString()),
                "错误信息应包含文件名 '${path.fileName}'，实际为: $message"
            )
            assertTrue(
                message.contains(expectedFragment),
                "错误信息应包含 '$expectedFragment'，实际为: $message"
            )
        }
    }

    /**
     * 在仓库 build 下创建唯一临时用例，执行 [block] 后递归删除。
     *
     * 前置条件：仓库 build 目录可写；[name] 仅用于文件名与断言，不参与路径穿越。
     * 失败模式：创建/删除失败抛 IO 异常，测试直接失败。
     * 为什么不用系统临时目录：项目约定所有临时产物留在仓库内。
     */
    private fun <T> withTempCase(name: String, content: String, block: (Path) -> T): T {
        val base = repositoryRoot.resolve("build").resolve("tmp").resolve("conformance-self")
        Files.createDirectories(base)
        val directory = Files.createTempDirectory(base, "$name-")
        val casePath = directory.resolve("$name.jas")
        Files.writeString(casePath, content, StandardCharsets.UTF_8)
        try {
            return block(casePath)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
