// ============================================================================
// ReferenceFrameCoroutinePipelineTest.kt —— S4-C 第二十二片「帧引用类型」验收
// ============================================================================
// 做什么：经真实 ANTLR 解析、类型检查、协程降级、IR 与 JVM 后端编译 Jasper 源码，加载生成帧并在
//   真实 JvmExecutor 上运行，验证具名引用类型与可空引用作参数、局部、await 结果、合成临时局部和
//   返回值时保持对象身份、null 与别名语义；另有固定种子属性测试、用户代码先后顺序与拒绝清单。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做平台反射驱动和有界等待。
//
// 失败模式：编译诊断、VerifyError、ClassCastException 或超时都由断言直接报告，不静默跳过。
// 为什么断言对象身份：帧字段必须保存引用本身，比较值相等看不出帧里多做了一次复制或装换。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 验证生成帧在引用类型上的编译与运行语义。 */
class ReferenceFrameCoroutinePipelineTest {

    private val root: Path = Paths.get("build", "tmp", "reference-frame-coroutine-pipeline")

    /** 清空并创建模块内目录；绝不写仓库外路径。 */
    private fun freshOutput(name: String): Path {
        val output = root.resolve(name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        return output
    }

    /** 编译标准库 + 源码；编译失败带聚合诊断。 */
    private fun compile(name: String, sources: Map<String, String>): URLClassLoader {
        val output = freshOutput(name)
        val result = JasperCompiler().compileAllWithStdlib(sources, output)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " +
                ((result as? JasperCompiler.CompileResult.Error)?.message ?: result.toString())
        )
        return URLClassLoader(arrayOf(output.toUri().toURL()))
    }

    /** 反射句柄集合。 */
    private class Runtime(val loader: ClassLoader) {
        val future: Class<*> = loader.loadClass("std.Future")
        val executorType: Class<*> = loader.loadClass("std.Executor")
        val executor: Any = loader.loadClass("std.JvmExecutor")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(1)

        fun newFuture(): Any = future.getConstructor().newInstance()
        fun complete(target: Any, value: Any?) { future.getMethod("complete", Any::class.java).invoke(target, value) }
        fun fail(target: Any, message: String) {
            val failure = loader.loadClass("std.Failure").getConstructor(String::class.java, String::class.java)
                .newInstance(message, "test")
            future.getMethod("fail", failure.javaClass).invoke(target, failure)
        }
        fun cancel(target: Any) { future.getMethod("cancel").invoke(target) }
        fun status(target: Any): String = future.getMethod("status").invoke(target).toString()

        /** 有界等待终态。 */
        fun awaitDone(target: Any, label: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (future.getMethod("isDone").invoke(target) != true && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(true, future.getMethod("isDone").invoke(target), "frame did not finish: $label")
        }

        fun value(target: Any, label: String): Any? {
            awaitDone(target, label)
            if (status(target) != "Completed") {
                val failure = future.getMethod("failureOrNull").invoke(target)
                throw AssertionError("$label: ${status(target)} ${failure?.let { it.javaClass.getMethod("message").invoke(it) }}")
            }
            return future.getMethod("get").invoke(target)
        }

        /** 启动 `demo.Program_<function>_Coroutine`；构造器形如 (Executor, Future<结果>, 参数...)。 */
        fun start(function: String, output: Any, vararg args: Any?) {
            val type = loader.loadClass("demo.Program_${function}_Coroutine")
            val frame = type.constructors.single().newInstance(executor, output, *args)
            type.getMethod("start").invoke(frame)
        }

        fun box(value: Int): Any = loader.loadClass("demo.Box")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(value)
        fun noisy(): Any = loader.loadClass("demo.Noisy").getConstructor().newInstance()
        fun boxValue(box: Any): Int = box.javaClass.getMethod("get").invoke(box) as Int
        fun bump(box: Any) { box.javaClass.getMethod("bump").invoke(box) }
        fun log(): String = loader.loadClass("demo.Program").getMethod("log").invoke(null) as String

        /**
         * 等工作线程跑完已入队的段：向单线程执行器排一个闸门任务并有界等待它运行。
         * 固定 sleep 在高负载下可能早于帧的段，随后的断言就看不到它的效果。
         */
        fun drainWorker() {
            val ran = CountDownLatch(1)
            val runnable = loader.loadClass("std.Runnable")
            val gate = Proxy.newProxyInstance(runnable.classLoader, arrayOf(runnable)) { _, method, _ ->
                if (method.name == "run") ran.countDown()
                null
            }
            executor.javaClass.getMethod("schedule", runnable).invoke(executor, gate)
            assertTrue(ran.await(30, TimeUnit.SECONDS), "worker did not drain")
        }

        fun shutdown() {
            executor.javaClass.getMethod("shutdown").invoke(executor)
            assertEquals(true, executor.javaClass.getMethod("awaitTermination", Long::class.javaPrimitiveType)
                .invoke(executor, 30000L))
        }
    }

    /** 单个函数的运行：输入未完成时启动，再逐个完成。 */
    private fun Runtime.runSuspended(function: String, inputs: List<Any?>, vararg plain: Any?): Any? {
        val output = newFuture()
        val futures = inputs.map { newFuture() }
        start(function, output, *(futures + plain.toList()).toTypedArray())
        futures.zip(inputs).forEach { (future, value) -> complete(future, value) }
        return value(output, function)
    }

    private val program = """
        package demo;
        import std.Future;
        import std.MutableList;

        public class Base {
            public function tag(): string { return "base"; }
        }

        public class Derived extends Base {
            public function tag(): string { return "derived"; }
        }

        public class Box {
            private var v: int32;
            public constructor(v: int32) { this.v = v; }
            public function get(): int32 { return this.v; }
            public function bump(): int32 { this.v = this.v + 1; return this.v; }
        }

        // 相等比较与字符串化进入用户代码：记录调用，用来锁定它们与挂起的先后。
        public class Noisy {
            public function equals(other: any): bool {
                Program.note("eq;");
                return true;
            }
            public function toString(): string {
                Program.note("str;");
                return "Noisy";
            }
        }

        public class Program {
            private static var logged: string = "";

            public static function note(text: string): void { Program.logged = Program.logged + text; }
            public static function log(): string {
                var text: string = Program.logged;
                Program.logged = "";
                return text;
            }

            public static function identity(input: Future<Box>): Box {
                var got: Box = await input;
                return got;
            }

            public static function direct(input: Future<Box>): Box { return await input; }

            public static function param(input: Future<int32>, box: Box): int32 {
                var n: int32 = await input;
                return box.get() + n;
            }

            // 帧保存引用本身：挂起期间别处对同一对象的修改，恢复后可见。
            public static function alias(box: Box, input: Future<int32>): int32 {
                var before: int32 = box.bump();
                var n: int32 = await input;
                return box.get() * 100 + before * 10 + n;
            }

            public static function localNew(input: Future<int32>): int32 {
                var box: Box = new Box(5);
                var n: int32 = await input;
                var after: int32 = box.bump();
                return after + n;
            }

            public static function nullable(input: Future<Box?>): Box? {
                var got: Box? = await input;
                return got;
            }

            public static function nullLocal(input: Future<int32>): Box? {
                var none: Box? = null;
                var n: int32 = await input;
                return none;
            }

            public static function nullLiteralReturn(input: Future<int32>): Box? {
                var n: int32 = await input;
                return null;
            }

            public static function orElse(input: Future<Box?>, fallback: Box): Box {
                var got: Box? = await input;
                return got ?? fallback;
            }

            public static function isNull(input: Future<Box?>): bool {
                var got: Box? = await input;
                return got != null;
            }

            public static function nullableString(input: Future<string?>): string {
                var got: string? = await input;
                return got ?? "none";
            }

            public static function safeRead(input: Future<Box?>): int32 {
                var got: Box? = await input;
                if (got != null) {
                    var sure: Box = cast got as Box;
                    return sure.get();
                }
                return -1;
            }

            // 恢复块经 checkcast 绑定，泛型实参由检查器保留：元素类型在帧外仍是 int32。
            public static function makeList(): MutableList<int32> {
                var items: MutableList<int32> = new MutableList<int32>();
                items.add(1);
                items.add(2);
                return items;
            }

            public static function list(input: Future<MutableList<int32>>): int32 {
                var items: MutableList<int32> = await input;
                return items.size();
            }

            public static function nested(input: Future<Future<int64>>): int64 {
                var inner: Future<int64> = await input;
                var value: int64 = await inner;
                return value + 1L;
            }

            // 帧字段按声明类型；Derived 经普通赋值与 return 宽化到 Base。
            public static function widen(input: Future<int32>): Base {
                var base: Base = new Derived();
                var n: int32 = await input;
                return base;
            }

            public static function widenReturn(input: Future<Derived>): Base {
                var got: Derived = await input;
                return got;
            }

            public static function overwrite(first: Future<Box>, second: Future<Box>): int32 {
                var sum: int32 = 0;
                var current: Box = await first;
                sum = sum + current.get();
                current = await second;
                sum = sum * 100 + current.get();
                return sum;
            }

            public static function loop(first: Future<Box>, second: Future<Box>, rounds: int32): Box {
                var current: Box = new Box(0);
                var i: int32 = 0;
                while (i < rounds) {
                    if (i % 2 == 0) {
                        current = await first;
                    } else {
                        current = await second;
                    }
                    i++;
                }
                return current;
            }

            // try 体内 return 的值先固定，清理改写局部不影响已求出的引用。
            public static function pinned(input: Future<Box>, other: Box): Box {
                var got: Box = await input;
                try {
                    return got;
                } finally {
                    got = other;
                }
            }

            // 字段读在 await 之后原位求值。
            public static function afterAwait(input: Future<int32>, box: Box): int32 {
                return await input + box.get();
            }

            // 提升：左侧引用读取留在原位，equals 在 await 之后才调用（与源码求值顺序一致）。
            public static function equalsAfter(left: Noisy, input: Future<Noisy>): bool {
                return left == await input;
            }

            public static function concatAfter(left: Noisy, input: Future<string>): string {
                return await input + left;
            }

            public static function callArgs(input: Future<Box>, other: Box): int32 {
                return Program.pick(other, await input);
            }

            // 同步路径：string + 引用同样经 String.valueOf（曾是 VerifyError）。
            public static function concatSync(left: Noisy, none: Noisy?): string { return "a" + left + none; }

            // `??` 只读局部，推迟到挂起之后不改变结果。
            public static function coalesceArg(input: Future<Box>, none: Box?, other: Box): int32 {
                return Program.pick(none ?? other, await input);
            }

            public static function pick(a: Box, b: Box): int32 { return a.get() * 10 + b.get(); }
        }
    """.trimIndent()

    /** 同一份编译产物跑一组用例，降低编译开销。 */
    private fun withProgram(name: String, body: Runtime.() -> Unit) {
        compile(name, mapOf("Program.jas" to program)).use { loader ->
            val runtime = Runtime(loader)
            withCleanup({ runtime.shutdown() }) { runtime.body() }
        }
    }

    @Test
    fun `references cross suspensions by identity`() = withProgram("identity") {
        val box = box(7)
        assertSame(box, runSuspended("identity", listOf(box)))
        assertSame(box, runSuspended("direct", listOf(box)))
        assertEquals(9, runSuspended("param", listOf(2), box(7)))
        assertEquals(6 + 3, runSuspended("localNew", listOf(3)))
    }

    @Test
    fun `mutation during suspension is visible through the frame slot`() = withProgram("alias") {
        val box = box(1)
        val output = newFuture()
        val input = newFuture()
        start("alias", output, box, input)
        // 帧执行 bump 后才注册：等到首段完成（值变 2）再从外部改一次。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (boxValue(box) != 2 && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(2, boxValue(box))
        bump(box)
        complete(input, 4)
        assertEquals(3 * 100 + 2 * 10 + 4, value(output, "alias"))
    }

    @Test
    fun `nullable references keep null and non null across suspensions`() = withProgram("nullable") {
        val box = box(3)
        assertSame(box, runSuspended("nullable", listOf(box)))
        assertNull(runSuspended("nullable", listOf(null)))
        assertNull(runSuspended("nullLocal", listOf(1)))
        assertNull(runSuspended("nullLiteralReturn", listOf(1)))
        val fallback = box(8)
        assertSame(box, runSuspended("orElse", listOf(box), fallback))
        assertSame(fallback, runSuspended("orElse", listOf(null), fallback))
        assertEquals(true, runSuspended("isNull", listOf(box)))
        assertEquals(false, runSuspended("isNull", listOf(null)))
        assertEquals("x", runSuspended("nullableString", listOf("x")))
        assertEquals("none", runSuspended("nullableString", listOf(null)))
        assertEquals(3, runSuspended("safeRead", listOf(box)))
        assertEquals(-1, runSuspended("safeRead", listOf(null)))
    }

    @Test
    fun `generic references and nested futures lower`() = withProgram("generic") {
        val items = loader.loadClass("demo.Program").getMethod("makeList").invoke(null)
        assertEquals(2, runSuspended("list", listOf(items)))

        val inner = newFuture()
        val output = newFuture()
        val outer = newFuture()
        start("nested", output, outer)
        complete(outer, inner)
        complete(inner, 41L)
        assertEquals(42L, value(output, "nested"))
    }

    @Test
    fun `subclass values widen through the declared slot and result`() = withProgram("widen") {
        val widened = assertNotNull(runSuspended("widen", listOf(1)))
        assertEquals("demo.Derived", widened.javaClass.name)
        val derived = loader.loadClass("demo.Derived").getConstructor().newInstance()
        assertSame(derived, runSuspended("widenReturn", listOf(derived)))
    }

    @Test
    fun `reference slots rebind across awaits branches and loops`() = withProgram("rebind") {
        assertEquals(102, runSuspended("overwrite", listOf(box(1), box(2))))
        val first = box(1)
        val second = box(2)
        assertSame(second, runSuspended("loop", listOf(first, second), 2))
        assertSame(first, runSuspended("loop", listOf(first, second), 3))
        assertEquals(0, boxValue(runSuspended("loop", listOf(first, second), 0)!!))
    }

    @Test
    fun `return value in try is pinned before cleanup rewrites the local`() = withProgram("pinned") {
        val box = box(1)
        assertSame(box, runSuspended("pinned", listOf(box), box(2)))
    }

    @Test
    fun `user equals and toString run after the await they follow`() = withProgram("order") {
        log()
        val output = newFuture()
        val input = newFuture()
        start("equalsAfter", output, noisy(), input)
        drainWorker()
        Thread.sleep(50)
        assertEquals("", log(), "equals must not run before the await resumes")
        complete(input, noisy())
        assertEquals(true, value(output, "equalsAfter"))
        assertEquals("eq;", log())

        val text = newFuture()
        val textOutput = newFuture()
        start("concatAfter", textOutput, noisy(), text)
        drainWorker()
        Thread.sleep(50)
        assertEquals("", log(), "toString must not run before the await resumes")
        complete(text, "!")
        assertEquals("!Noisy", value(textOutput, "concatAfter"))
        assertEquals("str;", log())
    }

    @Test
    fun `string concat with a reference operand uses String valueOf on the synchronous path`() = withProgram("concat") {
        log()
        val method = loader.loadClass("demo.Program").methods.single { it.name == "concatSync" }
        assertEquals("aNoisynull", method.invoke(null, noisy(), null))
        assertEquals("str;", log())
    }

    @Test
    fun `reference reads stay in place around hoisted awaits`() = withProgram("reads") {
        assertEquals(5 + 2, runSuspended("afterAwait", listOf(2), box(5)))
        assertEquals(3 * 10 + 4, runSuspended("callArgs", listOf(box(4)), box(3)))
        assertEquals(3 * 10 + 4, runSuspended("coalesceArg", listOf(box(4)), null, box(3)))
    }

    @Test
    fun `failure and cancellation channels do not touch reference slots`() = withProgram("channels") {
        val failed = newFuture()
        val failedInput = newFuture()
        start("identity", failed, failedInput)
        fail(failedInput, "boom")
        awaitDone(failed, "failed")
        assertEquals("Failed", status(failed))

        val cancelled = newFuture()
        val cancelledInput = newFuture()
        start("nullable", cancelled, cancelledInput)
        cancel(cancelledInput)
        awaitDone(cancelled, "cancelled")
        assertEquals("Cancelled", status(cancelled))
    }

    /** 类型只在这个文件可见的被调函数，其 Future 结果跨包传入。 */
    @Test
    fun `reference types from another package resolve to the declaring class`() {
        val library = """
            package lib;
            import std.Future;
            public class Item {
                public function name(): string { return "lib"; }
            }
            public class Factory {
                public static function make(input: Future<Item>): Future<Item> { return input; }
            }
        """.trimIndent()
        val imported = """
            package demo;
            import std.Future;
            import lib.Item;
            import lib.Factory;
            public class Program {
                public static function run(input: Future<Item>): Item {
                    var got: Item = await Factory.make(input);
                    return got;
                }
            }
        """.trimIndent()
        compile("cross-imported", mapOf("Lib.jas" to library, "Program.jas" to imported)).use { loader ->
            val runtime = Runtime(loader)
            withCleanup({ runtime.shutdown() }) {
                val item = loader.loadClass("lib.Item").getConstructor().newInstance()
                assertSame(item, runtime.runSuspended("run", listOf(item)))
            }
        }
    }

    private val itemLibrary = """
        package lib;
        import std.Future;
        public class Item {
            public function name(): string { return "lib"; }
        }
        public class Factory {
            public static function make(): Future<Item> {
                var ready: Future<Item> = new Future<Item>();
                ready.complete(new Item());
                return ready;
            }
        }
    """.trimIndent()

    /** 编译应失败且不写 class；返回聚合诊断。 */
    private fun compileError(name: String, sources: Map<String, String>): String {
        val output = freshOutput(name)
        val result = JasperCompiler().compileAllWithStdlib(sources, output)
        assertTrue(result is JasperCompiler.CompileResult.Error, "$name unexpectedly compiled")
        Files.walk(output).use { paths ->
            assertFalse(paths.anyMatch { it.toString().endsWith(".class") }, "$name wrote bytecode")
        }
        return (result as JasperCompiler.CompileResult.Error).message
    }

    /** 调用方没有 import 结果类型：帧字段放不下，按未知类型诊断，补上 import 后可用。 */
    @Test
    fun `untyped binding of a type the caller did not import is diagnosed`() {
        fun caller(importLine: String) = """
            package demo;
            import lib.Factory;
            $importLine
            public class Program {
                public static function run(): string {
                    var got = await Factory.make();
                    return got.name();
                }
            }
        """.trimIndent()
        val message = compileError("cross-unimported", mapOf("Lib.jas" to itemLibrary, "Program.jas" to caller("")))
        assertTrue(message.contains("Unknown type 'Item'"), message)
        compile("cross-imported-untyped", mapOf("Lib.jas" to itemLibrary, "Program.jas" to caller("import lib.Item;"))).use { loader ->
            val runtime = Runtime(loader)
            withCleanup({ runtime.shutdown() }) {
                assertEquals("lib", runtime.runSuspended("run", emptyList()))
            }
        }
    }

    /** 调用方包里有同名类型：帧字段不得把别的包的结果绑定到本包的同名类。 */
    @Test
    fun `same named type in the caller package is diagnosed unless the signature qualifies it`() {
        val caller = """
            package demo;
            import lib.Factory;
            public class Item {
                public function name(): string { return "demo"; }
            }
            public class Program {
                public static function run(): string {
                    var got = await Factory.make();
                    return got.name();
                }
            }
        """.trimIndent()
        val message = compileError("cross-shadow", mapOf("Lib.jas" to itemLibrary, "Program.jas" to caller))
        assertTrue(message.contains("await result type 'Item' is declared in several packages"), message)

        // 同名类型藏在类型实参里同样诊断：擦除后不报 ClassCastException，元素读取才错。
        val inArgument = itemLibrary.replace(
            "public static function make(): Future<Item> {",
            """
            public static function makeAll(): Future<Future<Item>> {
                return new Future<Future<Item>>();
            }
            public static function make(): Future<Item> {
            """.trimIndent()
        )
        val argumentCaller = caller.replace("var got = await Factory.make();\n", "var all = await Factory.makeAll();\n")
            .replace("return got.name();", "return \"\";")
        val argumentMessage = compileError("cross-shadow-argument", mapOf("Lib.jas" to inArgument, "Program.jas" to argumentCaller))
        assertTrue(argumentMessage.contains("await result type 'Item' is declared in several packages"), argumentMessage)

        val qualified = itemLibrary.replace("Future<Item>", "Future<lib.Item>")
        compile("cross-shadow-qualified", mapOf("Lib.jas" to qualified, "Program.jas" to caller)).use { loader ->
            val runtime = Runtime(loader)
            withCleanup({ runtime.shutdown() }) {
                assertEquals("lib", runtime.runSuspended("run", emptyList()))
            }
        }
    }

    /**
     * 固定种子的属性测试：随机函数在若干 Box 输入上重绑定局部、读取 / 修改对象、走可空分支；
     * 生成时用 Kotlin 模型算出期望的返回对象（下标）与累计值，每个函数跑两种完成时机。
     */
    @Test
    fun `seeded reference programs agree with the Kotlin oracle`() {
        val seed = 20261143L
        val random = Random(seed)
        val programs = (0 until 160).map { generate(it, random) }
        assertTrue(programs.count { it.usesNull } >= 40, "nullable coverage ${programs.count { it.usesNull }}")
        assertTrue(programs.count { it.expectedIndex == null } >= 10, "null result coverage")
        val source = buildString {
            append("package demo;\nimport std.Future;\n")
            append("public class Box {\n    private var v: int32;\n    public constructor(v: int32) { this.v = v; }\n")
            append("    public function get(): int32 { return this.v; }\n")
            append("    public function bump(): int32 { this.v = this.v + 1; return this.v; }\n}\n")
            append("public class Program {\n")
            programs.forEach { append(it.source) }
            append("}\n")
        }
        compile("seeded", mapOf("Program.jas" to source)).use { loader ->
            val runtime = Runtime(loader)
            withCleanup({ runtime.shutdown() }) {
                for (program in programs) {
                    for (completeFirst in listOf(true, false)) {
                        val context = "seed=$seed ${program.name} completeFirst=$completeFirst\n${program.source}"
                        val boxes = program.initial.map(runtime::box)
                        val inputs = program.awaited.map { index -> if (index == null) null else boxes[index] }
                        val futures = inputs.map { runtime.newFuture() }
                        val output = runtime.newFuture()
                        if (completeFirst) futures.zip(inputs).forEach { (f, v) -> runtime.complete(f, v) }
                        runtime.start(program.name, output, *futures.toTypedArray())
                        if (!completeFirst) futures.zip(inputs).forEach { (f, v) -> runtime.complete(f, v) }
                        val result = runtime.value(output, context)
                        val expected = program.expectedIndex?.let { boxes[it] }
                        assertSame(expected, result, context)
                        assertEquals(program.expectedValues, boxes.map(runtime::boxValue), context)
                    }
                }
            }
        }
    }

    private class Generated(
        val name: String,
        val source: String,
        val initial: List<Int>,
        val awaited: List<Int?>,
        val expectedIndex: Int?,
        val expectedValues: List<Int>,
        val usesNull: Boolean
    )

    /** 生成一个函数及其模型结果：局部 a（Box?）、b（Box），语句对模型同步求值。 */
    private fun generate(index: Int, random: Random): Generated {
        val name = "f$index"
        val initial = List(3) { random.nextInt(50) }
        val values = initial.toMutableList()
        val awaited = mutableListOf<Int?>()
        val body = StringBuilder()
        val nullableParams = mutableSetOf<Int>()
        var usesNull = false

        /** 取下一个输入；可空输入有 1/3 概率是 null。 */
        fun nextInput(nullable: Boolean): Pair<String, Int?> {
            val value = if (nullable && random.nextInt(3) == 0) null else random.nextInt(initial.size)
            if (nullable) nullableParams += awaited.size
            awaited += value
            return "p${awaited.size - 1}" to value
        }

        val (firstNullable, firstNullableValue) = nextInput(true)
        val (firstBox, firstBoxValue) = nextInput(false)
        var a: Int? = firstNullableValue
        var b: Int = requireNotNull(firstBoxValue)
        body.append("        var a: Box? = await $firstNullable;\n")
        body.append("        var b: Box = await $firstBox;\n")
        var temp = 0
        repeat(2 + random.nextInt(5)) {
            when (random.nextInt(6)) {
                0 -> {
                    val (param, value) = nextInput(true)
                    body.append("        a = await $param;\n")
                    a = value
                    usesNull = true
                }
                1 -> {
                    val (param, value) = nextInput(false)
                    body.append("        b = await $param;\n")
                    b = requireNotNull(value)
                }
                2 -> {
                    body.append("        var t${temp++}: int32 = b.bump();\n")
                    values[b] = values[b] + 1
                }
                3 -> {
                    body.append("        b = a ?? b;\n")
                    b = a ?: b
                    usesNull = true
                }
                4 -> {
                    body.append("        a = b;\n")
                    a = b
                }
                else -> {
                    // 分支内挂起：a 非 null 时重绑定 b，否则修改 b。
                    val (param, value) = nextInput(false)
                    body.append("        if (a != null) {\n            b = await $param;\n        } else {\n")
                    body.append("            var t${temp++}: int32 = b.bump();\n        }\n")
                    if (a != null) b = requireNotNull(value) else values[b] = values[b] + 1
                    usesNull = true
                }
            }
        }
        val returnsNullable = random.nextBoolean()
        body.append("        return ${if (returnsNullable) "a" else "b"};\n")
        val typed = awaited.indices.joinToString(", ") { position ->
            "p$position: Future<${if (position in nullableParams) "Box?" else "Box"}>"
        }
        val signature = "    public static function $name($typed): Box? {\n"
        // 分支未走到的输入从未被 await：照样完成，不影响结果。
        return Generated(
            name,
            signature + body + "    }\n",
            initial,
            awaited,
            if (returnsNullable) a else b,
            values,
            usesNull || firstNullableValue == null
        )
    }

    /** 这些形态必须诊断、不写 class：提升会改变用户代码与挂起的先后，或帧放不下。 */
    @Test
    fun `unsupported reference shapes are diagnosed and write no class`() {
        val cases = mapOf(
            "binding-subclass" to """
                public static function f(input: Future<Derived>): int32 {
                    var value: Base = await input;
                    return 1;
                }
            """,
            "binding-nullable" to """
                public static function f(input: Future<Box>): int32 {
                    var value: Box? = await input;
                    return 1;
                }
            """,
            "equals-before-await" to """
                public static function f(a: Box, b: Box, input: Future<bool>): bool {
                    return (a == b) == await input;
                }
            """,
            "not-equals-before-await" to """
                public static function f(a: Box, b: Box, input: Future<bool>): bool {
                    return (a != null) == await input;
                }
            """,
            "concat-before-await" to """
                public static function f(a: Box, input: Future<string>): string {
                    return ("x" + a) + await input;
                }
            """,
            "equals-then-await" to """
                public static function f(a: Box, first: Future<Box>, second: Future<bool>): bool {
                    return (a == await first) == await second;
                }
            """,
            "field-read-before-await" to """
                public static function f(a: Box, input: Future<int32>): int32 {
                    return a.get() + await input;
                }
            """,
            "await-left-of-coalesce" to """
                public static function f(input: Future<Box?>, other: Box): Box {
                    return (await input) ?? other;
                }
            """,
            "await-right-of-coalesce" to """
                public static function f(a: Box?, input: Future<Box>): Box {
                    return a ?? await input;
                }
            """,
            "catch-parameter-in-coalesce" to """
                public static function f(input: Future<int32>): int32 {
                    try {
                        return await input;
                    } catch (e: std.Error) {
                        var kept: std.Error = e ?? e;
                        return 0;
                    }
                }
            """,
            "builtin-alias-spelling" to """
                public static function f(input: Future<Boolean>): int32 {
                    var value: Boolean = await input;
                    return 1;
                }
            """,
                        "array-result" to """
                public static function f(input: Future<int32[]>): int32 {
                    var value: int32[] = await input;
                    return 1;
                }
            """,
            "nullable-number" to """
                public static function f(input: Future<int32?>): int32 {
                    var value: int32? = await input;
                    return 1;
                }
            """,
            "any-result" to """
                public static function f(input: Future<any>): int32 {
                    var value: any = await input;
                    return 1;
                }
            """
        )
        for ((name, body) in cases) {
            val output = freshOutput("unsupported-$name")
            val source = """
                package demo;
                import std.Future;
                public class Base {}
                public class Derived extends Base {}
                public class Box {}
                public class Program {
                $body
                }
            """.trimIndent()
            val result = JasperCompiler().compileAllWithStdlib(mapOf("Program.jas" to source), output)
            assertTrue(result is JasperCompiler.CompileResult.Error, "$name unexpectedly compiled")
            val message = (result as JasperCompiler.CompileResult.Error).message
            assertTrue(message.contains("non-blocking await lowering is not implemented yet"), "$name: $message")
            Files.walk(output).use { paths ->
                assertFalse(paths.anyMatch { it.toString().endsWith(".class") }, "$name wrote bytecode")
            }
        }
    }
}
