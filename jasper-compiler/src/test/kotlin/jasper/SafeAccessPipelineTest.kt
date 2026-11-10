// ============================================================================
// SafeAccessPipelineTest.kt —— `?.` 安全访问与可空基本类型（`int32?`）的端到端回归
// ============================================================================
// 真编译 + 真加载 + 真执行：缺陷本身横跨检查器（结果类型）、翻译器（空判断与接收者只求值
// 一次）、后端（包装类描述符与装箱），只看 IR 或 .class 是否存在都发现不了。
// 产物写在模块 build/tmp 下，不落项目外目录。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafeAccessPipelineTest {

    private val root: Path = Paths.get("build", "tmp", "safe-access-pipeline")

    private fun freshOutput(name: String): Path {
        val output = root.resolve(name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        return output
    }

    /** 公共声明：Node 用显式构造器赋字段；Trace 记录求值顺序（reset 后才可读）。 */
    private val prelude = """
        package demo;

        public class Trace {
            public static var log: string = "";
            public static function reset(): void { Trace.log = ""; }
            public static function tick(k: int32): int32 {
                Trace.log = Trace.log + k + ";";
                return k;
            }
            public static function pass(n: Node?, k: int32): Node? {
                Trace.log = Trace.log + k + ";";
                return n;
            }
        }

        public class Node {
            public var value: int32;
            public var wide: int64;
            public var ratio: float64;
            public var flag: bool;
            public var title: string;
            public var next: Node?;
            public var maybe: int32?;
            public constructor(value: int32, title: string, next: Node?) {
                this.value = value;
                this.wide = value;
                this.ratio = value;
                this.flag = value > 0;
                this.title = title;
                this.next = next;
                this.maybe = value;
            }
            public function get(): int32 { return this.value; }
            public function add(n: int32): int32 { return this.value + n; }
            public function name(): string { return this.title; }
            public function nextNode(): Node? { return this.next; }
            public function self(): Node { return this; }
            public function bump(): void { this.value = this.value + 1; }
            public function nullableValue(): int32? { return this.maybe; }
            public function clearMaybe(): void { this.maybe = null; }
        }
    """.trimIndent()

    private inner class Program(name: String, body: String) {
        val loader: URLClassLoader
        val main: Class<*>
        val trace: Class<*>
        val node: Class<*>

        init {
            val output = freshOutput(name)
            val source = "$prelude\n\npublic class Main {\n$body\n}\n"
            val result = JasperCompiler().compileToDirWithIr(source, output)
            assertTrue(
                result is JasperCompiler.CompileResult.Success,
                "compilation failed for $name: " + (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
            )
            loader = URLClassLoader(arrayOf(output.toUri().toURL()))
            main = loader.loadClass("demo.Main")
            trace = loader.loadClass("demo.Trace")
            node = loader.loadClass("demo.Node")
        }

        fun newNode(value: Int, label: String = "n$value", next: Any? = null): Any =
            node.constructors.single().newInstance(value, label, next)

        fun call(function: String, vararg args: Any?): Any? {
            val method = main.declaredMethods.single { it.name == function }
            try {
                return method.invoke(null, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        fun resetTrace() { trace.getMethod("reset").invoke(null) }
        fun traceLog(): String = trace.getField("log").get(null) as String
    }

    private fun rejected(name: String, body: String, expected: String) {
        val output = freshOutput("rejected-$name")
        val result = JasperCompiler().compileToDirWithIr("$prelude\n\npublic class Main {\n$body\n}\n", output)
        assertTrue(result is JasperCompiler.CompileResult.Error, "expected rejection: $name")
        val message = (result as JasperCompiler.CompileResult.Error).message
        assertTrue(expected in message, "$name: expected '$expected' in: $message")
    }

    // ---- ?. 字段与方法 ----

    @Test
    fun safeAccessYieldsNullAndSkipsMember() {
        val p = Program("basic", """
            public static function call(b: Node?): int32? { return b?.get(); }
            public static function field(b: Node?): string? { return b?.title; }
            public static function wide(b: Node?): int64? { return b?.wide; }
            public static function ratio(b: Node?): float64? { return b?.ratio; }
            public static function flag(b: Node?): bool? { return b?.flag; }
            public static function chain(b: Node?): string? { return b?.nextNode()?.name(); }
            public static function fieldChain(b: Node?): int32? { return b?.next?.value; }
            public static function nested(b: Node?): int32? { return b?.nullableValue(); }
        """)
        val leaf = p.newNode(7, "leaf")
        val head = p.newNode(3, "head", leaf)
        assertEquals(null, p.call("call", null as Any?))
        assertEquals(3, p.call("call", head))
        assertEquals(null, p.call("field", null as Any?))
        assertEquals("head", p.call("field", head))
        assertEquals(null, p.call("wide", null as Any?))
        assertEquals(3L, p.call("wide", head))
        assertEquals(null, p.call("ratio", null as Any?))
        assertEquals(3.0, p.call("ratio", head))
        assertEquals(null, p.call("flag", null as Any?))
        assertEquals(true, p.call("flag", head))
        assertEquals(null, p.call("chain", null as Any?))
        assertEquals(null, p.call("chain", leaf))
        assertEquals("leaf", p.call("chain", head))
        assertEquals(null, p.call("fieldChain", leaf))
        assertEquals(7, p.call("fieldChain", head))
        // 成员本身返回可空：不再包一层，值与 null 都原样穿过。
        assertEquals(3, p.call("nested", head))
        head.javaClass.getMethod("clearMaybe").invoke(head)
        assertEquals(null, p.call("nested", head))
    }

    @Test
    fun coalescingConsumesSafeResult() {
        val p = Program("coalesce", """
            public static function primitive(b: Node?): int32 { return b?.get() ?? 40; }
            public static function field(b: Node?): int32 { return b?.value ?? 41; }
            public static function text(b: Node?): string { return b?.name() ?? "none"; }
            public static function widen(b: Node?): int64 { return b?.get() ?? 5000000000L; }
            public static function narrowDefault(b: Node?): int64 { return b?.wide ?? 2; }
            public static function ratio(b: Node?): float64 { return b?.get() ?? 0.5; }
            public static function flag(b: Node?): bool { return b?.flag ?? false; }
            public static function local(b: Node?): int32 {
                var n: int32? = b?.get();
                return n ?? 42;
            }
            public static function chained(b: Node?, c: Node?): int32 { return b?.get() ?? c?.get() ?? 43; }
            public static function chainedText(a: string?, b: string?): string { return a ?? b ?? "c"; }
            public static function stillNullable(b: Node?, c: Node?): int32? { return b?.get() ?? c?.get(); }
            public static function nonNullReceiver(b: Node): int32 { return b?.get() ?? 44; }
        """)
        val node = p.newNode(9)
        val none: Any? = null
        assertEquals(40, p.call("primitive", none))
        assertEquals(9, p.call("primitive", node))
        assertEquals(41, p.call("field", none))
        assertEquals(9, p.call("field", node))
        assertEquals("none", p.call("text", none))
        assertEquals("n9", p.call("text", node))
        assertEquals(5000000000L, p.call("widen", none))
        assertEquals(9L, p.call("widen", node))
        assertEquals(2L, p.call("narrowDefault", none))
        assertEquals(9L, p.call("narrowDefault", node))
        assertEquals(0.5, p.call("ratio", none))
        assertEquals(9.0, p.call("ratio", node))
        assertEquals(false, p.call("flag", none))
        assertEquals(true, p.call("flag", node))
        assertEquals(42, p.call("local", none))
        assertEquals(9, p.call("local", node))
        assertEquals(43, p.call("chained", none, none))
        assertEquals(9, p.call("chained", none, node))
        assertEquals(9, p.call("chained", node, none))
        assertEquals("c", p.call("chainedText", none, none))
        assertEquals("b", p.call("chainedText", none, "b"))
        assertEquals("a", p.call("chainedText", "a", "b"))
        assertEquals(null, p.call("stillNullable", none, none))
        assertEquals(9, p.call("stillNullable", none, node))
        assertEquals(9, p.call("nonNullReceiver", node))
    }

    @Test
    fun receiverEvaluatedOnceAndArgumentsOnlyWhenPresent() {
        val p = Program("order", """
            public static function call(b: Node?): int32? { return Trace.pass(b, 1)?.add(Trace.tick(2)); }
            public static function field(b: Node?): int32? { return Trace.pass(b, 1)?.value; }
            public static function right(b: Node?): int32 { return Trace.pass(b, 1)?.add(Trace.tick(2)) ?? Trace.tick(3); }
            public static function stmt(b: Node?): int32 {
                Trace.pass(b, 1)?.bump();
                Trace.pass(b, 2)?.get();
                return 0;
            }
        """)
        val node = p.newNode(10)
        val none: Any? = null
        fun run(function: String, arg: Any?): Pair<Any?, String> {
            p.resetTrace()
            val value = p.call(function, arg)
            return value to p.traceLog()
        }
        assertEquals(null to "1;", run("call", none))
        assertEquals(12 to "1;2;", run("call", node))
        assertEquals(null to "1;", run("field", none))
        assertEquals(10 to "1;", run("field", node))
        assertEquals(3 to "1;3;", run("right", none))
        assertEquals(12 to "1;2;", run("right", node))
        assertEquals(0 to "1;2;", run("stmt", none))
        assertEquals(0 to "1;2;", run("stmt", node))
        assertEquals(11, node.javaClass.getMethod("get").invoke(node))
    }

    @Test
    fun voidCallAndPlatformMembers() {
        val p = Program("platform", """
            public static function bump(b: Node?): int32 {
                b?.bump();
                return 1;
            }
            public static function trim(s: string?): string? { return s?.trim(); }
            public static function trimOr(s: string?): string { return s?.trim() ?? "d"; }
            public static function isEmpty(s: string?): bool? { return s?.isEmpty(); }
            public static function length(s: string?): int32 { return s?.length ?? -1; }
            public static function byteCount(s: string?): int32 { return s?.byteLength() ?? -1; }
            public static function point(s: string?): int32 { return s?.codePointAt(0) ?? -1; }
            public static function trimStatement(s: string?): int32 {
                s?.trim();
                return 2;
            }
        """)
        val none: Any? = null
        val node = p.newNode(1)
        assertEquals(1, p.call("bump", none))
        assertEquals(1, p.call("bump", node))
        assertEquals(2, node.javaClass.getMethod("get").invoke(node))
        assertEquals(null, p.call("trim", none))
        assertEquals("a", p.call("trim", " a "))
        assertEquals("d", p.call("trimOr", none))
        assertEquals("a", p.call("trimOr", " a "))
        assertEquals(null, p.call("isEmpty", none))
        assertEquals(true, p.call("isEmpty", ""))
        assertEquals(-1, p.call("length", none))
        assertEquals(1, p.call("length", "😀"))
        assertEquals(-1, p.call("byteCount", none))
        assertEquals(4, p.call("byteCount", "😀"))
        assertEquals(-1, p.call("point", none))
        assertEquals(0x1F600, p.call("point", "😀"))
        assertEquals(2, p.call("trimStatement", none))
        assertEquals(2, p.call("trimStatement", " a "))
    }

    // ---- 可空基本类型 ----

    @Test
    fun nullablePrimitiveIsBoxed() {
        val p = Program("boxed", """
            public static function local(): int32 {
                var x: int32? = null;
                return x ?? 7;
            }
            public static function assigned(): int32 {
                var x: int32? = null;
                x = 3;
                return x ?? 7;
            }
            public static function param(x: int32?): int32 { return x ?? 1; }
            public static function viaCall(): int32 { return Main.param(5) + Main.param(null); }
            public static function ret(c: bool): int32? {
                if (c) { return 3; }
                return null;
            }
            public static function ternary(c: bool): int32? { return c ? 1 : null; }
            public static function ternaryElse(c: bool): int64? { return c ? null : 8L; }
            public static function widenLiteral(): int64? {
                var x: int64? = 3;
                return x;
            }
            public static function widenArgument(): int64 { return Main.wideParam(4); }
            public static function wideParam(x: int64?): int64 { return x ?? 0L; }
            public static function array(): int32 {
                var a: (int32?)[] = [1, null];
                return (a[1] ?? 10) + (a[0] ?? 20);
            }
            public static function field(n: Node): int32 {
                n.maybe = 6;
                var first: int32 = n.maybe ?? 0;
                n.maybe = null;
                return first + (n.maybe ?? 100);
            }
            public static function eq(x: int32?): bool { return x == 3; }
            public static function ne(x: int32?): bool { return x != 3; }
            public static function eqFlipped(x: int32?): bool { return 3 == x; }
            public static function isNull(x: int32?): bool { return x == null; }
            public static function bothBoxed(x: int32?, y: int32?): bool { return x == y; }
            public static function wideEq(x: int64?): bool { return x == 3; }
            public static function concat(x: int32?): string { return "v=" + x; }
            public static function primitiveLeft(x: int32): int32 { return x ?? Trace.tick(1); }
            public static function small(x: char?, y: char): char { return x ?? y; }
            public static function smallArray(y: char): char {
                var a: (char?)[] = [null];
                return a[0] ?? y;
            }
            public static function nullRight(x: int32?): int32? { return x ?? null; }
        """)
        val none: Any? = null
        assertEquals(7, p.call("local"))
        assertEquals(3, p.call("assigned"))
        assertEquals(1, p.call("param", none))
        assertEquals(2, p.call("param", 2))
        assertEquals(6, p.call("viaCall"))
        assertEquals(3, p.call("ret", true))
        assertEquals(null, p.call("ret", false))
        assertEquals(1, p.call("ternary", true))
        assertEquals(null, p.call("ternary", false))
        assertEquals(null, p.call("ternaryElse", true))
        assertEquals(8L, p.call("ternaryElse", false))
        assertEquals(3L, p.call("widenLiteral"))
        assertEquals(4L, p.call("widenArgument"))
        assertEquals(11, p.call("array"))
        assertEquals(106, p.call("field", p.newNode(1)))
        assertEquals(true, p.call("eq", 3))
        assertEquals(false, p.call("eq", 4))
        assertEquals(false, p.call("eq", none))
        assertEquals(true, p.call("ne", none))
        assertEquals(false, p.call("ne", 3))
        assertEquals(true, p.call("eqFlipped", 3))
        assertEquals(true, p.call("isNull", none))
        assertEquals(false, p.call("isNull", 0))
        assertEquals(true, p.call("bothBoxed", 1000, 1000))
        assertEquals(true, p.call("bothBoxed", none, none))
        assertEquals(false, p.call("bothBoxed", 1, none))
        assertEquals(true, p.call("wideEq", 3L))
        assertEquals("v=null", p.call("concat", none))
        assertEquals("v=5", p.call("concat", 5))
        // 左侧基本类型恒非 null：右侧不求值。
        p.resetTrace()
        assertEquals(9, p.call("primitiveLeft", 9))
        assertEquals("", p.traceLog())
        // char 是 32 位码点（Q229），在 JVM 上是 int / Integer：反射传入、读回都用码点。
        assertEquals('b'.code, p.call("small", none, 'b'.code))
        assertEquals('c'.code, p.call("small", 'c'.code, 'b'.code))
        assertEquals('d'.code, p.call("smallArray", 'd'.code))
        assertEquals(null, p.call("nullRight", none))
        assertEquals(1, p.call("nullRight", 1))
        // JVM 表示是包装类：与 Java 互操作和泛型实参一致。
        assertEquals(java.lang.Integer::class.java, p.main.getMethod("param", java.lang.Integer::class.java).parameterTypes[0])
        assertEquals(java.lang.Long::class.java, p.main.getMethod("wideParam", java.lang.Long::class.java).parameterTypes[0])
        assertEquals(java.lang.Integer::class.java, p.node.getField("maybe").type)
    }

    @Test
    fun plainAccessOnNullReceiverStillThrows() {
        val p = Program("plain", """
            public static function f(b: Node?): int32 { return b.get(); }
            public static function g(b: Node?): int32 { return b?.self().get(); }
        """)
        assertEquals(1, p.call("f", p.newNode(1)))
        assertTrue(runCatching { p.call("f", null as Any?) }.exceptionOrNull() is NullPointerException)
        // `?.` 只作用于自身这一段，不短路后面的普通 `.`（边界文档已写明）。
        assertTrue(runCatching { p.call("g", null as Any?) }.exceptionOrNull() is NullPointerException)
    }

    @Test
    fun checkerRejects() {
        val cases = listOf(
            Triple("arith", "public static function f(x: int32?): int32? { return x + 1; }", "Operator '+' cannot be applied"),
            Triple("order", "public static function f(x: int32?): bool { return x < 1; }", "Operator '<' cannot be applied"),
            Triple("unary", "public static function f(x: int32?): int32? { return -x; }", "Operator '-' cannot be applied"),
            Triple("not", "public static function f(x: bool?): bool? { return !x; }", "Operator '!' cannot be applied"),
            Triple("and", "public static function f(x: bool?): bool { return x && true; }", "Operator '&&' cannot be applied"),
            Triple("safe-arith", "public static function f(b: Node?): int32? { return b?.get() + 1; }", "Operator '+' cannot be applied"),
            Triple("assign-non-null", "public static function f(x: int32?): int32 { var y: int32 = x; return y; }", "cannot assign int32?"),
            Triple("return-non-null", "public static function f(b: Node?): int32 { return b?.get(); }", "Return type mismatch"),
            Triple("string-non-null", "public static function f(b: Node?): string { return b?.name(); }", "Return type mismatch"),
            Triple("box-widen", "public static function f(x: int32?): int64? { return x; }", "Return type mismatch"),
            Triple("box-to-string", "public static function f(x: int32?): string? { return x; }", "Return type mismatch"),
            Triple("unrelated-nullable", "public static function f(x: Node?): Trace? { return x; }", "Return type mismatch"),
            Triple("unrelated-declared", "public static function f(x: Trace?): Node? { return x; }", "Return type mismatch"),
            Triple("coalesce-mismatch", "public static function f(x: int32?): int32 { return x ?? \"s\"; }", "Type mismatch in '??'"),
            Triple("coalesce-still-nullable", "public static function f(x: int32?, y: int32?): int32 { return x ?? y; }", "Return type mismatch"),
            Triple("condition", "public static function f(x: bool?): int32 { if (x) { return 1; } return 2; }", "condition must be bool"),
            Triple("ternary-condition", "public static function f(x: bool?): int32 { return x ? 1 : 2; }", "Operator '?:' cannot be applied"),
            Triple("ternary-non-null", "public static function f(c: bool): int32 { return c ? 1 : null; }", "Return type mismatch"),
            Triple("ternary-non-null-then", "public static function f(c: bool): string { return c ? null : \"x\"; }", "Return type mismatch"),
            Triple("target", "public static function f(b: Node?): int32 { b?.value = 1; return 2; }", "cannot be an assignment target"),
            Triple("type-name", "public static function f(): int32 { return Trace?.tick(1); }", "requires a value receiver"),
            Triple("primitive-receiver", "public static function f(x: int32?): int32? { return x?.foo; }", "is not available on int32?"),
            Triple("box-compare", "public static function f(x: int32?): bool { return x == 3L; }", "Cannot compare int32? with int64"),
            Triple("box-compare-both", "public static function f(x: int32?, y: int64?): bool { return x == y; }", "Cannot compare int32? with int64?"),
            Triple("missing-member", "public static function f(b: Node?): int32? { return b?.nothingHere; }", "Cannot resolve member"),
            Triple("wrong-args", "public static function f(b: Node?): int32? { return b?.add(); }", "expects 1 argument"),
        )
        for ((name, body, expected) in cases) rejected(name, body, expected)
    }

    // ---- 属性测试：随机 `?.` 表达式 vs Kotlin 模型 ----

    /** 模型里的节点：与 Jasper 的 Node 一一对应。 */
    private class Model(val value: Int, val next: Model?)

    /** 接收者表达式：返回 Node?；求值可能写轨迹。 */
    private sealed class Recv {
        object Param : Recv()
        class Pass(val inner: Recv, val k: Int) : Recv()
        class NextCall(val inner: Recv) : Recv()
        class NextField(val inner: Recv) : Recv()
        class Self(val inner: Recv) : Recv()
    }

    /** 值表达式：`recv?.xxx`，可选 `?? 默认值`。 */
    private class ValueExpr(val recv: Recv, val member: Int, val argTick: Int, val fallback: Int?, val fallbackTick: Int?)

    private fun source(recv: Recv): String = when (recv) {
        Recv.Param -> "b"
        is Recv.Pass -> "Trace.pass(${source(recv.inner)}, ${recv.k})"
        is Recv.NextCall -> "${source(recv.inner)}?.nextNode()"
        is Recv.NextField -> "${source(recv.inner)}?.next"
        is Recv.Self -> "${source(recv.inner)}?.self()"
    }

    private fun source(e: ValueExpr): String {
        val r = source(e.recv)
        val access = when (e.member) {
            0 -> "$r?.value"
            1 -> "$r?.get()"
            else -> "$r?.add(Trace.tick(${e.argTick}))"
        }
        return when {
            e.fallbackTick != null -> "$access ?? Trace.tick(${e.fallbackTick})"
            e.fallback != null -> "$access ?? ${e.fallback}"
            else -> access
        }
    }

    private fun model(recv: Recv, head: Model?, log: StringBuilder): Model? = when (recv) {
        Recv.Param -> head
        is Recv.Pass -> model(recv.inner, head, log).also { log.append(recv.k).append(';') }
        is Recv.NextCall -> model(recv.inner, head, log)?.next
        is Recv.NextField -> model(recv.inner, head, log)?.next
        is Recv.Self -> model(recv.inner, head, log)
    }

    private fun model(e: ValueExpr, head: Model?, log: StringBuilder): Int? {
        val r = model(e.recv, head, log)
        val value = r?.let {
            when (e.member) {
                0, 1 -> it.value
                else -> {
                    log.append(e.argTick).append(';')
                    it.value + e.argTick
                }
            }
        }
        if (value != null) return value
        if (e.fallbackTick != null) {
            log.append(e.fallbackTick).append(';')
            return e.fallbackTick
        }
        return e.fallback
    }

    private fun randomRecv(random: Random, depth: Int): Recv {
        if (depth == 0 || random.nextInt(4) == 0) return Recv.Param
        val inner = randomRecv(random, depth - 1)
        return when (random.nextInt(4)) {
            0 -> Recv.Pass(inner, 100 + random.nextInt(100))
            1 -> Recv.NextCall(inner)
            2 -> Recv.NextField(inner)
            else -> Recv.Self(inner)
        }
    }

    private fun randomValue(random: Random): ValueExpr {
        val recv = randomRecv(random, 4)
        val member = random.nextInt(3)
        return when (random.nextInt(3)) {
            0 -> ValueExpr(recv, member, 200 + random.nextInt(100), null, null)
            1 -> ValueExpr(recv, member, 200 + random.nextInt(100), random.nextInt(1000), null)
            else -> ValueExpr(recv, member, 200 + random.nextInt(100), null, 300 + random.nextInt(100))
        }
    }

    @Test
    fun randomSafeChainsMatchModel() {
        val random = Random(20261149L)
        val exprs = List(160) { randomValue(it.let { random }) }
        val body = exprs.withIndex().joinToString("\n") { (i, e) ->
            val type = if (e.fallback == null && e.fallbackTick == null) "int32?" else "int32"
            "    public static function f$i(b: Node?): $type { return ${source(e)}; }"
        }
        val p = Program("random", body)
        var nulls = 0
        var values = 0
        var ticks = 0
        for ((i, e) in exprs.withIndex()) {
            // 链长 0..3：覆盖在任意一段断开。
            for (length in 0..3) {
                var modelHead: Model? = null
                var realHead: Any? = null
                for (k in length downTo 1) {
                    modelHead = Model(k * 10, modelHead)
                    realHead = p.newNode(k * 10, "n$k", realHead)
                }
                val expectedLog = StringBuilder()
                val expected = model(e, modelHead, expectedLog)
                p.resetTrace()
                assertEquals(expected, p.call("f$i", realHead), "f$i length=$length: ${source(e)}")
                assertEquals(expectedLog.toString(), p.traceLog(), "trace f$i length=$length: ${source(e)}")
                if (expected == null) nulls++ else values++
                if (expectedLog.isNotEmpty()) ticks++
            }
        }
        // 覆盖下限：null 与值两边都要真实发生，否则这个属性测试退化。
        assertTrue(nulls >= 60 && values >= 200 && ticks >= 100, "coverage: nulls=$nulls values=$values ticks=$ticks")
    }
}
