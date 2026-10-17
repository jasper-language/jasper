// =============================================================================
// R2dbcDefectRegressionTest.kt —— R2DBC 探针异步地基四缺陷的回归测试
// =============================================================================
// 职责：
//   为 `probes/r2dbc/FINDINGS.md` 中「异步地基」四条编译器缺陷各固定一条回归用例：
//     #1 `await` 生成 unknown 调用、#2 `go` 执行语义被破坏、#4 `finally` 语义不可靠、
//     #11 数组元素读取类型为 void。每条都走**真实编译管线**
//     （ANTLR -> AST -> 检查 -> Kotlin IR -> JVM 字节码），写盘后用 URLClassLoader
//     真加载、真执行，对可观察结果断言——避免「编译通过但字节码静默错误」再次发生。
//
// 所属层次 / 与其他模块关系：
//   测试层，与 `ProbeDefectRegressionTest`（JDBC 批次）、`CrossPackageLinkageTest`
//   并列，只依赖 `JasperCompiler` 的公开入口。所有产物写入本模块 `build/scratch/`
//   下，绝不使用 `/tmp` 或仓库外路径。
//
// 为什么单独成组：
//   这四条都属「异步 / 挂起模型的形状地基」，是 R2DBC 探针里最影响可写性的缺陷；
//   把它们集中在一处，后续改动翻译器/后端时一眼能看出是否回归。
//
// 已知阻塞（明确记录，不伪装成通过）：
//   #2 的后半「`go` 可作普通标识符/方法名」受 `.g4` 硬关键字约束，无法在不动语法的
//   前提下修复（见下 `go as an ordinary identifier...` 用例）。该用例断言当前**仍被拒绝**，
//   作为阻塞标记：一旦 `Go` 改为软关键字，此用例会失败并提醒更新。
// =============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R2DBC 探针异步地基缺陷（#1/#2/#4/#11）的回归测试。
 *
 * 做什么：为每条缺陷编译一段最小 Jasper 源码并真正运行，断言可观察结果。
 * 什么时候用：改动 `JasperToIrTranslator` 的一元运算/数组访问、或 `JvmIrBackend`
 *   的 `IrTry`/`IrReturn` 分支后必跑。
 * 前置条件：模块测试工作目录下可写 `build/`。
 * 失败模式：编译失败/运行期 `VerifyError`/返回值不符，均直接使对应用例失败。
 */
class R2dbcDefectRegressionTest {

    /** 为单个用例准备干净输出目录；固定在本模块 `build/` 下，不用外部临时目录。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "r2dbc-defects", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 编译源码并要求成功；失败时把编译诊断带进断言消息。 */
    private fun compile(source: String, name: String): Path {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    /** 加载 `className` 的无参静态方法并调用，返回其结果。 */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        val clazz = URLClassLoader(arrayOf(outputDir.toUri().toURL())).loadClass(className)
        val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
        return target.invoke(null)
    }

    /**
     * #1：`await e` 必须返回 `e` 的值，且该值可直接参与算术与字符串拼接。
     *
     * 为什么这样断言：旧实现把 `await` 降级为对 `unknown` 的调用，运行期抛
     * `NoSuchMethodError`；本用例把 await 结果同时用于整数比较与字符串拼接，任何
     * 「丢值 / 类型退化」都会立刻暴露。
     */
    @Test
    fun `await value participates in arithmetic and string concatenation`() {
        val source = """
            package test;
            public function five(): int32 { return 5; }
            public function greet(): string { return "hi"; }
            public function main(): string {
                var n: int32 = await five();
                var greeting: string = (await greet()) + "!";
                if (n + 1 == 6) {
                    return greeting + "ok";
                }
                return "bad";
            }
        """.trimIndent()
        val out = compile(source, "await-value")
        assertEquals("hi!ok", invokeStatic(out, "test.main", "main"))
    }

    /**
     * #2（前半）：`go task()` 必须真的执行任务体的副作用，而不是被静默丢弃。
     *
     * 为什么这样断言：旧实现把 `go` 直接转发给目标，语义不可观测；本用例让 `go` 调用一个
     * 会修改对象字段的方法，随后读取该字段，副作用发生则结果非 0。
     */
    @Test
    fun `go executes the task body and its side effect is observable`() {
        val source = """
            package test;
            public class Counter {
                public var hits: int32;
                public constructor() { this.hits = 0; }
                public function bump(): void { this.hits = this.hits + 1; }
            }
            public function main(): int32 {
                var c: Counter = new Counter();
                go c.bump();
                return c.hits;
            }
        """.trimIndent()
        val out = compile(source, "go-executes")
        assertEquals(1, invokeStatic(out, "test.main", "main"))
    }

    /**
     * #2（后半，阻塞标记）：`go` 目前是 `.g4` 里的硬关键字，不能作普通标识符/方法名。
     *
     * 期望行为：`t.go()` 合法。
     * 当前行为：解析阶段报 `mismatched input 'go' expecting Identifier`。
     * 本用例**断言当前被拒绝**，把「语法层阻塞」固定下来；修复方式必须改动
     * `JasperLexer.g4`（把 `Go` 改为软关键字或仅在语句起始处生效），而本次任务硬约束
     * 禁止改动任何 `.g4`，故此处只作记录。一旦语法放开，此用例会失败并提示更新为正向断言。
     */
    @Test
    fun `go as an ordinary identifier is blocked by the hard keyword`() {
        val source = """
            package test;
            public class T { public function go(): int32 { return 1; } }
            public function main(): int32 { var t: T = new T(); return t.go(); }
        """.trimIndent()
        val result = JasperCompiler().compileToDirWithIr(source, scratchDir("go-identifier"))
        val error = result as? JasperCompiler.CompileResult.Error
            ?: fail("expected `go` to be rejected as an identifier while it stays a hard lexer keyword")
        assertTrue(
            error.message.contains("go"),
            "expected a syntax diagnostic mentioning `go`, got: ${error.message}"
        )
    }

    /**
     * #4：`finally` 在正常、异常、提前 return 三条路径上各执行且**仅执行一次**。
     *
     * 为什么这样断言：用一个 `int32[]` 单元累加每次 finally 的加数，返回
     * `early * 100 + cell[0]`，把「路径正确」与「次数正确」编码进一个整数：
     *   early：提前 return 路径，finally +1                     => cell=1,  a=1
     *   normal：正常路径，先 +10 再 finally +1                  => cell=12
     *   caught：抛异常穿过 throwing 的内层 finally（+5），被 catch 接住后
     *           再由本函数的 finally（+1）收尾                 => cell=18
     * 期望 `1*100 + 18 = 118`。任一条路径漏跑或多跑 finally 都会改变结果。
     */
    @Test
    fun `finally runs exactly once on normal exception and early return paths`() {
        val source = """
            package test;
            public function early(cell: int32[]): int32 {
                try { return 1; } finally { cell[0] = cell[0] + 1; }
            }
            public function normal(cell: int32[]): void {
                try { cell[0] = cell[0] + 10; } finally { cell[0] = cell[0] + 1; }
            }
            public function throwing(cell: int32[]): void {
                try { throw new java.lang.Exception(); } finally { cell[0] = cell[0] + 5; }
            }
            public function caught(cell: int32[]): int32 {
                var r: int32 = 0;
                try {
                    throwing(cell);
                    r = 1;
                } catch (e: java.lang.Exception) {
                    r = 7;
                } finally {
                    cell[0] = cell[0] + 1;
                }
                return r;
            }
            public function main(): int32 {
                var cell: int32[] = new int32[1];
                cell[0] = 0;
                var a: int32 = early(cell);
                normal(cell);
                var ignored: int32 = caught(cell);
                return a * 100 + cell[0];
            }
        """.trimIndent()
        val out = compile(source, "finally-paths")
        assertEquals(118, invokeStatic(out, "test.main", "main"))
    }

    /**
     * #11：数组元素读取 `a[i]` 的类型必须是元素类型，`var x = a[i];` 可立即参与运算。
     *
     * 为什么这样断言：旧实现把 `a[i]` 的 IR 类型记为 Unit（描述符 `V`），`var x = a[i]`
     * 会推断出 void 局部变量并生成非法字节码；本用例不做显式类型标注，直接把推断结果
     * 用于加法，覆盖「类型推断」这一最关键路径。
     */
    @Test
    fun `array element read is immediately usable`() {
        val source = """
            package test;
            public function main(): int32 {
                var a: int32[] = new int32[1];
                a[0] = 41;
                var x = a[0];
                return x + 1;
            }
        """.trimIndent()
        val out = compile(source, "array-read")
        assertEquals(42, invokeStatic(out, "test.main", "main"))
    }
}
