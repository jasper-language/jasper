// ============================================================================
// InterfaceWideningTest.kt —— Q30（C13）：具体类型宽化到声明接口/父类
// 所属层次：测试层；只依赖 JasperCompiler 的公开编译入口与 JDK 自带 javap/URLClassLoader。
//
// 干什么：守住「赋值 / 传参 / 返回 / 字段写入」四类落点都必须按**声明目标类型**生成
//   CHECKCAST，并让经接口接收者的调用使用 INVOKEINTERFACE + 接口描述符。
//   每条用例都走真编译 → 真加载 → 真执行；字节码形状用 javap 复核，避免
//   「测试绿但运行期 NoSuchMethodError / VerifyError」。
//
// 为什么必须真跑：缺陷 #8 的症状是编译通过、运行期描述符不匹配
//   （`NoSuchMethodError: '<init>(LMemRepo;)V'`、`PUTFIELD slot:LMemRepo;`）。
//   这类问题只能在 JVM 链接/执行阶段暴露；同时 javap 证明宽化真的落在字节码里，
//   而不是靠验证器的赋值兼容性「碰巧通过」。
//
// 产物位置：jasper-compiler/build/scratch/interface-widening/ 下，不写仓库外路径。
// ============================================================================

package jasper

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
 * 做什么：C13（具体类型 → 接口/父类宽化）的端到端回归测试。
 * 什么时候用：改动 TypeChecker 的子类型判定、translator 的实参/构造器签名处理，
 *   或 JvmIrBackend 的赋值/返回/字段写入转换逻辑时。
 * 前置条件：测试 JVM 提供 javap（JDK 而非裸 JRE）。
 * 失败模式：编译失败、加载/执行抛 NoSuchMethodError/VerifyError、或 javap 中缺少
 *   CHECKCAST/INVOKEINTERFACE/接口描述符时断言失败。
 * 为什么这样设计：宽化横跨 checker/translator/backend 三层，单层单测无法证明
 *   「描述符与声明类型一致」；真编译 + 真执行 + javap 是唯一的端到端证据。
 */
class InterfaceWideningTest {

    /**
     * 做什么：为单个用例准备干净输出目录（固定在模块 build/ 下）。
     * 什么时候用：每个测试开始处。
     * 前置条件：无。
     * 失败模式：创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：产物落在仓库内、可复查，且不写项目外临时目录。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "interface-widening", name)
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
     * 做什么：编译单文件并返回诊断结果（不要求成功）。
     * 什么时候用：负向用例（期望被拒绝的赋值）。
     * 前置条件：source 是完整源文件文本。
     * 失败模式：不抛异常；返回 CompileResult（可能是 Success）。
     */
    private fun compileResult(source: String, name: String): JasperCompiler.CompileResult {
        val outputDir = scratchDir(name)
        return JasperCompiler().compileToDirWithIr(source, outputDir)
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
     * 做什么：对生成的 class 运行 `javap -v -p`，返回反汇编文本并把证据写入 build/。
     * 什么时候用：需要断言 CHECKCAST / INVOKEINTERFACE / 描述符形状时。
     * 前置条件：测试 JVM 的 java.home 下存在 bin/javap。
     * 失败模式：javap 退出码非 0 时断言失败并附上完整输出。
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
     * 做什么：用例 1——具体实现赋给接口变量，再经接口变量调用。
     * 守什么：变量声明处按 `Repo` 生成 CHECKCAST；调用是 `invokeinterface Repo.find`。
     */
    @Test
    fun `concrete implementation assigned to interface variable dispatches through interface`() {
        val source = """
            package test;
            public interface Repo {
                public function find(id: int32): string;
            }
            public class MemRepo implements Repo {
                public function find(id: int32): string { return "mem"; }
            }
            public function main(): string {
                var r: Repo = new MemRepo();
                return r.find(1);
            }
        """.trimIndent()
        val out = compile(source, "variable-dispatch")
        assertEquals("mem", invokeStatic(out, "test.main", "main"), "经接口变量调用应返回 mem")
        val code = javap(out, "test.main", "variable-dispatch")
        assertTrue(code.contains("checkcast") && code.contains("class test/Repo"), "赋值处应有 CHECKCAST test/Repo:\n$code")
        assertTrue(
            code.contains("InterfaceMethod test/Repo.find:(I)Ljava/lang/String;"),
            "接口调用应使用接口描述符:\n$code"
        )
    }

    /**
     * 做什么：用例 2——具体实现作为接口形参传入（顶层函数 + 成员方法 + 构造器）。
     * 守什么：构造器实参也必须宽化到 `Repo`，否则会写出类里不存在的
     *   `<init>(LMemRepo;)V`（C13 的原始运行期症状）。
     */
    @Test
    fun `concrete implementation passed as interface argument is widened`() {
        val source = """
            package test;
            public interface Repo {
                public function find(id: int32): string;
            }
            public class MemRepo implements Repo {
                public function find(id: int32): string { return "m"; }
            }
            public class Sink {
                private var repo: Repo;
                public constructor(repo: Repo) { this.repo = repo; }
                public function keep(repo: Repo): string { return repo.find(1); }
                public function value(): string { return this.repo.find(1); }
            }
            public class BaseSink {
                private var repo: Repo;
                public constructor(repo: Repo) { this.repo = repo; }
                public function value(): string { return this.repo.find(1); }
            }
            public class ChildSink extends BaseSink {
                public constructor(repo: Repo) { super(repo); }
            }
            public function use(repo: Repo): string { return repo.find(1); }
            public function main(): string {
                var sink: Sink = new Sink(new MemRepo());
                var child: ChildSink = new ChildSink(new MemRepo());
                return use(new MemRepo()) + sink.keep(new MemRepo()) + sink.value() + child.value();
            }
        """.trimIndent()
        val out = compile(source, "argument")
        assertEquals("mmmm", invokeStatic(out, "test.main", "main"), "顶层/成员/构造器/父构造器四处接口实参都应可用")
        val sinkCode = javap(out, "test.Sink", "argument")
        assertTrue(
            sinkCode.contains("public test.Sink(test.Repo);"),
            "构造器声明应保持接口形参:\n$sinkCode"
        )
        // 构造器调用点在 main 里：必须按声明形参 `Repo` 拼描述符，并在实参处 CHECKCAST。
        val mainCode = javap(out, "test.main", "argument")
        assertTrue(
            mainCode.contains("Method test/Sink.\"<init>\":(Ltest/Repo;)V"),
            "构造器调用点必须使用接口描述符:\n$mainCode"
        )
        assertTrue(
            mainCode.contains("checkcast") && mainCode.contains("class test/Repo"),
            "构造器/成员/顶层实参处应有 CHECKCAST Repo:\n$mainCode"
        )
        val childCode = javap(out, "test.ChildSink", "argument")
        assertTrue(
            childCode.contains("Method test/BaseSink.\"<init>\":(Ltest/Repo;)V"),
            "super(...) 委托也必须按父构造器声明形参拼描述符:\n$childCode"
        )
    }

    /**
     * 做什么：用例 3——具体实现作为接口返回值返回。
     * 守什么：`return new MemRepo()` 必须按方法声明的 `Repo` 生成 CHECKCAST；
     *   调用方经返回值继续接口分派。
     */
    @Test
    fun `concrete implementation returned as interface is widened`() {
        val source = """
            package test;
            public interface Repo {
                public function find(id: int32): string;
            }
            public class MemRepo implements Repo {
                public function find(id: int32): string { return "ret"; }
            }
            public function make(): Repo { return new MemRepo(); }
            public function main(): string {
                var r: Repo = make();
                return r.find(2);
            }
        """.trimIndent()
        val out = compile(source, "return-value")
        assertEquals("ret", invokeStatic(out, "test.main", "main"), "接口返回值应可继续分派")
        val makeCode = javap(out, "test.make", "return-value")
        assertTrue(makeCode.contains("public static test.Repo make();"), "返回类型应为接口:\n$makeCode")
        assertTrue(makeCode.contains("checkcast") && makeCode.contains("class test/Repo"), "返回处应有 CHECKCAST Repo:\n$makeCode")
    }

    /**
     * 做什么：用例 4——接口类型字段的写入与读取。
     * 守什么：字段描述符是声明的接口；直接写具体实现时按字段声明类型宽化，
     *   读取后调用仍是 `invokeinterface`。
     */
    @Test
    fun `interface typed field accepts concrete implementation and reads through interface`() {
        val source = """
            package test;
            public interface Repo {
                public function find(id: int32): string;
            }
            public class MemRepo implements Repo {
                public function find(id: int32): string { return "field"; }
            }
            public class Holder {
                private var slot: Repo;
                public constructor() { this.slot = new MemRepo(); }
                public function store(repo: Repo): void { this.slot = repo; }
                public function read(): string { return this.slot.find(3); }
            }
            public function main(): string {
                var h: Holder = new Holder();
                h.store(new MemRepo());
                return h.read();
            }
        """.trimIndent()
        val out = compile(source, "field")
        assertEquals("field", invokeStatic(out, "test.main", "main"), "接口字段读写应可用")
        val holderCode = javap(out, "test.Holder", "field")
        assertTrue(holderCode.contains("public test.Repo slot;"), "字段应声明为接口类型:\n$holderCode")
        assertTrue(
            holderCode.contains("Field slot:Ltest/Repo;"),
            "PUTFIELD/GETFIELD 必须使用接口描述符:\n$holderCode"
        )
        assertTrue(
            holderCode.contains("InterfaceMethod test/Repo.find:(I)Ljava/lang/String;"),
            "字段读取后的调用应为 invokeinterface:\n$holderCode"
        )
    }

    /**
     * 做什么：用例 5——可空接口变量（`Repo?`）赋具体实现，判空后调用。
     * 守什么：可空性不改变 JVM 描述符（仍是 `Ltest/Repo;`），也不丢 null 语义；
     *   判空分支内的调用仍走接口分派。
     */
    @Test
    fun `nullable interface variable keeps null semantics and interface dispatch`() {
        val source = """
            package test;
            public interface Repo {
                public function find(id: int32): string;
            }
            public class MemRepo implements Repo {
                public function find(id: int32): string { return "nullable"; }
            }
            public function main(): int32 {
                var r: Repo? = new MemRepo();
                var missing: Repo? = null;
                if (missing == null && r != null) {
                    if (r.find(4) == "nullable") { return 1; }
                }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "nullable")
        assertEquals(1, invokeStatic(out, "test.main", "main"), "可空接口判空后应正常调用")
        val code = javap(out, "test.main", "nullable")
        assertTrue(code.contains("checkcast") && code.contains("class test/Repo"), "可空接口赋值也应宽化到 Repo:\n$code")
        assertTrue(
            code.contains("InterfaceMethod test/Repo.find:(I)Ljava/lang/String;"),
            "可空接口调用应仍是 invokeinterface:\n$code"
        )
    }

    /**
     * 做什么：用例 6——javap 字节码形状总检：赋值 CHECKCAST + INVOKEINTERFACE + 接口描述符。
     * 守什么：不能只靠验证器的「子类型可赋值」通过；赋值处必须显式 CHECKCAST，
     *   调用点不得退化成具体类的 `invokevirtual`/具体描述符。
     */
    @Test
    fun `javap shows checkcast at assignment and invokeinterface at call site`() {
        val source = """
            package test;
            public interface Repo {
                public function find(id: int32): string;
            }
            public class MemRepo implements Repo {
                public function find(id: int32): string { return "shape"; }
            }
            public function main(): string {
                var r: Repo = new MemRepo();
                return r.find(5);
            }
        """.trimIndent()
        val out = compile(source, "javap-shape")
        val code = javap(out, "test.main", "javap-shape")
        assertTrue(code.contains("checkcast"), "赋值处必须出现 CHECKCAST:\n$code")
        assertTrue(code.contains("// class test/Repo"), "CHECKCAST 目标必须是声明接口 Repo:\n$code")
        assertTrue(code.contains("invokeinterface"), "接口调用必须使用 INVOKEINTERFACE:\n$code")
        assertTrue(
            code.contains("InterfaceMethod test/Repo.find:(I)Ljava/lang/String;"),
            "调用描述符必须使用接口与接口方法签名的视角:\n$code"
        )
        assertFalse(
            code.contains("Method test/MemRepo.find:(I)Ljava/lang/String;"),
            "接口变量调用不得退化为具体类描述符:\n$code"
        )
    }

    /**
     * 做什么：用例 7（负向）——不实现接口的类赋给接口变量，编译期必须明确报错且不产出 class。
     * 守什么：宽化只对真实子类型开放；失败时不留下半成品字节码。
     */
    @Test
    fun `unrelated class cannot be assigned to interface variable`() {
        val source = """
            package test;
            public interface Repo {
                public function find(id: int32): string;
            }
            public class NotARepo {
                public function find(id: int32): string { return "no"; }
            }
            public function main(): int32 {
                var r: Repo = new NotARepo();
                return 0;
            }
        """.trimIndent()
        val outputDir = scratchDir("unrelated")
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        val error = result as? JasperCompiler.CompileResult.Error
        assertTrue(error != null, "未实现接口的类赋给接口变量应被拒绝，实际: $result")
        assertTrue(
            error!!.message.contains("Type mismatch"),
            "应报告 Type mismatch，实际: ${error.message}"
        )
        val classCount = Files.walk(outputDir).use { stream ->
            stream.filter { it.toString().endsWith(".class") }.count()
        }
        assertEquals(0L, classCount, "编译失败时不应产生任何 class 文件")
    }

    /**
     * 做什么：用例 8——类继承宽化回归（`Base b = new Child()`）。
     * 守什么：C3/C7 的类继承赋值不因本次接口宽化改动而回归：声明处 CHECKCAST Base，
     *   经基类视角虚分派到子类 override。
     */
    @Test
    fun `class inheritance widening still works`() {
        val source = """
            package test;
            public class Base {
                public function greet(): string { return "base"; }
            }
            public class Child extends Base {
                public function greet(): string { return "child"; }
            }
            public function main(): string {
                var b: Base = new Child();
                return b.greet();
            }
        """.trimIndent()
        val out = compile(source, "class-inheritance")
        assertEquals("child", invokeStatic(out, "test.main", "main"), "子类应经基类视角虚分派")
        val code = javap(out, "test.main", "class-inheritance")
        assertTrue(code.contains("checkcast") && code.contains("class test/Base"), "基类赋值处应有 CHECKCAST Base:\n$code")
        assertTrue(code.contains("Method test/Base.greet:()Ljava/lang/String;"), "调用应以基类视角发 invokevirtual:\n$code")
    }
}
