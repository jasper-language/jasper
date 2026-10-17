// =============================================================================
// VarargsTest.kt —— 真 varargs（语法裁决②，用户 2026-10-17 选 A）的回归测试
// =============================================================================
// 职责：固定 JDBC 探针 #10 的修复——`args: T...` 必须是真正的可变参数：
//   1. 声明侧：形参在函数体内的类型是元素数组 `T[]`，支持 `.length`、下标与 for-in；
//   2. 调用侧：多余的实参被打包成数组（零个多余实参 ⇒ 空数组）；
//   3. 数组直传：实参个数恰好对齐且最后一个实参本身就是数组时，原样传入（不再套一层）；
//   4. 诊断：`...` 只允许出现在最后一个形参上；打包实参的元素类型不兼容要报错。
//
// 所属层次：测试层，只依赖 JasperCompiler 的公开入口；走真实管线
//   （ANTLR -> AST -> 检查 -> IR -> JVM 字节码），写盘后真加载、真执行。
// 失败模式：编译失败 / VerifyError / 返回值不符，均直接使对应用例失败。
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

class VarargsTest {

    /** 为单个用例准备干净输出目录；固定在本模块 `build/` 下。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "varargs", name)
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

    /** 编译源码并要求失败，返回诊断文本。 */
    private fun compileError(source: String, name: String): String {
        val result = JasperCompiler().compileToDirWithIr(source, scratchDir(name))
        val err = result as? JasperCompiler.CompileResult.Error
        assertTrue(err != null, "期望编译失败，实际成功：$name")
        return err!!.message
    }

    /** 加载 `className` 的无参静态方法并调用，返回其结果。 */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        val clazz = URLClassLoader(arrayOf(outputDir.toUri().toURL())).loadClass(className)
        val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
        return target.invoke(null)
    }

    /** 顶层函数：展开调用打包成数组；函数体内可 for-in 遍历。 */
    @Test
    fun `top-level varargs packs spread arguments`() {
        val out = compile(
            """
            package test;
            function sum(xs: int32...): int32 {
                var total: int32 = 0;
                for (x in xs) { total = total + x; }
                return total;
            }
            public function main(): int32 { return sum(1, 2, 3, 4); }
            """.trimIndent(),
            "spread",
        )
        assertEquals(10, invokeStatic(out, "test.main", "main"))
    }

    /** 固定形参 + 零个多余实参 ⇒ 空数组（`.length == 0`）。 */
    @Test
    fun `zero extra arguments become an empty array`() {
        val out = compile(
            """
            package test;
            function count(prefix: string, xs: int32...): int32 { return xs.length; }
            public function main(): int32 { return count("a") * 10 + count("b", 7, 8); }
            """.trimIndent(),
            "empty",
        )
        assertEquals(2, invokeStatic(out, "test.main", "main"))
    }

    /** 实参个数对齐且最后一个实参就是数组 ⇒ 原样传入，不再套一层。 */
    @Test
    fun `array argument is passed through unchanged`() {
        val out = compile(
            """
            package test;
            function first(xs: int32...): int32 { return xs[0] * 100 + xs.length; }
            public function main(): int32 {
                var arr: int32[] = new int32[] { 5, 6, 7 };
                return first(arr);
            }
            """.trimIndent(),
            "passthrough",
        )
        assertEquals(503, invokeStatic(out, "test.main", "main"))
    }

    /** 成员方法 + `any...`：JDBC 探针 #10 的原始形状（`s.u("x", 1, "two", 3)`）。 */
    @Test
    fun `member any varargs from jdbc probe`() {
        val out = compile(
            """
            package test;
            public class S {
                public function u(sql: string, args: any...): int32 { return args.length; }
                public function second(args: any...): string { var v: any = args[1]; return cast v as string; }
            }
            public function main(): int32 {
                var s = new S();
                var n: int32 = s.u("x", 1, "two", 3);
                var t: string = s.second(1, "two");
                if (t == "two") { return n; }
                return -1;
            }
            """.trimIndent(),
            "member-any",
        )
        assertEquals(3, invokeStatic(out, "test.main", "main"))
    }

    /** `...` 只能出现在最后一个形参上。 */
    @Test
    fun `varargs must be the last parameter`() {
        val msg = compileError(
            """
            package test;
            function bad(xs: int32..., tail: string): int32 { return 0; }
            public function main(): int32 { return 0; }
            """.trimIndent(),
            "not-last",
        )
        assertTrue(msg.contains("last parameter"), "诊断应指出 varargs 必须是最后一个形参，实际：$msg")
    }

    /** 打包实参的元素类型不兼容 ⇒ 编译期报错。 */
    @Test
    fun `incompatible packed argument is rejected`() {
        val msg = compileError(
            """
            package test;
            function sum(xs: int32...): int32 { return xs.length; }
            public function main(): int32 { return sum(1, "two"); }
            """.trimIndent(),
            "bad-element",
        )
        assertTrue(msg.contains("sum"), "诊断应指向 sum 调用，实际：$msg")
    }
}
