// ============================================================================
// InheritanceTest —— 类继承子类型 + 继承方法/字段描述符回归测试（Q21 / C3+C7）
// 所属层次：测试层；只依赖 JasperCompiler 的公开编译入口与 JDK 自带 javap/URLClassLoader。
// 干什么：真编译 → 真加载 → 真执行 + javap 字节码形状断言，守住两条此前缺失的链路：
//   （C3）类继承进入赋值子类型：`var b: Base = new Child()` 编译并运行；
//   （C7）经子类/基类类型调用继承方法时描述符正确（不再退化为 `()V`）。
// 产物位置：模块 build/scratch/inheritance/ 下，不写仓库外路径。
//
// 为什么必须真跑而不是只编译：
//   缺陷 #6 的症状是「编译通过、运行期 NoSuchMethodError: 'void std.D.s()'」——错误的
//   调用描述符只在 JVM 链接/执行时才暴露。因此这里既断言运行结果，也用 javap 断言
//   Base/Child 的方法描述符与调用点 owner，避免「测试绿但运行期错」。
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
 * 做什么：Q21（类继承子类型 / 继承成员描述符）的端到端回归测试。
 * 什么时候用：改动 TypeChecker 的子类型判定、translator 的成员索引传播或后端调用点描述符时。
 * 前置条件：测试 JVM 提供 javap（JDK 而非裸 JRE）。
 * 失败模式：编译失败、加载/执行抛 VerifyError/NoSuchMethodError/AbstractMethodError，
 *   或 javap 形状不符时测试失败。
 * 为什么这样设计：该缺陷横跨 checker/translator/backend 三层，单元行为无法防止
 *   「编译通过但运行期炸」，故统一用真编译 + 真运行 + javap 证据守门。
 */
class InheritanceTest {

    /**
     * 做什么：为某个用例准备干净的输出目录（位于模块 build/ 下）。
     * 什么时候用：每个测试开始处。
     * 前置条件：无。
     * 失败模式：创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：产物落在仓库内、可复查，且不写项目外临时目录。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "inheritance", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /**
     * 做什么：编译一个（可含多个顶层声明的）Jasper 源文件并断言成功。
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
     * 做什么：编译多个源文件（键为文件名）并断言成功，返回输出目录。
     * 什么时候用：跨文件继承/先用后声明（缺陷 #6 的原始形状）。
     * 前置条件：sources 的迭代顺序即文件处理顺序（调用方按需要把「使用方」排在前面）。
     * 失败模式：任一文件编译失败时断言失败并附诊断。
     */
    private fun compileAll(sources: Map<String, String>, name: String): Path {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileAllToDir(sources, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    /**
     * 做什么：编译单文件并返回诊断列表（不要求成功）。
     * 什么时候用：负向用例（期望被拒绝的赋值 / 不兼容 override）。
     * 前置条件：source 是完整源文件文本。
     * 失败模式：不抛异常；返回 CompileResult（可能为 Success）。
     */
    private fun compileResult(source: String, name: String): JasperCompiler.CompileResult {
        val outputDir = scratchDir(name)
        return JasperCompiler().compileToDirWithIr(source, outputDir)
    }

    /**
     * 做什么：对生成的 class 运行 `javap -v -p`，返回反汇编文本并把证据写入 build/。
     * 什么时候用：需要断言描述符或方法形状时。
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
     * 用例 1（虚方法分派）：基类类型变量接收子类实例，调用被 override 的方法分派到子类。
     * 断言真执行返回 "child"，且 `main` 的调用点 owner 为 `test/Child`（JVM 虚分派）。
     */
    @Test
    fun `base typed variable dispatches override to child`() {
        val source = """
            package test;
            public class Base { public function greet(): string { return "base"; } }
            public class Child extends Base { public function greet(): string { return "child"; } }
            public function main(): string {
                var b: Base = new Child();
                return b.greet();
            }
        """.trimIndent()
        val out = compile(source, "virtual-dispatch")
        assertEquals("child", invokeStatic(out, "test.main", "main"), "经基类变量应虚分派到 Child.greet")

        val mainCode = javap(out, "test.main", "virtual-dispatch")
        assertTrue(
            mainCode.contains("invokevirtual") && mainCode.contains("test/Base.greet:()Ljava/lang/String;"),
            "main 应 invokevirtual test/Base.greet:()Ljava/lang/String;（运行时虚分派到 Child）:\n$mainCode"
        )
    }

    /**
     * 用例 2（继承方法）：子类未 override 时，经基类变量调用走到 Base 的实现。
     */
    @Test
    fun `inherited method resolves to base implementation`() {
        val source = """
            package test;
            public class Base {
                public function greet(): string { return "base"; }
                public function baseOnly(): string { return "only"; }
            }
            public class Child extends Base { public function greet(): string { return "child"; } }
            public function main(): string {
                var b: Base = new Child();
                return b.baseOnly();
            }
        """.trimIndent()
        val out = compile(source, "inherited-method")
        assertEquals("only", invokeStatic(out, "test.main", "main"), "未 override 时应调用 Base.baseOnly")
    }

    /**
     * 用例 3（形参/返回值）：函数形参声明为 Base 接收 Child，函数返回类型声明为 Base 返回 Child。
     */
    @Test
    fun `subtype works for parameters and return values`() {
        val source = """
            package test;
            public class Base { public function greet(): string { return "base"; } }
            public class Child extends Base { public function greet(): string { return "child"; } }
            public function consume(b: Base): string { return b.greet(); }
            public function make(): Base { return new Child(); }
            public function main(): string {
                return consume(make());
            }
        """.trimIndent()
        val out = compile(source, "parameter-return")
        assertEquals("child", invokeStatic(out, "test.main", "main"), "Child 应可作 Base 形参/返回值")
    }

    /**
     * 用例 4（继承字段）：经基类类型读/写声明在基类上的字段。
     */
    @Test
    fun `inherited field is readable and writable through base type`() {
        val source = """
            package test;
            public class Base { public var n: int32; public constructor() { this.n = 5; } }
            public class Child extends Base { }
            public function main(): int32 {
                var b: Base = new Child();
                b.n = 9;
                return b.n;
            }
        """.trimIndent()
        val out = compile(source, "inherited-field")
        assertEquals(9, invokeStatic(out, "test.main", "main"), "经基类变量读写继承字段应为 9")
    }

    /**
     * 用例 5（三层继承）：C 赋给 A，方法与字段解析沿 A<-B<-C 链正确。
     */
    @Test
    fun `three level inheritance resolves methods and fields`() {
        val source = """
            package test;
            public class A {
                public var a: int32;
                public constructor() { this.a = 1; }
                public function name(): string { return "A"; }
            }
            public class B extends A {
                public function name(): string { return "B"; }
                public function bOnly(): int32 { return 2; }
            }
            public class C extends B {
                public function name(): string { return "C"; }
            }
            public function main(): int32 {
                var x: A = new C();
                if (x.name() != "C") { return -1; }
                if (x.a != 1) { return -2; }
                var y: B = new C();
                if (y.bOnly() != 2) { return -3; }
                return 7;
            }
        """.trimIndent()
        val out = compile(source, "three-level")
        assertEquals(7, invokeStatic(out, "test.main", "main"), "三层继承的虚分派/继承方法/字段都应正确")
    }

    /**
     * 用例 6（继承 + 接口组合）：`class C extends B implements I`，经 I 与 B 两个视角都可用。
     */
    @Test
    fun `class inheritance combines with interface implementation`() {
        val source = """
            package test;
            public interface I { public function tag(): string; }
            public class B { public function baseTag(): string { return "B"; } }
            public class C extends B implements I { public function tag(): string { return "I"; } }
            public function main(): string {
                var c: C = new C();
                var i: I = c;
                var b: B = c;
                return i.tag() + b.baseTag();
            }
        """.trimIndent()
        val out = compile(source, "class-plus-interface")
        assertEquals("IB", invokeStatic(out, "test.main", "main"), "经接口与经基类两个视角都应可用")
    }

    /**
     * 用例 7（描述符正确）：Base/Child 的非 void 方法描述符正确，无非法 void 描述符。
     */
    @Test
    fun `method descriptors are correct and never degenerate to void`() {
        val source = """
            package test;
            public class Base {
                public function greet(): string { return "base"; }
                public function baseOnly(): string { return "only"; }
            }
            public class Child extends Base { public function greet(): string { return "child"; } }
            public function main(): string {
                var b: Base = new Child();
                return b.greet() + b.baseOnly();
            }
        """.trimIndent()
        val out = compile(source, "descriptors")
        val baseCode = javap(out, "test.Base", "descriptors")
        val childCode = javap(out, "test.Child", "descriptors")

        assertTrue(baseCode.contains("public java.lang.String greet();"), "Base.greet 应返回 String:\n$baseCode")
        assertTrue(baseCode.contains("descriptor: ()Ljava/lang/String;"), "Base 应有 ()Ljava/lang/String; 描述符:\n$baseCode")
        assertFalse(
            baseCode.contains("public void greet();"),
            "Base.greet 绝不能退化为 void（FINDINGS #6）:\n$baseCode"
        )
        assertTrue(childCode.contains("descriptor: ()Ljava/lang/String;"), "Child.greet 描述符应为 ()Ljava/lang/String;:\n$childCode")
        assertTrue(childCode.contains("public java.lang.String greet();"), "Child.greet 应返回 String:\n$childCode")
        assertFalse(
            childCode.contains("public void greet();"),
            "Child.greet 描述符不得退化为 void（FINDINGS #6）:\n$childCode"
        )
    }

    /**
     * 用例 8a（负向）：无关类之间的赋值仍被拒绝。
     */
    @Test
    fun `unrelated classes are still rejected on assignment`() {
        val source = """
            package test;
            public class Apple { }
            public class Orange { }
            public function main(): int32 {
                var a: Apple = new Orange();
                return 0;
            }
        """.trimIndent()
        val result = compileResult(source, "unrelated")
        val error = result as? JasperCompiler.CompileResult.Error
        assertTrue(error != null, "无关类赋值应被拒绝，实际: $result")
        assertTrue(
            error!!.message.contains("Type mismatch"),
            "应报告 Type mismatch，实际: ${error.message}"
        )
    }

    /**
     * 用例 8b（负向）：override 返回类型不兼容时给出明确错误（不协变）。
     */
    @Test
    fun `incompatible override return type is reported clearly`() {
        val source = """
            package test;
            public class Base { public function value(): string { return "s"; } }
            public class Child extends Base { public function value(): int32 { return 1; } }
            public function main(): int32 { return 0; }
        """.trimIndent()
        val result = compileResult(source, "bad-override")
        val error = result as? JasperCompiler.CompileResult.Error
        assertTrue(error != null, "不兼容 override 应被拒绝，实际: $result")
        assertTrue(
            error!!.message.contains("Incompatible override"),
            "应报告 Incompatible override，实际: ${error.message}"
        )
    }

    /**
     * 用例 8c（负向）：循环类继承被明确诊断，而不是无限递归或静默错误。
     */
    @Test
    fun `cyclic class inheritance is diagnosed`() {
        val source = """
            package test;
            public class A extends B { }
            public class B extends A { }
            public function main(): int32 { return 0; }
        """.trimIndent()
        val result = compileResult(source, "cyclic-class")
        val error = result as? JasperCompiler.CompileResult.Error
        assertTrue(error != null, "循环类继承应被诊断，实际: $result")
        assertTrue(
            error!!.message.contains("Cyclic class inheritance"),
            "应报告 Cyclic class inheritance，实际: ${error.message}"
        )
    }

    /**
     * 用例 8d（负向）：循环接口继承被明确诊断。
     */
    @Test
    fun `cyclic interface inheritance is diagnosed`() {
        val source = """
            package test;
            public interface A extends B { }
            public interface B extends A { }
            public function main(): int32 { return 0; }
        """.trimIndent()
        val result = compileResult(source, "cyclic-interface")
        val error = result as? JasperCompiler.CompileResult.Error
        assertTrue(error != null, "循环接口继承应被诊断，实际: $result")
        assertTrue(
            error!!.message.contains("Cyclic interface inheritance"),
            "应报告 Cyclic interface inheritance，实际: ${error.message}"
        )
    }

    /**
     * 用例 9（不复制方法体）：子类源码为空时，Child 的 class 不应出现父类方法。
     * 这是「不要用把继承方法复制进子类来绕过」的机械证据：javap -p 只列本类声明的方法。
     */
    @Test
    fun `empty child does not contain copied inherited method body`() {
        val source = """
            package test;
            public class Base { public function greet(): string { return "base"; } }
            public class Child extends Base { }
            public function main(): string {
                var c: Child = new Child();
                return c.greet();
            }
        """.trimIndent()
        val out = compile(source, "no-copy")
        val childCode = javap(out, "test.Child", "no-copy")
        assertFalse(
            childCode.contains("greet"),
            "空 Child 不应复制继承来的 greet 方法体:\n$childCode"
        )
        // 但子类接收者经 JVM 方法解析仍能调到 Base.greet：真跑验证。
        assertEquals("base", invokeStatic(out, "test.main", "main"), "空 Child 应经继承解析调到 Base.greet")
    }

    /**
     * 跨文件回归（缺陷 #6 原始形状）：使用方文件排在声明方之前，子类接收者调用继承方法。
     * 修复前会生成 `invokevirtual std/D.s:()V`，运行期 NoSuchMethodError。
     */
    @Test
    fun `cross file inherited method keeps correct return descriptor`() {
        val mainSrc = """
            package std;
            public class D extends B { }
            public function main(): string {
                var d: D = new D();
                return d.s();
            }
        """.trimIndent()
        val baseSrc = """
            package std;
            public class B { public function s(): string { return "x"; } }
        """.trimIndent()
        val out = compileAll(mapOf("Main.jas" to mainSrc, "Base.jas" to baseSrc), "cross-file")
        assertEquals("x", invokeStatic(out, "std.main", "main"), "跨文件继承方法应返回 x（无 NoSuchMethodError）")

        val mainCode = javap(out, "std.main", "cross-file")
        assertTrue(
            mainCode.contains("std/D.s:()Ljava/lang/String;"),
            "跨文件继承调用描述符应为 ()Ljava/lang/String; 而非 ()V:\n$mainCode"
        )
        assertFalse(
            mainCode.contains("std/D.s:()V"),
            "继承方法描述符不得退化为 void（FINDINGS #6）:\n$mainCode"
        )
    }
}
