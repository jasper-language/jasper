// ============================================================================
// MemberOverloadTest.kt —— 成员方法按「参数个数」重载的端到端回归测试（Q28 / C10）
// ============================================================================
// 职责：
//   固定「类/接口的实例方法可以同名但参数个数不同」这一语义，并确保：
//     1. 检查器按 arity + 实参类型选择重载，不再只看见第一个同名声明；
//     2. 翻译/后端用**同一个**重载的声明签名生成调用描述符（javap 证据）；
//     3. 继承来的重载、接口声明的重载、跨文件/跨包的成员重载都可见；
//     4. 不存在的元数与同元数歧义给出明确诊断，且不落任何 .class。
//
// 所属层次 / 与其他模块关系：
//   测试层。只依赖 JasperCompiler 的公开入口（compileToDirWithIr / compileAllToDir /
//   CompileResult）以及 JDK 自带的 javap / URLClassLoader，不触碰 SymbolTable、
//   TypeChecker、translator、backend 的内部实现，描述的是可观察行为。
//
// 失败模式：编译失败、链接/验证期异常、返回值不符、或 javap 中调用描述符与选中的
//   重载不一致，都会让对应用例失败。
//
// 产物位置：所有 .class 与 javap 证据写在模块自己的 `build/scratch/member-overload/`
//   下，不落项目外临时目录。
//
// 已知语法边界（重要）：
//   权威 .g4 的 `functionParameter` 只允许 `name: Type` 与末尾 `...`，**没有参数默认值
//   语法**；`JasParameter.defaultValue` 目前恒为 null。本任务禁止修改 .g4，因此测试
//   无法写出 `q(x: string, y: int32 = 0)` 这种声明，改用「精确元数优先于 vararg/默认
//   元数」的可观察行为守门；解析/检查层的 arity 过滤仍按 required/default/vararg
//   三种来源实现，待语法未来开放默认参数时即可直接生效。
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
 * 做什么：Q28 / C10（成员方法不同元数重载）的端到端回归测试集合。
 * 什么时候用：`./gradlew test` 全量回归时自动执行。
 * 前置条件：测试 JVM 提供 `javap`（JDK 而非裸 JRE）。
 * 失败模式：编译诊断、类加载、方法调用或字节码描述符任一不符合预期时失败。
 * 为什么这样设计：重载决议横跨 SymbolTable（索引）、TypeChecker（选择）与
 *   translator/backend（描述符）三层；只测其中一层无法防止「检查选对了、字节码却
 *   调错重载」的退化，因此统一使用真编译 + 真加载 + 真执行 + javap 证据。
 */
class MemberOverloadTest {

    /** 为单个用例准备干净输出目录；固定在本模块 `build/` 下。 */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "member-overload", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** 编译单文件源码并要求成功；失败时把编译诊断带进断言消息。 */
    private fun compile(source: String, name: String): Path {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    /** 编译多文件源码并要求成功；覆盖跨文件/跨包的共享符号表路径。 */
    private fun compileAll(sources: Map<String, String>, name: String): Path {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileAllToDir(sources, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )
        return outputDir
    }

    /** 编译源码并要求失败，返回（诊断文本, 输出目录）。 */
    private fun compileError(source: String, name: String): Pair<String, Path> {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        val err = result as? JasperCompiler.CompileResult.Error
        assertTrue(err != null, "期望编译失败，实际成功：$name")
        return err!!.message to outputDir
    }

    /** 加载 `className` 的无参静态方法并调用，返回其结果。 */
    private fun invokeStatic(outputDir: Path, className: String, method: String): Any? {
        URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
            val clazz = loader.loadClass(className)
            val target = clazz.methods.first { it.name == method && it.parameterCount == 0 }
            return target.invoke(null)
        }
    }

    /** 对生成的 class 运行 `javap -p -c`，把证据落盘并返回反汇编文本。 */
    private fun javap(outputDir: Path, className: String, caseName: String): String {
        val javap = Paths.get(System.getProperty("java.home"), "bin", "javap").toString()
        val process = ProcessBuilder(
            javap, "-p", "-c", "-classpath", outputDir.toAbsolutePath().toString(), className
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        val evidence = outputDir.resolve("javap-${className.replace('.', '_')}-$caseName.txt")
        Files.writeString(evidence, output)
        assertEquals(0, exitCode, "javap failed for $className:\n$output")
        return output
    }

    /** 断言输出目录中没有指定类的 .class 产物。 */
    private fun assertNoClass(outputDir: Path, vararg classNames: String) {
        for (className in classNames) {
            val relative = className.replace('.', '/') + ".class"
            assertFalse(
                Files.exists(outputDir.resolve(relative)),
                "negative case must not emit $relative"
            )
        }
    }

    // ------------------------------------------------------------------
    // 1. 三种不同元数分别返回不同值，并用 javap 锁定三个调用描述符。
    // ------------------------------------------------------------------
    @Test
    fun `different arities select different overloads`() {
        val out = compile(
            """
            package test;
            public class P {
                public function q(): int32 { return 1; }
                public function q(x: string): int32 { return 2; }
                public function q(a: int32, b: int32): int32 { return 3; }
            }
            public function main(): int32 {
                var p: P = new P();
                return p.q() * 100 + p.q("x") * 10 + p.q(1, 2);
            }
            """.trimIndent(),
            "different-arities",
        )
        assertEquals(123, invokeStatic(out, "test.main", "main"))
        val text = javap(out, "test.main", "calls")
        assertTrue(text.contains("test/P.q:()I"), "missing zero-arg descriptor:\n$text")
        assertTrue(text.contains("test/P.q:(Ljava/lang/String;)I"), "missing string descriptor:\n$text")
        assertTrue(text.contains("test/P.q:(II)I"), "missing two-int descriptor:\n$text")
    }

    // ------------------------------------------------------------------
    // 2. 精确元数优先于 vararg 元数（默认参数在语法层尚不可写，见文件头说明）。
    // ------------------------------------------------------------------
    @Test
    fun `exact arity wins over vararg and default-capable overloads`() {
        val out = compile(
            """
            package test;
            public class Q {
                public function q(): int32 { return 1; }
                public function q(x: string): int32 { return 2; }
                public function q(xs: int32...): int32 { return 100 + xs.length; }
            }
            public function main(): int32 {
                var q: Q = new Q();
                return q.q() * 10000 + q.q("x") * 100 + q.q(1, 2, 3);
            }
            """.trimIndent(),
            "vararg-mix",
        )
        assertEquals(10303, invokeStatic(out, "test.main", "main"))
        val text = javap(out, "test.main", "calls")
        assertTrue(text.contains("test/Q.q:()I"), "zero-arg call must pick q():\n$text")
        assertTrue(text.contains("test/Q.q:(Ljava/lang/String;)I"), "string call must pick q(string):\n$text")
        assertTrue(text.contains("test/Q.q:([I)I"), "vararg call must pack int32[]:\n$text")
    }

    // ------------------------------------------------------------------
    // 3. 接口声明重载，经接口变量调用（INVOKEINTERFACE 描述符必须正确）。
    // ------------------------------------------------------------------
    @Test
    fun `interface overloads dispatch through interface typed variable`() {
        val out = compile(
            """
            package test;
            public interface I {
                function q(): int32;
                function q(x: string): int32;
            }
            public class C implements I {
                public function q(): int32 { return 5; }
                public function q(x: string): int32 { return 7; }
            }
            public function main(): int32 {
                var i: I = new C();
                return i.q() * 10 + i.q("x");
            }
            """.trimIndent(),
            "interface",
        )
        assertEquals(57, invokeStatic(out, "test.main", "main"))
        val text = javap(out, "test.main", "calls")
        assertTrue(text.contains("test/I.q:()I"), "missing interface zero-arg descriptor:\n$text")
        assertTrue(text.contains("test/I.q:(Ljava/lang/String;)I"), "missing interface string descriptor:\n$text")
        assertTrue(text.contains("invokeinterface"), "interface call must use invokeinterface:\n$text")
    }

    // ------------------------------------------------------------------
    // 4. 继承来的重载与子类新增重载同时可见。
    // ------------------------------------------------------------------
    @Test
    fun `inherited overloads coexist with subclass overloads`() {
        val out = compile(
            """
            package test;
            public class Base {
                public function q(): int32 { return 10; }
                public function q(x: string): int32 { return 20; }
            }
            public class Child extends Base {
                public function q(a: int32, b: int32): int32 { return 30; }
            }
            public function main(): int32 {
                var c: Child = new Child();
                return c.q() + c.q("x") + c.q(1, 2);
            }
            """.trimIndent(),
            "inheritance",
        )
        assertEquals(60, invokeStatic(out, "test.main", "main"))
        val text = javap(out, "test.main", "calls")
        // 继承方法在 JVM 上可以经子类常量池引用（invokevirtual Child.q 会解析到 Base.q），
        // 描述符必须与选中重载一致即可，不强制 owner 写成 Base。
        assertTrue(
            text.contains("test/Base.q:()I") || text.contains("test/Child.q:()I"),
            "inherited zero-arg call must target the inherited q():\n$text"
        )
        assertTrue(
            text.contains("test/Base.q:(Ljava/lang/String;)I") ||
                text.contains("test/Child.q:(Ljava/lang/String;)I"),
            "inherited string call must target the inherited q(string):\n$text"
        )
        assertTrue(text.contains("test/Child.q:(II)I"), "subclass call must target Child:\n$text")
    }

    // ------------------------------------------------------------------
    // 5. 跨文件/跨包：声明与调用点分处不同文件、不同包。
    // ------------------------------------------------------------------
    @Test
    fun `cross package member overloads link and run`() {
        val out = compileAll(
            linkedMapOf(
                "a/P.j" to """
                    package a;
                    public class P {
                        public function q(): int32 { return 4; }
                        public function q(x: string): int32 { return 6; }
                    }
                """.trimIndent(),
                "b/Main.j" to """
                    package b;
                    import a.P;
                    public function main(): int32 {
                        var p: P = new P();
                        return p.q() * 10 + p.q("x");
                    }
                """.trimIndent(),
            ),
            "cross-package",
        )
        assertEquals(46, invokeStatic(out, "b.main", "main"))
        val text = javap(out, "b.main", "calls")
        assertTrue(text.contains("a/P.q:()I"), "missing cross-package zero-arg descriptor:\n$text")
        assertTrue(text.contains("a/P.q:(Ljava/lang/String;)I"), "missing cross-package string descriptor:\n$text")
    }

    // ------------------------------------------------------------------
    // 6. 负向：不存在的元数必须报错，并列出可用元数；不得产出 .class。
    // ------------------------------------------------------------------
    @Test
    fun `unknown arity reports available arities and emits no class`() {
        val (message, out) = compileError(
            """
            package test;
            public class P {
                public function q(): int32 { return 1; }
                public function q(x: string): int32 { return 2; }
            }
            public function main(): int32 {
                var p: P = new P();
                return p.q(1, 2);
            }
            """.trimIndent(),
            "unknown-arity",
        )
        assertTrue(message.contains("q"), "diagnostic must name the method:\n$message")
        assertTrue(message.contains("available"), "diagnostic must list available arities:\n$message")
        assertTrue(message.contains("0") && message.contains("1"), "available arities must include 0 and 1:\n$message")
        assertNoClass(out, "test.P", "test.main")
    }

    // ------------------------------------------------------------------
    // 7a. 同元数、类型互斥：v1 暂不支持，声明处必须明确拒绝，不能静默取第一个。
    // ------------------------------------------------------------------
    @Test
    fun `same arity overloads are reported as unsupported`() {
        val (message, out) = compileError(
            """
            package test;
            public class P {
                public function q(x: string): int32 { return 1; }
                public function q(x: int32): int32 { return 2; }
            }
            public function main(): int32 {
                var p: P = new P();
                return p.q("x") * 10 + p.q(7);
            }
            """.trimIndent(),
            "same-arity-types",
        )
        assertTrue(message.contains("Duplicate method"), "diagnostic must reject duplicate arity:\n$message")
        assertTrue(message.contains("not supported"), "diagnostic must say same-arity is unsupported:\n$message")
        assertNoClass(out, "test.P", "test.main")
    }

    // ------------------------------------------------------------------
    // 7b. 同元数且多个候选都兼容（any vs string）：同样必须在声明处拒绝。
    // ------------------------------------------------------------------
    @Test
    fun `ambiguous same arity overloads are rejected explicitly`() {
        val (message, out) = compileError(
            """
            package test;
            public class P {
                public function q(x: any): int32 { return 1; }
                public function q(x: string): int32 { return 2; }
            }
            public function main(): int32 {
                var p: P = new P();
                return p.q("x");
            }
            """.trimIndent(),
            "ambiguous-same-arity",
        )
        assertTrue(message.contains("Duplicate method"), "diagnostic must reject duplicate arity:\n$message")
        assertTrue(message.contains("not supported"), "diagnostic must say same-arity is unsupported:\n$message")
        assertNoClass(out, "test.P", "test.main")
    }
}
