// ============================================================================
// CrossPackageLinkageTest.kt —— 「跨包 / 链接」类缺陷的回归测试
// ============================================================================
// 职责：
//   为 JDBC 探针（probes/jdbc/FINDINGS.md）第二批修复的四条缺陷各自钉一个
//   端到端回归测试，全部走真实编译管线（ANTLR → AST → checker → IR → JVM
//   字节码）并在同一 JVM 里加载运行，因此任何「静默错误字节码」都会在这里
//   被验证器/类加载器当场打回，而不是留给用户。
//
// 覆盖条目（一一对应）：
//   * #14 import 被忽略、跨包类型不可链接；
//   * #12 点分（多段）包名 + 接口实现 → 非法类名；
//   * #1  用户类对象数组丢失包名；
//   * #7  跨文件接口子类型赋值失败（实现类与赋值点分处不同文件）。
//
// 所属层次 / 与其他模块关系：
//   测试层。只依赖 jasper.compiler.JasperCompiler 的公开入口
//   （compileAllToDir + CompileResult），不触碰编译器内部实现，保证测试
//   「描述行为」而非「锁定实现」。测试产物写到本模块的 build/scratch/ 下，
//   绝不使用 /tmp 或仓库外的临时目录。
//
// 为什么每个用例都单独编译一组源文件：
//   #14/#7 的缺陷只在「多个包 / 多个文件」时才暴露，单文件用例无法复现；
//   因此本类全部使用 compileAllToDir（共享同一 SymbolTable 的多文件路径），
//   刻意不退回单文件入口，避免把真实摩擦挡在测试之外。
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
 * 跨包 / 链接类缺陷的回归测试集合。
 *
 * 做什么：把每个缺陷的最小复现写成「多文件源码 -> 编译 -> 反射调用 -> 断言返回值」。
 * 什么时候用：每次改动符号解析、类型映射或字节码后端后运行。
 * 前置条件：无外部依赖；编译器可在当前 JVM 内自举完成编译。
 * 失败模式：编译失败时以编译诊断信息断言失败；运行失败（验证器/类加载器拒绝）
 *   时由 JVM 抛出的异常直接使测试失败。
 * 为什么集中在一个类：四条的根因都落在「名字 → JVM 内部名」的解析链上，
 *   放在一起便于对照检查解析规则是否自洽。
 */
class CrossPackageLinkageTest {

    /** 为单个用例准备干净的输出目录；路径固定在模块 build/scratch 下。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "cross-package", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /**
     * 编译一组「文件名 -> 源码」并断言成功，返回输出目录。
     *
     * 为什么用 compileAllToDir：只有它会把所有文件放进同一 SymbolTable，
     * 从而覆盖跨文件/跨包的符号解析。
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

    /** 从输出目录加载类并调用其无参静态方法。 */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
        val clazz = loader.loadClass(className)
        val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
        return target.invoke(null)
    }

    /**
     * #14：一个包里的类型被另一个包 `import` 后可以引用、实例化并调用。
     *
     * 守什么：代码生成必须使用 import 信息把简单名解析到被导入的全限定类名，
     * 而不是一律拼成「当前包 + 简单名」。若回归，`new Thing()` 会生成
     * `app/Thing` 导致运行时 NoClassDefFoundError / ClassNotFoundException。
     */
    @Test
    fun `imported type from another package links and runs`() {
        val thing = """
            package a.b;
            public class Thing {
                public function v(): int32 { return 41; }
            }
        """.trimIndent()
        val app = """
            package app;
            import a.b.Thing;
            public function main(): int32 {
                var t: Thing = new Thing();
                return t.v() + 1;
            }
        """.trimIndent()
        val out = compileAll(mapOf("Thing.jas" to thing, "App.jas" to app), "import-cross-package")
        assertEquals(42, invokeStatic(out, "app.main", "main"))
    }

    /**
     * #12：多段（点分）包名下的接口实现类名必须被规范成 JVM 内部名。
     *
     * 守什么：`implements` 记录的接口名要把包名里的 `.` 换成 `/`，否则
     * ClassWriter 会写出 `com.example.deep/Sink` 这种非法内部名，
     * 加载时报 ClassFormatError。
     */
    @Test
    fun `dotted package name with interface implementation is legal`() {
        val source = """
            package com.example.deep;
            public interface Sink { public function send(): int32; }
            public class Impl implements Sink { public function send(): int32 { return 7; } }
            public function main(): int32 { var p: Sink = new Impl(); return p.send(); }
        """.trimIndent()
        val out = compileAll(mapOf("Sink.jas" to source), "dotted-package-interface")
        assertEquals(7, invokeStatic(out, "com.example.deep.main", "main"))
    }

    /**
     * #1：用户类对象数组可以创建、写入并读回字段。
     *
     * 守什么：`new Row[n]` 的数组元素描述符必须带包名（`[Larr/Row;`），
     * 否则 ANEWARRAY 会去找无包名的 `Row`，类加载失败。
     */
    @Test
    fun `user class object array keeps package and round-trips fields`() {
        val source = """
            package arr;
            public class Row {
                public var v: int32;
                public constructor(v: int32) { this.v = v; }
            }
            public function main(): int32 {
                var rows: Row[] = new Row[3];
                rows[0] = new Row(10);
                rows[1] = new Row(20);
                rows[2] = new Row(30);
                var sum: int32 = 0;
                var i: int32 = 0;
                while (i < 3) {
                    sum = sum + rows[i].v;
                    i = i + 1;
                }
                return sum;
            }
        """.trimIndent()
        val out = compileAll(mapOf("ArrayMain.jas" to source), "user-class-array")
        assertEquals(60, invokeStatic(out, "arr.main", "main"))
    }

    /**
     * #7：接口与其实现类分处不同文件时，接口类型赋值仍然成立。
     *
     * 守什么：类型检查器判断接口子类型时，必须能看到「其它文件」里登记的
     * `implements` 关系；否则 `var s: S = new MyS()` 会被误报
     * "cannot assign MyS to variable of type S"。
     */
    @Test
    fun `interface implementation in a separate file is assignable`() {
        val iface = "package x; public interface S { public function k(): string; }"
        val impl = "package x; public class MyS implements S { public function k(): string { return \"x\"; } }"
        val main = """
            package x;
            public function main(): int32 {
                var s: S = new MyS();
                if (s.k() == "x") { return 1; }
                return 0;
            }
        """.trimIndent()
        val out = compileAll(
            mapOf("S.jas" to iface, "MyS.jas" to impl, "Main.jas" to main),
            "cross-file-implementation"
        )
        assertEquals(1, invokeStatic(out, "x.main", "main"))
    }
}
