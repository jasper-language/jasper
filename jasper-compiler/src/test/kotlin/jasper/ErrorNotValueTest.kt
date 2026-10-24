// ============================================================================
// ErrorNotValueTest.kt —— 「错误类型不是值」的语言裁决回归测试
// ============================================================================
// 职责：
//   锁定 2026-10-24 用户裁决：`Error` / `RecoverableError` / `FatalError` 及其
//   子类只能出现在 `throw new X(...)` 与 `catch (e: X)` 中，不得作为函数返回类型、
//   元组元素、字段类型、参数类型、局部变量类型或泛型实参。
//
// 所属层次：
//   测试层。只依赖 JasperCompiler 的公开编译入口（compileAllWithStdlib）与 JDK
//   的 URLClassLoader，不触碰 TypeChecker 内部实现，保证测试描述「可观察行为」。
//
// 为什么必须真实编译（部分用例还真实执行）：
//   本裁决的失败模式是「检查器放行 → 翻译器把 Error 当普通引用类型生成字节码」，
//   只检查诊断文案容易漏掉跨层漂移。因此负例断言编译期语义错误，正例断言仍能编译，
//   throw/catch 正例进一步加载生成的 class 并执行，证明异常控制流未被误伤。
//
// 产物位置：模块内 build/scratch/error-not-value/，不写仓库外路径。
// ============================================================================

package jasper

import jasper.compiler.CompileError
import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 做什么：验证错误类型在值位置被拒绝、在多返回值与业务 `Result` 中不被误伤、
 *   并在 `throw` / `catch` 中保持可用。
 *
 * 什么时候用：改动 TypeChecker 的声明类型检查、错误类型识别、`Result<T,E>`
 *   约定或标准库错误层级时。
 *
 * 前置条件：模块测试工作目录可写 `build/`；标准库资源在测试 classpath 上。
 *
 * 失败模式：负例编译成功、正例编译失败、诊断文案丢失 `error is not a value`，
 *   或 throw/catch 正例运行结果不符时，对应用例失败。
 *
 * 为什么同时保留固定用例与固定种子性质测试：固定用例给出可读的语言契约快照；
 *   性质测试把「类型位置 × 错误类型 × 包装形状」的笛卡尔积固定下来，避免只测到
 *   最容易想到的一两个交叉点。
 */
class ErrorNotValueTest {

    /** 为单个用例准备干净的输出目录；固定在本模块 build/ 下（仓库内）。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "error-not-value", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /**
     * 做什么：把「标准库 + 用户源码」一起编译，返回结果与输出目录。
     * 什么时候用：所有需要 `std.Error` 层级可见的用例。
     * 前置条件：source 是完整 Jasper 源文件。
     * 失败模式：不抛异常；编译失败以 CompileResult.Error 返回。
     * 为什么带标准库：错误类型与其子类都声明在 std 包，必须与用户代码同次编译。
     */
    private fun compileWithStdlib(source: String, name: String): Pair<JasperCompiler.CompileResult, Path> {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("T.jas" to source), outputDir)
        return result to outputDir
    }

    /** 把聚合诊断拼成文本，便于一次性断言稳定措辞。 */
    private fun messages(result: JasperCompiler.CompileResult): String =
        (result as? JasperCompiler.CompileResult.Error)
            ?.errors
            ?.joinToString("\n") { it.message }
            .orEmpty()

    /**
     * 做什么：断言源码编译失败，且诊断是语义/类型错误而非编译器内部错误。
     * 什么时候用：所有负例。
     * 前置条件：source 应包含一个值位置上的错误类型。
     * 失败模式：编译成功或出现 Internal 诊断时断言失败。
     */
    private fun expectSemanticError(source: String, name: String): String {
        val (result, _) = compileWithStdlib(source, name)
        assertTrue(
            result is JasperCompiler.CompileResult.Error,
            "[$name] expected compilation to fail, got: $result"
        )
        val errors = (result as JasperCompiler.CompileResult.Error).errors
        assertTrue(
            errors.none { it is CompileError.Internal },
            "[$name] expected semantic diagnostics, got internal error: ${messages(result)}"
        )
        return messages(result)
    }

    /** 加载输出目录并调用 `test.main` 的无参静态 main。 */
    private fun runMain(outputDir: Path, className: String): Any? =
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass(className)
            clazz.methods.first { it.name == "main" && it.parameterCount == 0 }.invoke(null)
        }

    // ── 负例：任务书点名的每个值位置都必须被拒绝 ──────────────────────────

    /** 元组元素是返回类型的一部分：`(int32, Error)` 必须报 `error is not a value`。 */
    @Test
    fun `tuple return type containing Error is rejected`() {
        val text = expectSemanticError(
            """
                package test;
                import std.Error;
                public class T {
                    public function f(): (int32, Error) {
                        throw new Error("x");
                    }
                }
            """.trimIndent(),
            "tuple-return"
        )
        assertTrue(text.contains("error is not a value"), "got:\n$text")
    }

    /** 直接以 Error 作为返回类型必须被拒绝。 */
    @Test
    fun `direct Error return type is rejected`() {
        val text = expectSemanticError(
            """
                package test;
                import std.Error;
                public class T {
                    public function f(): Error {
                        throw new Error("x");
                    }
                }
            """.trimIndent(),
            "direct-return"
        )
        assertTrue(text.contains("error is not a value"), "got:\n$text")
    }

    /** 字段/属性类型是值位置，不能是 Error。 */
    @Test
    fun `Error field type is rejected`() {
        val text = expectSemanticError(
            """
                package test;
                import std.Error;
                public class T {
                    public var e: Error;
                }
            """.trimIndent(),
            "field"
        )
        assertTrue(text.contains("error is not a value"), "got:\n$text")
    }

    /** 形参类型是值位置，不能是 Error。 */
    @Test
    fun `Error parameter type is rejected`() {
        val text = expectSemanticError(
            """
                package test;
                import std.Error;
                public class T {
                    public function f(e: Error): void { }
                }
            """.trimIndent(),
            "parameter"
        )
        assertTrue(text.contains("error is not a value"), "got:\n$text")
    }

    /** 局部变量显式类型是值位置，不能是 Error。 */
    @Test
    fun `Error local variable type is rejected`() {
        val text = expectSemanticError(
            """
                package test;
                import std.Error;
                public class T {
                    public function f(): void {
                        var e: Error;
                    }
                }
            """.trimIndent(),
            "local"
        )
        assertTrue(text.contains("error is not a value"), "got:\n$text")
    }

    /** 由初始化器推断出的局部变量类型同样不能是 Error。 */
    @Test
    fun `inferred Error local variable type is rejected`() {
        val text = expectSemanticError(
            """
                package test;
                import std.Error;
                public class T {
                    public function f(): void {
                        var e = new Error("x");
                    }
                }
            """.trimIndent(),
            "inferred-local"
        )
        assertTrue(text.contains("error is not a value"), "got:\n$text")
    }

    /** `Result<T, Error>` 的 E 必须是业务数据；诊断要提示改用 throw。 */
    @Test
    fun `Result with Error payload is rejected and points at throw`() {
        val text = expectSemanticError(
            """
                package test;
                import std.Error;
                public class Result<T, E> {
                    public var value: T;
                    public var failure: E;
                    public constructor(value: T, failure: E) {
                        this.value = value;
                        this.failure = failure;
                    }
                }
                public class T {
                    public function f(): Result<int32, Error> {
                        throw new Error("x");
                    }
                }
            """.trimIndent(),
            "result-error"
        )
        assertTrue(text.contains("error is not a value"), "got:\n$text")
        assertTrue(text.contains("throw the error instead"), "got:\n$text")
    }

    // ── 正例：多返回值、业务 Result、throw/catch 均不受影响 ────────────────

    /** `(int32, int32)` 的多返回与 `Result<int32, ParseFailure>` 必须继续可用。 */
    @Test
    fun `multi return and business Result remain valid`() {
        val (result, _) = compileWithStdlib(
            """
                package test;
                import std.NumberFormatError;
                public class ParseFailure {
                    public var code: int32;
                    public constructor(code: int32) { this.code = code; }
                }
                public class Result<T, E> {
                    public var value: T;
                    public var failure: E;
                    public constructor(value: T, failure: E) {
                        this.value = value;
                        this.failure = failure;
                    }
                }
                public function divide(a: int32, b: int32): (int32, int32) {
                    throw new NumberFormatError("not executed");
                }
                public function useResult(r: Result<int32, ParseFailure>): int32 {
                    return 0;
                }
                public function main(): int32 { return 0; }
            """.trimIndent(),
            "positive-multi-return"
        )
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "expected success, got:\n${messages(result)}"
        )
    }

    /** `throw new NumberFormatError` 与 `catch (e: RecoverableError)` 必须保持可用且可执行。 */
    @Test
    fun `throw and catch error hierarchy still compile and run`() {
        val (result, outputDir) = compileWithStdlib(
            """
                package test;
                import std.NumberFormatError;
                import std.RecoverableError;
                public function main(): int32 {
                    try {
                        throw new NumberFormatError("bad");
                    } catch (e: RecoverableError) {
                        return 1;
                    }
                    return 0;
                }
            """.trimIndent(),
            "positive-throw-catch"
        )
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "expected success, got:\n${messages(result)}"
        )
        assertEquals(1, runMain(outputDir, "test.main"), "catch block must run")
    }

    // ── 固定种子性质测试：类型位置 × 错误类型 × 包装形状 ────────────────────

    /**
     * 描述一个「声明类型位置」，render 负责把具体类型片段嵌入合法声明。
     *
     * 为什么用函数字段：不同位置（返回/参数/字段/局部/lambda 形参）的模板差异大，
     *   但都只需「唯一名字 + 类型片段」，把变化点收敛在数据里，性质测试主体只做
     *   笛卡尔积与断言，避免为每个位置复制一遍循环。
     */
    private data class TypePosition(val label: String, val render: (String, String) -> String)

    /** 描述一种错误类型包装形状（裸类型/可空/数组/容器实参/Result 载荷）。 */
    private data class TypeWrapper(val label: String, val render: (String) -> String)

    /** 一条性质用例：位置 × 基础错误类型 × 包装形状。 */
    private data class Case(
        val position: String,
        val baseType: String,
        val wrapper: String,
        val renderedType: String
    )

    /**
     * 做什么：固定种子属性测试——把所有「值位置 × 错误类型（含用户子类）× 包装形状」
     *   打乱后生成同一个源文件，逐条断言都产生 `error is not a value` 诊断。
     *
     * 为什么批量放在一个源文件：每次编译都要带上标准库，逐例编译会把 100+ 次
     *   标准库解析/检查成本叠加；批量编译既保留每个组合的独立诊断，又让测试时间可控。
     * 为什么断言条数相等：每个组合恰好对应一条错误；多于组合数说明同一错误被重复
     *   报告，少于组合数说明某个位置被漏检。固定种子保证失败可复现。
     */
    @Test
    fun `every value position rejects every error type shape with fixed seed`() {
        val seed = 20261024
        val positions = listOf(
            TypePosition("return") { name, type ->
                "public function f$name(): $type { throw new Error(\"x\"); }"
            },
            TypePosition("parameter") { name, type ->
                "public function p$name(x: $type): void { }"
            },
            TypePosition("field") { name, type ->
                "public class C$name { public var f: $type; }"
            },
            TypePosition("local") { name, type ->
                "public function l$name(): void { var x: $type; }"
            },
            TypePosition("lambda-parameter") { name, type ->
                "public function q$name(): void { var cb = lambda (x: $type) { }; }"
            }
        )
        val wrappers = listOf(
            TypeWrapper("bare") { it },
            TypeWrapper("nullable") { "$it?" },
            TypeWrapper("array") { "$it[]" },
            TypeWrapper("generic-argument") { "List<$it>" },
            TypeWrapper("result-payload") { "Result<int32, $it>" }
        )
        // 包含 std 层级根、分支与叶子，以及用户自定义子类（验证继承图而非名字硬编码）。
        val errorTypes = listOf("Error", "RecoverableError", "FatalError", "NumberFormatError", "MyError")

        val cases = positions
            .flatMap { position ->
                errorTypes.flatMap { baseType ->
                    wrappers.map { wrapper ->
                        Case(
                            position = position.label,
                            baseType = baseType,
                            wrapper = wrapper.label,
                            renderedType = wrapper.render(baseType)
                        )
                    }
                }
            }
            // 固定种子乱序：声明顺序不构成隐含依赖，失败时按种子复现。
            .shuffled(Random(seed))

        val declarations = cases.mapIndexed { index, case ->
            positions.first { it.label == case.position }.render(index.toString(), case.renderedType)
        }.joinToString("\n")

        val source = """
            package test;
            import std.Error;
            import std.RecoverableError;
            import std.FatalError;
            import std.NumberFormatError;

            public class MyError extends RecoverableError {
                public constructor(message: string) { super(message); }
            }

            public class Result<T, E> {
                public var value: T;
                public var failure: E;
                public constructor(value: T, failure: E) {
                    this.value = value;
                    this.failure = failure;
                }
            }

            $declarations
        """.trimIndent()

        val (result, _) = compileWithStdlib(source, "property-matrix")
        assertTrue(
            result is JasperCompiler.CompileResult.Error,
            "expected all ${cases.size} cases to be rejected, got: $result"
        )
        val errors = (result as JasperCompiler.CompileResult.Error).errors
        assertFalse(
            errors.any { it is CompileError.Internal },
            "expected semantic diagnostics only, got internal error: ${messages(result)}"
        )
        val rejected = errors.count { it.message.contains("error is not a value") }
        val rejectedDetail = errors
            .filter { it.message.contains("error is not a value") }
            .groupBy { it.line }
            .map { (line, atLine) ->
                val allAtLine = errors.filter { it.line == line }
                "$line(x${atLine.size}): ${source.lines().getOrNull(line - 1)?.trim().orEmpty()} " +
                    allAtLine.joinToString(" | ") { "col=${it.column}:${it.message.take(70)}" }
            }
            .joinToString("\n")
        assertEquals(
            cases.size,
            rejected,
            "seed=$seed: expected exactly ${cases.size} error-is-not-a-value diagnostics, " +
                "got $rejected; cases=${cases.joinToString { "${it.position}/${it.baseType}/${it.wrapper}" }}\n" +
                rejectedDetail
        )
    }
}
