// ============================================================================
// UnknownTypeTest.kt —— 「未知类型必须在编译期报错」回归测试
// ============================================================================
// 职责：
//   固定 Q16 的核心契约：用户**显式写出的类型引用**若无法解析到
//   「本程序声明 / 显式 import / 标准库 / 语言内建 / 保留的 JDK 互操作类型」，
//   必须在编译期报出带名字与位置的明确错误，而不是静默兜底成 any 并在运行期
//   炸 NoClassDefFoundError（probes/jpa/FINDINGS.md #13）。
//
// 所属层次 / 与其他模块关系：
//   测试层。只依赖 JasperCompiler 的公开入口（compile / compileAll /
//   compileAllWithStdlib / CompileResult），不触碰 TypeChecker 内部实现，
//   保证测试描述「可观察行为」而非锁定实现细节。
//
// 为什么既测负向也测正向：
//   只测「未知会报错」很容易把检查写成「凡名字都报错」；因此每个负向用例都配一条
//   正向对照（显式 import、跨文件声明、内建/别名），确保严格检查没有误伤合法代码。
//
// 产物路径：所有编译输出固定在本模块 build/scratch/unknown-type/ 下，
//   不使用 /tmp 或仓库外路径。
// ============================================================================

package jasper

import jasper.compiler.CompileError
import jasper.compiler.JasperCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 未知类型检查的回归测试集合。
 *
 * 做什么：以真实编译管线（ANTLR → AST → 注解 → 符号表 → 类型检查）编译最小
 *   Jasper 源码，断言未知类型在编译期失败、合法类型不被误报。
 * 什么时候用：改动 TypeChecker 的类型引用解析、SymbolTable 的包/import 索引，
 *   或调整内建/JDK 互操作白名单后必跑。
 * 前置条件：模块测试工作目录可写 build/。
 * 失败模式：编译结果与预期不符时以带真实诊断的断言失败，便于定位。
 */
class UnknownTypeTest {

    /** 为单个用例准备干净输出目录；固定在本模块 build/scratch/ 下（仓库内）。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "unknown-type", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 编译单文件源码并要求失败，返回结构化错误列表。 */
    private fun compileError(source: String, name: String): JasperCompiler.CompileResult.Error {
        val result = JasperCompiler().compile(source)
        assertTrue(
            result is JasperCompiler.CompileResult.Error,
            "[$name] expected CompileResult.Error but got: $result"
        )
        return result as JasperCompiler.CompileResult.Error
    }

    /**
     * 以「标准库 + 用户源码」方式编译并要求失败，返回 (错误, 输出目录)。
     *
     * 为什么带标准库：FINDINGS #13 的真实场景就是「标准库里声明了 std.Error，
     *   但用户文件在另一个包且没 import」。只有把标准库一起编译，才能验证检查
     *   区分了「别包同名类型」与「本包可见类型」，而不是仅凭简单名放行。
     */
    private fun compileWithStdlibError(source: String, name: String): JasperCompiler.CompileResult.Error {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("T.jas" to source), outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Error,
            "[$name] expected CompileResult.Error but got: $result"
        )
        return result as JasperCompiler.CompileResult.Error
    }

    /** 把一批诊断拼成一段文本，便于一次性断言名字/提示。 */
    private fun messages(error: JasperCompiler.CompileResult.Error): String =
        error.errors.joinToString("\n") { it.message }

    /**
     * 用例 1（FINDINGS #13 原始形态）：别包里的 `Error` 未 import，必须编译期报错。
     *
     * 同时断言错误里给出了可执行的 import 建议（`std.Error`），以及至少一条诊断
     * 带有真实行列位置——这是「让用户能直接理解并修复」的最低要求。
     */
    @Test
    fun `unimported Error catch is a compile-time error`() {
        val source = """
            package probes.jpa;
            public class T {
                public function f(): void {
                    try { throw new Error("x"); } catch (e: Error) { }
                }
            }
        """.trimIndent()

        val error = compileWithStdlibError(source, "unimported-error")
        val text = messages(error)
        assertTrue(text.contains("Unknown type 'Error'"), "Expected unknown-type error, got:\n$text")
        assertTrue(
            text.contains("did you mean to import std.Error?"),
            "Expected an actionable import suggestion, got:\n$text"
        )
        assertTrue(
            error.errors.any { it is CompileError.Type && it.line > 0 },
            "Expected at least one positioned type error, got: ${error.errors}"
        )
    }

    /**
     * 用例 4（正向对照）：显式 `import std.Error;` 后，同样的 catch 必须编译成功。
     *
     * 为什么必须成功：本任务只要求「未 import 时报错」，不要求自动导入；因此
     * 显式 import 是用户修复该错误的正确路径，不能被误伤。
     */
    @Test
    fun `imported std Error makes the same catch compile`() {
        val source = """
            package probes.jpa;
            import std.Error;
            public class T {
                public function f(): void {
                    try { throw new Error("x"); } catch (e: Error) { }
                }
            }
        """.trimIndent()

        val outputDir = scratchDir("imported-error")
        val result = JasperCompiler().compileAllWithStdlib(mapOf("T.jas" to source), outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "Explicitly imported std.Error should compile, got: " +
                (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
    }

    /** 用例 2：局部变量显式类型未知 -> 编译错误（且消息含类型名）。 */
    @Test
    fun `unknown variable type is a compile-time error`() {
        val source = """
            package test;
            public function main(): void {
                var x: NoSuchType;
            }
        """.trimIndent()

        val error = compileError(source, "unknown-variable")
        assertTrue(
            messages(error).contains("Unknown type 'NoSuchType'"),
            "Expected unknown variable type error, got:\n${messages(error)}"
        )
    }

    /** 用例 3a：形参类型未知 -> 编译错误。 */
    @Test
    fun `unknown parameter type is a compile-time error`() {
        val source = """
            package test;
            public function take(p: MissingParamType): void { }
            public function main(): void { }
        """.trimIndent()

        val error = compileError(source, "unknown-parameter")
        assertTrue(
            messages(error).contains("Unknown type 'MissingParamType'"),
            "Expected unknown parameter type error, got:\n${messages(error)}"
        )
    }

    /** 用例 3b：返回类型未知 -> 编译错误。 */
    @Test
    fun `unknown return type is a compile-time error`() {
        val source = """
            package test;
            public function give(): MissingReturnType {
                return new MissingReturnType();
            }
            public function main(): void { }
        """.trimIndent()

        val error = compileError(source, "unknown-return")
        assertTrue(
            messages(error).contains("Unknown type 'MissingReturnType'"),
            "Expected unknown return type error, got:\n${messages(error)}"
        )
    }

    /**
     * 用例补充（无 package 声明）：默认包文件引用别包（`std`）类型而不 import，
     * 同样必须在编译期报错，不得因为「文件没有 package」就放行。
     *
     * 为什么单独测：可见性判定把「无 package」归一为空串（默认包），只能引用默认包内
     * 声明；若把「无 package」当作「任意包都可见」，就会重现 FINDINGS #13 的静默解析。
     */
    @Test
    fun `unimported packaged type in a no-package file is a compile-time error`() {
        val source = """
            public class T {
                public var bad: Error;
            }
        """.trimIndent()

        val error = compileWithStdlibError(source, "no-package-unimported")
        assertTrue(
            messages(error).contains("Unknown type 'Error'"),
            "Expected unknown-type error in no-package file, got:\n${messages(error)}"
        )
    }

    /** 用例补充：字段（类属性）类型未知 -> 编译错误。 */
    @Test
    fun `unknown field type is a compile-time error`() {
        val source = """
            package test;
            public class Holder {
                public var bad: NoSuchFieldType;
            }
        """.trimIndent()

        val error = compileError(source, "unknown-field")
        assertTrue(
            messages(error).contains("Unknown type 'NoSuchFieldType'"),
            "Expected unknown field type error, got:\n${messages(error)}"
        )
    }

    /** 用例补充：泛型实参未知 -> 编译错误（泛型实参也是显式类型引用）。 */
    @Test
    fun `unknown generic argument is a compile-time error`() {
        val source = """
            package test;
            public class Box<T> {
                private var value: T;
                public constructor(v: T) { this.value = v; }
            }
            public function main(): void {
                var b: Box<NoSuchArg>;
            }
        """.trimIndent()

        val error = compileError(source, "unknown-generic-arg")
        assertTrue(
            messages(error).contains("Unknown type 'NoSuchArg'"),
            "Expected unknown generic argument error, got:\n${messages(error)}"
        )
    }

    /** 用例补充：cast 目标类型未知 -> 编译错误。 */
    @Test
    fun `unknown cast target is a compile-time error`() {
        val source = """
            package test;
            public function main(): void {
                var a: any;
                var b: NoSuchCastType = cast a as NoSuchCastType;
            }
        """.trimIndent()

        val error = compileError(source, "unknown-cast")
        assertTrue(
            messages(error).contains("Unknown type 'NoSuchCastType'"),
            "Expected unknown cast target error, got:\n${messages(error)}"
        )
    }

    /**
     * 用例 5（正向对照）：同一程序另一个文件里声明的类型必须可见（同包跨文件）。
     *
     * 为什么：`compileAll` 会先把所有文件声明收进同一 SymbolTable，再逐文件检查；
     * 检查必须使用这份全局声明视图，不能误判「声明在别的文件」为未知。
     */
    @Test
    fun `type declared in another file of the same package is visible`() {
        val widget = """
            package test;
            public class Widget {
                public function v(): int32 { return 41; }
            }
        """.trimIndent()
        val app = """
            package test;
            public function main(): int32 {
                var w: Widget = new Widget();
                return w.v() + 1;
            }
        """.trimIndent()

        val result = JasperCompiler().compile(mapOf("Widget.jas" to widget, "App.jas" to app))
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "Cross-file same-package type should compile, got: " +
                (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
    }

    /** 用例 5 前向引用变体：类型在使用点之后声明，也必须可见（收集先于检查）。 */
    @Test
    fun `forward reference to a later declaration in the same file is visible`() {
        val source = """
            package test;
            public function main(): int32 {
                var w: Widget = new Widget();
                return w.v();
            }
            public class Widget {
                public function v(): int32 { return 7; }
            }
        """.trimIndent()

        val result = JasperCompiler().compile(source)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "Forward reference to a later declaration should compile, got: " +
                (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
    }

    /**
     * 用例 6（正向对照）：语言内建、别名与已声明类型不得误报。
     *
     * 覆盖：基本类型（int32/int64/float64/string/bool/any）、内建别名
     *   （Bool/Boolean）、本文件声明的类、对象数组元素类型、泛型实参。
     */
    @Test
    fun `builtin aliases and declared types are not reported as unknown`() {
        val source = """
            package test;
            public class Node {
                public var next: Node?;
                public var n: int32;
                public constructor(n: int32) { this.n = n; }
            }
            public class Box<T> {
                private var value: T;
                public constructor(v: T) { this.value = v; }
            }
            public function main(): int32 {
                var i: int32 = 1;
                var l: int64 = 2;
                var f: float64 = 1.5;
                var s: string = "x";
                var ok: bool = true;
                var alias: Bool = ok;
                var booleanAlias: Boolean = ok;
                var anything: any;
                var nodes: Node[] = new Node[1];
                nodes[0] = new Node(i);
                var box: Box<int32>;
                return nodes[0].n;
            }
        """.trimIndent()

        val result = JasperCompiler().compile(source)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "Builtin/declared types must not be reported as unknown, got: " +
                (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
    }

    /**
     * 用例补充：未知类型必须在写盘前中断，不得产出任何 .class。
     *
     * 断言方式：编译到仓库内固定输出目录；失败后遍历目录，确认没有 class 文件。
     * 这直接对应任务目标「不得产出对不存在类的引用」。
     */
    @Test
    fun `unknown type produces no class files`() {
        val source = """
            package probes.jpa;
            public class T {
                public var bad: NoSuchType;
            }
        """.trimIndent()

        val outputDir = scratchDir("no-class-output")
        val result = JasperCompiler().compileAllWithStdlib(mapOf("T.jas" to source), outputDir)
        assertTrue(result is JasperCompiler.CompileResult.Error, "Expected compile failure, got: $result")

        val classFiles = outputDir.toFile().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .toList()
        assertTrue(classFiles.isEmpty(), "Unknown type must not emit class files, found: $classFiles")
        assertFalse(
            Files.exists(outputDir.resolve("probes")),
            "Translation/back-end must not run for a program with unknown types"
        )
    }
}
