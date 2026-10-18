// ============================================================================
// NestedGenericsTest —— 嵌套泛型 `>>` / `>>>` 连写解析 + 移位/比较不受影响回归测试
// 所属层次：测试层；只依赖 JasperCompiler 的公开编译入口与 JDK 自带 URLClassLoader。
// 干什么：真编译 → 真加载 → 真执行，守住「类型实参语境的 `>>` 拆成两个 GT」这条链路
//   （FINDINGS #9），同时确认非类型语境的移位/比较语义一字不变。
// 产物位置：模块 build/scratch/nested-generics/ 下，不写仓库外路径。
//
// 为什么必须真跑而不是只编译：
//   解析修复的关键风险有两个——（a）把真正的移位 `a >> b` 误拆成 `> >` 导致编译失败或
//   语义改变；（b）拆分吞掉缺失 `>` 的语法错误。只断言「能编译」覆盖不到这两个反面，
//   因此这里同时做正向真执行、`> >` 空格写法回归、移位/比较的运行期取值、以及负向语法错误。
// ============================================================================
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
 * 做什么：Q22 / C20（嵌套泛型 `>>` token 流拆分）的端到端回归测试。
 * 什么时候用：改动 createParser 的 token 源/parser 装配、或词法层的 `>>`/`>>>` 相关规则时。
 * 前置条件：无额外依赖（只用 JDK 的 URLClassLoader）。
 * 失败模式：编译失败、加载/执行抛异常，或运行期取值不符时测试失败。
 * 为什么这样设计：该修复横跨「词法 token 形状」与「parser 语境」，单元测试无法覆盖
 *   「类型语境拆、表达式语境不拆」的分流，故用真编译 + 真执行 + 负向用例统一守门。
 */
class NestedGenericsTest {

    /**
     * 做什么：为某个用例准备干净的输出目录（位于模块 build/ 下）。
     * 什么时候用：每个正向测试开始处。
     * 前置条件：无。
     * 失败模式：创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：产物落在仓库内、可复查，且不写项目外临时目录。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "nested-generics", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /**
     * 做什么：编译一个 Jasper 源文件并断言成功，返回输出目录。
     * 什么时候用：所有需要真加载/真执行的正向用例。
     * 前置条件：source 是完整源文件文本（含顶层声明）。
     * 失败模式：编译失败时以断言失败报告聚合诊断。
     * 为什么这样设计：把「编译 + 失败信息格式化」收敛到一处，避免每个测试重复样板。
     */
    private fun compile(source: String, name: String): Path {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    /**
     * 做什么：编译并加载输出目录，调用 `test.main` 的无参静态 main，返回其结果。
     * 什么时候用：需要真执行生成代码、断言运行期取值时。
     * 前置条件：source 里存在 `public function main(): int32`。
     * 失败模式：编译失败、类/方法不存在或调用抛异常（含 JVM 链接期错误）时测试失败。
     * 为什么这样设计：把「编译 → 加载 → 调用」三步收敛，测试体只关心期望值。
     */
    private fun compileAndRun(source: String, name: String): Any? {
        val out = compile(source, name)
        URLClassLoader(arrayOf(out.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass("test.main")
            val target = clazz.methods.first { it.name == "main" && it.parameterCount == 0 }
            return target.invoke(null)
        }
    }

    /** 验收点 ①：局部变量类型 `List<List<int32>>` 连写可解析，程序真跑返回 7。 */
    @Test
    fun `local variable with nested generic double angle parses and runs`() {
        val source = """
            package test;
            public function main(): int32 {
                var xs: List<List<int32>>;
                return 7;
            }
        """.trimIndent()
        assertEquals(7, compileAndRun(source, "local-var"), "局部变量 List<List<int32>> 应可连写并真执行")
    }

    /** 验收点 ②：字段 / 形参 / 返回值中的 `Map<string, List<int32>>` 连写可解析并真执行。 */
    @Test
    fun `field parameter and return type with map of list parse and run`() {
        val source = """
            package test;
            public class Store {
                public var items: Map<string, List<int32>>;
                public constructor() { }
                public function get(k: string): Map<string, List<int32>> { return this.items; }
                public function put(k: string, v: Map<string, List<int32>>): void { this.items = v; }
            }
            public function main(): int32 {
                var s: Store = new Store();
                return 2;
            }
        """.trimIndent()
        assertEquals(2, compileAndRun(source, "map-of-list"), "Map<string, List<int32>> 在字段/形参/返回值都应可连写")
    }

    /** 验收点 ③：三层嵌套 `A<B<C<int32>>>`（`>>>`）连写可解析并真执行。 */
    @Test
    fun `three level nested generics triple angle parses and runs`() {
        val source = """
            package test;
            public class A<T> { public constructor() { } }
            public class B<T> { public constructor() { } }
            public class C<T> { public constructor() { } }
            public function main(): int32 {
                var v: A<B<C<int32>>>;
                return 3;
            }
        """.trimIndent()
        assertEquals(3, compileAndRun(source, "triple"), "A<B<C<int32>>> 应可连写并真执行")
    }

    /** 验收点 ④：泛型实例化 `new Box<List<int32>>()` 连写可解析并真执行。 */
    @Test
    fun `generic instantiation with nested argument parses and runs`() {
        val source = """
            package test;
            public class Box<T> { public constructor() { } }
            public function main(): int32 {
                var b: Box<List<int32>> = new Box<List<int32>>();
                return 4;
            }
        """.trimIndent()
        assertEquals(4, compileAndRun(source, "new-nested"), "new Box<List<int32>>() 应可连写并真执行")
    }

    /** 验收点 ⑤：`> >` 空格写法仍可解析（向后兼容，不能被本修复破坏）。 */
    @Test
    fun `spaced angle brackets still parse and run`() {
        val source = """
            package test;
            public function main(): int32 {
                var xs: List<List<int32> >;
                return 5;
            }
        """.trimIndent()
        assertEquals(5, compileAndRun(source, "spaced"), "List<List<int32> > 空格写法应继续可用")
    }

    /** 验收点 ⑥：移位运算符在非类型语境语义不变：`1 << 3` + `8 >> 1` + `16 >>> 2` = 8+4+4 = 16。 */
    @Test
    fun `shift operators keep their semantics`() {
        val source = """
            package test;
            public function main(): int32 {
                var a: int32 = 1 << 3;
                var b: int32 = 8 >> 1;
                var c: int32 = 16 >>> 2;
                return a + b + c;
            }
        """.trimIndent()
        assertEquals(16, compileAndRun(source, "shifts"), "a >> b / a >>> b 必须仍是移位，不能被拆成泛型尖括号")
    }

    /**
     * 验收点 ⑥（加强）：把泛型尖括号与移位放在同一表达式语境里，确认拆分只作用于类型位置。
     * `List<int32> >> 1`：先声明泛型类型，再对 int32 值右移，两个字面量相邻但语境不同。
     */
    @Test
    fun `generic type next to a shift expression disambiguates by context`() {
        val source = """
            package test;
            public function main(): int32 {
                var xs: List<int32>;
                var v: int32 = 16;
                return v >> 1;
            }
        """.trimIndent()
        assertEquals(8, compileAndRun(source, "mixed-shift"), "类型位置之后的表达式 >> 仍应是移位")
    }

    /** 验收点 ⑦：比较运算符语义不变：`1 < 2` 且 `2 > 1` 时返回 9。 */
    @Test
    fun `comparison operators keep their semantics`() {
        val source = """
            package test;
            public function main(): int32 {
                if (1 < 2) { if (2 > 1) { return 9; } }
                return 0;
            }
        """.trimIndent()
        assertEquals(9, compileAndRun(source, "comparisons"), "1 < 2、2 > 1 必须仍是比较")
    }

    /**
     * 验收点 ⑧（负向）：`List<List<int32>` 缺一个 `>` 仍必须报语法错误。
     * 为什么单列：拆分逻辑一旦「贪心补全」右尖括号，就会把这个真正的语法错误吞掉；
     * 本用例正是防止修复过度、把错误输入当作合法。
     */
    @Test
    fun `missing closing angle is still a syntax error`() {
        val source = """
            package test;
            public function main(): int32 {
                var xs: List<List<int32>;
                return 0;
            }
        """.trimIndent()
        val result = JasperCompiler().compile(source)
        assertTrue(
            result is JasperCompiler.CompileResult.Error,
            "缺少 '>' 的 List<List<int32> 应报语法错误，而不是被静默接受"
        )
    }
}
