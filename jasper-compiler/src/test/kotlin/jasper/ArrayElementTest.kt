// ============================================================================
// ArrayElementTest.kt —— Q29（C11）：数组元素读取的类型与字节码回归测试
// ============================================================================
// 职责：
//   - 固定「`expr[index]` 的静态类型就是数组元素类型」这一契约：读取结果必须能
//     直接参与算术、作为方法实参/返回值、调用成员方法，以及赋给不带显式类型的
//     `var` 后继续使用。
//   - 用真编译 + URLClassLoader 真执行 + javap 断言三重守门，确保元素读取在
//     JVM 层生成 xALOAD（基本类型）或 AALOAD（引用类型），而不是把 Unit/V
//     当成结果类型。
//
// 所属层次 / 与其他模块关系：
//   测试层。只依赖 JasperCompiler 的公开编译入口与 JDK 自带 javap/URLClassLoader，
//   不触碰 TypeChecker / translator / backend 的内部实现，因此断言的是可观察行为。
//
// 产物位置：模块 build/scratch/array-element/ 下，不写仓库外路径。
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
 * 做什么：C11（数组元素读取类型）的端到端回归测试。
 * 什么时候用：改动类型推断、数组翻译或字节码加载指令时运行。
 * 前置条件：测试 JVM 提供 javap（JDK 而非裸 JRE）。
 * 失败模式：编译失败、加载/执行抛 VerifyError、或指令/描述符不符时断言失败。
 * 为什么这样设计：该缺陷的根因可能在 checker（推断成 void）或 translator
 *   （IR 结果类型写成 Unit），单层测试无法定位；真编译 + 真执行能同时覆盖两层。
 */
class ArrayElementTest {

    /** 为单个用例准备干净输出目录（固定在模块 build/ 下）。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "array-element", name)
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

    /**
     * C11 用例 1：`int32[]` 元素读取后直接参与算术。
     * 守什么：`a[0] + 1` 不能被推断成 void；后端必须生成 IALOAD + IADD。
     */
    @Test
    fun `int array element participates directly in arithmetic`() {
        val source = """
            package test;
            public function main(): int32 {
                var a: int32[] = [1, 2, 3];
                return a[0] + 1;
            }
        """.trimIndent()
        val out = compile(source, "int-arithmetic")
        assertEquals(2, invokeStatic(out, "test.main", "main"), "a[0] + 1 应为 2")
        val code = javap(out, "test.main", "int-arithmetic")
        assertTrue(code.contains("iaload"), "int32[] 读取必须生成 iaload:\n$code")
    }

    /**
     * C11 用例 2：`string[]` 元素读取后作为方法实参并作为返回值。
     * 守什么：引用元素读取必须生成 AALOAD，且返回值描述符为 String。
     */
    @Test
    fun `string array element works as argument and return value`() {
        val source = """
            package test;
            public class Sink {
                public function keep(v: string): string { return v; }
            }
            public function take(v: string): string { return v + "!"; }
            public function main(): string {
                var a: string[] = ["ab", "cd"];
                var s: Sink = new Sink();
                return take(s.keep(a[1]));
            }
        """.trimIndent()
        val out = compile(source, "string-argument-return")
        assertEquals("cd!", invokeStatic(out, "test.main", "main"), "string[] 读取应可作实参/返回值")
        val code = javap(out, "test.main", "string-argument-return")
        assertTrue(code.contains("aaload"), "string[] 读取必须生成 aaload:\n$code")
    }

    /**
     * C11 用例 3：对象数组元素读取后直接调用成员方法。
     * 守什么：`rows[i].id()` 的接收者类型必须是元素类，AALOAD 后 invokes the method。
     */
    @Test
    fun `object array element calls member method directly`() {
        val source = """
            package test;
            public class Row {
                public var n: int32;
                public constructor(n: int32) { this.n = n; }
                public function id(): int32 { return this.n; }
            }
            public function main(): int32 {
                var rows: Row[] = [new Row(7), new Row(9)];
                return rows[1].id();
            }
        """.trimIndent()
        val out = compile(source, "object-member-call")
        assertEquals(9, invokeStatic(out, "test.main", "main"), "rows[1].id() 应为 9")
        val code = javap(out, "test.main", "object-member-call")
        assertTrue(code.contains("aaload"), "对象数组读取必须生成 aaload:\n$code")
        assertTrue(
            code.contains("test/Row.id:()I"),
            "经数组元素调用成员方法必须使用 Row.id 的正确描述符:\n$code"
        )
    }

    /**
     * C11 用例 4：元素读取赋给不带显式类型的 `var` 后继续使用。
     * 守什么：`var x = a[0]` 的推断类型必须是 int32，而不是 void/unit；
     * 这是旧探针「先写显式类型局部变量」规避的原始形状。
     */
    @Test
    fun `inferred local from array element keeps element type`() {
        val source = """
            package test;
            public function main(): int32 {
                var a: int32[] = [4, 5, 6];
                var x = a[2];
                return x + 1;
            }
        """.trimIndent()
        val out = compile(source, "inferred-local")
        assertEquals(7, invokeStatic(out, "test.main", "main"), "var x = a[2] 后 x + 1 应为 7")
        val code = javap(out, "test.main", "inferred-local")
        assertTrue(code.contains("iaload"), "推断局部变量的初值读取必须生成 iaload:\n$code")
        assertTrue(code.contains("iadd"), "推断局部变量参与算术必须生成 iadd:\n$code")
    }

    /**
     * C11 用例 5：跨文件/跨包的对象数组元素读取与成员调用。
     * 守什么：元素类型解析与用户类数组描述符在跨包时仍正确。
     */
    @Test
    fun `cross package object array element reads and dispatches`() {
        val row = """
            package app.model;
            public class Row {
                public var n: int32;
                public constructor(n: int32) { this.n = n; }
                public function id(): int32 { return this.n; }
            }
        """.trimIndent()
        val main = """
            package app;
            import app.model.Row;
            public function main(): int32 {
                var rows: Row[] = [new Row(3), new Row(8)];
                var x = rows[1];
                return x.id() + rows[0].id();
            }
        """.trimIndent()
        val out = scratchDir("cross-package")
        val result = JasperCompiler().compileAllToDir(
            mapOf("Row.jas" to row, "Main.jas" to main), out
        )
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        assertEquals(11, invokeStatic(out, "app.main", "main"), "跨包对象数组读取应为 3 + 8")
    }
}
