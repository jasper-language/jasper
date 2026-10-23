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

    /**
     * 做什么：编译一个只调用 `call` 并返回其错误消息的 Jasper 程序。
     * 什么时候用：断言解析器拒绝非法输入、且异常类型与消息都符合契约时。
     * 前置条件：call 是 `Numbers.parseInt` / `parseFloat` 的完整调用表达式。
     * 失败模式：若调用没有抛 NumberFormatError，main 返回 "no-error"；若抛了别的
     *   异常类型，runMain 会把 InvocationTargetException 抛出来，让测试直接失败。
     * 为什么让 Jasper 侧 catch NumberFormatError：这样同时验证了「抛的是该类型」与
     *   「消息可由 catch 方读取」，比在 Kotlin 侧猜测异常包装更接近用户用法。
     */
    private fun rejectedMessage(name: String, call: String): String {
        val (value, _) = runMain(
            name,
            """
            package std;
            public function main(): string {
                try {
                    $call;
                    return "no-error";
                } catch (e: NumberFormatError) {
                    return e.message();
                }
            }
            """.trimIndent()
        )
        return value as String
    }

    /**
     * 做什么：断言解析调用被 NumberFormatError 拒绝，且消息包含原始输入。
     * 什么时候用：每个失败样例只需给出调用表达式与原始文本时。
     * 前置条件：input 是调用中传入的原始文本；空串用 contains 检查 `""` 标记。
     * 失败模式：没有抛错或消息不含输入时，以带真实消息的断言失败。
     * 为什么单独抽 helper：失败路径的装配（编译、catch、取 message）完全一致，
     *   避免十多个用例各自复制一遍。
     */
    private fun assertRejected(name: String, call: String, input: String) {
        val message = rejectedMessage(name, call)
        assertTrue(message != "no-error", "Expected $call to be rejected, but it returned normally")
        val marker = if (input.isEmpty()) "\"\"" else input
        assertTrue(
            message.contains(marker),
            "Rejection message must contain the original input '$input', got: $message"
        )
    }

    @Test
    fun `parseInt parses valid decimal integers`() {
        val (value, _) = runMain(
            "parse-int-valid",
            """
            package std;
            public function main(): int32 {
                if (parseInt("42") != 42) { return 1; }
                if (parseInt("-7") != 0 - 7) { return 2; }
                if (parseInt("+0") != 0) { return 3; }
                if (parseInt("2147483647") != 2147483647) { return 4; }
                if (parseInt("-2147483648") != (0 - 2147483647 - 1)) { return 5; }
                return 42;
            }
            """.trimIndent()
        )
        assertEquals(42, value, "parseInt should accept the specified signed decimal forms")
    }

    @Test
    fun `parseInt rejects empty input`() {
        assertRejected("parse-int-empty", "parseInt(\"\")", "")
    }

    @Test
    fun `parseInt rejects leading whitespace`() {
        assertRejected("parse-int-leading-space", "parseInt(\" 1\")", " 1")
    }

    @Test
    fun `parseInt rejects a decimal point`() {
        assertRejected("parse-int-decimal", "parseInt(\"1.0\")", "1.0")
    }

    @Test
    fun `parseInt rejects hexadecimal prefixes`() {
        assertRejected("parse-int-hex", "parseInt(\"0x10\")", "0x10")
    }

    @Test
    fun `parseInt rejects values above int32 max`() {
        assertRejected("parse-int-overflow", "parseInt(\"2147483648\")", "2147483648")
    }

    @Test
    fun `parseInt rejects values below int32 min`() {
        assertRejected("parse-int-underflow", "parseInt(\"-2147483649\")", "-2147483649")
    }

    /**
     * 做什么：验证超长输入在累加早期就按溢出拒绝，而不是被平台宽类型截断。
     * 失败模式：若实现先转成平台整数再检查范围，可能得到截断后的伪合法值。
     * 为什么：这是「溢出判定由 Jasper 自己算」的关键证据之一。
     */
    @Test
    fun `parseInt rejects a long digit string as overflow`() {
        assertRejected(
            "parse-int-long-overflow",
            "parseInt(\"123456789012345678901234567890\")",
            "123456789012345678901234567890"
        )
    }

    @Test
    fun `parseFloat parses decimals and scientific notation`() {
        val (value, _) = runMain(
            "parse-float-valid",
            """
            package std;
            public function main(): int32 {
                var decimal: float64 = parseFloat("1.5");
                if (decimal != 1.5) { return 1; }
                var scientific: float64 = parseFloat("-2e3");
                if (scientific != 0.0 - 2000.0) { return 2; }
                var fraction: float64 = parseFloat(".25");
                if (fraction != 0.25) { return 3; }
                var exponentSign: float64 = parseFloat("+1.5E-1");
                if (exponentSign != 0.15) { return 4; }
                var zero: float64 = parseFloat("-0");
                if (zero != 0.0) { return 5; }
                return 42;
            }
            """.trimIndent()
        )
        assertEquals(42, value, "parseFloat should accept decimal and scientific forms")
    }

    @Test
    fun `parseFloat rejects NaN text`() {
        assertRejected("parse-float-nan", "parseFloat(\"NaN\")", "NaN")
    }

    @Test
    fun `parseFloat rejects Infinity text`() {
        assertRejected("parse-float-infinity", "parseFloat(\"Infinity\")", "Infinity")
    }

    @Test
    fun `parseFloat rejects type suffixes`() {
        assertRejected("parse-float-suffix", "parseFloat(\"1f\")", "1f")
    }

    @Test
    fun `parseFloat rejects empty input`() {
        assertRejected("parse-float-empty", "parseFloat(\"\")", "")
    }

    /**
     * 做什么：验证 NumberFormatError 位于 RecoverableError 分支下，可被父类型捕获。
     * 失败模式：若层级挂错，`catch (e: RecoverableError)` 不会命中，main 会返回
     *   "no-error" 或让异常逃逸。
     * 为什么：任务书要求错误模型「异常为主」，且可恢复输入错误必须能被统一捕获。
     */
    @Test
    fun `NumberFormatError is catchable as RecoverableError`() {
        val (value, _) = runMain(
            "number-format-error-hierarchy",
            """
            package std;
            public function main(): string {
                try {
                    parseInt("not-a-number");
                    return "no-error";
                } catch (e: RecoverableError) {
                    return e.message();
                }
            }
            """.trimIndent()
        )
        val message = value as String
        assertTrue(message != "no-error", "RecoverableError must catch NumberFormatError")
        assertTrue(message.contains("not-a-number"), "Caught message should carry the original input")
    }

    @Test
    fun `standard library registers the numeric parsers after RecoverableError`() {
        assertTrue(StdLib.FILES.contains("NumberFormatError.jas"), "StdLib.FILES must list NumberFormatError.jas")
        assertTrue(StdLib.FILES.contains("Numbers.jas"), "StdLib.FILES must list Numbers.jas")
        assertTrue(
            StdLib.FILES.indexOf("RecoverableError.jas") < StdLib.FILES.indexOf("NumberFormatError.jas"),
            "NumberFormatError must be translated after RecoverableError"
        )
        assertTrue(
            StdLib.FILES.indexOf("NumberFormatError.jas") < StdLib.FILES.indexOf("Numbers.jas"),
            "Numbers must be translated after NumberFormatError"
        )
        assertTrue(
            StdLib.sources().getValue("NumberFormatError.jas").contains("extends RecoverableError"),
            "NumberFormatError.jas should extend RecoverableError"
        )
    }

    /**
     * 做什么：把任意文本安全地嵌入 Jasper 字符串字面量。
     * 什么时候用：属性测试把随机输入拼进生成的 Jasper 源码时。
     * 前置条件：text 不含换行（本用例的输入都是单行数字文本）。
     * 失败模式：若遗漏转义，生成的源码会语法错误并让编译断言失败。
     * 为什么不用库函数：测试内部只需要最小转义，显式写出更易审计。
     */
    private fun jasperStringLiteral(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /**
     * 做什么：固定种子生成合法/非法数字串，与 Kotlin 侧参考实现逐条比对。
     * 什么时候用：验证 Jasper 手写扫描/累加与平台解析结果在数量级与边界上一致。
     * 前置条件：随机种子固定，失败可复现；浮点比较使用 1e-12 相对容差（正确舍入
     *   与逐次乘除缩放可能有末位 ulp 差异）。
     * 失败模式：任一用例不一致时 main 返回该用例的编号，Kotlin 断言给出编号。
     * 为什么在测试侧可以用平台函数：它们只作参考对照，不进入标准库实现；这正是
     *   验收要求「Jasper 实现与参考实现逐条比对」的落地方式。
     */
    @Test
    fun `numeric parsers agree with a fixed-seed reference corpus`() {
        val random = kotlin.random.Random(20261023)
        val code = StringBuilder()
        var check = 0

        code.appendLine("package std;")
        code.appendLine("public function main(): int32 {")

        // 合法整数：显式覆盖边界，再随机覆盖整个 int32 范围。
        val validInts = linkedSetOf("0", "+0", "-0", "1", "-1", "2147483647", "-2147483648")
        repeat(30) {
            validInts.add(
                random.nextLong(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong() + 1).toInt().toString()
            )
        }
        for (text in validInts) {
            check += 1
            val expected = text.toInt()
            val expectedExpr = if (expected == Int.MIN_VALUE) "(0 - 2147483647 - 1)" else expected.toString()
            code.appendLine("    if (parseInt(${jasperStringLiteral(text)}) != $expectedExpr) { return $check; }")
        }

        // 合法浮点：十进制小数、指数、符号、前导零与点号前省略整数部分的组合。
        val validFloats = linkedSetOf("1.5", "-2e3", "0.1", "-0.0", "1e-5", "1e308", "5e-324", ".25")
        repeat(40) {
            val sign = when (random.nextInt(3)) {
                0 -> "-"
                1 -> "+"
                else -> ""
            }
            val integerPart = buildString {
                repeat(1 + random.nextInt(9)) {
                    append(('0'.code + random.nextInt(10)).toChar())
                }
            }
            val fractionPart = if (random.nextBoolean()) {
                buildString {
                    append('.')
                    repeat(1 + random.nextInt(9)) {
                        append(('0'.code + random.nextInt(10)).toChar())
                    }
                }
            } else {
                ""
            }
            val exponentPart = if (random.nextBoolean()) {
                val exponentSign = if (random.nextBoolean()) "-" else "+"
                "e$exponentSign${random.nextInt(0, 21)}"
            } else {
                ""
            }
            val text = sign + integerPart + fractionPart + exponentPart
            val reference = text.toDouble()
            if (reference.isFinite()) {
                validFloats.add(text)
            }
        }
        for (text in validFloats) {
            check += 1
            val reference = text.toDouble()
            // Double.toString 对有限值总会产生带小数点或指数的 Jasper 浮点字面量。
            val literal = reference.toString()
            val suffix = check.toString()
            code.appendLine("    var actual$suffix: float64 = parseFloat(${jasperStringLiteral(text)});")
            code.appendLine("    var expected$suffix: float64 = $literal;")
            code.appendLine("    var diff$suffix: float64 = actual$suffix - expected$suffix;")
            code.appendLine("    if (diff$suffix < 0.0) { diff$suffix = -diff$suffix; }")
            code.appendLine("    var scale$suffix: float64 = expected$suffix;")
            code.appendLine("    if (scale$suffix < 0.0) { scale$suffix = -scale$suffix; }")
            code.appendLine("    if (scale$suffix < 1.0) { scale$suffix = 1.0; }")
            code.appendLine("    if (diff$suffix > scale$suffix * 1.0e-12) { return $check; }")
        }

        // 非法输入：固定样例 + 在合法串中注入空格/字母，二者都必须被拒绝。
        val invalidInts = linkedSetOf(
            "", " ", " 1", "1 ", "+", "-", "1.0", "0x10", "1e3", "1f",
            "2147483648", "-2147483649", "123456789012345678901234567890"
        )
        val invalidFloats = linkedSetOf(
            "", " ", " 1", "1 ", "+", "-", "NaN", "Infinity", "-Infinity", "1f",
            "0x10", "1.", ".", "1e", "1e+", "1.2.3", "1e309"
        )
        repeat(10) {
            val base = validInts.elementAt(random.nextInt(validInts.size))
            val position = random.nextInt(base.length + 1)
            val injected = if (random.nextBoolean()) " " else "x"
            invalidInts.add(base.substring(0, position) + injected + base.substring(position))
        }
        repeat(10) {
            val base = validFloats.elementAt(random.nextInt(validFloats.size))
            val position = random.nextInt(base.length + 1)
            val injected = if (random.nextBoolean()) " " else "x"
            invalidFloats.add(base.substring(0, position) + injected + base.substring(position))
        }

        fun emitRejection(parser: String, text: String) {
            check += 1
            val suffix = check.toString()
            code.appendLine("    var threw$suffix: bool = false;")
            code.appendLine("    try { $parser(${jasperStringLiteral(text)}); } catch (e: NumberFormatError) { threw$suffix = true; }")
            code.appendLine("    if (!threw$suffix) { return $check; }")
        }
        for (text in invalidInts) emitRejection("parseInt", text)
        for (text in invalidFloats) emitRejection("parseFloat", text)

        code.appendLine("    return 0;")
        code.appendLine("}")

        val (value, _) = runMain("numeric-parser-property", code.toString())
        assertEquals(
            0,
            value,
            "Generated corpus mismatch at case $value (0 means every case matched the reference)"
        )
    }
}
