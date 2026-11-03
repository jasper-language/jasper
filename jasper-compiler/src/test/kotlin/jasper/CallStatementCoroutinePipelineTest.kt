// ============================================================================
// CallStatementCoroutinePipelineTest.kt —— S4-C 第二十三片「调用语句与裸 await」验收
// ============================================================================
// 做什么：经真实 ANTLR 解析、类型检查、协程降级、IR 与 JVM 后端编译 Jasper 源码，加载生成帧并在
//   真实 JvmExecutor 上运行，验证调用语句作帧语句时按源码顺序恰好执行一次、结果被丢弃（各种宽度
//   的返回值都不破坏操作数栈），裸 `await f;` 照常挂起并传播失败 / 取消；另有固定种子属性测试与
//   Kotlin 模型比对调用轨迹，以及拒绝清单。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做平台反射驱动和有界等待。
//
// 失败模式：编译诊断、VerifyError 或超时都由断言直接报告，不静默跳过。
// 为什么比对调用轨迹而不只比对返回值：本片的语义就是副作用的先后与次数，返回值看不出重放或遗漏。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证调用语句与裸 await 在生成帧中的编译与运行语义。 */
class CallStatementCoroutinePipelineTest {

    private val root: Path = Paths.get("build", "tmp", "call-statement-coroutine-pipeline")

    /** 清空并创建模块内目录；绝不写仓库外路径。 */
    private fun freshOutput(name: String): Path {
        val output = root.resolve(name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        return output
    }

    /** 编译标准库 + 源码；编译失败带聚合诊断。 */
    private fun compile(name: String, source: String): URLClassLoader {
        val output = freshOutput(name)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Program.jas" to source), output)
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
        fun isDone(target: Any): Boolean = future.getMethod("isDone").invoke(target) == true

        /** 有界等待终态。 */
        fun awaitDone(target: Any, label: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!isDone(target) && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(isDone(target), "frame did not finish: $label")
        }

        fun value(target: Any, label: String): Any? {
            awaitDone(target, label)
            if (status(target) != "Completed") {
                val failure = future.getMethod("failureOrNull").invoke(target)
                throw AssertionError("$label: ${status(target)} ${failure?.let { it.javaClass.getMethod("message").invoke(it) }}")
            }
            return future.getMethod("get").invoke(target)
        }

        fun failureMessage(target: Any): String? {
            val failure = future.getMethod("failureOrNull").invoke(target) ?: return null
            return failure.javaClass.getMethod("message").invoke(failure) as String
        }

        /** 启动 `demo.Program_<function>_Coroutine`；构造器形如 (Executor, Future<结果>, 参数...)。返回帧。 */
        fun start(function: String, output: Any, vararg args: Any?): Any {
            val type = loader.loadClass("demo.Program_${function}_Coroutine")
            val frame = type.constructors.single().newInstance(executor, output, *args)
            type.getMethod("start").invoke(frame)
            return frame
        }

        fun box(value: Int): Any = loader.loadClass("demo.Box")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(value)
        fun boxValue(box: Any): Int = box.javaClass.getMethod("get").invoke(box) as Int
        fun log(): String = loader.loadClass("demo.Program").getMethod("log").invoke(null) as String

        fun shutdown() {
            executor.javaClass.getMethod("shutdown").invoke(executor)
            assertEquals(true, executor.javaClass.getMethod("awaitTermination", Long::class.javaPrimitiveType)
                .invoke(executor, 5000L))
        }
    }

    /** 启动后再依次完成输入，返回成功值。 */
    private fun Runtime.runSuspended(function: String, inputs: List<Any?>, vararg plain: Any?): Any? {
        val output = newFuture()
        val futures = inputs.map { newFuture() }
        start(function, output, *(futures + plain.toList()).toTypedArray())
        futures.zip(inputs).forEach { (future, value) -> complete(future, value) }
        return value(output, function)
    }

    private val helpers = """
        package demo;
        import std.Future;

        public class Box {
            private var v: int32;
            public constructor(v: int32) { this.v = v; }
            public function get(): int32 { return this.v; }
            public function bump(): int32 { this.v = this.v + 1; return this.v; }
            public function set(v: int32): void { this.v = v; }
        }
    """.trimIndent()

    private val noteHelpers = """
            private static var logged: string = "";

            public static function note(text: string): void { Program.logged = Program.logged + text; }
            public static function noteInt(value: int32): void { Program.logged = Program.logged + value + ";"; }
            public static function log(): string {
                var text: string = Program.logged;
                Program.logged = "";
                return text;
            }
            public static function boom(): void { throw new std.Error("boom"); }
            public static function add(a: int32, b: int32): int32 { Program.note("add;"); return a + b; }
    """

    private val program = """
        $helpers

        public class Program {
            $noteHelpers
            public static function wide(): int64 { Program.note("wide;"); return 5000000000L; }
            public static function real(): float64 { Program.note("real;"); return 1.5; }
            public static function text(): string { Program.note("text;"); return "t"; }
            public static function flag(): bool { Program.note("flag;"); return true; }
            public static function object(): Box { Program.note("obj;"); return new Box(1); }
            public static function maybe(): Box? { Program.note("maybe;"); return null; }

            public static function order(input: Future<int32>): int32 {
                Program.note("a;");
                var x: int32 = await input;
                Program.noteInt(x);
                x = x + 1;
                Program.noteInt(x);
                Program.note("b;");
                return x;
            }

            // 各种宽度的返回值丢弃后操作数栈保持平衡（否则 JVM 校验失败）。
            public static function discarded(input: Future<int32>): int32 {
                Program.add(1, 2);
                Program.wide();
                Program.real();
                Program.text();
                Program.flag();
                Program.object();
                Program.maybe();
                var x: int32 = await input;
                Program.wide();
                Program.real();
                return x;
            }

            public static function receiver(input: Future<int32>, box: Box): int32 {
                box.bump();
                var n: int32 = await input;
                box.set(box.get() + n);
                var local: Box = new Box(10);
                local.bump();
                box.set(box.get() + local.get());
                return box.get();
            }

            // 调用语句可以是 Future 的方法：完成另一个 Future 不进入业务栈以外的恢复路径。
            public static function signal(input: Future<int32>, done: Future<int32>): int32 {
                var n: int32 = await input;
                done.complete(n + 1);
                var again: int32 = await done;
                return again * 2;
            }

            public static function argument(first: Future<int32>, second: Future<string>): int32 {
                Program.noteInt(await first);
                Program.note(await second);
                Program.noteInt(Program.add(await first, 1));
                return 0;
            }

            public static function bare(first: Future<int32>, wide: Future<int64>, text: Future<string>, box: Future<Box>): int32 {
                Program.note("s;");
                await first;
                Program.note("1;");
                await wide;
                Program.note("2;");
                await text;
                Program.note("3;");
                await box;
                Program.note("4;");
                return 9;
            }

            public static function bareLoop(gate: Future<int32>, n: int32): int32 {
                var i: int32 = 0;
                while (i < n) {
                    await gate;
                    Program.noteInt(i);
                    i++;
                }
                return i;
            }

            public static function forClauses(gate: Future<int32>, n: int32): int32 {
                var i: int32 = 0;
                for (Program.note("init;"); i < n; Program.note("step;")) {
                    i++;
                    if (i == 2) {
                        continue;
                    }
                    Program.noteInt(await gate);
                }
                return i;
            }

            public static function forBareAwaitStep(gate: Future<int32>, n: int32): int32 {
                var i: int32 = 0;
                for (; i < n; await gate) {
                    i++;
                    Program.noteInt(i);
                }
                return i;
            }

            public static function cleanup(input: Future<int32>): int32 {
                defer Program.note("deferred;");
                try {
                    Program.note("body;");
                    var x: int32 = await input;
                    Program.boom();
                    Program.note("unreached;");
                    return x;
                } catch (e: std.Error) {
                    Program.note("handler;");
                    await input;
                    Program.note("resumed;");
                } finally {
                    Program.note("finally;");
                }
                Program.note("after;");
                return 3;
            }

            public static function uncaught(input: Future<int32>): int32 {
                try {
                    await input;
                    Program.boom();
                    Program.note("unreached;");
                } finally {
                    Program.note("finally;");
                }
                return 0;
            }

            public static function bareFailure(input: Future<int32>): int32 {
                try {
                    await input;
                    Program.note("unreached;");
                    return 0;
                } catch (e: std.FutureFailureError) {
                    Program.note("failed;");
                    return 1;
                } catch (e: std.FutureCancelledError) {
                    Program.note("cancelled;");
                    return 2;
                }
            }

            public static function untouched(input: Future<int32>): int32 {
                Program.note("before;");
                await input;
                Program.note("after;");
                return 0;
            }

            public static function cancelled(input: Future<int32>): int32 {
                try {
                    Program.note("before;");
                    await input;
                    Program.note("after;");
                } finally {
                    Program.note("finally;");
                }
                return 0;
            }
        }
    """.trimIndent()

    private fun withProgram(name: String, body: Runtime.() -> Unit) {
        compile(name, program).use { loader ->
            val runtime = Runtime(loader)
            try { runtime.body() } finally { runtime.shutdown() }
        }
    }

    @Test
    fun `call statements run in source order and exactly once around a suspension`() = withProgram("order") {
        val output = newFuture()
        val input = newFuture()
        start("order", output, input)
        // 输入未完成时只执行挂起点之前的调用；恢复不重放它。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var seen = ""
        while (seen.isEmpty() && System.nanoTime() < deadline) { seen = log(); Thread.sleep(1) }
        assertEquals("a;", seen)
        assertFalse(isDone(output))
        complete(input, 4)
        assertEquals(5, value(output, "order"))
        assertEquals("4;5;b;", log())
    }

    @Test
    fun `discarded results of every width keep the operand stack balanced`() = withProgram("discarded") {
        assertEquals(6, runSuspended("discarded", listOf(6)))
        assertEquals("add;wide;real;text;flag;obj;maybe;wide;real;", log())
    }

    @Test
    fun `instance calls go through the frame slots of parameters and locals`() = withProgram("receiver") {
        val box = box(1)
        // bump -> 2；set(2 + 3) -> 5；local 11；set(5 + 11) -> 16。
        assertEquals(16, runSuspended("receiver", listOf(3), box))
        assertEquals(16, boxValue(box))
    }

    @Test
    fun `completing a future from a call statement resumes the frame through the dispatch gate`() = withProgram("signal") {
        val done = newFuture()
        assertEquals(16, runSuspended("signal", listOf(7), done))
        assertEquals(8, value(done, "done"))
    }

    @Test
    fun `awaits in static call statement arguments resume before the call`() = withProgram("argument") {
        val output = newFuture()
        val first = newFuture()
        val second = newFuture()
        start("argument", output, first, second)
        complete(first, 7)
        // 第二个输入未完成时，第二条调用不会先行：恰好停在 await second。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var seen = ""
        while (seen.isEmpty() && System.nanoTime() < deadline) { seen = log(); Thread.sleep(1) }
        assertEquals("7;", seen)
        complete(second, "s;")
        assertEquals(0, value(output, "argument"))
        assertEquals("s;add;8;", log())
    }

    @Test
    fun `bare await suspends on every result type and drops the value`() = withProgram("bare") {
        val box = box(1)
        assertEquals(9, runSuspended("bare", listOf(1, 5000000000L, "x", box)))
        assertEquals("s;1;2;3;4;", log())
    }

    @Test
    fun `bare await in a loop body suspends each round`() = withProgram("bareLoop") {
        assertEquals(3, runSuspended("bareLoop", listOf(1), 3))
        assertEquals("0;1;2;", log())
    }

    @Test
    fun `for clauses accept call statements and bare await`() = withProgram("forClauses") {
        assertEquals(3, runSuspended("forClauses", listOf(8), 3))
        assertEquals("init;8;step;step;8;step;", log())
        assertEquals(2, runSuspended("forBareAwaitStep", listOf(1), 2))
        assertEquals("1;2;", log())
    }

    @Test
    fun `calls that throw route through catch finally and defer`() = withProgram("cleanup") {
        assertEquals(3, runSuspended("cleanup", listOf(1)))
        assertEquals("body;handler;resumed;finally;after;deferred;", log())
    }

    @Test
    fun `uncaught throw from a call statement publishes the error after cleanup`() = withProgram("uncaught") {
        val output = newFuture()
        val input = newFuture()
        start("uncaught", output, input)
        complete(input, 1)
        awaitDone(output, "uncaught")
        assertEquals("Failed", status(output))
        assertEquals("boom", failureMessage(output))
        assertEquals("finally;", log())
    }

    @Test
    fun `bare await failure and cancellation reach catch like any await`() = withProgram("bareFailure") {
        val failed = newFuture()
        val failing = newFuture()
        start("bareFailure", failed, failing)
        fail(failing, "no")
        assertEquals(1, value(failed, "failed"))
        assertEquals("failed;", log())

        val cancelled = newFuture()
        val cancelling = newFuture()
        start("bareFailure", cancelled, cancelling)
        cancel(cancelling)
        assertEquals(2, value(cancelled, "cancelled"))
        assertEquals("cancelled;", log())

        val uncaught = newFuture()
        val input = newFuture()
        start("untouched", uncaught, input)
        fail(input, "no")
        awaitDone(uncaught, "untouched")
        assertEquals("Failed", status(uncaught))
        assertEquals("no", failureMessage(uncaught))
        assertEquals("before;", log())
    }

    @Test
    fun `calls after the suspension do not run once the frame is cancelled`() = withProgram("cancelled") {
        val output = newFuture()
        val input = newFuture()
        start("cancelled", output, input)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var seen = ""
        while (seen.isEmpty() && System.nanoTime() < deadline) { seen = log(); Thread.sleep(1) }
        assertEquals("before;", seen)
        cancel(output)
        var cleaned = ""
        while (cleaned.isEmpty() && System.nanoTime() < deadline) { cleaned = log(); Thread.sleep(1) }
        assertEquals("finally;", cleaned)
        // 晚到的输入不复活帧：恢复块的调用不执行。
        complete(input, 1)
        Thread.sleep(50)
        assertEquals("", log())
        assertEquals("Cancelled", status(output))
    }

    /** 属性测试的模型状态：局部 x、调用轨迹。 */
    private class Model(var x: Int, val inputs: IntArray) {
        val trace = StringBuilder()
    }

    /** 模型里 `Program.boom()` 的异常。 */
    private class Boom : RuntimeException()

    /** 同时生成 Jasper 源码与 Kotlin 模型的语句。 */
    private class Gen(val random: Random) {
        var names = 0
        var suspensions = 0
        var calls = 0
        var caught = 0

        fun fresh(prefix: String) = "$prefix${names++}"

        /** 语句序列；defer 把其后的语句包成 try / finally，与源码语义一致。 */
        fun sequence(depth: Int, count: Int): Pair<String, (Model) -> Unit> {
            if (count == 0) return "" to { _ -> }
            if (depth > 0 && random.nextInt(8) == 0) {
                val tag = names++
                val (rest, run) = sequence(depth, count - 1)
                calls++
                return "defer Program.note(\"d$tag;\");\n$rest" to { model ->
                    try { run(model) } finally { model.trace.append("d$tag;") }
                }
            }
            val (head, first) = statement(depth)
            val (rest, run) = sequence(depth, count - 1)
            return "$head\n$rest" to { model -> first(model); run(model) }
        }

        fun statement(depth: Int): Pair<String, (Model) -> Unit> {
            val choice = random.nextInt(if (depth > 0) 11 else 7)
            val input = random.nextInt(2)
            val k = random.nextInt(9) + 1
            return when (choice) {
                0, 1 -> {
                    calls++
                    "Program.note(\"n$k;\");" to { model -> model.trace.append("n$k;") }
                }
                2 -> {
                    calls++
                    "Program.noteInt(x);" to { model -> model.trace.append(model.x).append(';') }
                }
                3 -> {
                    suspensions++
                    "await f$input;" to { _ -> }
                }
                4 -> {
                    suspensions++
                    calls++
                    "Program.noteInt(await f$input);" to { model -> model.trace.append(model.inputs[input]).append(';') }
                }
                5 -> {
                    suspensions++
                    calls++
                    "Program.add(x, await f$input);" to { model -> model.trace.append("add;") }
                }
                6 -> "x = x + $k;" to { model -> model.x += k }
                7 -> {
                    val (thenBody, thenRun) = sequence(depth - 1, 2)
                    val (elseBody, elseRun) = sequence(depth - 1, 2)
                    "if (x % 2 == 0) {\n$thenBody} else {\n$elseBody}" to { model ->
                        if (Math.floorMod(model.x, 2) == 0) thenRun(model) else elseRun(model)
                    }
                }
                8 -> {
                    val i = fresh("i")
                    val rounds = random.nextInt(3)
                    val (body, run) = sequence(depth - 1, 2)
                    calls++
                    "var $i: int32 = 0;\nfor (; $i < $rounds; Program.note(\"s;\")) {\n$i++;\n$body}" to { model ->
                        repeat(rounds) { run(model); model.trace.append("s;") }
                    }
                }
                9 -> {
                    val (body, run) = sequence(depth - 1, 2)
                    calls++
                    "try {\n$body} finally {\nProgram.note(\"f$k;\");\n}" to { model ->
                        try { run(model) } finally { model.trace.append("f$k;") }
                    }
                }
                else -> {
                    val (before, runBefore) = sequence(depth - 1, 2)
                    val (handler, runHandler) = sequence(depth - 1, 2)
                    calls += 2
                    caught++
                    val e = fresh("e")
                    "try {\n${before}Program.boom();\nProgram.note(\"never;\");\n} catch ($e: std.Error) {\n$handler}" to { model ->
                        try {
                            runBefore(model)
                            throw Boom()
                        } catch (_: Boom) {
                            runHandler(model)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `seeded call statement programs agree with the Kotlin model`() {
        val random = Random(20261145L)
        val count = 160
        val programs = (0 until count).map { index ->
            val gen = Gen(random)
            var pair = gen.sequence(3, 6)
            // 每个函数至少一个挂起点，否则不是生成帧。
            while (gen.suspensions == 0) pair = gen.sequence(3, 6)
            val (body, run) = pair
            val source = """
                public static function g$index(f0: Future<int32>, f1: Future<int32>, start: int32): int32 {
                    var x: int32 = start;
                    $body
                    return x;
                }
            """.trimIndent()
            Triple(source, run, gen)
        }
        val source = "$helpers\n\npublic class Program {\n$noteHelpers\n${programs.joinToString("\n") { it.first }}\n}\n"
        // 覆盖下限：调用、挂起与被捕获的调用抛出都要真实出现。
        assertTrue(programs.sumOf { it.third.calls } >= 500, "calls ${programs.sumOf { it.third.calls }}")
        assertTrue(programs.sumOf { it.third.suspensions } >= 300, "suspensions ${programs.sumOf { it.third.suspensions }}")
        assertTrue(programs.sumOf { it.third.caught } >= 40, "caught ${programs.sumOf { it.third.caught }}")
        compile("seeded", source).use { loader ->
            val runtime = Runtime(loader)
            try {
                programs.forEachIndexed { index, (_, run, _) ->
                    val inputs = intArrayOf(random.nextInt(50), random.nextInt(50))
                    val start = random.nextInt(20)
                    val model = Model(start, inputs)
                    run(model)
                    for (preCompleted in listOf(true, false)) {
                        val output = runtime.newFuture()
                        val futures = inputs.map { runtime.newFuture() }
                        if (preCompleted) futures.zip(inputs.toList()).forEach { (f, v) -> runtime.complete(f, v) }
                        runtime.start("g$index", output, futures[0], futures[1], start)
                        if (!preCompleted) futures.zip(inputs.toList()).forEach { (f, v) -> runtime.complete(f, v) }
                        assertEquals(model.x, runtime.value(output, "g$index"), "g$index value")
                        assertEquals(model.trace.toString(), runtime.log(), "g$index trace")
                    }
                }
            } finally {
                runtime.shutdown()
            }
        }
    }

    @Test
    fun `unqualified static call statement resolves like the synchronous path`() {
        val source = """
            $helpers
            public class Other {
                public static function sibling(): void { Program.note("other;"); }
            }
            public class Program {
                $noteHelpers
                public static function sibling(): void { Program.note("program;"); }
                public static function value(n: int32): int32 { return n + 1; }
                private static function hidden(): void { Program.note("hidden;"); }
                public static function sync(): int32 {
                    sibling();
                    return 0;
                }
                public static function f(input: Future<int32>): int32 {
                    sibling();
                    await input;
                    sibling();
                    return 1;
                }
                // 写在局部初始化与 finally 中的同类调用走同一条改写。
                public static function otherPositions(input: Future<int32>): int32 {
                    var a: int32 = value(1);
                    var b: int32 = await input;
                    try {
                        return a + b;
                    } finally {
                        sibling();
                    }
                }
                public static function privateSibling(input: Future<int32>): int32 {
                    hidden();
                    return await input;
                }
            }
        """.trimIndent()
        compile("unqualified-sibling", source).use { loader ->
            val runtime = Runtime(loader)
            try {
                loader.loadClass("demo.Program").getMethod("sync").invoke(null)
                val sync = runtime.log()
                assertEquals(1, runtime.runSuspended("f", listOf(1)))
                assertEquals(sync + sync, runtime.log())
                assertEquals("program;", sync)
                assertEquals(6, runtime.runSuspended("otherPositions", listOf(4)))
                assertEquals("program;", runtime.log())
                assertEquals(2, runtime.runSuspended("privateSibling", listOf(2)))
                assertEquals("hidden;", runtime.log())
            } finally {
                runtime.shutdown()
            }
        }
    }

    @Test
    fun `unsupported statement shapes are diagnosed and write no class`() {
        val cases = mapOf(
            "instance-argument-await" to """
                public static function f(box: Box, input: Future<int32>): int32 {
                    box.set(await input);
                    return 1;
                }
            """,
            "receiver-await" to """
                public static function f(input: Future<Box>): int32 {
                    (await input).bump();
                    return 1;
                }
            """,
            "effect-before-argument-await" to """
                public static function add(a: int32, b: int32): int32 { return a + b; }
                public static function g(): int32 { return 1; }
                public static function f(input: Future<int32>): int32 {
                    Program.add(Program.g(), await input);
                    return 1;
                }
            """,
            "safe-call-argument-await" to """
                public static function f(box: Box?, input: Future<int32>): int32 {
                    box?.set(await input);
                    return 1;
                }
            """,
            "safe-call-statement" to """
                public static function f(box: Box?, input: Future<int32>): int32 {
                    box?.bump();
                    return await input;
                }
            """,
            "safe-call-initializer" to """
                public static function f(box: Box?, input: Future<int32>): int32 {
                    var n: int32 = box?.get() ?? 0;
                    return n + await input;
                }
            """,
            "safe-call-in-finally" to """
                public static function f(box: Box?, input: Future<int32>): int32 {
                    try {
                        return await input;
                    } finally {
                        box?.bump();
                    }
                }
            """,
            "suspending-call-statement" to """
                public static function g(input: Future<int32>): int32 { return await input; }
                public static function f(input: Future<int32>): int32 {
                    Program.g(input);
                    return await input;
                }
            """,
            "suspending-unqualified-sibling" to """
                public static function g(input: Future<int32>): int32 { return await input; }
                public static function f(input: Future<int32>): int32 {
                    g(input);
                    return await input;
                }
            """,
            "unqualified-sibling-argument-await" to """
                public static function g(a: int32): int32 { return a; }
                public static function f(input: Future<int32>): int32 {
                    g(await input);
                    return 1;
                }
            """,
            "instance-sibling-call" to """
                public function g(): void {}
                public static function f(input: Future<int32>): int32 {
                    g();
                    return await input;
                }
            """,
            "suspending-call-for-step" to """
                public static function g(input: Future<int32>): int32 { return await input; }
                public static function f(input: Future<int32>): int32 {
                    var i: int32 = 0;
                    for (; i < 2; Program.g(input)) {
                        i++;
                    }
                    return await input;
                }
            """,
            "suspending-call-in-coalesce" to """
                public static function g(input: Future<Box>): Box { return await input; }
                public static function f(a: Box?, input: Future<Box>): int32 {
                    var kept: Box = a ?? Program.g(input);
                    var other: Box = await input;
                    return 1;
                }
            """,
            "await-in-arithmetic-statement" to """
                public static function f(input: Future<int32>): int32 {
                    await input + 1;
                    return 1;
                }
            """,
            "new-statement" to """
                public static function f(input: Future<int32>): int32 {
                    new Box(1);
                    return await input;
                }
            """,
            "bare-await-unsupported-type" to """
                public static function f(input: Future<any>): int32 {
                    await input;
                    return 1;
                }
            """,
            "await-in-finally-call" to """
                public static function g(a: int32): void {}
                public static function f(input: Future<int32>): int32 {
                    var x: int32 = await input;
                    try {
                        return x;
                    } finally {
                        Program.g(await input);
                    }
                }
            """,
            "catch-parameter-in-call" to """
                public static function g(e: std.Error): void {}
                public static function f(input: Future<int32>): int32 {
                    try {
                        return await input;
                    } catch (e: std.Error) {
                        Program.g(e);
                        return 0;
                    }
                }
            """
        )
        for ((name, body) in cases) {
            val output = freshOutput("unsupported-$name")
            val source = """
                $helpers
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
