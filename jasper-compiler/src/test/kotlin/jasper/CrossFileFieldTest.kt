// ============================================================================
// CrossFileFieldTest —— 跨文件/跨包字段访问的顺序无关性回归测试（Q25 / C6+C16）
// 所属层次：测试层；只依赖 JasperCompiler 的公开编译入口与 JDK 自带的
//   URLClassLoader / javap，不触碰 translator 内部实现。
// 干什么：把「字段符号/类型只在声明文件被翻译时才登记」这一缺陷钉死。核心断言是
//   **同一组源文件、任意文件顺序都能正确编译、加载并运行**，而不仅仅是「A 在前时能跑」。
//
// 为什么必须覆盖两种文件顺序：
//   缺陷的症状正是顺序相关——`A.j` 先、`B.j` 后编译成功，反过来则报
//   `INTERNAL: unresolved field symbol (no name registered)`（字段访问点拿到了一个
//   与声明处不同身份的临时符号）。只测一种顺序会漏掉整类回归，因此每个场景都显式
//   断言「使用方排在声明方前面」也能通过。
//
// 为什么既真运行又看 javap：
//   顺序错误在旧实现里是**编译期** Internal（会直接被编译结果断言抓住）；但符号身份
//   错位还可能以「字段 owner/描述符写错」的形态漏到字节码里，只有加载执行或反汇编
//   才能暴露。故正向用例统一「编译 → URLClassLoader 真执行 → javap -v 断言字段坐标」。
//
// 产物位置：模块 build/scratch/cross-file-fields/ 下，不写仓库外路径。
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
 * 做什么：跨文件/跨包字段访问「与文件翻译顺序无关」的端到端回归测试。
 * 什么时候用：任何改动 translator 的字段预登记、字段符号登记或后端字段解析后运行。
 * 前置条件：测试 JVM 提供 javap（JDK 而非裸 JRE）。
 * 失败模式：编译返回 Error、加载/执行抛异常、或字段坐标与期望不符时测试失败。
 * 为什么这样设计：该缺陷横跨 translator（符号登记）与 backend（按符号反查 owner/描述符）
 *   两层，单测其中一层无法防止「编译过但字段身份错位」，故统一用真编译 + 真运行 + javap 守门。
 */
class CrossFileFieldTest {

    /**
     * 做什么：为某个用例准备干净的输出目录（位于模块 build/ 下）。
     * 什么时候用：每个测试开始处。
     * 前置条件：无。
     * 失败模式：目录创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：沿用既有测试的 scratch 约定，产物落在仓库内、可复查。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "cross-file-fields", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /**
     * 做什么：编译多个源文件并断言成功，返回输出目录。
     * 什么时候用：所有正向用例。
     * 前置条件：sources 的迭代顺序即文件处理顺序（调用方用 LinkedHashMap 控制）。
     * 失败模式：任一文件编译失败时以断言失败报告聚合诊断。
     * 为什么这样设计：本测试的核心变量就是「文件顺序」，故顺序必须由调用方显式给定，
     *   不能依赖 Map 的偶然实现。
     */
    private fun compileAll(sources: Map<String, String>, name: String): Path {
        val out = scratchDir(name)
        val result = JasperCompiler().compileAllToDir(sources, out)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " +
                (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return out
    }

    /**
     * 做什么：加载输出目录并调用某类的无参静态方法。
     * 什么时候用：需要真执行生成代码时。
     * 前置条件：outputDir 下有已编译的 className.class。
     * 失败模式：类/方法不存在或调用抛异常时测试失败（JVM 链接期错误会在此暴露）。
     * 为什么这样设计：URLClassLoader 能验证真实的链接期/运行期语义。
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
     * 什么时候用：需要断言字段 owner/描述符（常量池 Fieldref）时。
     * 前置条件：测试 JVM 的 java.home 下存在 bin/javap。
     * 失败模式：javap 退出码非 0 时断言失败并附上完整输出。
     * 为什么用 -v：常量池里的 `Fieldref ... // p/A.x:I` 才能同时证明 owner 与描述符正确；
     *   只用 -c 看不到 owner 的完整内部名。
     */
    private fun javap(outputDir: Path, className: String, caseName: String): String {
        val javap = Paths.get(System.getProperty("java.home"), "bin", "javap").toString()
        val process = ProcessBuilder(
            javap, "-v", "-p", "-classpath", outputDir.toAbsolutePath().toString(), className
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        val evidence = outputDir.resolve("javap-${className.replace('.', '_')}-$caseName.txt")
        Files.writeString(evidence, output)
        assertEquals(0, exitCode, "javap failed for $className:\n$output")
        return output
    }

    /** 同包两文件：A 声明方在前（基于顺序无关要求，这是基线，也必须通过）。 */
    @Test
    fun `same package instance field works when declaring file comes first`() {
        val a = """
            package p;
            public class A { public var x: int32; public constructor(x: int32) { this.x = x; } }
        """.trimIndent()
        val b = """
            package p;
            public function main(): int32 { var a = new A(7); a.x = a.x + 1; return a.x; }
        """.trimIndent()
        val out = compileAll(linkedMapOf("A.j" to a, "B.j" to b), "same-pkg-decl-first")
        assertEquals(8, invokeStatic(out, "p.main", "main"))
        // 字段访问点的 owner/描述符必须是真实的 p/A.x:I，而不是兜底伪字段。
        val mainCode = javap(out, "p.main", "same-pkg-decl-first")
        assertTrue(mainCode.contains("p/A.x:I"), "expected Fieldref p/A.x:I in p/main:\n$mainCode")
    }

    /**
     * 同包两文件：使用方 B 在前（旧实现的失败形态）。
     * 守什么：即使 B.j 先于 A.j 被翻译，`a.x` 的字段符号也必须与 A.j 声明处是同一个身份。
     * 若回归，编译会以 `INTERNAL: unresolved field symbol` 失败。
     */
    @Test
    fun `same package instance field works when using file comes first`() {
        val a = """
            package p;
            public class A { public var x: int32; public constructor(x: int32) { this.x = x; } }
        """.trimIndent()
        val b = """
            package p;
            public function main(): int32 { var a = new A(7); a.x = a.x + 1; return a.x; }
        """.trimIndent()
        val out = compileAll(linkedMapOf("B.j" to b, "A.j" to a), "same-pkg-use-first")
        assertEquals(8, invokeStatic(out, "p.main", "main"))
        val mainCode = javap(out, "p.main", "same-pkg-use-first")
        assertTrue(mainCode.contains("p/A.x:I"), "expected Fieldref p/A.x:I in p/main:\n$mainCode")
    }

    /**
     * 跨包：使用方 `p.b.main` 在前、声明方 `p.a.A` 在后。
     * 守什么：字段 owner 用「接收者声明类型的内部名」定位（`p/a/A`），
     * 而不是当前包或全局按名回退；跨包顺序反了也不能崩。
     */
    @Test
    fun `cross package instance field works when using file comes first`() {
        val a = """
            package p.a;
            public class A { public var x: int32; public constructor(x: int32) { this.x = x; } }
        """.trimIndent()
        val main = """
            package p.b;
            import p.a.A;
            public function main(): int32 { var a = new A(7); a.x = a.x + 1; return a.x; }
        """.trimIndent()
        val out = compileAll(linkedMapOf("Main.j" to main, "A.j" to a), "cross-pkg-use-first")
        assertEquals(8, invokeStatic(out, "p.b.main", "main"))
        val mainCode = javap(out, "p.b.main", "cross-pkg-use-first")
        assertTrue(mainCode.contains("p/a/A.x:I"), "expected Fieldref p/a/A.x:I in p.b.main:\n$mainCode")
    }

    /**
     * 经 `Holder` 间接访问 `this.a.x`，声明方 A 排在最后。
     * 守什么：字段解析要沿「成员字段的声明类型」拿到接收者类型（`Holder.a: A`），
     * 再从 A 的预登记字段表定位 `x`——即使 A 是最后才被翻译的文件。
     */
    @Test
    fun `indirect access through holder works when declaring file comes last`() {
        val a = """
            package p;
            public class A { public var x: int32; public constructor(x: int32) { this.x = x; } }
        """.trimIndent()
        val holder = """
            package p;
            public class Holder {
                public var a: A;
                public constructor(a: A) { this.a = a; }
                public function bump(): int32 { this.a.x = this.a.x + 1; return this.a.x; }
            }
        """.trimIndent()
        val main = """
            package p;
            public function main(): int32 { var a = new A(7); var h = new Holder(a); return h.bump(); }
        """.trimIndent()
        // A 最后：Holder/Main 先被翻译，字段访问点必须靠预登记符号解析。
        val out = compileAll(
            linkedMapOf("Main.j" to main, "Holder.j" to holder, "A.j" to a),
            "indirect-holder-last"
        )
        assertEquals(8, invokeStatic(out, "p.main", "main"))
        val holderCode = javap(out, "p.Holder", "indirect-holder-last")
        assertTrue(holderCode.contains("p/A.x:I"), "expected Fieldref p/A.x:I in p.Holder:\n$holderCode")
    }

    /**
     * 静态字段跨文件顺序：`Counter.total` 的 PUTSTATIC/GETSTATIC 在使用方先于声明方时也正确。
     * 守什么：静态字段符号在预登记阶段就进入 `staticFieldSymbols` 与按类索引，
     * 使 `Counter.total` 的静态识别不依赖 Counter 是否已被翻译。
     */
    @Test
    fun `static field access is order independent across files`() {
        val counter = """
            package p;
            public class Counter { public static var total: int32 = 3; }
        """.trimIndent()
        val main = """
            package p;
            public function main(): int32 { Counter.total = Counter.total + 4; return Counter.total; }
        """.trimIndent()
        val out = compileAll(linkedMapOf("Main.j" to main, "Counter.j" to counter), "static-use-first")
        assertEquals(7, invokeStatic(out, "p.main", "main"))
        val mainCode = javap(out, "p.main", "static-use-first")
        assertTrue(mainCode.contains("p/Counter.total:I"), "expected Fieldref p/Counter.total:I:\n$mainCode")
        assertTrue(mainCode.contains("getstatic"), "expected GETSTATIC in p/main:\n$mainCode")
        assertTrue(mainCode.contains("putstatic"), "expected PUTSTATIC in p/main:\n$mainCode")
    }

    /**
     * 接口常量跨文件顺序：`Statement.MAX_ROWS` 在使用方先于声明方时也正确。
     * 守什么：接口常量在预登记阶段就同时进入按类字段索引与 `staticFieldSymbols`，
     * 使读取点发 GETSTATIC（而非对类名求值的 GETFIELD）。
     */
    @Test
    fun `interface constant access is order independent across files`() {
        val stmt = """
            package p;
            public interface Statement { public const MAX_ROWS: int32 = 100; }
        """.trimIndent()
        val main = """
            package p;
            public function main(): int32 { return Statement.MAX_ROWS; }
        """.trimIndent()
        val out = compileAll(linkedMapOf("Main.j" to main, "Statement.j" to stmt), "iface-const-use-first")
        assertEquals(100, invokeStatic(out, "p.main", "main"))
        val mainCode = javap(out, "p.main", "iface-const-use-first")
        assertTrue(mainCode.contains("getstatic"), "expected GETSTATIC for interface constant:\n$mainCode")
    }

    /**
     * Q21 继承字段回归：经基类类型变量读继承字段仍然正确（字段预登记不得破坏沿父链查找）。
     * 守什么：字段解析仍走 `lookupFieldAlongHierarchy`，子类接收者能查到父类声明的字段。
     */
    @Test
    fun `inherited field still resolves through base type`() {
        val source = """
            package p;
            public class Base { public var n: int32; public constructor() { this.n = 5; } }
            public class Child extends Base { }
            public function main(): int32 { var b: Base = new Child(); b.n = 9; return b.n; }
        """.trimIndent()
        val out = compileAll(linkedMapOf("Main.j" to source), "inherited-field-regression")
        assertEquals(9, invokeStatic(out, "p.main", "main"))
    }

    /**
     * 负向：访问一个不存在的字段必须是明确的编译错误，不得退化成 Internal 或伪字段 `"field"`。
     * 守什么：字段预登记只登记真实存在的字段；解析失败仍由类型检查器先行拦截。
     */
    @Test
    fun `accessing a missing field is a clear compile error`() {
        val source = """
            package p;
            public class A { public var x: int32; public constructor(x: int32) { this.x = x; } }
            public function main(): int32 { var a = new A(1); return a.z; }
        """.trimIndent()
        val out = scratchDir("missing-field")
        val result = JasperCompiler().compileAllToDir(linkedMapOf("Main.j" to source), out)
        assertTrue(
            result is JasperCompiler.CompileResult.Error,
            "accessing a missing field must fail compilation, got: $result"
        )
        val message = (result as JasperCompiler.CompileResult.Error).message
        assertTrue(
            message.contains("Cannot resolve member 'z'"),
            "expected a clear missing-member diagnostic, got:\n$message"
        )
        assertTrue(
            !message.contains("INTERNAL: unresolved field symbol"),
            "missing field must not reach the backend as an internal error:\n$message"
        )
    }
}
