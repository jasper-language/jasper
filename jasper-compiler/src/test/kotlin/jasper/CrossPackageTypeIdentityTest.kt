// ============================================================================
// CrossPackageTypeIdentityTest.kt —— 跨包同名类型身份（C16）的端到端回归测试
// ============================================================================
// 职责：
//   验证「类型身份 = 包名 + 简单名」在整条编译链路上成立：
//   符号表可以同时容纳 p1.Thing 与 p2.Thing；类型检查按本包/import 解析简单名；
//   翻译与字节码后端写出 p1/Thing、p2/Thing 两个 JVM 内部名及正确描述符；
//   两个同名实现类可以在同一 JVM 中加载、实例化并分别调用。
//
// 所属层次：
//   测试层。只依赖 JasperCompiler 的公开入口（compileAllToDir / CompileResult）与
//   JDK 自带 URLClassLoader、javap，不触碰编译器内部实现，保证测试描述「行为」
//   而不是锁定某一种实现。
//
// 为什么必须真编译 + 真加载 + javap：
//   这类缺陷的典型失败模式是「编译通过、运行期 NoClassDefFoundError」或「描述符
//   指向了另一个包的同名类型」。只断言编译成功会把静默错误字节码留给用户；因此
//   正向用例都实际加载并调用，描述符用例再用 javap -v 核对 `Lp1/Thing;` 文本。
//
// 产物位置：
//   全部写到模块自己的 build/scratch/cross-package-identity/ 下，不使用仓库外临时目录。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 做什么：跨包同名类型身份的回归测试集合（C16）。
 * 什么时候用：每次改动 SymbolTable 的类型键、名字解析、translator 的类型表或
 *   字节码描述符生成后运行。
 * 前置条件：测试 JVM 提供 `javap`（JDK 而非裸 JRE）。
 * 失败模式：编译失败、类加载/调用异常、返回值不符或 javap 描述符不符时测试失败。
 * 为什么集中在一个类：这些用例共享「类型身份必须贯穿符号表 → 检查器 → 描述符」
 *   这一条断言链，放在一起便于对照哪一层先失守。
 */
class CrossPackageTypeIdentityTest {

    /** 为单个用例准备干净的输出目录（固定在模块 build/scratch 下）。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "cross-package-identity", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 编译一组「文件名 -> 源码」并断言成功，返回 class 输出目录。 */
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

    /** 编译一组源码并断言失败，返回聚合诊断文本。 */
    private fun compileAllExpectingError(sources: Map<String, String>, name: String): String {
        val out = scratchDir(name)
        val result = JasperCompiler().compileAllToDir(sources, out)
        assertTrue(
            result is JasperCompiler.CompileResult.Error,
            "expected compilation to fail for $name, but it succeeded"
        )
        return (result as JasperCompiler.CompileResult.Error).message
    }

    /** 加载类、实例化无参构造器，再调用无参实例方法。 */
    private fun invokeInstance(outputDir: Path, className: String, method: String): Any? {
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass(className)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
            return target.invoke(instance)
        }
    }

    /** 对生成的 class 运行 `javap -v -p`，返回反汇编文本并落盘为证据。 */
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

    /**
     * 1. 不同包的同名 interface 可以同时编译，不再报重复声明。
     *
     * 守什么：SymbolTable 的类型主键必须包含包名；若退回简单名主键，第二个
     *   `interface Thing` 会触发 `Duplicate interface declaration 'Thing'`。
     */
    @Test
    fun `same named interfaces in different packages coexist`() {
        val sources = mapOf(
            "P1Thing.jas" to """
                package p1;
                public interface Thing { public function v(): int32; }
            """.trimIndent(),
            "P2Thing.jas" to """
                package p2;
                public interface Thing { public function v(): int32; }
            """.trimIndent()
        )
        val out = compileAll(sources, "coexist-interfaces")

        URLClassLoader(arrayOf(out.toUri().toURL())).use { loader ->
            val first = loader.loadClass("p1.Thing")
            val second = loader.loadClass("p2.Thing")
            assertTrue(first.isInterface, "p1.Thing must be an interface")
            assertTrue(second.isInterface, "p2.Thing must be an interface")
            assertNotEquals(first, second, "p1.Thing and p2.Thing must be distinct runtime classes")
        }
    }

    /**
     * 2. p1.Impl / p2.Impl 分别实现各自包的 Thing，加载后返回不同值。
     *
     * 守什么：不仅类型要共存，继承/接口表也必须按包区分；否则两个 Impl 可能
     *   都实现同一个 Thing，运行期分派结果会混淆。
     */
    @Test
    fun `same named implementations dispatch to their own interface`() {
        val sources = mapOf(
            "P1Thing.jas" to """
                package p1;
                public interface Thing { public function v(): int32; }
            """.trimIndent(),
            "P1Impl.jas" to """
                package p1;
                public class Impl implements Thing { public function v(): int32 { return 1; } }
            """.trimIndent(),
            "P2Thing.jas" to """
                package p2;
                public interface Thing { public function v(): int32; }
            """.trimIndent(),
            "P2Impl.jas" to """
                package p2;
                public class Impl implements Thing { public function v(): int32 { return 2; } }
            """.trimIndent()
        )
        val out = compileAll(sources, "coexist-implementations")
        assertEquals(1, invokeInstance(out, "p1.Impl", "v"))
        assertEquals(2, invokeInstance(out, "p2.Impl", "v"))
    }

    /**
     * 3. 同名 class（不只是 interface）跨包共存并运行。
     *
     * 守什么：四类顶层类型共用同一套身份规则，不能只修 interface 路径。
     */
    @Test
    fun `same named classes in different packages coexist`() {
        val sources = mapOf(
            "P1Widget.jas" to """
                package p1;
                public class Widget { public function value(): int32 { return 10; } }
            """.trimIndent(),
            "P2Widget.jas" to """
                package p2;
                public class Widget { public function value(): int32 { return 20; } }
            """.trimIndent()
        )
        val out = compileAll(sources, "coexist-classes")
        assertEquals(10, invokeInstance(out, "p1.Widget", "value"))
        assertEquals(20, invokeInstance(out, "p2.Widget", "value"))
    }

    /**
     * 4. 同名 enum 跨包共存。
     *
     * 当前枚举路径复用同一 SymbolTable/translator 身份规则，因此本用例应该成立；
     * 这里用运行时 `getEnumConstants()` 验证两个 enum 都是独立、可加载的 JVM 枚举，
     * 而不是只看编译是否通过。若未来枚举路径退化，本用例会直接失败并暴露。
     */
    @Test
    fun `same named enums in different packages coexist`() {
        val sources = mapOf(
            "P1State.jas" to """
                package p1;
                public enum State { On, Off }
            """.trimIndent(),
            "P2State.jas" to """
                package p2;
                public enum State { Red, Blue }
            """.trimIndent()
        )
        val out = compileAll(sources, "coexist-enums")

        URLClassLoader(arrayOf(out.toUri().toURL())).use { loader ->
            val first = loader.loadClass("p1.State")
            val second = loader.loadClass("p2.State")
            assertTrue(first.isEnum, "p1.State must be a JVM enum")
            assertTrue(second.isEnum, "p2.State must be a JVM enum")
            assertEquals(listOf("On", "Off"), first.enumConstants.map { (it as Enum<*>).name })
            assertEquals(listOf("Red", "Blue"), second.enumConstants.map { (it as Enum<*>).name })
        }
    }

    /**
     * 5. 同一包内同名同 kind 仍报重复；同名不同 kind 仍报冲突。
     *
     * 守什么：放宽到「包 + 名」后不能把真正的重复声明也放过。
     */
    @Test
    fun `duplicate declarations inside the same package are still rejected`() {
        val sameKind = compileAllExpectingError(
            mapOf(
                "First.jas" to "package dup; public class Same { }",
                "Second.jas" to "package dup; public class Same { }"
            ),
            "duplicate-same-kind"
        )
        assertTrue(
            sameKind.contains("Duplicate class declaration 'dup.Same'"),
            "expected duplicate class diagnostic, got:\n$sameKind"
        )

        val differentKind = compileAllExpectingError(
            mapOf(
                "ClashClass.jas" to "package dup2; public class Clash { }",
                "ClashInterface.jas" to "package dup2; public interface Clash { }"
            ),
            "duplicate-different-kind"
        )
        assertTrue(
            differentKind.contains("Duplicate") && differentKind.contains("dup2.Clash"),
            "expected cross-kind duplicate diagnostic, got:\n$differentKind"
        )
    }

    /**
     * 6. 同包简单名解析到本包类型；显式 import 后解析到被导入包的同名类型。
     *
     * 同包部分由 p1.Impl 实现 p1.Thing 覆盖；import 部分让 app.Uses 的字段/构造器
     * 使用 `Thing`，同时 `import p2.Thing;`，最终必须链接到 p2/Thing 而不是 app/Thing。
     */
    @Test
    fun `simple names resolve to current package and explicit imports`() {
        val sources = mapOf(
            "P1Thing.jas" to """
                package p1;
                public interface Thing { public function v(): int32; }
            """.trimIndent(),
            "P1Impl.jas" to """
                package p1;
                public class Impl implements Thing { public function v(): int32 { return 1; } }
            """.trimIndent(),
            "P2Thing.jas" to """
                package p2;
                public interface Thing { public function v(): int32; }
            """.trimIndent(),
            "P2Impl.jas" to """
                package p2;
                public class Impl implements Thing { public function v(): int32 { return 2; } }
            """.trimIndent(),
            "AppUses.jas" to """
                package app;
                import p2.Thing;
                public class Uses {
                    public var thing: Thing;
                    public constructor(t: Thing) { this.thing = t; }
                    public function call(): int32 { return this.thing.v(); }
                }
            """.trimIndent()
        )
        val out = compileAll(sources, "simple-name-resolution")

        // 同包：p1.Impl 自然解析 p1.Thing。
        assertEquals(1, invokeInstance(out, "p1.Impl", "v"))

        // import：app.Uses 的字段/构造函数参数描述符必须是 p2/Thing。
        URLClassLoader(arrayOf(out.toUri().toURL())).use { loader ->
            val uses = loader.loadClass("app.Uses")
            val impl = loader.loadClass("p2.Impl")
            val ctor = uses.getDeclaredConstructor(impl.interfaces.single())
            val instance = ctor.newInstance(impl.getDeclaredConstructor().newInstance())
            val call = uses.methods.first { it.name == "call" && it.parameterCount == 0 }
            assertEquals(2, call.invoke(instance))
        }
    }

    /**
     * 7. 字段/方法形参/返回描述符引用正确的包（javap 证据）。
     *
     * 守什么：即使编译和加载都通过，描述符也可能悄悄指向错误包；这里直接断言
     *   p1.Holder 只出现 `Lp1/Thing;`，p2.Holder 只出现 `Lp2/Thing;`。
     */
    @Test
    fun `field and method descriptors reference the declaring package`() {
        val sources = mapOf(
            "P1Thing.jas" to """
                package p1;
                public interface Thing { public function v(): int32; }
            """.trimIndent(),
            "P2Thing.jas" to """
                package p2;
                public interface Thing { public function v(): int32; }
            """.trimIndent(),
            "P1Holder.jas" to """
                package p1;
                public class Holder {
                    public var thing: Thing;
                    public function take(t: Thing): Thing { return t; }
                }
            """.trimIndent(),
            "P2Holder.jas" to """
                package p2;
                public class Holder {
                    public var thing: Thing;
                    public function take(t: Thing): Thing { return t; }
                }
            """.trimIndent()
        )
        val out = compileAll(sources, "descriptor-packages")

        val first = javap(out, "p1.Holder", "descriptors")
        val second = javap(out, "p2.Holder", "descriptors")
        assertTrue(first.contains("Lp1/Thing;"), "p1.Holder must reference Lp1/Thing;:\n$first")
        assertTrue(!first.contains("Lp2/Thing;"), "p1.Holder must not reference p2/Thing:\n$first")
        assertTrue(second.contains("Lp2/Thing;"), "p2.Holder must reference Lp2/Thing;:\n$second")
        assertTrue(!second.contains("Lp1/Thing;"), "p2.Holder must not reference p1/Thing:\n$second")

        // 方法描述符也必须带包：形参与返回位置各出现一次。
        assertTrue(
            first.contains("take") && first.contains("(Lp1/Thing;)Lp1/Thing;"),
            "p1.Holder.take must have (Lp1/Thing;)Lp1/Thing; descriptor:\n$first"
        )
        assertTrue(
            second.contains("take") && second.contains("(Lp2/Thing;)Lp2/Thing;"),
            "p2.Holder.take must have (Lp2/Thing;)Lp2/Thing; descriptor:\n$second"
        )
    }

    /**
     * 8. 负向：没有 import 的跨包简单名不会被静默当成同包类型。
     *
     * 守什么：类型身份按包区分后，可见性规则必须保持既有语义——p3 里写 `Thing`
     *   而 p1/p2 各有同名类型时，仍应报 Unknown type，而不是任选一个包链接过去。
     */
    @Test
    fun `cross package simple name without import stays unknown`() {
        val diagnostics = compileAllExpectingError(
            mapOf(
                "P1Thing.jas" to """
                    package p1;
                    public interface Thing { public function v(): int32; }
                """.trimIndent(),
                "P2Thing.jas" to """
                    package p2;
                    public interface Thing { public function v(): int32; }
                """.trimIndent(),
                "BadUser.jas" to """
                    package p3;
                    public class BadUser { public var thing: Thing; }
                """.trimIndent()
            ),
            "unknown-cross-package-simple-name"
        )
        assertTrue(
            diagnostics.contains("Unknown type 'Thing'"),
            "expected Unknown type 'Thing', got:\n$diagnostics"
        )
    }
}
