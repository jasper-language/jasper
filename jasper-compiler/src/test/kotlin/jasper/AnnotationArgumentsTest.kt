// ============================================================================
// AnnotationArgumentsTest.kt —— 注解命名实参与嵌套注解的 AST 归一化回归（Q23 / C18）
// ============================================================================
// 职责：验证 JasperAstBuilder 在 AST 构建层把「位置参数里包着 name = value」折叠为
//       JasAnnotationNamedArg，并覆盖纯位置、混合、纯命名三种形态与嵌套注解递归；
//       末了用端到端编译证明 @Mapper/@Mapping 的实参读取路径仍然工作。
// 所属层次：test（编译器前端黑盒）。只通过 JasperCompiler.compileToPipeline 拿 AST，
//       不引入运行期反射来做 AST 遍历（反射仅用于端到端 class 加载）。
// 与谁协作：被测代码为 jasper.ast.JasperAstBuilder 与 jasper.annotations.AnnotationProcessor。
// 为什么这样设计：归一化是「注解实参形状」的变化点；把断言集中在 AST 形状上，
//       既锁住行为又不改任何 .g4 / 语义注解，改动面最小。
// ============================================================================

package jasper

import jasper.ast.JasAnnotationLiteral
import jasper.ast.JasAnnotationNamedArg
import jasper.ast.JasAnnotationPositionalArg
import jasper.ast.JasAnnotationType
import jasper.ast.JasAnnotationUse
import jasper.ast.JasAssignment
import jasper.ast.JasBinaryOp
import jasper.ast.JasBoolLiteral
import jasper.ast.JasClass
import jasper.ast.JasDeclaration
import jasper.ast.JasEnum
import jasper.ast.JasFunction
import jasper.ast.JasInterface
import jasper.ast.JasIntLiteral
import jasper.ast.JasProperty
import jasper.ast.JasStringLiteral
import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Q23 / C18：注解命名实参归一化。
 *
 * 背景：文法虽有专有的 `annotationNamedArgument` 规则，但 ANTLR 歧义消解把
 * `@Table(name = "person")` 判成位置参数分支，AST 里出现
 * `JasAnnotationPositionalArg(JasAssignment(...))`。本测试锁住「AST 构建层已把它
 * 折叠成 JasAnnotationNamedArg」这一事实，并覆盖三种实参形态与嵌套注解。
 */
class AnnotationArgumentsTest {

    // ── AST 遍历工具（不用运行期反射） ──────────────────────────────────────

    /**
     * 编译单段源码并收集文件中出现的全部注解使用（类/接口/枚举/函数/属性/注解类型上）。
     *
     * 前置条件：source 为可编译的完整源码；失败会抛 CompileException（测试即失败）。
     */
    private fun annotationsOf(source: String): List<JasAnnotationUse> {
        val pipeline = JasperCompiler().compileToPipeline(source.trimIndent())
        return collectAnnotations(pipeline.ast.declarations)
    }

    /** 递归收集一组声明及其成员上的注解使用，保持源码顺序。 */
    private fun collectAnnotations(declarations: List<JasDeclaration>): List<JasAnnotationUse> {
        val result = mutableListOf<JasAnnotationUse>()
        for (decl in declarations) {
            result.addAll(decl.declarationModifiers().filterIsInstance<JasAnnotationUse>())
            when (decl) {
                is JasClass -> result.addAll(collectAnnotations(decl.members))
                is JasInterface -> result.addAll(collectAnnotations(decl.members))
                is JasEnum -> result.addAll(collectAnnotations(decl.members))
                else -> {}
            }
        }
        return result
    }

    /**
     * 取某个声明携带的修饰符列表（注解混在普通关键字中）。
     * 只有带 modifiers 字段的声明子类才返回非空；其余返回空列表。
     */
    private fun JasDeclaration.declarationModifiers(): List<Any> = when (this) {
        is JasClass -> modifiers
        is JasInterface -> modifiers
        is JasEnum -> modifiers
        is JasFunction -> modifiers
        is JasProperty -> modifiers
        is JasAnnotationType -> modifiers
        else -> emptyList()
    }

    /** 编译源码并返回其中唯一一个指定简单名的注解，找不到或重复即测试失败。 */
    private fun annotationNamed(source: String, name: String): JasAnnotationUse =
        annotationsOf(source).single { it.name == name }

    // ── 1. 纯命名：@Table(name = "person") ────────────────────────────────

    @Test
    fun `named string argument is normalized to named arg`() {
        val ann = annotationNamed(
            """
            package test;
            @Table(name = "person")
            class Person { }
            """,
            "Table"
        )
        assertEquals(1, ann.arguments.size, "Expected exactly one argument, got: ${ann.arguments}")
        val arg = assertIs<JasAnnotationNamedArg>(ann.arguments[0], "name = value must fold into a named arg")
        assertEquals("name", arg.name)
        val value = assertIs<JasStringLiteral>(arg.value, "Value must stay the string literal")
        assertEquals("person", value.text)
    }

    // ── 2. 混合：@Tag(1, name = "x") ──────────────────────────────────────

    @Test
    fun `mixed positional and named arguments are both preserved`() {
        val ann = annotationNamed(
            """
            package test;
            @Tag(1, name = "x")
            class Person { }
            """,
            "Tag"
        )
        assertEquals(2, ann.arguments.size)
        val positional = assertIs<JasAnnotationPositionalArg>(ann.arguments[0])
        assertEquals(1, assertIs<JasIntLiteral>(positional.value).value)
        val named = assertIs<JasAnnotationNamedArg>(ann.arguments[1])
        assertEquals("name", named.name)
        assertEquals("x", assertIs<JasStringLiteral>(named.value).text)
    }

    // ── 3. 非字符串命名值：@Tag(name = 1 + 2) 不能被误拆/误判 ──────────────

    @Test
    fun `named argument with non-string expression is not mistaken for positional`() {
        val ann = annotationNamed(
            """
            package test;
            @Tag(name = 1 + 2)
            class Person { }
            """,
            "Tag"
        )
        assertEquals(1, ann.arguments.size)
        val named = assertIs<JasAnnotationNamedArg>(ann.arguments[0], "op '=' must fold to named regardless of value type")
        assertEquals("name", named.name)
        assertTrue(named.value is JasBinaryOp, "Value must remain a binary expression, got: ${named.value}")
    }

    // ── 4. 嵌套注解递归：@Outer(inner = @Inner(value = 1)) ─────────────────

    @Test
    fun `nested annotation argument is normalized recursively`() {
        val outer = annotationNamed(
            """
            package test;
            @Outer(inner = @Inner(value = 1))
            class Person { }
            """,
            "Outer"
        )
        assertEquals(1, outer.arguments.size)
        val innerArg = assertIs<JasAnnotationNamedArg>(outer.arguments[0])
        assertEquals("inner", innerArg.name)
        val literal = assertIs<JasAnnotationLiteral>(innerArg.value, "Nested annotation must be wrapped as an expression")
        assertEquals("Inner", literal.annotation.name)
        assertEquals(1, literal.annotation.arguments.size)
        val innerNamed = assertIs<JasAnnotationNamedArg>(literal.annotation.arguments[0], "Inner's argument must be normalized too")
        assertEquals("value", innerNamed.name)
        assertEquals(1, assertIs<JasIntLiteral>(innerNamed.value).value)
    }

    // ── 5. 纯位置：@Tag("x") 不能被误判为命名 ─────────────────────────────

    @Test
    fun `plain positional argument stays positional`() {
        val ann = annotationNamed(
            """
            package test;
            @Tag("x")
            class Person { }
            """,
            "Tag"
        )
        assertEquals(1, ann.arguments.size)
        val positional = assertIs<JasAnnotationPositionalArg>(ann.arguments[0])
        assertEquals("x", assertIs<JasStringLiteral>(positional.value).text)
    }

    // ── 6. 多个命名：顺序与名字正确 ───────────────────────────────────────

    @Test
    fun `multiple named arguments keep order and names`() {
        val ann = annotationNamed(
            """
            package test;
            @Tag(flag = true, count = 3)
            class Person { }
            """,
            "Tag"
        )
        assertEquals(2, ann.arguments.size)
        val flag = assertIs<JasAnnotationNamedArg>(ann.arguments[0])
        assertEquals("flag", flag.name)
        assertEquals(true, assertIs<JasBoolLiteral>(flag.value).value)
        val count = assertIs<JasAnnotationNamedArg>(ann.arguments[1])
        assertEquals("count", count.name)
        assertEquals(3, assertIs<JasIntLiteral>(count.value).value)
    }

    // ── 7. AST 层：@Mapping(source=..., target=...) 两个实参都归一化为命名 ──

    @Test
    fun `mapping annotation arguments are normalized at the ast level`() {
        val ann = annotationNamed(
            """
            package test;
            class Person { var firstName: string; constructor(firstName: string) { this.firstName = firstName; } }
            class PersonDto { var firstName: string; }
            @Mapper
            interface PersonMapper {
                @Mapping(source = "firstName", target = "firstName")
                function toDto(p: Person): PersonDto;
            }
            """,
            "Mapping"
        )
        assertEquals(2, ann.arguments.size)
        val source = assertIs<JasAnnotationNamedArg>(ann.arguments[0])
        assertEquals("source", source.name)
        assertEquals("firstName", assertIs<JasStringLiteral>(source.value).text)
        val target = assertIs<JasAnnotationNamedArg>(ann.arguments[1])
        assertEquals("target", target.name)
        assertEquals("firstName", assertIs<JasStringLiteral>(target.value).text)
    }

    // ── 8. 端到端：@Mapper/@Mapping 读取路径仍工作（编译→加载→反射执行） ──

    @Test
    fun `mapper with explicit mapping still compiles and runs end to end`() {
        val outputDir = Files.createTempDirectory("annargs")
        try {
            val result = JasperCompiler().compileToDirWithIr(
                """
                package test;
                class Person {
                    var firstName: string;
                    var age: int32;
                    constructor(firstName: string, age: int32) { this.firstName = firstName; this.age = age; }
                }
                class PersonDto {
                    var name: string;
                    var age: int32;
                }
                @Mapper
                interface PersonMapper {
                    @Mapping(source = "firstName", target = "name")
                    function toDto(p: Person): PersonDto;
                }
                function main(): string {
                    var mapper: PersonMapper = new PersonMapperImpl();
                    var dto = mapper.toDto(new Person("bob", 7));
                    return dto.name + ":" + toString(dto.age);
                }
                """.trimIndent(),
                outputDir
            )
            assertTrue(
                result is JasperCompiler.CompileResult.Success,
                "Compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message ?: result}"
            )
            URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
                assertEquals("bob:7", loader.loadClass("test.main").getMethod("main").invoke(null))
            }
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    // ── 补充：普通位置参数若值是 `==`/复合赋值，不得被误折叠 ───────────────

    @Test
    fun `positional argument with equality is not folded into named`() {
        // `==` 不是命名实参分隔符（op != "="），必须保持位置参数。
        val ann = annotationNamed(
            """
            package test;
            @Tag(1 == 1)
            class Person { }
            """,
            "Tag"
        )
        assertEquals(1, ann.arguments.size)
        val positional = assertIs<JasAnnotationPositionalArg>(ann.arguments[0])
        assertTrue(positional.value !is JasAssignment, "Equality must not be folded into a named arg")
    }
}
