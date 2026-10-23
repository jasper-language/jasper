// ============================================================================
// RemoveMethodTest.kt —— Q31（C15）：名为 `remove` 的接口/类方法不能被容器兜底吞掉
// 所属层次：测试层。只依赖 JasperCompiler 的公开编译入口与 JDK 自带 javap/URLClassLoader。
//
// 干什么：锁定「用户声明的 remove 永远按声明签名生成」这一语义，并证明后端的 JDK
//   流畅 API 兜底（StringBuilder.append* 返回接收者）只在**已知 JDK 接收者**上生效：
//     1. 接口 `remove` + 实现类，经接口变量调用 → `invokeinterface Store.remove(...)`；
//     2. 类方法 `remove` 经具体类型调用 → `invokevirtual` + 声明描述符；
//     3. 带参数/返回值的接口方法，javap 看到声明的擦除描述符；
//     4. StringBuilder.append 旧行为不回归；List/Map 集合成员改用 JDK 真实签名；
//     5. 同名但不同 owner 的两个 remove，各自使用自己的描述符；
//     6. 负向：接口没有 remove 时给出明确编译错误，且不产出 .class。
//
// 为什么必须真跑：JPA #7 的症状是编译通过、运行期 `VerifyError: Bad type on operand
//   stack`（ifeq 收到接收者引用）。只有真编译 + 真加载执行才能暴露；javap 则把
//   「声明签名优先」与「兜底边界」固定在字节码形状上，防止以后又按方法名全局特判。
//
// 产物位置：jasper-compiler/build/scratch/remove-method/ 下，不写仓库外路径。
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
 * 做什么：Q31 / C15（`remove` 方法名与 JDK 容器兜底冲突）的端到端回归测试集合。
 * 什么时候用：改动 translator 的成员签名索引（`memberSignaturesByType` / `memberCallKeys`）
 *   或 JvmIrBackend 的成员调用描述符生成逻辑时。
 * 前置条件：测试 JVM 提供 `javap`（JDK 而非裸 JRE）。
 * 失败模式：编译失败、类加载/执行抛 VerifyError/NoSuchMethodError、或 javap 中的
 *   owner/描述符与声明不一致时，对应用例失败。
 * 为什么这样设计：该缺陷横跨「签名预登记 → 调用点选键 → 后端描述符」三层，且症状只在
 *   JVM 链接/验证阶段出现；因此统一使用真编译 + 真执行 + javap，而不是断言内部 Map。
 */
class RemoveMethodTest {

    /**
     * 做什么：为单个用例准备干净输出目录（固定在模块 build/ 下）。
     * 什么时候用：每个测试开始处。
     * 前置条件：无。
     * 失败模式：创建失败时抛 IO 异常，测试直接失败。
     * 为什么这样设计：产物落在仓库内、可复查，且不写项目外临时目录。
     */
    private fun scratchDir(name: String): Path {
        val base = Paths.get("build", "scratch", "remove-method", name)
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
     * 做什么：编译单文件源码并断言失败，返回（诊断文本, 输出目录）。
     * 什么时候用：负向用例（调用不存在的接口方法）。
     * 前置条件：source 是完整源文件文本。
     * 失败模式：编译意外成功时断言失败。
     */
    private fun compileError(source: String, name: String): Pair<String, Path> {
        val outputDir = scratchDir(name)
        val result = JasperCompiler().compileToDirWithIr(source, outputDir)
        val err = result as? JasperCompiler.CompileResult.Error
        assertTrue(err != null, "期望编译失败，实际成功：$name")
        return err!!.message to outputDir
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
     * 做什么：对生成的 class 运行 `javap -p -c`，返回反汇编文本并把证据写入 build/。
     * 什么时候用：需要断言 owner / 调用描述符形状时。
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
    // 1. 接口 remove + 实现类，经接口变量调用：真执行 + invokeinterface。
    // ------------------------------------------------------------------
    @Test
    fun `interface remove dispatches through interface variable`() {
        val out = compile(
            """
            package test;
            public interface Store {
                public function remove(id: int32): bool;
            }
            public class MemStore implements Store {
                public function remove(id: int32): bool { return id == 1; }
            }
            public function main(): int32 {
                var s: Store = new MemStore();
                if (s.remove(1)) { return 100; }
                return 0;
            }
            """.trimIndent(),
            "interface-dispatch",
        )
        assertEquals(100, invokeStatic(out, "test.main", "main"), "经接口变量调用 remove 应返回声明的 bool")
        val code = javap(out, "test.main", "interface-dispatch")
        assertTrue(
            code.contains("InterfaceMethod test/Store.remove:(I)Z"),
            "接口调用必须是 invokeinterface Store.remove(I)Z:\n$code"
        )
        // 反向证据：不能退化成按接收者类型生成的错误描述符。
        assertFalse(
            code.contains("remove:(I)Ltest/Store;"),
            "remove 的返回描述符不得被容器兜底改成接收者类型:\n$code"
        )
    }

    // ------------------------------------------------------------------
    // 2. 类方法 remove 经具体类型调用：invokevirtual + 声明描述符。
    // ------------------------------------------------------------------
    @Test
    fun `class remove called through concrete type keeps declared descriptor`() {
        val out = compile(
            """
            package test;
            public class Counter {
                public function remove(): int32 { return 7; }
            }
            public function main(): int32 {
                var c: Counter = new Counter();
                return c.remove();
            }
            """.trimIndent(),
            "concrete-type",
        )
        assertEquals(7, invokeStatic(out, "test.main", "main"), "具体类型上的 remove 应返回声明的 int32")
        val code = javap(out, "test.main", "concrete-type")
        assertTrue(
            code.contains("Method test/Counter.remove:()I"),
            "具体类型调用必须使用声明描述符 Counter.remove()I:\n$code"
        )
        assertFalse(
            code.contains("remove:()Ltest/Counter;"),
            "remove 的返回描述符不得被容器兜底改成接收者类型:\n$code"
        )
    }

    // ------------------------------------------------------------------
    // 3. 接口方法带参数/返回值：javap 证据锁定完整擦除描述符。
    // ------------------------------------------------------------------
    @Test
    fun `interface remove with parameters uses full declared descriptor`() {
        val out = compile(
            """
            package test;
            public interface Store {
                public function remove(name: string, id: int32): bool;
            }
            public class MemoryStore implements Store {
                public function remove(name: string, id: int32): bool { return id > 0; }
            }
            public function main(): int32 {
                var s: Store = new MemoryStore();
                if (s.remove("Person", 1)) { return 100; }
                return 0;
            }
            """.trimIndent(),
            "interface-params",
        )
        assertEquals(100, invokeStatic(out, "test.main", "main"), "带参数的接口 remove 应正常分派")
        val code = javap(out, "test.main", "interface-params")
        assertTrue(
            code.contains("InterfaceMethod test/Store.remove:(Ljava/lang/String;I)Z"),
            "接口调用应使用 (Ljava/lang/String;I)Z:\n$code"
        )
        val storeCode = javap(out, "test.Store", "interface-params")
        assertTrue(
            storeCode.contains("public abstract boolean remove(java.lang.String, int);"),
            "接口声明应保留 bool 返回:\n$storeCode"
        )
    }

    // ------------------------------------------------------------------
    // 4a. StringBuilder.append 旧兜底不回归：返回接收者，链式/后续调用可用。
    // ------------------------------------------------------------------
    @Test
    fun `string builder append still returns receiver`() {
        val out = compile(
            """
            package test;
            public function main(): string {
                var sb = new java.lang.StringBuilder();
                sb.append("a");
                sb.append("b");
                sb.append(1);
                return sb.toString();
            }
            """.trimIndent(),
            "string-builder",
        )
        assertEquals("ab1", invokeStatic(out, "test.main", "main"), "StringBuilder.append 的基本行为不得回归")
        val code = javap(out, "test.main", "string-builder")
        assertTrue(
            code.contains("Method java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;"),
            "StringBuilder.append 必须保留返回接收者的 JDK 描述符:\n$code"
        )
        assertTrue(
            code.contains("Method java/lang/StringBuilder.append:(I)Ljava/lang/StringBuilder;"),
            "StringBuilder.append(int) 必须保留返回接收者的 JDK 描述符:\n$code"
        )
    }

    // ------------------------------------------------------------------
    // 4b. List/Map 的成员调用使用 JDK 真实签名（描述符形状锁）。
    //     语言内建集合元数据把 java.util.ArrayList/HashMap 也归入 LIST/MAP 族，
    //     因此 get/set/remove 必须走 PlatformMethodResolver 的真实签名，而不是
    //     旧的「返回接收者」流畅兜底。该用例把真实描述符固定在字节码形状上。
    // ------------------------------------------------------------------
    @Test
    fun `jdk list and map containers use real platform signatures`() {
        val out = compile(
            """
            package test;
            public function main(): int32 {
                var xs: java.util.ArrayList = new java.util.ArrayList();
                xs.get(0);
                xs.set(0, "x");
                xs.remove("x");
                var m = dict{ "a": 1 };
                m.get("a");
                m.remove("a");
                return 0;
            }
            """.trimIndent(),
            "containers",
        )
        val code = javap(out, "test.main", "containers")
        // get/set/remove 使用 JDK 真实签名：参数按其真实形参描述符，返回 Object/boolean。
        assertTrue(
            code.contains("Method java/util/ArrayList.get:(I)Ljava/lang/Object;"),
            "ArrayList.get 必须使用 JDK 真实签名 (I)Ljava/lang/Object;:\n$code"
        )
        assertTrue(
            code.contains("Method java/util/ArrayList.set:(ILjava/lang/Object;)Ljava/lang/Object;"),
            "ArrayList.set 必须使用 JDK 真实签名 (ILjava/lang/Object;)Ljava/lang/Object;:\n$code"
        )
        assertTrue(
            code.contains("Method java/util/ArrayList.remove:(Ljava/lang/Object;)Z"),
            "ArrayList.remove 必须使用 JDK 真实签名 (Ljava/lang/Object;)Z:\n$code"
        )
        assertTrue(
            code.contains("Method java/util/HashMap.get:(Ljava/lang/Object;)Ljava/lang/Object;"),
            "HashMap.get 必须使用 JDK 真实签名 (Ljava/lang/Object;)Ljava/lang/Object;:\n$code"
        )
        assertTrue(
            code.contains("Method java/util/HashMap.remove:(Ljava/lang/Object;)Ljava/lang/Object;"),
            "HashMap.remove 必须使用 JDK 真实签名 (Ljava/lang/Object;)Ljava/lang/Object;:\n$code"
        )
    }

    // ------------------------------------------------------------------
    // 5. 同名但不同 owner 的两个 remove：各自使用自己的声明描述符。
    // ------------------------------------------------------------------
    @Test
    fun `two remove methods with different owners keep their own descriptors`() {
        val out = compile(
            """
            package test;
            public class Counter {
                public function remove(): int32 { return 2; }
            }
            public interface Store {
                public function remove(id: int32): bool;
            }
            public class MemStore implements Store {
                public function remove(id: int32): bool { return id == 1; }
            }
            public function main(): int32 {
                var c: Counter = new Counter();
                var s: Store = new MemStore();
                var total: int32 = c.remove();
                if (s.remove(1)) { total = total + 10; }
                return total;
            }
            """.trimIndent(),
            "two-owners",
        )
        assertEquals(12, invokeStatic(out, "test.main", "main"), "两个不同 owner 的 remove 应各自分派")
        val code = javap(out, "test.main", "two-owners")
        assertTrue(code.contains("Method test/Counter.remove:()I"), "Counter.remove 应使用 ()I:\n$code")
        assertTrue(
            code.contains("InterfaceMethod test/Store.remove:(I)Z"),
            "Store.remove 应使用 (I)Z:\n$code"
        )
    }

    // ------------------------------------------------------------------
    // 6. 负向：接口没有 remove 时给出明确错误，且不产出 .class。
    // ------------------------------------------------------------------
    @Test
    fun `calling remove on interface without declaration is rejected clearly`() {
        val (message, outputDir) = compileError(
            """
            package test;
            public interface Empty {
                public function has(id: int32): bool;
            }
            public class EmptyImpl implements Empty {
                public function has(id: int32): bool { return true; }
            }
            public function main(): int32 {
                var e: Empty = new EmptyImpl();
                if (e.remove(1)) { return 1; }
                return 0;
            }
            """.trimIndent(),
            "missing-remove",
        )
        assertTrue(
            message.contains("Cannot resolve member 'remove'"),
            "应报告无法解析成员 remove，实际诊断：$message"
        )
        assertTrue(message.contains("Empty"), "诊断应指出接收者类型 Empty，实际诊断：$message")
        assertFalse(
            Files.exists(outputDir.resolve("test/main.class")),
            "编译失败时不得产出 test/main.class"
        )
    }

    // ------------------------------------------------------------------
    // 7. 兜底白名单的用户类型边界：未登记签名的用户类型（枚举）也不能被
    //    方法名 `remove` 误伤。这是 JPA #7 根因的直接回归守卫。
    // ------------------------------------------------------------------
    @Test
    fun `user defined remove on unindexed class kind ignores jdk fallback`() {
        val out = compile(
            """
            package test;
            public enum Mode {
                A,
                B;
                public function remove(id: int32): bool { return id == 1; }
            }
            public function main(): int32 {
                var m: Mode = Mode.A;
                if (m.remove(1)) { return 3; }
                return 0;
            }
            """.trimIndent(),
            "user-type-fallback",
        )
        assertEquals(3, invokeStatic(out, "test.main", "main"), "用户类型的 remove 必须按 IR 类型返回 bool")
        val code = javap(out, "test.main", "user-type-fallback")
        assertTrue(
            code.contains("Method test/Mode.remove:(I)Z"),
            "用户类型 remove 必须使用 (I)Z，而不是接收者描述符:\n$code"
        )
        assertFalse(
            code.contains("remove:(I)Ltest/Mode;"),
            "用户类型不得命中 JDK 流畅 API 兜底:\n$code"
        )
    }
}
