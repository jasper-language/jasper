package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 编译器缺陷 C26 的回归测试：**丢弃宽类型（float64 / int64）结果时后端必须发 `pop2`**。
 *
 * 背景：表达式语句（以及 `when` 分支体、`finally` 中只作副作用的表达式）在求值后需要把
 * 结果弹出操作数栈。JVM 的 `pop` 只弹一个 category 1 槽位，而 `double`/`long`（描述符
 * `D`/`J`）占两个槽位——对它们发 `pop` 会让类加载期的验证器直接报
 * `VerifyError: Type double_2nd (current frame, stack[1]) is not assignable to category1 type`。
 *
 * 每个用例都走完整流水线（ANTLR → AST → checker → IR → JVM 字节码），并**真正加载并执行**
 * 生成的类：只断言「编译成功」不足以发现这类缺陷，因为它是验证器在类加载/首次调用时才炸的。
 * 产物写在本模块自己的 `build/scratch/` 目录下（不使用外部临时路径）。
 */
class WideDiscardRegressionTest {

    /** 建立并清空一个模块内 scratch 目录（构建产物只留在仓库内）。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "wide-discard", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 编译源码到 scratch 目录；失败即断言失败并带出编译错误信息。 */
    private fun compile(source: String, name: String): Path {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    private fun loader(outputDir: Path): URLClassLoader =
        URLClassLoader(arrayOf(outputDir.toUri().toURL()))

    /** 加载生成的类并调用其无参静态方法（顶层函数 main 编译到 `包名.main` 类）。 */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        val clazz = loader(outputDir).loadClass(className)
        val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
        return target.invoke(null)
    }

    /** 成功路径①：丢弃一个返回 `float64` 的自由函数调用语句，`main` 仍能正常返回 0。 */
    @Test
    fun `discarding a float64 free function result runs cleanly`() {
        val source = """
            package test;
            function f(): float64 { return 1.5; }
            public function main(): int32 { f(); return 0; }
        """.trimIndent()
        val out = compile(source, "float64-free-call")
        assertEquals(0, invokeStatic(out, "test.main", "main"))
    }

    /** 成功路径②：丢弃一个返回 `int64`（JVM `long`，同样是 category 2）的调用语句。 */
    @Test
    fun `discarding an int64 free function result runs cleanly`() {
        val source = """
            package test;
            function g(): int64 {
                var v: int64 = 70368744177664;
                return v;
            }
            public function main(): int32 { g(); return 0; }
        """.trimIndent()
        val out = compile(source, "int64-free-call")
        assertEquals(0, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 成功路径③：同一个函数里先丢弃 `float64` 再丢弃 `int32`，之后局部变量仍能正常读写——
     * 若弹栈宽度错位，后续的局部变量槽位会被错误的栈形状污染，这里会直接抛 VerifyError。
     */
    @Test
    fun `mixed wide and narrow discards keep the stack balanced`() {
        val source = """
            package test;
            function df(): float64 { return 1.5; }
            function inf(): int32 { return 5; }
            public function main(): int32 {
                df();
                inf();
                var x: int32 = 3;
                x = x + 4;
                return x;
            }
        """.trimIndent()
        val out = compile(source, "mixed-discard")
        assertEquals(7, invokeStatic(out, "test.main", "main"))
    }

    /** 成功路径④：丢弃 `float64` 的**成员方法**调用（走 INVOKEVIRTUAL，返回值同样是 `D`）。 */
    @Test
    fun `discarding a float64 member method result runs cleanly`() {
        val source = """
            package test;
            public class Holder {
                public function half(): float64 { return 2.5; }
            }
            public function main(): int32 {
                var h = new Holder();
                h.half();
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "float64-member-call")
        assertEquals(0, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 成功路径⑤：丢弃宽类型结果出现在**循环体**内——栈失衡会在第二次迭代时暴露，
     * 因此该用例能覆盖「重复执行语句边界」的场景，而不只是一次性语句。
     */
    @Test
    fun `discarding wide results inside a loop stays balanced across iterations`() {
        val source = """
            package test;
            function df(): float64 { return 1.5; }
            public function main(): int32 {
                var i: int32 = 0;
                while (i < 3) {
                    df();
                    i = i + 1;
                }
                return i;
            }
        """.trimIndent()
        val out = compile(source, "float64-in-loop")
        assertEquals(3, invokeStatic(out, "test.main", "main"))
    }
}
