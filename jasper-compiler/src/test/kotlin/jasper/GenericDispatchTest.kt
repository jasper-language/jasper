// ============================================================================
// GenericDispatchTest —— JVM 泛型擦除 + 桥接方法 + 泛型接口分派回归测试
// 所属层次：测试层；只依赖 JasperCompiler 的公开编译入口与 JDK 自带 javap/URLClassLoader。
// 干什么：真编译 → 真加载 → 真执行 + javap 字节码形状断言，守住「泛型接口经接口变量
//   分派」这条链路（FINDINGS #1 / R2DBC #3/#8 的根因）。
// 产物位置：模块 build/scratch/generic-dispatch/ 下，不写仓库外路径。
//
// 为什么必须真跑而不是只编译：
//   泛型擦除缺陷（错误描述符 / 缺桥接方法）在编译期完全看不出问题，只在 JVM 链接期
//   以 VerifyError / NoSuchMethodError / AbstractMethodError 暴露。因此这里既断言运行
//   结果，也直接用 javap 断言描述符、桥接方法与 invokeinterface/checkcast 指令形状。
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
 * 做什么：Q20（泛型擦除 / 桥接方法 / 泛型接口分派）的端到端回归测试。
 * 什么时候用：改动 TypeMapper 的类型擦除、translator 的调用点描述符、或后端的方法发射时。
 * 前置条件：测试 JVM 提供 javap（JDK 而非裸 JRE）。
 * 失败模式：编译失败、加载/执行抛 VerifyError/NoSuchMethodError/AbstractMethodError，
 *   或 javap 形状不符时测试失败。
 * 为什么这样设计：该缺陷横跨 TypeMapper/translator/backend 三层，单元行为无法防止
 *   「编译通过但运行期炸」，故统一用真编译 + 真运行 + javap 证据守门。
 */
class GenericDispatchTest {

    /**
     * 做什么：为某个用例准备干净的输出目录（位于模块 build/ 下）。
     * 什么时候用：每个测试开始处。
     * 前置条件：无。
     * 失败模式：创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：产物落在仓库内、可复查，且不写项目外临时目录。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "generic-dispatch", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /**
     * 做什么：编译一个（可含多个顶层声明的）Jasper 源文件并断言成功。
     * 什么时候用：所有正向用例。
     * 前置条件：source 是完整源文件文本。
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
     * 做什么：对生成的 class 运行 `javap -v -p`，返回反汇编文本并把证据写入 build/。
     * 什么时候用：需要断言描述符、方法标志（ACC_BRIDGE）或指令形状时。
     * 前置条件：测试 JVM 的 java.home 下存在 bin/javap。
     * 失败模式：javap 退出码非 0 时断言失败并附上完整输出。
     * 为什么这样设计：javap 是 JDK 自带、与 ASM 版本无关的独立证据；`-v` 同时给出
     *   `descriptor:` 行、方法 `flags:`（含 ACC_BRIDGE）与字节码指令。
     */
    private fun javap(outputDir: Path, className: String, caseName: String): String {
        val javap = Paths.get(System.getProperty("java.home"), "bin", "javap").toString()
        val process = ProcessBuilder(
            javap, "-v", "-p", "-classpath", outputDir.toAbsolutePath().toString(), className
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        Files.writeString(outputDir.resolve("javap-${className.replace('.', '_')}-$caseName.txt"), output)
        assertEquals(0, exitCode, "javap failed for $className:\n$output")
        return output
    }

    /**
     * 做什么：加载输出目录并调用某类的无参静态方法。
     * 什么时候用：需要真执行生成代码时。
     * 前置条件：outputDir 下有已编译的 className.class。
     * 失败模式：类/方法不存在或调用抛异常时测试失败（JVM 链接期错误会在此暴露）。
     */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass(className)
            val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
            return target.invoke(null)
        }
    }

    /**
     * 验收点 ①②③④：`Repo<T>` 经接口变量分派，接口描述符擦除、实现类补桥、调用点补 checkcast。
     *
     * 断言：
     *   - 真执行 `probes` 等价用例返回 2（无 VerifyError）；
     *   - `Repo.save` 描述符是 `(Ljava/lang/Object;)V`、`Repo.get` 是 `(I)Ljava/lang/Object;`；
     *   - `Mem` 有桥接方法（含 ACC_BRIDGE 标志）；
     *   - `main` 用 `invokeinterface Repo.save:(Ljava/lang/Object;)V`，且 `get` 后有 `checkcast test/Person`。
     */
    @Test
    fun `generic interface dispatch through an interface variable runs`() {
        val source = """
            package test;
            public interface Repo<T> {
                public function save(x: T): void;
                public function get(id: int32): T;
            }
            public class Person { public var id: int32; public constructor(id: int32) { this.id = id; } }
            public class Mem implements Repo<Person> {
                public function save(x: Person): void { }
                public function get(id: int32): Person { return new Person(id); }
            }
            public function main(): int32 {
                var r: Repo<Person> = new Mem();
                r.save(new Person(1));
                var p: Person = r.get(2);
                return p.id;
            }
        """.trimIndent()
        val out = compile(source, "repo")

        // 真跑：接口变量分派返回 get(2).id = 2。
        assertEquals(2, invokeStatic(out, "test.main", "main"), "经接口变量分派应返回 2（无 VerifyError）")

        // 接口方法声明必须是擦除后的描述符。
        val repoCode = javap(out, "test.Repo", "repo")
        assertTrue(
            repoCode.contains("descriptor: (Ljava/lang/Object;)V"),
            "Repo.save 描述符应为 (Ljava/lang/Object;)V:\n$repoCode"
        )
        assertTrue(
            repoCode.contains("descriptor: (I)Ljava/lang/Object;"),
            "Repo.get 描述符应为 (I)Ljava/lang/Object;:\n$repoCode"
        )

        // 实现类必须补桥接方法。
        val memCode = javap(out, "test.Mem", "repo")
        assertTrue(memCode.contains("ACC_BRIDGE"), "Mem 应有 ACC_BRIDGE 桥接方法:\n$memCode")
        assertTrue(
            memCode.contains("descriptor: (Ljava/lang/Object;)V"),
            "Mem 应有 save(Ljava/lang/Object;)V 桥:\n$memCode"
        )
        assertTrue(
            memCode.contains("descriptor: (I)Ljava/lang/Object;"),
            "Mem 应有 get(I)Ljava/lang/Object; 桥:\n$memCode"
        )
        // 反射复核 ACC_BRIDGE 语义（比 javap 文本更强的 JVM 级证据）。
        URLClassLoader(arrayOf(out.toUri().toURL())).use { loader ->
            val mem = loader.loadClass("test.Mem")
            val bridgeSaves = mem.declaredMethods.filter {
                it.isBridge && it.name == "save" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Any::class.java
            }
            val bridgeGets = mem.declaredMethods.filter {
                it.isBridge && it.name == "get" && it.returnType == Any::class.java
            }
            assertTrue(bridgeSaves.isNotEmpty(), "Mem.save(Object) 桥应被反射识别为 bridge")
            assertTrue(bridgeGets.isNotEmpty(), "Mem.get(int):Object 桥应被反射识别为 bridge")
        }

        // 调用点：接口描述符 + 返回处 checkcast。
        val mainCode = javap(out, "test.main", "repo")
        assertTrue(
            mainCode.contains("invokeinterface") && mainCode.contains("test/Repo.save:(Ljava/lang/Object;)V"),
            "main 应用 invokeinterface Repo.save:(Ljava/lang/Object;)V:\n$mainCode"
        )
        assertTrue(
            mainCode.contains("test/Repo.get:(I)Ljava/lang/Object;"),
            "main 应用 invokeinterface Repo.get:(I)Ljava/lang/Object;:\n$mainCode"
        )
        assertTrue(
            mainCode.contains("checkcast") && mainCode.contains("test/Person"),
            "main 在 get 后应补 checkcast test/Person:\n$mainCode"
        )
    }

    /** 验收点 ⑤：两个类型参数 `Pair<A,B>` 的存取（返回类型分别替换为具体类型）。 */
    @Test
    fun `two type parameters are substituted and dispatched`() {
        val source = """
            package test;
            public interface Pair<A, B> {
                public function first(): A;
                public function second(): B;
            }
            public class Person { public var id: int32; public constructor(id: int32) { this.id = id; } }
            public class Tag { public var code: int32; public constructor(code: int32) { this.code = code; } }
            public class PersonTag implements Pair<Person, Tag> {
                private var p: Person;
                private var t: Tag;
                public constructor(p: Person, t: Tag) { this.p = p; this.t = t; }
                public function first(): Person { return this.p; }
                public function second(): Tag { return this.t; }
            }
            public function main(): int32 {
                var pair: Pair<Person, Tag> = new PersonTag(new Person(31), new Tag(7));
                var p: Person = pair.first();
                var t: Tag = pair.second();
                return p.id * 100 + t.code;
            }
        """.trimIndent()
        val out = compile(source, "pair")
        assertEquals(3107, invokeStatic(out, "test.main", "main"), "Pair<A,B> 两个类型参数都应被替换")
    }

    /** 验收点 ⑥：基本类型实参 `Box<int32>` 的装箱/拆箱（桥内拆箱、返回处装箱）。 */
    @Test
    fun `primitive type argument boxes and unboxes through bridges`() {
        val source = """
            package test;
            public interface Box<T> {
                public function get(): T;
                public function put(x: T): void;
            }
            public class IntBox implements Box<int32> {
                private var value: int32;
                public constructor() { this.value = 0; }
                public function get(): int32 { return this.value; }
                public function put(x: int32): void { this.value = x; }
            }
            public function main(): int32 {
                var b: Box<int32> = new IntBox();
                b.put(42);
                var v: int32 = b.get();
                return v;
            }
        """.trimIndent()
        val out = compile(source, "box")
        assertEquals(42, invokeStatic(out, "test.main", "main"), "Box<int32> 应经装箱/拆箱桥走通")
    }

    /**
     * 验收点 ⑦：JPA `Repository<T, ID>` 形状的最小等价用例——接口变量分派可用
     * （原 FINDINGS #1 的复现形状）。
     */
    @Test
    fun `jpa style generic repository shape is usable through the interface`() {
        val source = """
            package test;
            public interface Repository<T, ID> {
                public function save(entity: T): void;
                public function findById(id: ID): T;
                public function count(): int32;
            }
            public class Person { public var id: int32; public constructor(id: int32) { this.id = id; } }
            public class MemoryRepo implements Repository<Person, int32> {
                public function save(entity: Person): void { }
                public function findById(id: int32): Person { return new Person(id); }
                public function count(): int32 { return 1; }
            }
            public function main(): int32 {
                var repo: Repository<Person, int32> = new MemoryRepo();
                repo.save(new Person(1));
                var p: Person = repo.findById(5);
                return p.id;
            }
        """.trimIndent()
        val out = compile(source, "repository")
        assertEquals(5, invokeStatic(out, "test.main", "main"), "Repository<T,ID> 经接口变量分派应返回 5")

        // findById 的桥需要把 Object 主键拆箱为 int32，故应同时有 (Object)Object 与 (I)Person。
        val memCode = javap(out, "test.MemoryRepo", "repository")
        assertTrue(
            memCode.contains("descriptor: (Ljava/lang/Object;)Ljava/lang/Object;"),
            "MemoryRepo 应有 findById(Ljava/lang/Object;)Ljava/lang/Object; 桥:\n$memCode"
        )
        assertTrue(memCode.contains("ACC_BRIDGE"), "MemoryRepo 应有 ACC_BRIDGE 桥:\n$memCode")
    }

    /**
     * 回归：非泛型接口（方法签名不含类型变量）不应产生桥接方法，且经接口变量调用仍正常。
     * 为什么单列：桥接生成必须只对「擦除后签名与实现不一致」的情形生效，不能误伤普通接口分派。
     */
    @Test
    fun `non generic interface implementation gets no bridge`() {
        val source = """
            package test;
            public interface Greeter {
                public function greet(name: string): string;
            }
            public class Hello implements Greeter {
                public function greet(name: string): string { return "hi"; }
            }
            public function main(): int32 {
                var g: Greeter = new Hello();
                if (g.greet("x") == "hi") { return 7; }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "nongeneric")
        assertEquals(7, invokeStatic(out, "test.main", "main"), "非泛型接口分派应正常")
        val helloCode = javap(out, "test.Hello", "nongeneric")
        assertTrue(
            !helloCode.contains("ACC_BRIDGE"),
            "非泛型接口实现不应生成桥接方法:\n$helloCode"
        )
    }
}
