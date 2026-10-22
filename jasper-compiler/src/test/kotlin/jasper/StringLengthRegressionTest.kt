package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 缺陷 S5（第一步）的回归测试：`string` 的长度语义。
 *
 * 语言约定 §11.3 规定：
 *  - `string.length` 是 Unicode **码点数**（O(n)），不是 JVM `String.length()` 的
 *    UTF-16 code unit 数；
 *  - `string.byteLength()` 是 UTF-8 编码后的**字节数**；
 *  - 字符串**不能用整数索引**，`s[i]` 必须被编译期拒绝。
 *
 * 每个成功用例都走完整流水线（ANTLR → AST → checker → IR → JVM 字节码），并**真正加载
 * 执行**生成的类——只断言“编译成功”发现不了 `NoSuchMethodError` / `VerifyError` 这类
 * 只会在类加载或调用时暴露的缺陷。失败用例则断言诊断文本，并确认没有 class 产物。
 *
 * 产物写在本模块自己的 `build/scratch/` 下（构建产物只留在仓库内，不使用外部临时路径）。
 */
class StringLengthRegressionTest {

    /** 创建并清空一个模块内 scratch 目录。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "string-length", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 编译源码并断言成功；失败时把聚合诊断带进断言信息。 */
    private fun compile(source: String, name: String): Path {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    /** 编译源码并断言失败，返回输出目录与聚合诊断文本。 */
    private fun compileExpectError(source: String, name: String): Pair<Path, String> {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        val error = result as? JasperCompiler.CompileResult.Error
        assertNotNull(error, "expected compilation to fail for $name, got: $result")
        return outputDir to error.message
    }

    /** 加载输出目录并调用无参静态方法（顶层 `main` 编译到 `包名.main` 类）。 */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass(className)
            val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
            return target.invoke(null)
        }
    }

    /** 递归统计输出目录里的 class 文件，用于断言负向用例“没有产物”。 */
    private fun classFiles(outputDir: Path): List<Path> =
        Files.walk(outputDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".class") }.toList()
        }

    /** 纯 ASCII：码点数与 UTF-16 单元数相同，先守住最基本路径。 */
    @Test
    fun `ascii length counts code points`() {
        val source = """
            package test;
            public function main(): int32 { return "abc".length; }
        """.trimIndent()
        val out = compile(source, "ascii")
        assertEquals(3, invokeStatic(out, "test.main", "main"))
    }

    /** BMP 中文：每个汉字 1 个码点，JVM 里同样是 1 个 UTF-16 单元。 */
    @Test
    fun `bmp chinese length counts code points`() {
        val source = """
            package test;
            public function main(): int32 { return "你好".length; }
        """.trimIndent()
        val out = compile(source, "bmp-chinese")
        assertEquals(2, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 补充平面字符（emoji）：1 个码点在 UTF-16 里占 2 个单元。
     * 这是本任务最关键的用例——若错误地调用 `String.length()` 会得到 4 而不是 3。
     */
    @Test
    fun `supplementary plane emoji length counts code points not utf16 units`() {
        val source = """
            package test;
            public function main(): int32 { return "a😀b".length; }
        """.trimIndent()
        val out = compile(source, "supplementary-plane")
        assertEquals(3, invokeStatic(out, "test.main", "main"))
    }

    /** 空串边界：码点数为 0，且 `codePointCount(0, 0)` 不应越界。 */
    @Test
    fun `empty string length is zero`() {
        val source = """
            package test;
            public function main(): int32 { return "".length; }
        """.trimIndent()
        val out = compile(source, "empty")
        assertEquals(0, invokeStatic(out, "test.main", "main"))
    }

    /** UTF-8 字节数：两个汉字各 3 字节，共 6 字节。 */
    @Test
    fun `bmp chinese byte length is utf8 byte count`() {
        val source = """
            package test;
            public function main(): int32 { return "你好".byteLength(); }
        """.trimIndent()
        val out = compile(source, "bmp-chinese-bytes")
        assertEquals(6, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 补充平面字符的 UTF-8 字节数：`a`=1、`😀`=4、`b`=1，共 6 字节。
     * 与码点数（3）不同，可同时防止把 `.length` 与 `.byteLength()` 实现混用。
     */
    @Test
    fun `supplementary plane emoji byte length is utf8 byte count`() {
        val source = """
            package test;
            public function main(): int32 { return "a😀b".byteLength(); }
        """.trimIndent()
        val out = compile(source, "supplementary-plane-bytes")
        assertEquals(6, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 接收者有副作用时 `.length` 只能求值一次：后端若把接收者表达式生成两遍
     * （例如分别用于 `length()` 与 `codePointCount` 的接收者），计数器会变成 2。
     * 返回值 `len * 10 + calls` 同时断言长度与调用次数。
     */
    @Test
    fun `length evaluates its receiver exactly once`() {
        val source = """
            package test;
            public class Counter {
                private var calls: int32;
                public constructor() { this.calls = 0; }
                public function text(): string {
                    this.calls = this.calls + 1;
                    return "你好";
                }
                public function callCount(): int32 { return this.calls; }
            }
            public function main(): int32 {
                var c = new Counter();
                var len: int32 = c.text().length;
                return len * 10 + c.callCount();
            }
        """.trimIndent()
        val out = compile(source, "receiver-once")
        assertEquals(21, invokeStatic(out, "test.main", "main"), "length 必须只求值接收者一次")
    }

    /** 字符串索引是语言错误：诊断必须明确，且编译失败不产出任何 class 文件。 */
    @Test
    fun `string cannot be indexed by integer`() {
        val source = """
            package test;
            public function main(): int32 {
                var s: string = "abc";
                return s[0];
            }
        """.trimIndent()
        val (out, message) = compileExpectError(source, "string-index")
        assertTrue(
            message.contains("cannot be indexed"),
            "字符串索引应被拒绝并给出 cannot be indexed 诊断，实际: $message"
        )
        assertEquals(0, classFiles(out).size, "编译失败时不应产生任何 class 文件")
    }

    /** `byteLength` 是零参方法：多给实参必须编译期拒绝，而不是留到运行期。 */
    @Test
    fun `byteLength rejects arguments`() {
        val source = """
            package test;
            public function main(): int32 { return "abc".byteLength(1); }
        """.trimIndent()
        val (_, message) = compileExpectError(source, "byte-length-args")
        assertTrue(
            message.contains("Function 'byteLength' expects 0 argument(s), got 1"),
            "byteLength(1) 应报告参数个数错误，实际: $message"
        )
    }
}
