// ============================================================================
// DictCollectionDispatchTest.kt —— `dict<K,V>` 类型与集合成员分派的端到端回归
// 所属层次：测试层。只依赖 JasperCompiler 的公开编译入口、JDK URLClassLoader 与 javap。
//
// 干什么：
//   1. 锁定 `dict<K,V>` 作为字段 / 形参 / 返回值 / 局部变量时可用，且支持嵌套形式
//      `dict<string,List<int32>>`；
//   2. 锁定构造位置 `new dict<K,V>()`（含嵌套 `>>`）可解析、可执行；
//   3. 锁定 `get` 返回 V、`size` 返回 int32、`put` 的返回值被丢弃时不会按 V 解箱；
//   4. 锁定同一个类里数组 `length` 与集合 `size()` 按接收者真实类型分派；
//   5. 锁定非法组合（集合属性、实参类型、数组集合方法）在编译期报语义错误。
//
// 为什么必须真编译 + 真执行：
//   集合缺陷的典型症状是「检查器放行、翻译器拼错描述符、JVM 链接期才炸」
//   （NoSuchMethodError / VerifyError / NPE）。只断言能编译覆盖不到这些风险；
//   因此同时断言运行结果与 javap 里的真实 JDK 签名。
//
// 产物位置：模块 build/scratch/dict-collection/ 下，不写仓库外路径。
// ============================================================================

package jasper

import jasper.compiler.CompileError
import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 做什么：`dict<K,V>` 类型位置与集合成员分派的端到端回归测试集合。
 * 什么时候用：改动 BuiltinCollections、TypeChecker 的集合分派、translator 的
 *   集合调用/字典字面量/构造降级，或 JvmIrBackend 的 `c:` 平台签名路径时。
 * 前置条件：测试 JVM 提供 javap（JDK 而非裸 JRE）。
 * 失败模式：编译失败、运行期抛链接/类型错误，或 javap 中的 owner/描述符不符合
 *   JDK 真实签名时，对应用例失败。
 * 为什么这样设计：集合语义横跨「类型检查 → IR 降级 → 字节码描述符」三层，单一层
 *   的单元测试无法防止跨层漂移；这里用真实编译产物作为唯一事实证据。
 */
class DictCollectionDispatchTest {

    /**
     * 做什么：为单个用例准备干净的输出目录（固定在模块 build/ 下）。
     * 什么时候用：每个测试开始处。
     * 前置条件：无。
     * 失败模式：创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：产物落在仓库内、可复查，且不写项目外临时目录。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "dict-collection", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /**
     * 做什么：编译单文件源码并断言成功，返回输出目录。
     * 什么时候用：所有正向用例。
     * 前置条件：source 是完整源文件文本。
     * 失败模式：编译失败时以断言失败报告聚合诊断。
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
     * 做什么：加载输出目录并调用 `test.main` 的无参静态 main。
     * 什么时候用：需要真执行生成代码、断言运行期取值时。
     * 前置条件：source 里存在 `public function main(): int32`。
     * 失败模式：类/方法不存在或调用抛异常（含 JVM 链接期错误）时测试失败。
     */
    private fun runMain(outputDir: Path): Any? {
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass("test.main")
            val target = clazz.methods.first { it.name == "main" && it.parameterCount == 0 }
            return target.invoke(null)
        }
    }

    /**
     * 做什么：对生成的 class 运行 `javap -p -c`，返回反汇编文本并把证据写入 build/。
     * 什么时候用：需要断言集合调用的 owner / 真实描述符形状时。
     * 前置条件：测试 JVM 的 java.home 下存在 bin/javap。
     * 失败模式：javap 退出码非 0 时断言失败并附上完整输出。
     */
    private fun javap(outputDir: Path, className: String, caseName: String): String {
        val javap = Paths.get(System.getProperty("java.home"), "bin", "javap").toString()
        val process = ProcessBuilder(
            javap, "-p", "-c", "-classpath", outputDir.toAbsolutePath().toString(), className
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        Files.writeString(outputDir.resolve("javap-${className.replace('.', '_')}-$caseName.txt"), output)
        assertEquals(0, exitCode, "javap failed for $className:\n$output")
        return output
    }

    /**
     * 做什么：断言一段源码编译失败，且失败是**语义诊断**而不是编译器内部错误。
     * 什么时候用：非法集合成员/实参组合的负向用例。
     * 前置条件：source 是完整源文件文本。
     * 失败模式：编译意外成功、或错误列表含 CompileError.Internal 时断言失败。
     * 为什么这样设计：把「必须报错」与「不能崩溃」两件事收敛到一处，避免每个负向
     *   用例重复样板。
     */
    private fun assertSemanticError(source: String, label: String) {
        val result = JasperCompiler().compile(source)
        val error = result as? JasperCompiler.CompileResult.Error
        assertTrue(error != null, "$label 应编译失败")
        assertFalse(
            error!!.errors.any { it is CompileError.Internal },
            "$label 必须是语义诊断而不是内部错误：${error.message}"
        )
        assertTrue(error.message.isNotBlank(), "$label 的诊断文本不应为空")
    }

    /**
     * 验收点 ①：`dict<string,List<int32>>` 作为字段/形参/返回值/局部变量时全部可用，
     * 嵌套泛型 `>>` 与 `get` 的值类型均正确。
     *
     * 期望值：局部字典 size=1 → 10；`got.get(0)=2`、`got.get(1)=3`；形参字典 size=1。
     * 10 + 2 + 3 + 1 = 16。
     */
    @Test
    fun `nested dict type works in field parameter return and local positions`() {
        val source = """
            package test;
            public class Registry {
                public var rows: dict<string, List<int32>>;
                public constructor() { this.rows = dict{}; }
                public function add(k: string, v: List<int32>): void { this.rows.put(k, v); }
                public function get(k: string): List<int32> { return this.rows.get(k); }
                public function count(): int32 { return this.rows.size(); }
                public function snapshot(): dict<string, List<int32>> { return this.rows; }
                public function countOf(other: dict<string, List<int32>>): int32 { return other.size(); }
            }
            public function main(): int32 {
                var reg: Registry = new Registry();
                var xs: MutableList<int32> = new MutableList<int32>();
                xs.add(2);
                xs.add(3);
                reg.add("k", xs);
                var got: List<int32> = reg.get("k");
                var local: dict<string, List<int32>> = reg.snapshot();
                return local.size() * 10 + got.get(0) + got.get(1) + reg.countOf(local);
            }
        """.trimIndent()
        val out = compile(source, "nested-dict-positions")
        assertEquals(
            16,
            runMain(out),
            "dict<string,List<int32>> 在字段/形参/返回值/局部变量位置都应可用"
        )
        // get 调用发生在 Registry.get 内，main 只调用 Registry 的声明签名；
        // 因此证据要从 Registry 的字节码取，而不是 main。
        val code = javap(out, "test.Registry", "nested-dict-positions")
        assertTrue(
            code.contains("Method java/util/HashMap.get:(Ljava/lang/Object;)Ljava/lang/Object;"),
            "dict.get 必须使用 HashMap 的真实签名:\n$code"
        )
    }

    /**
     * 验收点 ②：构造位置 `new dict<K,V>()` 可解析；嵌套实参 `new dict<string,List<int32>>()`
     * 同时覆盖 `>>` token 拆分与构造位置关键字改写。
     */
    @Test
    fun `new dict constructor position supports simple and nested type arguments`() {
        val source = """
            package test;
            public function main(): int32 {
                var d: dict<string, int32> = new dict<string, int32>();
                d.put("a", 4);
                var nested: dict<string, List<int32>> = new dict<string, List<int32>>();
                nested.put("k", new MutableList<int32>());
                return d.get("a") * 10 + d.size() + nested.size();
            }
        """.trimIndent()
        val out = compile(source, "new-dict")
        assertEquals(42, runMain(out), "new dict<K,V>() 与嵌套实参构造应可编译并真执行")
        val code = javap(out, "test.main", "new-dict")
        assertTrue(
            code.contains("class java/util/HashMap"),
            "new dict 必须实例化 java.util.HashMap:\n$code"
        )
    }

    /**
     * 验收点 ③：`put` 的返回值被丢弃时不得按 V 解箱。
     *
     * 为什么单列：HashMap.put 返回**旧值**，新键时为 null；若调用点被记为 V，
     *   语句位置也会生成 `Integer.intValue()` 并对 null 抛 NPE。这里连续覆盖
     *   「新键」「覆盖已有键」两种路径，并在 javap 中确认 put 后直接 POP。
     */
    @Test
    fun `put result is discarded without unboxing the previous value`() {
        val source = """
            package test;
            public function main(): int32 {
                var d: dict<string, int32> = dict{};
                d.put("a", 1);
                d.put("a", 2);
                d.put("b", 3);
                if (d.get("a") != 2) { return 1; }
                return d.size();
            }
        """.trimIndent()
        val out = compile(source, "put-discard")
        assertEquals(2, runMain(out), "put 的返回值被丢弃时不应解箱旧值")
        val code = javap(out, "test.main", "put-discard")
        assertTrue(
            code.contains("Method java/util/HashMap.put:(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            "dict.put 必须使用 HashMap 的真实签名:\n$code"
        )
        // 断言 put 调用之后紧跟 POP（丢弃 Object 返回值），而不是 Integer.intValue。
        val putThenPop = Regex(
            "HashMap\\.put:\\(Ljava/lang/Object;Ljava/lang/Object;\\)Ljava/lang/Object;.*\\n\\s*\\d+: pop"
        )
        assertTrue(
            putThenPop.containsMatchIn(code),
            "put 的返回值必须被 POP 丢弃，不能按值类型解箱:\n$code"
        )
    }

    /**
     * 验收点 ④：同一个类里数组 `length` 与集合 `size()` 共存，按接收者真实类型分派。
     */
    @Test
    fun `array length and dict size coexist in the same class`() {
        val source = """
            package test;
            public class Mixed {
                public function combined(xs: int32[], d: dict<string, int32>): int32 {
                    return xs.length * 10 + d.size();
                }
            }
            public function main(): int32 {
                var m: Mixed = new Mixed();
                var xs: int32[] = [1, 2, 3];
                var d: dict<string, int32> = dict{ "a": 1, "b": 2 };
                return m.combined(xs, d);
            }
        """.trimIndent()
        val out = compile(source, "length-vs-size")
        assertEquals(32, runMain(out), "数组 length 与 dict.size() 应各自分派且结果正确")
        val code = javap(out, "test.Mixed", "length-vs-size")
        assertTrue(code.contains("arraylength"), "数组长度必须编译成 ARRAYLENGTH:\n$code")
        assertTrue(
            code.contains("Method java/util/HashMap.size:()I"),
            "dict.size 必须生成 HashMap.size()I:\n$code"
        )
    }

    /**
     * 验收点 ⑤：`get` 的静态类型是 V；赋给 int32 时按 Integer.intValue 拆箱。
     */
    @Test
    fun `get returns the value type and unboxes it`() {
        val source = """
            package test;
            public function main(): int32 {
                var d: dict<string, int32> = dict{ "a": 3, "b": 4 };
                return d.get("a") + d.size();
            }
        """.trimIndent()
        val out = compile(source, "get-value-type")
        assertEquals(5, runMain(out), "dict.get 应返回 int32 并参与算术")
        val code = javap(out, "test.main", "get-value-type")
        assertTrue(
            code.contains("java/lang/Integer.intValue"),
            "dict.get 返回 int32 时必须拆箱:\n$code"
        )
    }

    /**
     * 验收点 ⑥（负向）：集合属性误用、实参类型不匹配、数组调用集合 API 都必须
     * 在编译期报语义错误，不能留到运行期变成 NoSuchMethodError / ClassCastException。
     */
    @Test
    fun `invalid collection member combinations are compile time semantic errors`() {
        val cases = listOf(
            "var d: dict<string, int32> = dict{}; d.length;" to "dict.length",
            "var d: dict<string, int32> = dict{}; d.get(1);" to "dict.get(int32)",
            "var d: dict<string, int32> = dict{}; d.put(\"a\", \"b\");" to "dict.put(string,string)",
            "var xs: int32[] = [1]; xs.size();" to "array.size()",
            "var xs: int32[] = [1]; xs.get(0);" to "array.get(0)"
        )
        for ((body, label) in cases) {
            val source = """
                package test;
                public function main(): int32 {
                    $body
                    return 0;
                }
            """.trimIndent()
            assertSemanticError(source, label)
        }
    }
}
