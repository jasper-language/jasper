// ============================================================================
// ArrayAssignTest.kt —— Q29（C12）：多元素数组字面量/赋值与元素类型宽化
// ============================================================================
// 职责：
//   - 固定裸数组字面量 `[a, b, ...]` 的端到端契约：子类元素可赋给 `Base[]`、
//     实现类元素可赋给接口 `I[]`，数组创建描述符与元素 aastore 必须正确。
//   - 固定「同一函数内多种元素类型数组赋值」不再因临时局部变量重名而污染槽位类型
//     （FINDINGS #3 的原始形状），包括字段数组赋值与多个数组字面量并存。
//   - 负向守门：元素类型与声明的数组元素类型不兼容时，编译期必须报明确错误且不产出 class。
//
// 所属层次 / 与其他模块关系：
//   测试层。只依赖 JasperCompiler 的公开编译入口与 JDK 自带 javap/URLClassLoader；
//   不触碰 checker/translator/backend 内部实现，断言的是可观察的编译结果与字节码。
//
// 产物位置：模块 build/scratch/array-assign/ 下，不写仓库外路径。
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
 * 做什么：C12（多元素数组字面量 / 数组赋值）的端到端回归测试。
 * 什么时候用：改动数组字面量推断、元素宽化、临时局部变量命名或后端 xASTORE 选择时。
 * 前置条件：测试 JVM 提供 javap（JDK 而非裸 JRE）。
 * 失败模式：编译失败、加载/执行抛 VerifyError/ArrayStoreException、或描述符/指令不符时失败。
 * 为什么这样设计：C12 的症状横跨「checker 的元素类型收敛」与「backend 的槽位/xASTORE」，
 *   只看 IR 或只看 .class 是否存在都不够；故统一真编译 + 真执行 + javap 证据。
 */
class ArrayAssignTest {

    /** 为单个用例准备干净输出目录（固定在模块 build/ 下）。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "array-assign", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 编译单文件源码并断言成功，返回输出目录。 */
    private fun compile(source: String, name: String): Path {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    /** 编译单文件源码并断言失败，返回输出目录与聚合诊断文本。 */
    private fun compileExpectError(source: String, name: String): Pair<Path, String> {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Error,
            "expected compilation to fail for $name, got: $result"
        )
        return outputDir to (result as JasperCompiler.CompileResult.Error).message
    }

    /** 加载输出目录并调用无参静态方法。 */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass(className)
            val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
            return target.invoke(null)
        }
    }

    /** 运行 javap -v -p 并把证据写入输出目录。 */
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

    /** 递归统计输出目录里的 .class 文件（负向用例断言「没有产物」）。 */
    private fun classFiles(outputDir: Path): List<Path> =
        Files.walk(outputDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".class") }.toList()
        }

    /**
     * C12 用例 1：多元素数组字面量 `Base[] = [new A(), new B()]` 真运行并虚分派。
     * 守什么：元素按声明类型 Base 装入、数组描述符为 `[Ltest/Base;`，虚方法分派到 A/B。
     */
    @Test
    fun `multi element subclass array literal widens to base and dispatches`() {
        val source = """
            package test;
            public class Base {
                public var n: int32;
                public constructor(n: int32) { this.n = n; }
                public function id(): int32 { return this.n; }
            }
            public class A extends Base {
                public constructor(n: int32) { super(n); }
                public function id(): int32 { return this.n + 10; }
            }
            public class B extends Base {
                public constructor(n: int32) { super(n); }
                public function id(): int32 { return this.n + 20; }
            }
            public function main(): int32 {
                var xs: Base[] = [new A(1), new B(2)];
                return xs[0].id() + xs[1].id();
            }
        """.trimIndent()
        val out = compile(source, "base-widening")
        assertEquals(33, invokeStatic(out, "test.main", "main"), "A.id=11 + B.id=22 应为 33")

        val code = javap(out, "test.main", "base-widening")
        assertTrue(code.contains("aastore"), "数组字面量填充必须使用 aastore:\n$code")
        assertTrue(code.contains("aaload"), "Base[] 元素读取必须使用 aaload:\n$code")
        // 经 Base[] 元素调用时，调用点 owner 是声明的 Base（JVM 运行期再做虚分派），
        // 关键是描述符必须是 ()I 而不是 ()V。
        assertTrue(code.contains("test/Base.id:()I"), "虚分派调用点应使用 Base.id:()I:\n$code")
        assertFalse(
            Regex("""test/(A|B|Base)\.id:\(\)V""").containsMatchIn(code),
            "方法描述符不得退化为 void:\n$code"
        )
    }

    /**
     * C12 用例 2：接口数组 `I[] = [new A(), new B()]`，经接口变量调用。
     * 守什么：元素宽化为接口 I，数组描述符 `[Ltest/I;`，接口调用点 owner 为 I。
     */
    @Test
    fun `interface array literal widens to interface and calls through interface`() {
        val source = """
            package test;
            public interface I { public function id(): int32; }
            public class A implements I {
                public var n: int32;
                public constructor(n: int32) { this.n = n; }
                public function id(): int32 { return this.n; }
            }
            public class B implements I {
                public var n: int32;
                public constructor(n: int32) { this.n = n; }
                public function id(): int32 { return this.n * 2; }
            }
            public function main(): int32 {
                var xs: I[] = [new A(3), new B(4)];
                var sum: int32 = 0;
                var i: int32 = 0;
                while (i < xs.length) {
                    sum = sum + xs[i].id();
                    i = i + 1;
                }
                return sum;
            }
        """.trimIndent()
        val out = compile(source, "interface-widening")
        assertEquals(11, invokeStatic(out, "test.main", "main"), "A.id=3 + B.id=8 应为 11")

        val code = javap(out, "test.main", "interface-widening")
        assertTrue(code.contains("aastore"), "接口数组填充必须使用 aastore:\n$code")
        assertTrue(code.contains("aaload"), "接口数组读取必须使用 aaload:\n$code")
        assertTrue(
            code.contains("test/I.id:()I"),
            "经接口变量调用应使用 I.id 的声明描述符:\n$code"
        )
    }

    /**
     * C12 用例 3：同一函数内多种元素类型的数组写值（FINDINGS #3 原始形状）。
     * 守什么：int32[] 与 string[] 的元素赋值临时槽不得互相污染；读取结果类型正确。
     */
    @Test
    fun `multiple element types in one method keep independent array slots`() {
        val source = """
            package test;
            public class T {
                private var ic: int32[];
                private var sc: string[];
                public constructor() {
                    this.ic = new int32[1];
                    this.sc = new string[1];
                    this.ic[0] = 1;
                    this.sc[0] = "ada";
                }
                public function i(): int32 { return this.ic[0]; }
                public function s(): string { return this.sc[0]; }
            }
            public function main(): int32 {
                var t = new T();
                if (t.s() == "ada") { return t.i() + 1; }
                return 0;
            }
        """.trimIndent()
        val out = compile(source, "mixed-element-types")
        assertEquals(2, invokeStatic(out, "test.main", "main"), "字符串/整数数组元素应各自读写正确")
    }

    /**
     * C12 用例 4：同一函数内多个不同元素类型的数组字面量。
     * 守什么：每个字面量的构造临时变量都有唯一名字，descriptor 不串味。
     */
    @Test
    fun `multiple array literals in one method keep independent descriptors`() {
        val source = """
            package test;
            public class Base {
                public var n: int32;
                public constructor(n: int32) { this.n = n; }
                public function id(): int32 { return this.n; }
            }
            public class A extends Base { public constructor(n: int32) { super(n); } }
            public function main(): int32 {
                var ints: int32[] = [1, 2];
                var strs: string[] = ["a", "b"];
                var objs: Base[] = [new A(5), new A(6)];
                return ints[0] + strs.length + objs[1].id();
            }
        """.trimIndent()
        val out = compile(source, "multiple-literals")
        // 1 + 2 + 6 = 9
        assertEquals(9, invokeStatic(out, "test.main", "main"), "多个数组字面量应各自保持元素类型")
    }

    /**
     * C12 用例 5：数组字段的声明描述符必须是数组（不是 V），且元素写入用 aastore。
     * 守什么：`Base[]` 字段的 JVM 描述符为 `[Ltest/Base;`，构造器里多元素填充正常。
     */
    @Test
    fun `array field descriptor is an array not void`() {
        val source = """
            package test;
            public class Base {
                public var n: int32;
                public constructor(n: int32) { this.n = n; }
                public function id(): int32 { return this.n; }
            }
            public class A extends Base { public constructor(n: int32) { super(n); } }
            public class B extends Base { public constructor(n: int32) { super(n); } }
            public class Holder {
                public var items: Base[];
                public constructor() { this.items = [new A(1), new B(2)]; }
                public function total(): int32 { return this.items[0].id() + this.items[1].id(); }
            }
            public function main(): int32 {
                var h = new Holder();
                return h.total();
            }
        """.trimIndent()
        val out = compile(source, "array-field-descriptor")
        assertEquals(3, invokeStatic(out, "test.main", "main"), "数组字段元素读取应为 1 + 2")

        val holder = javap(out, "test.Holder", "array-field-descriptor")
        assertTrue(
            holder.contains("[Ltest/Base;"),
            "items 字段描述符必须是 [Ltest/Base;，不能是 V:\n$holder"
        )
        assertFalse(
            Regex("""items;\s+descriptor:\s+V""").containsMatchIn(holder),
            "items 字段描述符不得为 void:\n$holder"
        )
    }

    /**
     * C12 用例 6：负向——元素类型与声明的数组元素类型不兼容时编译期报错且不产出 class。
     * 守什么：不能因为公共类型退化成 any 就静默放行，否则运行期会 ArrayStoreException。
     */
    @Test
    fun `incompatible array literal element is rejected at compile time`() {
        val source = """
            package test;
            public class Base {
                public var n: int32;
                public constructor(n: int32) { this.n = n; }
            }
            public class A extends Base { public constructor(n: int32) { super(n); } }
            public class Unrelated {
                public var s: string;
                public constructor(s: string) { this.s = s; }
            }
            public function main(): int32 {
                var xs: Base[] = [new A(1), new Unrelated("x")];
                return xs[0].n;
            }
        """.trimIndent()
        val (out, message) = compileExpectError(source, "incompatible-element")
        assertTrue(
            message.contains("Array literal element 2", ignoreCase = false),
            "诊断应明确指出第 2 个数组元素不兼容:\n$message"
        )
        assertTrue(
            message.contains("incompatible", ignoreCase = true),
            "诊断应包含 incompatible 说明:\n$message"
        )
        assertTrue(classFiles(out).isEmpty(), "编译失败时不得产出任何 .class: ${classFiles(out)}")
    }

    /**
     * C12 用例 7：跨文件/跨包的最小数组字面量赋值。
     * 守什么：元素类型与数组描述符跨包解析正确（`app.model.Base` / `app.model.A`）。
     */
    @Test
    fun `cross package array literal assignment runs`() {
        val model = """
            package app.model;
            public class Base {
                public var n: int32;
                public constructor(n: int32) { this.n = n; }
                public function id(): int32 { return this.n; }
            }
            public class A extends Base { public constructor(n: int32) { super(n); } }
            public class B extends Base { public constructor(n: int32) { super(n); } }
        """.trimIndent()
        val main = """
            package app;
            import app.model.Base;
            import app.model.A;
            import app.model.B;
            public function main(): int32 {
                var xs: Base[] = [new A(4), new B(5)];
                return xs[0].id() + xs[1].id();
            }
        """.trimIndent()
        val out = scratchDir("cross-package")
        val result = JasperCompiler().compileAllToDir(
            mapOf("Model.jas" to model, "Main.jas" to main), out
        )
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        assertEquals(9, invokeStatic(out, "app.main", "main"), "跨包 Base[] 字面量应为 4 + 5")
    }

    /**
     * C12 用例 8：对已有数组变量重新赋一个字面量（赋值右侧的上下文推断）。
     * 守什么：`xs = [..]` 也要按 xs 的声明元素类型生成描述符与 aastore。
     */
    @Test
    fun `array literal on assignment right hand side uses target element type`() {
        val source = """
            package test;
            public class Base {
                public var n: int32;
                public constructor(n: int32) { this.n = n; }
                public function id(): int32 { return this.n; }
            }
            public class A extends Base { public constructor(n: int32) { super(n); } }
            public class B extends Base { public constructor(n: int32) { super(n); } }
            public function main(): int32 {
                var xs: Base[] = [new A(1)];
                xs = [new A(2), new B(3)];
                return xs[1].id();
            }
        """.trimIndent()
        val out = compile(source, "assignment-rhs")
        assertEquals(3, invokeStatic(out, "test.main", "main"), "赋值右侧字面量应沿用目标元素类型")
    }
}
