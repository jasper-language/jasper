// =============================================================================
// AnnotationValidationTest.kt —— 编译期注解规则校验验收（Q32 / C17）
// =============================================================================
// 职责：
//   用真实编译管线验证「注解规则注册表」的编译期行为：合法实体通过；缺 @Id、
//   目标不匹配、重复/互斥、未知/缺失/非常量元素都会产生 ANNOTATION 诊断且不落 class；
//   同时回归现有 @Getter/@Setter/@EqualsAndHashCode/@Mapper 的 codegen，并证明运行期
//   MappingRegistry 的缺 @Id 检查仍作为纵深防御保留。
//
// 所属层次：测试层。只依赖 JasperCompiler 的公开入口与 probes/jpa 源文件，
//   不触碰编译器内部实现；所有输出目录固定在模块 build/scratch/ 下，
//   不写 /tmp 或仓库外路径（遵循仓库资源/临时文件纪律）。
//
// 与谁协作：
//   - 被测代码：jasper.annotations.AnnotationProcessor / AnnotationValidator /
//     AnnotationRuleSet；
//   - 运行期防御性证据复用 probes/jpa/Mapping.jas + JpaException.jas。
//
// 为什么必须真编译/真加载：
//   注解错误必须发生在写字节码之前，单测 AST 只能证明「产生了错误对象」，不能证明
//   「没有 class 产物」；因此负向用例都断言 outputDir 中不存在目标 class。
// =============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Q32 / C17：编译期注解校验。
 *
 * 做什么：覆盖规则注册表的目标/元素/重复/互斥/组合约束，以及现有 codegen 回归。
 * 什么时候用：修改注解处理、规则表、诊断模型或 JPA 探针后运行。
 * 前置条件：Gradle 测试 cwd 为 jasper-compiler 模块目录；probes/jpa 相对可见。
 * 失败模式：编译未按预期失败/成功、诊断文案缺失关键名字、或负向用例仍产出 class。
 */
class AnnotationValidationTest {

    /**
     * 准备一个仓库内的干净输出目录。
     *
     * 做什么：位于 `jasper-compiler/build/scratch/annotation-validation/<name>`，
     *   先删除旧内容再创建，避免上一轮产物污染「无 class 产物」断言。
     * 什么时候用：每个需要落盘的用例开头。
     * 前置条件：name 在测试类内唯一。
     * 失败模式：IO 失败直接使测试失败（路径固定，不会写到仓库外）。
     */
    private fun freshOutputDir(name: String): Path {
        val base = Paths.get("build", "scratch", "annotation-validation", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 单文件编译并返回结果 + 输出目录；调用方负责在断言后清理目录。 */
    private fun compile(source: String, dirName: String): Pair<JasperCompiler.CompileResult, Path> {
        val outputDir = freshOutputDir(dirName)
        val result = JasperCompiler().compileToDirWithIr(source.trimIndent(), outputDir)
        return result to outputDir
    }

    /** 多文件编译并返回结果 + 输出目录；用于跨文件/跨包校验。 */
    private fun compileFiles(
        sources: Map<String, String>,
        dirName: String
    ): Pair<JasperCompiler.CompileResult, Path> {
        val outputDir = freshOutputDir(dirName)
        val result = JasperCompiler().compileAllToDir(sources, outputDir)
        return result to outputDir
    }

    /** 断言编译失败并取回完整诊断文本；失败时给出原始结果便于定位。 */
    private fun errorMessage(result: JasperCompiler.CompileResult): String {
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected a compile error, got: $result")
        return result.message
    }

    /** 断言编译成功；失败时把诊断文本带进断言消息。 */
    private fun assertSuccess(result: JasperCompiler.CompileResult) {
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "Compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message ?: result}"
        )
    }

    /** 定位 probes/jpa 目录；从模块目录向上回退一层也能找到。 */
    private fun probesDir(): Path {
        val candidates = listOf(Paths.get("../probes/jpa"), Paths.get("probes/jpa"))
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("probes/jpa not found from ${Paths.get("").toAbsolutePath()}")
    }

    // ── 1. 合法实体 ────────────────────────────────────────────────────────

    /**
     * 合法实体：@Entity + @Id 属性 + 带元素的 @Table/@Column 应编译并运行。
     *
     * 为什么同时跑 main：证明新增校验没有把合法注解误判成错误，也没有破坏字段访问。
     */
    @Test
    fun `entity with id property compiles and runs`() {
        val (result, outputDir) = compile(
            """
            package test;
            @Entity
            @Table(name = "people")
            class Person {
                @Id
                @GeneratedValue(strategy = "identity")
                var id: int32;
                @Column(name = "full_name")
                var name: string;
                constructor(id: int32, name: string) { this.id = id; this.name = name; }
            }
            function main(): string {
                var p = new Person(7, "ada");
                return p.name + ":" + toString(p.id);
            }
            """,
            "valid-entity"
        )
        try {
            assertSuccess(result)
            URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
                assertEquals("ada:7", loader.loadClass("test.main").getMethod("main").invoke(null))
            }
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ── 2. @Entity 缺 @Id：编译期错误 + 无 class 产物 ──────────────────────

    /**
     * @Entity 缺少 @Id 时必须在编译期报错，且不生成实体 class。
     *
     * 这是 JPA #6 的核心验收：错误不能等到运行期 MappingRegistry.register() 才暴露。
     */
    @Test
    fun `entity without id is rejected before class emission`() {
        val (result, outputDir) = compile(
            """
            package test;
            @Entity
            class Legacy {
                var name: string;
                constructor(name: string) { this.name = name; }
            }
            function main(): int32 { return 0; }
            """,
            "missing-id"
        )
        try {
            val message = errorMessage(result)
            assertTrue(message.contains("ANNOTATION"), "Expected an ANNOTATION diagnostic, got: $message")
            assertTrue(message.contains("Legacy"), "Error must name the entity, got: $message")
            assertTrue(message.contains("@Id"), "Error must mention @Id, got: $message")
            assertFalse(
                outputDir.resolve("test/Legacy.class").toFile().exists(),
                "An invalid entity must not produce a class file"
            )
            assertFalse(
                outputDir.resolve("test/main.class").toFile().exists(),
                "Compilation must stop before emitting any class for this source"
            )
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ── 3. 目标不匹配 ─────────────────────────────────────────────────────

    /** @Getter 只能贴在类或属性上；贴到方法上应在编译期报目标错误。 */
    @Test
    fun `getter on a function is rejected`() {
        val (result, outputDir) = compile(
            """
            package test;
            class Foo {
                var x: int32;
                constructor(x: int32) { this.x = x; }
                @Getter
                function notAProperty(): int32 { return this.x; }
            }
            function main(): int32 { return 0; }
            """,
            "getter-on-function"
        )
        try {
            val message = errorMessage(result)
            assertTrue(message.contains("@Getter"), "Error must mention @Getter, got: $message")
            assertTrue(message.contains("function"), "Error must name the illegal target kind, got: $message")
            assertTrue(message.contains("notAProperty"), "Error must name the target, got: $message")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ── 4. 重复与互斥 ────────────────────────────────────────────────────

    /** 同一位置重复 @Getter 属于不可重复注解，应在编译期报错。 */
    @Test
    fun `duplicate getter is rejected`() {
        val (result, outputDir) = compile(
            """
            package test;
            @Getter
            @Getter
            class Foo {
                var name: string;
                constructor(name: string) { this.name = name; }
            }
            function main(): int32 { return 0; }
            """,
            "duplicate-getter"
        )
        try {
            val message = errorMessage(result)
            assertTrue(message.contains("Duplicate @Getter"), "Expected duplicate diagnostic, got: $message")
            assertTrue(message.contains("Foo"), "Error must name the declaration, got: $message")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    /** @Column 与关系注解 @ManyToOne 语义互斥；同时出现应在编译期报冲突。 */
    @Test
    fun `conflicting column and relation annotations are rejected`() {
        val (result, outputDir) = compile(
            """
            package test;
            @Entity
            class Address {
                @Id
                var id: int32;
                @Column(name = "person_id")
                @ManyToOne(targetEntity = "Person")
                var personId: int32;
                constructor(id: int32, personId: int32) { this.id = id; this.personId = personId; }
            }
            function main(): int32 { return 0; }
            """,
            "conflicting-annotations"
        )
        try {
            val message = errorMessage(result)
            assertTrue(message.contains("Conflicting annotations"), "Expected conflict diagnostic, got: $message")
            assertTrue(message.contains("@Column") && message.contains("@ManyToOne"),
                "Conflict must name both annotations, got: $message")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ── 5. 必需 / 未知 / 非常量元素 ───────────────────────────────────────

    /** @Mapping 的 source/target 都是必需元素；缺 target 应报缺失。 */
    @Test
    fun `missing required annotation element is rejected`() {
        val (result, outputDir) = compile(
            """
            package test;
            class Person {
                var name: string;
                constructor(name: string) { this.name = name; }
            }
            class PersonDto {
                var name: string;
            }
            @Mapper
            interface PersonMapper {
                @Mapping(source = "name")
                function toDto(p: Person): PersonDto;
            }
            function main(): int32 { return 0; }
            """,
            "missing-element"
        )
        try {
            val message = errorMessage(result)
            assertTrue(message.contains("missing required element 'target'"),
                "Expected missing element diagnostic, got: $message")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    /** 规则表未声明的元素名应报未知元素，而不是被静默忽略。 */
    @Test
    fun `unknown annotation element is rejected`() {
        val (result, outputDir) = compile(
            """
            package test;
            @Table(bogus = "x")
            class Foo {
                var id: int32;
            }
            function main(): int32 { return 0; }
            """,
            "unknown-element"
        )
        try {
            val message = errorMessage(result)
            assertTrue(message.contains("Unknown element 'bogus'"), "Expected unknown element diagnostic, got: $message")
            assertTrue(message.contains("@Table"), "Error must name the annotation, got: $message")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    /** 注解元素必须可静态判定；函数调用不是常量，应在编译期报错。 */
    @Test
    fun `non constant annotation element is rejected`() {
        val (result, outputDir) = compile(
            """
            package test;
            function tableName(): string { return "person"; }
            @Table(name = tableName())
            class Foo {
                var id: int32;
            }
            function main(): int32 { return 0; }
            """,
            "non-constant-element"
        )
        try {
            val message = errorMessage(result)
            assertTrue(message.contains("must be a compile-time constant"),
                "Expected constant diagnostic, got: $message")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ── 6. 现有四类注解 codegen 回归 ──────────────────────────────────────

    /**
     * @Getter/@Setter/@EqualsAndHashCode/@Mapper 的合法 codegen 不受规则校验影响。
     *
     * 断言真实执行：setter 写入、getter 读取、equals 成立、Mapper 实现类可实例化并映射。
     */
    @Test
    fun `existing annotation codegen still works`() {
        val (result, outputDir) = compile(
            """
            package test;
            @Getter
            @Setter
            @EqualsAndHashCode
            class Person {
                var name: string;
                constructor(name: string) { this.name = name; }
            }
            class PersonDto {
                var name: string;
            }
            @Mapper
            interface PersonMapper {
                function toDto(p: Person): PersonDto;
            }
            function main(): string {
                var p = new Person("a");
                p.setName("b");
                var same = new Person("b");
                if (p.equals(same) == false) { return "not-equal"; }
                var mapper: PersonMapper = new PersonMapperImpl();
                var dto = mapper.toDto(p);
                return p.getName() + ":" + dto.name;
            }
            """,
            "codegen-regression"
        )
        try {
            assertSuccess(result)
            URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
                assertEquals("b:b", loader.loadClass("test.main").getMethod("main").invoke(null))
                val person = loader.loadClass("test.Person")
                assertTrue(person.declaredMethods.any { it.name == "getName" }, "Expected synthesized getter")
                assertTrue(person.declaredMethods.any { it.name == "setName" }, "Expected synthesized setter")
                assertTrue(person.declaredMethods.any { it.name == "equals" }, "Expected synthesized equals")
            }
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ── 7. 跨文件 / 跨包实体校验 ──────────────────────────────────────────

    /**
     * 不同文件、不同包中的实体分别按自己的成员校验 @Id。
     *
     * 先编译两个合法实体（alpha/beta）证明跨文件不会误报；再加入 gamma 包中缺 @Id
     * 的实体，断言错误定位到该实体，且不产出它的 class。
     */
    @Test
    fun `entity validation works across files and packages`() {
        val alpha = """
            package alpha;
            @Entity
            public class AlphaEntity {
                @Id public var id: int32;
                public constructor(id: int32) { this.id = id; }
            }
        """.trimIndent()
        val beta = """
            package beta;
            @Entity
            @Table(name = "beta_entity")
            public class BetaEntity {
                @Id public var id: int32;
                public constructor(id: int32) { this.id = id; }
            }
        """.trimIndent()

        val (validResult, validDir) = compileFiles(
            mapOf("Alpha.jas" to alpha, "Beta.jas" to beta),
            "cross-file-valid"
        )
        try {
            assertSuccess(validResult)
            assertTrue(validDir.resolve("alpha/AlphaEntity.class").toFile().exists(),
                "Valid entity in alpha package should be emitted")
            assertTrue(validDir.resolve("beta/BetaEntity.class").toFile().exists(),
                "Valid entity in beta package should be emitted")
        } finally {
            validDir.toFile().deleteRecursively()
        }

        val legacy = """
            package gamma;
            @Entity
            public class LegacyEntity {
                public var name: string;
                public constructor(name: string) { this.name = name; }
            }
        """.trimIndent()
        val (invalidResult, invalidDir) = compileFiles(
            mapOf("Alpha.jas" to alpha, "Beta.jas" to beta, "Legacy.jas" to legacy),
            "cross-file-invalid"
        )
        try {
            val message = errorMessage(invalidResult)
            assertTrue(message.contains("LegacyEntity"), "Error must name the offending entity, got: $message")
            assertTrue(message.contains("@Id"), "Error must mention @Id, got: $message")
            assertFalse(
                invalidDir.resolve("gamma/LegacyEntity.class").toFile().exists(),
                "Invalid cross-file entity must not produce a class"
            )
        } finally {
            invalidDir.toFile().deleteRecursively()
        }
    }

    // ── 8. 运行期纵深防御仍保留 ───────────────────────────────────────────

    /**
     * MappingRegistry.register() 仍拒绝 idField 为空的元数据。
     *
     * 为什么保留：编译期规则是最先防线，但手写元数据/未来外部来源仍可能绕过注解；
     *   运行期检查作为纵深防御不能被删除或放宽。这里直接编译 probes/jpa 的
     *   Mapping.jas + JpaException.jas，并通过反射调用真实 register()。
     */
    @Test
    fun `runtime mapping registry still rejects missing id`() {
        val outputDir = freshOutputDir("runtime-registry")
        try {
            val sources = mapOf(
                "Mapping.jas" to String(Files.readAllBytes(probesDir().resolve("Mapping.jas"))),
                "JpaException.jas" to String(Files.readAllBytes(probesDir().resolve("JpaException.jas")))
            )
            val result = JasperCompiler().compileAllWithStdlib(sources, outputDir)
            assertSuccess(result)

            URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
                val metaClass = loader.loadClass("probes.jpa.EntityMetadata")
                val meta = metaClass
                    .getConstructor(String::class.java, String::class.java, String::class.java, Boolean::class.java, Int::class.java)
                    .newInstance("Legacy", "legacy", "", false, 0)
                val registryClass = loader.loadClass("probes.jpa.MappingRegistry")
                val registry = registryClass.getDeclaredConstructor().newInstance()

                val failure = assertFailsWith<InvocationTargetException> {
                    registryClass.getMethod("register", metaClass).invoke(registry, meta)
                }
                val message = failure.targetException.message.orEmpty()
                assertTrue(
                    message.contains("@Id") && message.contains("Legacy"),
                    "Runtime MappingRegistry must still reject missing @Id, got: $message"
                )
            }
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }
}
