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
 * 编译器缺陷 C25 的回归测试：**宽类型（float64 / int64）局部变量与参数按两个槽位分配，
 * 且后面出现分支时 ASM 仍能正确计算栈帧**。
 *
 * 背景：函数里先有引用类型局部、再声明 `float64` 局部、之后出现分支时，后端在
 * `visitMaxs`（COMPUTE_FRAMES）阶段崩溃：`Index -1 out of bounds for length 0`。
 * 这类缺陷只在帧计算或类加载验证时暴露，因此每个用例都走完整流水线并**真正加载并执行**
 * 生成的类，断言运行结果而不只是「编译成功」。
 * 产物写在本模块自己的 `build/scratch/` 目录下（不使用外部临时路径）。
 */
class WideLocalSlotRegressionTest {

    /** 建立并清空一个模块内 scratch 目录（构建产物只留在仓库内）。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "wide-local", name)
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

    /** 加载生成的类并调用其无参静态方法（顶层函数 main 编译到 `包名.main` 类）。 */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        val clazz = URLClassLoader(arrayOf(outputDir.toUri().toURL())).loadClass(className)
        val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
        return target.invoke(null)
    }

    /** 成功路径①：缺陷原始复现——引用局部 + `float64` 局部 + 分支，两条分支都返回正确值。 */
    @Test
    fun `float64 local after reference local survives a branch`() {
        val source = """
            package test;
            function g(s: string): float64 {
                var t: string = s;
                var d: float64 = 2.5;
                if (t == "x") { return 0.0; }
                return d;
            }
            public function main(): int32 {
                var r: int32 = 0;
                if (g("y") == 2.5) { r = r + 1; }
                if (g("x") == 0.0) { r = r + 10; }
                return r;
            }
        """.trimIndent()
        val out = compile(source, "float64-local-branch")
        assertEquals(11, invokeStatic(out, "test.main", "main"))
    }

    /**
     * 成功路径①b：缺陷登记时的原样程序——`main` 以语句形式丢弃 `g("y")` 的 `float64` 结果。
     * 崩溃的真正根因是丢弃宽结果时发了 `pop`（C26）：帧计算随后对已失衡的栈求类型才越界，
     * 槽位分配本身无误；保留此用例防止两者任一回退。
     */
    @Test
    fun `original C25 program with a discarded float64 call runs cleanly`() {
        val source = """
            package test;
            public function g(s: string): float64 {
                var t: string = s;
                var d: float64 = 2.5;
                if (t == "x") { return 0.0; }
                return d;
            }
            public function main(): int32 { g("y"); return 0; }
        """.trimIndent()
        val out = compile(source, "c25-original")
        assertEquals(0, invokeStatic(out, "test.main", "main"))
    }

    /** 成功路径②：`int64` 局部同理——引用局部 + `int64` 局部 + 分支。 */
    @Test
    fun `int64 local after reference local survives a branch`() {
        val source = """
            package test;
            function k(s: string): int64 {
                var t: string = s;
                var n: int64 = 40000000000;
                if (t == "x") { return 1; }
                return n;
            }
            public function main(): int32 {
                var r: int32 = 0;
                if (k("y") == 40000000000) { r = r + 1; }
                if (k("x") == 1) { r = r + 10; }
                return r;
            }
        """.trimIndent()
        val out = compile(source, "int64-local-branch")
        assertEquals(11, invokeStatic(out, "test.main", "main"))
    }

    /** 成功路径③：`float64` 参数后跟引用参数——参数槽号也必须按宽度推进，分支里两者都读对。 */
    @Test
    fun `float64 parameter followed by a reference parameter keeps both readable`() {
        val source = """
            package test;
            function h(a: float64, s: string): string {
                if (a > 1.0) { return s; }
                return "small";
            }
            public function main(): int32 {
                var r: int32 = 0;
                if (h(2.5, "big") == "big") { r = r + 1; }
                if (h(0.5, "big") == "small") { r = r + 10; }
                return r;
            }
        """.trimIndent()
        val out = compile(source, "float64-param-then-ref")
        assertEquals(11, invokeStatic(out, "test.main", "main"))
    }

    /** 成功路径④：两个宽局部连续声明再加一个 `int32` 局部，循环里反复读写三者。 */
    @Test
    fun `two wide locals and an int32 local stay distinct across a loop`() {
        val source = """
            package test;
            public function main(): int32 {
                var a: float64 = 0.5;
                var b: int64 = 3;
                var i: int32 = 0;
                while (i < 4) {
                    a = a + 1.0;
                    b = b * 2;
                    i = i + 1;
                }
                var r: int32 = 0;
                if (a == 4.5) { r = r + 1; }
                if (b == 48) { r = r + 10; }
                if (i == 4) { r = r + 100; }
                return r;
            }
        """.trimIndent()
        val out = compile(source, "two-wide-locals-loop")
        assertEquals(111, invokeStatic(out, "test.main", "main"))
    }

    /** 成功路径⑤：实例方法（槽 0 是 `this`）里引用局部 + 宽局部 + 分支。 */
    @Test
    fun `wide local inside an instance method survives a branch`() {
        val source = """
            package test;
            public class Meter {
                public function scale(s: string): float64 {
                    var t: string = s;
                    var d: float64 = 1.25;
                    if (t == "zero") { return 0.0; }
                    return d * 2.0;
                }
            }
            public function main(): int32 {
                var m = new Meter();
                var r: int32 = 0;
                if (m.scale("one") == 2.5) { r = r + 1; }
                if (m.scale("zero") == 0.0) { r = r + 10; }
                return r;
            }
        """.trimIndent()
        val out = compile(source, "wide-local-instance-method")
        assertEquals(11, invokeStatic(out, "test.main", "main"))
    }
}
