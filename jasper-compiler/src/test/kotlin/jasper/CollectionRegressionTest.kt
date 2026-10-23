// ============================================================================
// CollectionRegressionTest.kt —— 语言内建集合（dict/List/MutableList/Map）端到端回归
// 所属层次：测试层。只依赖 JasperCompiler 的公开编译入口、JDK URLClassLoader 与 javap。
//
// 干什么：
//   1. 锁定 `dict<string,int32>` 作为字段/形参/返回值时，get/size/containsKey/put
//      的语义与真实字节码描述符；
//   2. 证明数组 `length` 与集合 `size()` 按真实接收者类型分派，互不串线；
//   3. 证明泛型集合可用于 for-in，且嵌套 `dict<string,List<int32>>` 的读写与迭代
//      都能真跑；
//   4. 证明 `new List`/`new MutableList`/`new Map`/`new dict` 落到具体 JDK 实现。
//
// 为什么必须真编译 + 真执行：
//   集合缺陷的典型症状是「检查器放行、翻译器拼错描述符、JVM 链接期才炸」
//   （NoSuchMethodError / VerifyError / ClassCastException）。只断言能编译无法
//   覆盖这些风险；因此同时断言运行结果与 javap 里的真实 JDK 签名。
//
// 产物位置：模块 build/scratch/collections/ 下，不写仓库外路径。
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
 * 做什么：语言内建集合的端到端回归测试集合。
 * 什么时候用：改动 BuiltinCollections、TypeChecker 的集合分派、translator 的集合调用/
 *   for-in/new 降级，或 JvmIrBackend 的 `c:` 平台签名路径时。
 * 前置条件：测试 JVM 提供 javap（JDK 而非裸 JRE）。
 * 失败模式：编译失败、运行期抛链接/类型错误、或 javap 中的 owner/描述符不符合
 *   JDK 真实签名时，对应用例失败。
 * 为什么这样设计：集合语义横跨「类型检查 → IR 降级 → 字节码描述符」三层，单一层
 *   的单元测试无法防止跨层漂移；这里用真实编译产物作为唯一事实证据。
 */
class CollectionRegressionTest {

    /**
     * 做什么：为单个用例准备干净的输出目录（固定在模块 build/ 下）。
     * 什么时候用：每个测试开始处。
     * 前置条件：无。
     * 失败模式：创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：产物落在仓库内、可复查，且不写项目外临时目录。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "collections", name)
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

    // ------------------------------------------------------------------
    // 1. dict<string,int32> 作为字段/形参/返回值 + get/size/containsKey/put。
    // ------------------------------------------------------------------
    @Test
    fun `dict field parameter return and members run with typed values`() {
        val source = """
            package test;
            public class Inventory {
                public var counts: dict<string, int32>;
                public constructor() { this.counts = dict{}; }
                public function seed(k: string, v: int32): void { this.counts.put(k, v); }
                public function count(): int32 { return this.counts.size(); }
                public function has(k: string): bool { return this.counts.containsKey(k); }
                public function valueOf(k: string): int32 { return this.counts.get(k); }
                public function snapshot(): dict<string, int32> { return this.counts; }
            }
            public function main(): int32 {
                var inv: Inventory = new Inventory();
                inv.seed("a", 3);
                inv.seed("b", 4);
                if (!inv.has("a")) { return 1; }
                var d: dict<string, int32> = inv.snapshot();
                if (!d.containsKey("b")) { return 2; }
                return inv.count() * 10 + d.get("b") + inv.valueOf("a");
            }
        """.trimIndent()
        val out = compile(source, "dict-field-param-return")
        // 2 个键 → size()=2；2*10 + 4 + 3 = 27。
        assertEquals(27, runMain(out), "dict<string,int32> 的字段/形参/返回值与成员调用应真跑")
        val code = javap(out, "test.main", "dict-field-param-return")
        val inventoryCode = javap(out, "test.Inventory", "dict-field-param-return")
        assertTrue(
            inventoryCode.contains("Method java/util/HashMap.put:(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            "dict.put 必须使用 HashMap 的真实签名:\n$inventoryCode"
        )
        assertTrue(
            code.contains("Method java/util/HashMap.get:(Ljava/lang/Object;)Ljava/lang/Object;"),
            "dict.get 必须使用 HashMap 的真实签名:\n$code"
        )
        // 反向证据：不得回退到「返回接收者」的旧容器兜底形状。
        assertTrue(
            !inventoryCode.contains("put:(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/HashMap;"),
            "dict.put 不得返回接收者类型:\n$inventoryCode"
        )
    }

    // ------------------------------------------------------------------
    // 2. 数组 length 与集合 size() 共存，按真实接收者分派。
    // ------------------------------------------------------------------
    @Test
    fun `array length and collection size coexist without misdispatch`() {
        val source = """
            package test;
            public function main(): int32 {
                var xs: int32[] = [1, 2, 3];
                var ys: MutableList<int32> = new MutableList<int32>();
                ys.add(5);
                ys.add(6);
                if (xs.length != 3) { return 1; }
                if (ys.size() != 2) { return 2; }
                if (ys.get(1) != 6) { return 3; }
                return xs.length * 10 + ys.size();
            }
        """.trimIndent()
        val out = compile(source, "length-vs-size")
        assertEquals(32, runMain(out), "数组 length 与集合 size() 应各自分派且结果正确")
        val code = javap(out, "test.main", "length-vs-size")
        // 数组仍走 ARRAYLENGTH，而不是拼出不存在的 size() 调用。
        assertTrue(code.contains("arraylength"), "数组长度必须编译成 ARRAYLENGTH:\n$code")
        // MutableList.size 走 java.util.List 接口真实签名 ()I。
        assertTrue(
            code.contains("InterfaceMethod java/util/List.size:()I"),
            "集合 size 必须是 List.size()I 的接口调用:\n$code"
        )
    }

    // ------------------------------------------------------------------
    // 3. 泛型集合用于 for-in；嵌套 dict<string,List<int32>> 读写 + 双迭代。
    // ------------------------------------------------------------------
    @Test
    fun `nested generic dict of list supports put get and for in`() {
        val source = """
            package test;
            public function main(): int32 {
                var rows: dict<string, List<int32>> = dict{};
                var xs: MutableList<int32> = new MutableList<int32>();
                xs.add(2);
                xs.add(3);
                rows.put("k", xs);
                var got: List<int32> = rows.get("k");
                var sum: int32 = 0;
                for (v in got) { sum = sum + v; }
                for (k in rows) { if (k == "k") { sum = sum + 1; } }
                return sum;
            }
        """.trimIndent()
        val out = compile(source, "nested-for-in")
        // 序列元素 2+3=5，映射键命中 +1 → 6。
        assertEquals(6, runMain(out), "dict<string,List<int32>> 的嵌套泛型读写与 for-in 应真跑")
        val code = javap(out, "test.main", "nested-for-in")
        // List 的 for-in 走 get/size 下标循环；Map 的 for-in 走 keySet().iterator()。
        assertTrue(
            code.contains("InterfaceMethod java/util/List.get:(I)Ljava/lang/Object;"),
            "List for-in 必须调用 List.get(I)Object:\n$code"
        )
        assertTrue(
            code.contains("keySet:()Ljava/util/Set;"),
            "Map for-in 必须调用 keySet() 取得键视图:\n$code"
        )
    }

    // ------------------------------------------------------------------
    // 4. dict 字面量在带类型上下文中保留 K/V，get 后按值类型拆箱。
    // ------------------------------------------------------------------
    @Test
    fun `dict literal keeps key value types through get`() {
        val source = """
            package test;
            public function main(): int32 {
                var d: dict<string, int32> = dict{ "a": 3, "b": 4 };
                return d.get("a") + d.size();
            }
        """.trimIndent()
        val out = compile(source, "dict-literal")
        assertEquals(5, runMain(out), "dict 字面量应保留值类型并支持 get/size")
        val code = javap(out, "test.main", "dict-literal")
        assertTrue(
            code.contains("Method java/util/HashMap.put:(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            "dict 字面量初始化必须使用 HashMap.put 真实签名:\n$code"
        )
        // 返回值是 int32：get 得到 Object 后必须拆箱（Integer.intValue）。
        assertTrue(
            code.contains("java/lang/Integer.intValue"),
            "dict.get 返回 int32 时必须拆箱:\n$code"
        )
    }

    // ------------------------------------------------------------------
    // 5. new List/MutableList/Map/dict 落到具体 JDK 实现，且可实际使用。
    // ------------------------------------------------------------------
    @Test
    fun `new collection interfaces allocate concrete jdk implementations`() {
        val source = """
            package test;
            public function main(): int32 {
                var a: MutableList<int32> = new MutableList<int32>();
                a.add(1);
                a.add(2);
                var b: Map<string, int32> = new Map<string, int32>();
                b.put("x", 9);
                var c: dict<string, int32> = dict{ "y": 4 };
                return a.size() * 100 + b.get("x") * 10 + c.get("y");
            }
        """.trimIndent()
        val out = compile(source, "new-collections")
        assertEquals(294, runMain(out), "new List/MutableList/Map/dict 应实例化可用集合")
        val code = javap(out, "test.main", "new-collections")
        assertTrue(
            code.contains("class java/util/ArrayList"),
            "new MutableList 必须实例化 java.util.ArrayList:\n$code"
        )
        assertTrue(
            code.contains("class java/util/HashMap"),
            "new Map/dict 必须实例化 java.util.HashMap:\n$code"
        )
    }
}
