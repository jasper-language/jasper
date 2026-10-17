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
// 历史裁决（2026-10-17，用户选 A）：
//   `go` 前缀运算符已从语法中删除，启动任务改由标准库 Task 协议承担
//   （`Task<T>` + `ImmediateTask.run`）。因此 #2 的两半都在本轮收口：
//   执行语义不再需要（没有该运算符），"能否作标识符"变成正向断言。
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
     * #2（收口，2026-10-17 裁决 A）：`go` 关键字已从语言中移除，现为**普通标识符**。
     *
     * 为什么这样断言：原先是硬关键字，`t.go()` 在解析阶段就被拒绝（FINDINGS #2 后半）。
     * 用户裁决"删掉 `go` 语法糖、启动任务交给标准库 Task"后，最直接的回归就是：
     * 方法名、局部变量名都能叫 `go`，且调用结果正确。
     *
     * 取值说明：`t.go()` 返回 1，局部变量 `go = 40`，相加得 41。
     */
    @Test
    fun `go is an ordinary identifier after keyword removal`() {
        val source = """
            package test;
            public class T { public function go(): int32 { return 1; } }
            public function main(): int32 {
                var t: T = new T();
                var go: int32 = 40;
                return t.go() + go;
            }
        """.trimIndent()
        val out = compile(source, "go-identifier")
        assertEquals(41, invokeStatic(out, "test.main", "main"))
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
