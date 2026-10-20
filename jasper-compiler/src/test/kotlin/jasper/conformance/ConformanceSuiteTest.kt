// ============================================================================
// ConformanceSuiteTest.kt —— 一致性用例的发现、执行与期望比较
// ----------------------------------------------------------------------------
// 职责：递归发现 conformance/ 下所有 `.jas` 文件，为每个文件生成一个 JUnit
//   动态测试；用 ConformanceBackend 执行，并按 pos / rej / edge 比较结果。
// 边界：本文件不解析头部细节（交给 ConformanceCaseParser），也不实现后端。
// English: Discovers conformance cases and verifies each outcome.
// ============================================================================

package jasper.conformance

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 一致性套件的 JUnit 入口。
 *
 * 做什么：把用例目录中的每个文件映射成一个动态测试，并在失败信息中给出相对
 *   路径、规范引用、期望与实际结果。
 * 什么时候用：执行 `:jasper-compiler:test --tests 'jasper.conformance.*'` 时。
 * 前置条件：仓库根可定位，`conformance/` 与 `docs/` 均存在。
 * 失败模式：格式错误、编译结果不符或运行故障都会让对应用例失败；不会跳过。
 * 为什么用动态测试：用例是数据文件，数量随语言演进而增长，动态生成避免为每个
 *   语义手写一个 Kotlin 测试方法。
 */
class ConformanceSuiteTest {
    private val backend: ConformanceBackend = JvmConformanceBackend()

    /**
     * 发现全部用例并生成动态测试。
     *
     * 失败模式：目录遍历失败会直接让测试工厂失败；单个用例的格式/行为问题只让
     *   该用例失败，便于一次看到全部偏差。
     */
    @TestFactory
    fun conformanceCases(): List<DynamicTest> {
        val repositoryRoot = ConformanceLayout.repositoryRoot()
        val conformanceRoot = ConformanceLayout.conformanceRoot(repositoryRoot)
        return discoverCases(conformanceRoot).map { casePath ->
            val displayName = conformanceRoot.relativize(casePath).toString()
            DynamicTest.dynamicTest(displayName) {
                val case = ConformanceCaseParser.parse(casePath, repositoryRoot)
                val outcome = backend.compileAndRun(case.source, case.name)
                val failure = conformanceFailure(case, outcome)
                assertNull(failure, failure)
            }
        }
    }

    /**
     * 校验种子语料的最低覆盖形状。
     *
     * 做什么：要求五个章节目录都存在，总数不少于 15，且每章 pos / rej / edge
     *   各至少一个。
     * 什么时候用：与动态测试一起运行，防止后续重构误删整类用例。
     * 失败模式：数量或类型缺失时以明确消息失败。
     * 为什么单独成测：动态测试只验证已有文件；本测验证「该有的文件还在」。
     */
    @Test
    fun seedCorpusCoversEveryChapterAndKind() {
        val repositoryRoot = ConformanceLayout.repositoryRoot()
        val conformanceRoot = ConformanceLayout.conformanceRoot(repositoryRoot)
        val cases = discoverCases(conformanceRoot).map {
            ConformanceCaseParser.parse(it, repositoryRoot)
        }
        assertTrue(cases.size >= 15, "一致性用例应至少有 15 个，实际 ${cases.size}")

        val chapters = listOf(
            "02-字面量",
            "04-变量与声明",
            "05-函数",
            "09-表达式",
            "10-语句与控制流"
        )
        for (chapter in chapters) {
            val inChapter = cases.filter { it.path.startsWith(conformanceRoot.resolve(chapter)) }
            assertTrue(inChapter.size >= 3, "章节 '$chapter' 应至少有 3 个用例，实际 ${inChapter.size}")
            for (kind in ConformanceKind.entries) {
                assertTrue(
                    inChapter.any { it.kind == kind },
                    "章节 '$chapter' 缺少 ${kind.headerValue} 用例"
                )
            }
        }
    }

    /**
     * 递归发现 `.jas` 用例并按路径排序。
     *
     * 前置条件：[conformanceRoot] 是已存在的目录。
     * 失败模式：IO 异常向上传播，测试显式失败。
     * 为什么排序：动态测试顺序稳定，失败报告与本地复跑一致。
     */
    private fun discoverCases(conformanceRoot: Path): List<Path> {
        Files.walk(conformanceRoot).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jas") }
                .sorted()
                .toList()
        }
    }
}

/**
 * 比较一个用例的期望与实际结果。
 *
 * 做什么：成功时返回 null；不符时返回一段包含用例名、规范引用、期望与实际值的
 *   失败说明。
 * 什么时候用：一致性套件的动态测试与运行器自检共用，确保两处判定口径一致。
 * 前置条件：[case] 已通过头部解析；[outcome] 来自任意实现的后端。
 * 失败模式：本函数不抛异常，所有不符合都转成可断言的消息。
 * 为什么不用 assertEquals：`rej` 需要同时检查「是否拒绝」和「诊断片段」，
 *   统一函数还能把「意外编译通过」写成明确失败，而不是依赖空值比较。
 */
internal fun conformanceFailure(
    case: ConformanceCase,
    outcome: ConformanceOutcome
): String? {
    val prefix = "用例 '${case.name}'（spec: ${case.spec}, kind: ${case.kind.headerValue}）"
    return when (val expectation = case.expectation) {
        is ConformanceExpectation.Exit -> when (outcome) {
            is ConformanceOutcome.Exited ->
                if (outcome.exitCode == expectation.code) {
                    null
                } else {
                    "$prefix 期望 exit ${expectation.code}，实际 exit ${outcome.exitCode}"
                }

            is ConformanceOutcome.Rejected ->
                "$prefix 期望 exit ${expectation.code}，但编译被拒绝:\n${outcome.diagnostic}"
        }

        is ConformanceExpectation.Reject -> when (outcome) {
            is ConformanceOutcome.Exited ->
                "$prefix 期望编译被拒且诊断含 '${expectation.diagnosticFragment}'，" +
                    "但实际意外编译通过并以 exit ${outcome.exitCode} 结束"

            is ConformanceOutcome.Rejected ->
                if (outcome.diagnostic.contains(expectation.diagnosticFragment)) {
                    null
                } else {
                    "$prefix 编译被拒，但诊断不含 '${expectation.diagnosticFragment}':\n" +
                        outcome.diagnostic
                }
        }
    }
}
