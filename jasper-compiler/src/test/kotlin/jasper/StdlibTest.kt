package jasper

import jasper.compiler.JasperCompiler
import jasper.stdlib.StdLib
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real compile-and-execute tests for the Jasper standard library v1, first
 * phase: the function-object protocol family (`Mapper`, `Predicate`, `Reducer`,
 * `Comparator`, `Action`), the `Error` hierarchy, and the iteration protocol.
 *
 * Each protocol test compiles the standard library together with a user source,
 * instantiates the protocol with a lambda, invokes it, and asserts the result.
 * Output is written under the module's own `build/` directory (never an
 * external temp path).
 */
class StdlibTest {

    private fun freshOutputDir(name: String): Path {
        val base = Paths.get("build", "tmp", "stdlib-tests", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /** Compiles [source] with the stdlib, invokes `std.main.main()`, captures stdout. */
    private fun runMain(name: String, source: String): Pair<Any?, String> {
        val outputDir = freshOutputDir(name)
        try {
            val result = JasperCompiler().compileAllWithStdlib(mapOf("Main.jas" to source), outputDir)
            assertTrue(
                result is JasperCompiler.CompileResult.Success,
                "Compilation failed: ${(result as? JasperCompiler.CompileResult.Error)?.message ?: result}"
            )

            val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))
            val clazz = loader.loadClass("std.main")
            val method = clazz.getMethod("main")

            val originalOut = System.out
            val captured = ByteArrayOutputStream()
            val value = try {
                System.setOut(PrintStream(captured))
                method.invoke(null)
            } finally {
                System.setOut(originalOut)
            }
            return value to captured.toString()
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Mapper protocol is implemented by a lambda and applied`() {
        val (value, _) = runMain(
            "mapper",
            """
            package std;
            public function main(): int32 {
                var m: Mapper<int32, int32> = func(a: int32): int32 { return a + 1; };
                return m.apply(5);
            }
            """.trimIndent()
        )
        assertEquals(6, value, "Mapper.apply should add one")
    }

    @Test
    fun `Reducer protocol folds two values`() {
        val (value, _) = runMain(
            "reducer",
            """
            package std;
            public function main(): int32 {
                var r: Reducer<int32, int32> = lambda (acc: int32, x: int32) { return acc + x; };
                return r.reduce(3, 4);
            }
            """.trimIndent()
        )
        assertEquals(7, value, "Reducer.reduce should sum accumulator and value")
    }

    @Test
    fun `Comparator protocol orders two values`() {
        val (value, _) = runMain(
            "comparator",
            """
            package std;
            public function main(): int32 {
                var c: Comparator<int32> = lambda (a: int32, b: int32) { return a - b; };
                return c.compare(5, 3);
            }
            """.trimIndent()
        )
        assertEquals(2, value, "Comparator.compare should return a - b")
    }

    @Test
    fun `Predicate protocol tests true and false`() {
        val (value, _) = runMain(
            "predicate",
            """
            package std;
            public function main(): int32 {
                var p: Predicate<int32> = lambda (x: int32) { return x > 0; };
                var hits: int32 = 0;
                if (p.test(5)) { hits = hits + 1; }
                if (!p.test(0 - 3)) { hits = hits + 10; }
                return hits;
            }
            """.trimIndent()
        )
        assertEquals(11, value, "Predicate.test should classify positive and non-positive inputs")
    }

    @Test
    fun `Action protocol is invoked for its side effect`() {
        val (value, stdout) = runMain(
            "action",
            """
            package std;
            public function main(): int32 {
                var a: Action<int32> = lambda (t: int32) { println(t); };
                a.accept(9);
                return 0;
            }
            """.trimIndent()
        )
        assertEquals(0, value, "Action-using main should complete")
        assertEquals("9" + System.lineSeparator(), stdout, "Action.accept should print its argument")
    }

    @Test
    fun `Error is thrown caught and carries its message`() {
        val (value, _) = runMain(
            "error",
            """
            package std;
            public function main(): string {
                try {
                    throw new RecoverableError("boom");
                } catch (e: Error) {
                    return e.message();
                }
            }
            """.trimIndent()
        )
        assertEquals("boom", value, "Caught Error should expose the thrown message")
    }

    @Test
    fun `FatalError is catchable through the Error base class`() {
        val (value, _) = runMain(
            "fatal-error",
            """
            package std;
            public function main(): string {
                try {
                    throw new FatalError("fatal");
                } catch (e: Error) {
                    return e.message();
                }
            }
            """.trimIndent()
        )
        assertEquals("fatal", value, "FatalError should be catchable as Error")
    }

    @Test
    fun `Iterator protocol drives for-in traversal`() {
        val (value, _) = runMain(
            "iterator",
            """
            package std;
            public class IntRange implements Iterator<int32> {
                private var n: int32;
                private var i: int32;
                public constructor(n: int32) { this.n = n; this.i = 0; }
                public function hasNext(): bool { return this.i < this.n; }
                public function next(): int32 {
                    var v: int32 = this.i;
                    this.i = this.i + 1;
                    return v;
                }
            }
            public function main(): int32 {
                var r: IntRange = new IntRange(4);
                var sum: int32 = 0;
                for (x in r) {
                    sum = sum + x;
                }
                return sum;
            }
            """.trimIndent()
        )
        assertEquals(6, value, "for-in over an Iterator should visit 0+1+2+3")
    }

    /**
     * 以「标准库 + 用户源码」编译并要求失败。
     *
     * 做什么：复用与 [runMain] 相同的目录约定，断言编译返回 [JasperCompiler.CompileResult.Error]，
     *   并进一步断言输出目录里没有任何 `.class` 文件。
     * 什么时候用：验证协议实现不完整/签名错误必须在编译期被拒绝时。
     * 前置条件：stdout 捕获与本用例无关；输出目录固定在模块 `build/` 下。
     * 失败模式：编译意外成功或失败却仍产出类文件时，以带真实结果/文件列表的断言失败。
     * 为什么单独抽成 helper：成功路径与失败路径共享同一套「干净目录 + 标准库」装配，
     *   避免两条路径各自演化出不同的编译前提。
     */
    private fun compileWithStdlibError(name: String, source: String): JasperCompiler.CompileResult.Error {
        val outputDir = freshOutputDir(name)
        try {
            val result = JasperCompiler().compileAllWithStdlib(mapOf("Main.jas" to source), outputDir)
            assertTrue(
                result is JasperCompiler.CompileResult.Error,
                "Expected compilation to fail, but got: $result"
            )
            val classFiles = Files.walk(outputDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                    .toList()
            }
            assertTrue(
                classFiles.isEmpty(),
                "A rejected compilation must not emit class files, found: $classFiles"
            )
            return result as JasperCompiler.CompileResult.Error
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    /**
     * Hashable 成功路径：直接调用 `hash()`，并经由 `Equatable<Point>` 变量调用 `equals`。
     *
     * 为什么同时列出 `Equatable<Point>`：当前 JVM 后端只为类**直接** implements 的接口
     * 生成泛型擦除桥；`Hashable extends Equatable` 的继承桥不在其覆盖范围内。显式重复
     * 父接口不改变语义（Hashable 本就继承 Equatable），只是让 `equals(Point)` 的
     * `equals(Object)` 桥能生成，从而真正验证向上转型后的接口分派。
     */
    @Test
    fun `Hashable implementation exposes equals hash and widens to Equatable`() {
        val (value, _) = runMain(
            "hashable",
            """
            package std;
            public class Point implements Hashable<Point>, Equatable<Point> {
                private var x: int32;
                private var y: int32;
                public constructor(x: int32, y: int32) { this.x = x; this.y = y; }
                public function equals(other: Point): bool {
                    return this.x == other.x && this.y == other.y;
                }
                public function hash(): int32 { return this.x * 31 + this.y; }
            }
            public function main(): int32 {
                var p: Point = new Point(3, 4);
                var same: Point = new Point(3, 4);
                var other: Point = new Point(1, 2);
                var e: Equatable<Point> = p;
                if (!e.equals(same)) { return 1; }
                if (p.equals(other)) { return 2; }
                var h: int32 = p.hash();
                if (h != 97) { return 3; }
                return h;
            }
            """.trimIndent()
        )
        assertEquals(97, value, "Hashable.equals/hash should work directly and through Equatable")
    }

    @Test
    fun `Comparable implementation orders values through compareTo`() {
        val (value, _) = runMain(
            "comparable",
            """
            package std;
            public class Version implements Comparable<Version> {
                private var major: int32;
                public constructor(major: int32) { this.major = major; }
                public function compareTo(other: Version): int32 {
                    return this.major - other.major;
                }
            }
            public function main(): int32 {
                var newer: Version = new Version(5);
                var older: Version = new Version(3);
                var same: Version = new Version(5);
                if (newer.compareTo(older) <= 0) { return 1; }
                if (older.compareTo(newer) >= 0) { return 2; }
                if (newer.compareTo(same) != 0) { return 3; }
                return 7;
            }
            """.trimIndent()
        )
        assertEquals(7, value, "Comparable.compareTo should report negative, zero and positive ordering")
    }

    @Test
    fun `Iterable implementation returns a working Iterator`() {
        val (value, _) = runMain(
            "iterable",
            """
            package std;
            public class IntRangeIterator implements Iterator<int32> {
                private var n: int32;
                private var i: int32;
                public constructor(n: int32) { this.n = n; this.i = 0; }
                public function hasNext(): bool { return this.i < this.n; }
                public function next(): int32 {
                    var v: int32 = this.i;
                    this.i = this.i + 1;
                    return v;
                }
            }
            public class IntRange implements Iterable<int32> {
                private var n: int32;
                public constructor(n: int32) { this.n = n; }
                public function iterator(): Iterator<int32> {
                    return new IntRangeIterator(this.n);
                }
            }
            public function main(): int32 {
                var range: IntRange = new IntRange(4);
                var it: Iterator<int32> = range.iterator();
                var sum: int32 = 0;
                while (it.hasNext()) {
                    sum = sum + it.next();
                }
                return sum;
            }
            """.trimIndent()
        )
        assertEquals(6, value, "Iterable.iterator should drive an Iterator over 0+1+2+3")
    }

    @Test
    fun `Hashable implementation without hash is rejected`() {
        val error = compileWithStdlibError(
            "hashable-missing-hash",
            """
            package std;
            public class Point implements Hashable<Point> {
                private var x: int32;
                public constructor(x: int32) { this.x = x; }
                public function equals(other: Point): bool { return this.x == other.x; }
            }
            public function main(): int32 { return 0; }
            """.trimIndent()
        )
        assertTrue(
            error.errors.isNotEmpty(),
            "Missing Hashable.hash() must produce at least one diagnostic"
        )
    }

    @Test
    fun `Comparable compareTo with bool return type is rejected`() {
        val error = compileWithStdlibError(
            "comparable-bad-return",
            """
            package std;
            public class Version implements Comparable<Version> {
                public function compareTo(other: Version): bool { return true; }
            }
            public function main(): int32 { return 0; }
            """.trimIndent()
        )
        assertTrue(
            error.errors.isNotEmpty(),
            "compareTo returning bool must produce at least one diagnostic"
        )
    }

    @Test
    fun `standard library registers the protocol family in dependency order`() {
        val protocols = listOf("Equatable.jas", "Hashable.jas", "Comparable.jas", "Iterable.jas")
        assertTrue(StdLib.FILES.containsAll(protocols), "StdLib.FILES must list every protocol source")
        assertTrue(
            StdLib.FILES.indexOf("Equatable.jas") < StdLib.FILES.indexOf("Hashable.jas"),
            "Equatable must be translated before Hashable"
        )
        assertTrue(
            StdLib.FILES.indexOf("Iterator.jas") < StdLib.FILES.indexOf("Iterable.jas"),
            "Iterator must be translated before Iterable"
        )
        val sources = StdLib.sources()
        for (file in protocols) {
            assertTrue(sources.getValue(file).contains("package std;"), "$file should declare package std")
        }
        // 真正编译一次「标准库 + 用户源码」：只要任一新增文件解析失败，这里就会失败。
        val (value, _) = runMain(
            "protocol-family-parse",
            """
            package std;
            public function main(): int32 { return 0; }
            """.trimIndent()
        )
        assertEquals(0, value, "The whole standard library, including the new protocols, should compile")
    }

    @Test
    fun `standard library bundles every declared source`() {
        val sources = StdLib.sources()
        assertEquals(StdLib.FILES.toSet(), sources.keys, "StdLib.sources() should expose every declared file")
        for (file in StdLib.FILES) {
            val text = sources.getValue(file)
            assertTrue(text.contains("package std;"), "$file should declare package std")
            assertTrue(text.isNotBlank(), "$file should not be empty")
        }
    }

    /**
     * 做什么：验证空构造的 StringBuilder 同时满足 isEmpty() 为真、toString() 为 ""。
     * 失败模式：若平台缓冲未初始化或 isEmpty 判定错误，断言会给出实际返回值。
     * 为什么：空构造是链式追加与 clear 的共同基线，必须先固定下来。
     */
    @Test
    fun `StringBuilder empty construction reports empty and renders empty text`() {
        val (value, _) = runMain(
            "string-builder-empty",
            """
            package std;
            public function main(): string {
                var sb: StringBuilder = new StringBuilder();
                if (!sb.isEmpty()) {
                    return "not-empty";
                }
                return sb.toString();
            }
            """.trimIndent()
        )
        assertEquals("", value, "A fresh StringBuilder must be empty and render as an empty string")
    }

    /**
     * 做什么：验证 append 返回接收者，链式调用可依次追加中文与 emoji。
     * 失败模式：返回值不是自身或 UTF-8 文本受损时，最终字符串断言失败。
     * 为什么：链式契约与源码原样往返是文本模块的最小可用保证。
     */
    @Test
    fun `StringBuilder append chains text including Chinese and emoji`() {
        val (value, _) = runMain(
            "string-builder-append",
            """
            package std;
            public function main(): string {
                var sb: StringBuilder = new StringBuilder();
                sb.append("你好").append("🙂").append(" Jasper");
                return sb.toString();
            }
            """.trimIndent()
        )
        assertEquals("你好🙂 Jasper", value, "Chained append must preserve UTF-8 text and return the receiver")
    }

    /**
     * 做什么：验证 appendLine 在文本后追加且只追加一个 "\n"。
     * 失败模式：换行缺失或重复时，完整字符串断言失败。
     * 为什么：逐行生成文本依赖固定的换行语义，不能随宿主的行分隔符漂移。
     */
    @Test
    fun `StringBuilder appendLine appends text and a newline`() {
        val (value, _) = runMain(
            "string-builder-append-line",
            """
            package std;
            public function main(): string {
                var sb: StringBuilder = new StringBuilder();
                sb.appendLine("第一行").appendLine("第二行");
                return sb.toString();
            }
            """.trimIndent()
        )
        assertEquals("第一行\n第二行\n", value, "appendLine must append the text followed by a single newline")
    }

    /**
     * 做什么：验证 clear 之后 isEmpty() 为真，且同一实例仍可继续 append。
     * 失败模式：clear 未清空或使实例不可用时，后续断言失败。
     * 为什么：复用构建器是 clear 的唯一价值，必须覆盖清空后的继续使用。
     */
    @Test
    fun `StringBuilder clear empties the buffer and stays usable`() {
        val (value, _) = runMain(
            "string-builder-clear",
            """
            package std;
            public function main(): string {
                var sb: StringBuilder = new StringBuilder();
                sb.append("旧内容");
                sb.clear();
                if (!sb.isEmpty()) {
                    return "not-empty";
                }
                sb.append("重来");
                return sb.toString();
            }
            """.trimIndent()
        )
        assertEquals("重来", value, "clear must empty the buffer while keeping it usable for later appends")
    }

    /**
     * 做什么：验证 toString 返回的快照不随后续 append 改变。
     * 失败模式：若返回的是可变视图或延迟渲染，快照会变成 "snaptail"。
     * 为什么：调用方需要持有稳定的只读字符串，快照语义是文本模块的边界。
     */
    @Test
    fun `StringBuilder toString returns a snapshot unaffected by later appends`() {
        val (value, _) = runMain(
            "string-builder-snapshot",
            """
            package std;
            public function main(): string {
                var sb: StringBuilder = new StringBuilder();
                sb.append("snap");
                var snapshot: string = sb.toString();
                sb.append("tail");
                return snapshot + "|" + sb.toString();
            }
            """.trimIndent()
        )
        assertEquals("snap|snaptail", value, "A string obtained from toString must not change after further appends")
    }

    /**
     * 做什么：验证公开面之外的 sb.length() 在编译期被拒绝，且诊断包含方法名。
     * 失败模式：编译意外成功或诊断不含 "length" 时断言失败。
     * 为什么：标准库刻意不提供长度语义，必须证明平台方法没有泄漏到语言 API。
     */
    @Test
    fun `StringBuilder rejects methods outside its public surface`() {
        val error = compileWithStdlibError(
            "string-builder-unknown-method",
            """
            package std;
            public function main(): int32 {
                var sb: StringBuilder = new StringBuilder();
                sb.length();
                return 0;
            }
            """.trimIndent()
        )
        assertTrue(
            error.errors.any { it.message.contains("length") },
            "Calling the absent StringBuilder.length() must produce a diagnostic naming 'length', got: ${error.message}"
        )
    }

    /**
     * 做什么：验证 StdLib.FILES 登记了 StringBuilder，且排在协议族之后。
     * 失败模式：漏登记、顺序错误或资源不可读时断言失败。
     * 为什么：标准库以显式清单分发，登记与资源必须同步，否则装载期才暴露缺文件。
     */
    @Test
    fun `standard library registers StringBuilder after the protocol family`() {
        assertTrue(
            StdLib.FILES.contains("StringBuilder.jas"),
            "StdLib.FILES must list the StringBuilder source"
        )
        assertTrue(
            StdLib.FILES.indexOf("Iterable.jas") < StdLib.FILES.indexOf("StringBuilder.jas"),
            "StringBuilder must be translated after the protocol family"
        )
        assertTrue(
            StdLib.sources().getValue("StringBuilder.jas").contains("package std;"),
            "StringBuilder.jas should declare package std"
        )
    }
}
