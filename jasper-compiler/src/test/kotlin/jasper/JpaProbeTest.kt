// =============================================================================
// JpaProbeTest.kt —— JPA 形状探针的验收测试
// =============================================================================
// 职责：
//   把 `probes/jpa/` 下的 Jasper 探针源码交给**真实编译管线**
//   （ANTLR -> AST -> 注解处理 -> 符号表 -> 类型检查 -> Kotlin IR -> JVM 字节码），
//   写盘后用 URLClassLoader 加载并**真正执行**，对可观察结果做断言。
//
// 所属层次 / 与其他模块关系：
//   测试层。只依赖 `jasper.compiler.JasperCompiler` 的公开入口
//   （compileAllWithStdlib / compileAll / CompileResult），不触碰编译器内部实现，
//   保证测试「描述行为」而非「锁定实现」。所有产物写入本模块 build/ 下，
//   绝不用 /tmp 或仓库外路径。
//
// 与另两个探针测试的关系：
//   与 `JdbcProbeTest`（同步 IO）、`R2dbcProbeTest`（异步流）同构；
//   本测试压的是**注解驱动 + 泛型 + 实体映射**这一族。
//
// 为什么必须真跑而不是只编译：
//   本探针要压的运行期形状有：泛型接口分派、`any` + `cast` 的实体存取、
//   生命周期回调、`return null`、名为 `remove` 的方法等——这些摩擦几乎全部
//   表现为 `VerifyError` / `NoSuchMethodError` / `ClassFormatError`，只有真加载
//   真执行才会暴露。
//
// 覆盖的验收点（任务书要求 ①②③）：
//   ① 实体落库再查回，字段值一致（roundTripName）；
//   ② @OneToMany 关系可遍历（addressTraversal）；
//   ③ 实体缺 @Id 时有明确运行期错误（missingIdError / missingIdGuard）。
//   另加：注解处理阶段确实把实体注解族登记进 `AnnotationIndex`（注解面证据）。
// =============================================================================

package jasper

import jasper.compiler.JasperCompiler
import jasper.stdlib.StdLib
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 编译并运行 `probes/jpa/` 下的 JPA 形状探针。
 *
 * 做什么：走完整编译管线并把内存假实现跑起来，断言实体映射/关系/仓储/生命周期
 *   的可观察结果，并核对注解索引。
 * 什么时候用：改动注解处理阶段、类型映射、接口分派或字节码后端后运行。
 * 前置条件：`probes/jpa/` 相对模块工作目录可见（Gradle 测试 cwd 为模块目录）。
 * 失败模式：编译失败时以编译诊断断言失败；运行期由 JVM 抛出的
 *   `VerifyError`/`NoSuchMethodError` 等直接使测试失败——这正是探针要暴露的信号。
 * 为什么分两个用例：一个负责「真跑」（端到端），一个负责「注解面」（编译期索引），
 *   两者失败原因不同，分开便于定位。
 */
class JpaProbeTest {

    /** 定位探针目录；从模块目录（Gradle 测试 cwd）向上回退一层也能找到。 */
    private fun probesDir(): Path {
        val candidates = listOf(Paths.get("../probes/jpa"), Paths.get("probes/jpa"))
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("probes/jpa not found from ${Paths.get("").toAbsolutePath()}")
    }

    /** 读取探针目录下全部 `.jas` 源文件，按文件名排序，保证编译输入确定。 */
    private fun probeSources(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Files.list(probesDir()).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jas") }
                .sorted()
                .forEach { p -> out[p.fileName.toString()] = String(Files.readAllBytes(p)) }
        }
        return out
    }

    /** 为本用例准备干净的输出目录；路径固定在模块 build/ 下。 */
    private fun freshOutputDir(name: String): Path {
        val base = Paths.get("build", "scratch", "jpa-probe", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 加载输出目录并调用某类的无参静态方法。 */
    private fun invokeStatic(loader: URLClassLoader, className: String, method: String): Any? {
        val clazz = loader.loadClass(className)
        val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
        return target.invoke(null)
    }

    /**
     * 验收点①②③：探针经完整管线编译、真加载、真执行。
     *
     * 断言：
     *   - `probes.jpa.main` 聚合结果等于期望值；
     *   - `roundTripName()`：落库再查回，字段值一致；
     *   - `addressTraversal()`：@OneToMany 关系可遍历；
     *   - `missingIdError()`：缺 @Id 得到明确错误消息（含实体名与 @Id）；
     *   - `missingIdGuard()`：缺 @Id 时抛错且先前的合法注册仍然有效。
     */
    @Test
    fun `jpa probe compiles and the in-memory provider runs`() {
        val sources = probeSources()
        assertTrue(sources.isNotEmpty(), "probes/jpa should contain at least one .jas file")

        val outputDir = freshOutputDir("run")
        val result = JasperCompiler().compileAllWithStdlib(sources, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "JPA probe failed to compile: " +
                (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )

        val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))

        // 端到端聚合：生成 id(1+2) + 字段一致(10) + PrePersist(1) + PostLoad(1)
        // + 受管(2) + 关系(22) + 仓储命中(100) + 仓储计数(1) + 泛型仓储(9)
        // + 缺 @Id 守卫(1) + flush(1) + 删除(1000) = 1151。
        val mainValue = invokeStatic(loader, "probes.jpa.main", "main")
        assertEquals(1151, mainValue, "JPA 探针聚合结果应为 1151（见 main() 注释逐项推导）")

        // 验收点 ①：落库再查回，字段值一致。
        val name = invokeStatic(loader, "probes.jpa.roundTripName", "roundTripName")
        assertEquals("ada", name, "实体落库再查回应保持姓名字段一致")

        // 验收点 ②：关系遍历：命中 2 个、关系总数 2 -> 22。
        val traversal = invokeStatic(loader, "probes.jpa.addressTraversal", "addressTraversal")
        assertEquals(22, traversal, "@OneToMany 关系应可遍历（命中 2 * 10 + 总数 2）")

        // 验收点 ③：缺 @Id 的明确错误（不得静默）。
        val error = invokeStatic(loader, "probes.jpa.missingIdError", "missingIdError") as String
        assertTrue(
            error.contains("@Id") && error.contains("Legacy"),
            "缺 @Id 时应抛出含实体名与 @Id 的明确错误，实际=[$error]"
        )
        val guard = invokeStatic(loader, "probes.jpa.missingIdGuard", "missingIdGuard")
        assertEquals(1, guard, "缺 @Id 报错时，先前合法注册应仍然保留（count=1）")

        // 记录反射观测到 build/ 下的报告文件，便于人工核对（不使用外部临时目录）。
        Files.createDirectories(Paths.get("build", "scratch"))
        Files.write(
            Paths.get("build", "scratch", "jpa-probe-report.txt"),
            buildString {
                appendLine("probe-files=${sources.keys.sorted().joinToString(",")}")
                appendLine("main-result=$mainValue")
                appendLine("round-trip-name=$name")
                appendLine("address-traversal=$traversal")
                appendLine("missing-id-error=$error")
                appendLine("missing-id-guard=$guard")
            }.toByteArray()
        )
    }

    /**
     * 注解面证据：实体注解族确实被注解处理阶段登记进 `AnnotationIndex`。
     *
     * 为什么单独编译一次：`compileAllWithStdlib` 只返回「成功/失败」摘要，不带索引；
     * 而 `compileAll` 返回的每个 pipeline 都带该文件的 `annotationIndex`。
     * 本用例只关心编译期数据，不写盘。
     */
    @Test
    fun `entity annotation family is recorded in the annotation index`() {
        val sources = probeSources()
        val merged = LinkedHashMap<String, String>()
        merged.putAll(StdLib.sources())
        merged.putAll(sources)

        val pipelines = JasperCompiler().compileAll(merged)
        val personIndex = pipelines.getValue("Person.jas").annotationIndex
        val addressIndex = pipelines.getValue("Address.jas").annotationIndex

        val personRecords = personIndex.forClass("Person")
        fun personHas(annotation: String, kind: String, target: String? = null): Boolean =
            personRecords.any {
                it.annotationName == annotation && it.targetKind == kind &&
                    (target == null || it.targetName == target)
            }

        assertTrue(personHas("Entity", "class"), "Person 应有类级 @Entity，实际=$personRecords")
        assertTrue(personHas("Table", "class"), "Person 应有类级 @Table，实际=$personRecords")
        assertTrue(personHas("Id", "property", "id"), "Person.id 应有 @Id，实际=$personRecords")
        assertTrue(
            personHas("GeneratedValue", "property", "id"),
            "Person.id 应有 @GeneratedValue，实际=$personRecords"
        )
        assertTrue(
            personHas("Column", "property", "name"),
            "Person.name 应有 @Column，实际=$personRecords"
        )
        assertTrue(
            personHas("OneToMany", "property", "addresses"),
            "Person.addresses 应有 @OneToMany，实际=$personRecords"
        )
        assertTrue(
            personHas("PrePersist", "function", "prePersist"),
            "Person.prePersist 应有 @PrePersist，实际=$personRecords"
        )
        assertTrue(
            personHas("PostLoad", "function", "postLoad"),
            "Person.postLoad 应有 @PostLoad，实际=$personRecords"
        )

        val addressRecords = addressIndex.forClass("Address")
        assertTrue(
            addressRecords.any { it.annotationName == "Entity" && it.targetKind == "class" },
            "Address 应有类级 @Entity，实际=$addressRecords"
        )
        assertTrue(
            addressRecords.any {
                it.annotationName == "ManyToOne" && it.targetKind == "property" && it.targetName == "personId"
            },
            "Address.personId 应有 @ManyToOne，实际=$addressRecords"
        )
    }
}
