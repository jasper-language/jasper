// ============================================================================
// FrameSlotCoroutinePipelineTest.kt —— 帧引用槽裁决（用户 2026-11-04）验收
// ============================================================================
// 做什么：验证挂起帧引用字段的四条规则：
//   ① 不声明隐式业务默认值（构造后引用字段都是 null、有效标记为 false，string 也不例外）；
//   ② 首次读取前完成初始化（规划器拒绝可能先读后写的块表）；
//   ③ 可空引用 = 有效标记 + null，非空引用有效时不为 null（写入 null 报 FrameSlotError）；
//   ④ 槽位死亡后清除引用（挂起时已不活跃的字段为 null；finally / catch 还要读的保留；回收时全部清除）。
//
// 所属层次：compiler 模块端到端测试层；经真实 ANTLR 解析、类型检查、协程降级与 JVM 执行。帧字段
//   按「参数在前、局部按源码出现顺序」编号（LinearCoroutineEmitter），测试直接反射读取。
//
// 失败模式：编译诊断、VerifyError 或超时都由断言直接报告，不静默跳过。
// ============================================================================

package jasper

import jasper.ast.*
import jasper.compiler.JasperCompiler
import jasper.translator.CoroutineBlockPlanner
import java.lang.ref.WeakReference
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

/** 验证生成帧引用槽的初始化、有效性与清除规则。 */
class FrameSlotCoroutinePipelineTest {

    private val root: Path = Paths.get("build", "tmp", "frame-slot-coroutine-pipeline")

    private fun compile(name: String, source: String): URLClassLoader {
        val output = root.resolve(name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
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
        fun failureMessage(target: Any): String? = future.getMethod("failureOrNull").invoke(target)
            ?.let { it.javaClass.getMethod("message").invoke(it) as String }

        fun awaitDone(target: Any, label: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (future.getMethod("isDone").invoke(target) != true && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(true, future.getMethod("isDone").invoke(target), "frame did not finish: $label")
        }

        fun value(target: Any, label: String): Any? {
            awaitDone(target, label)
            assertEquals("Completed", status(target), "$label: ${failureMessage(target)}")
            return future.getMethod("get").invoke(target)
        }

        /** 只构造不启动。 */
        fun frame(function: String, output: Any, vararg args: Any?): Any {
            val type = loader.loadClass("demo.Program_${function}_Coroutine")
            return type.constructors.single().newInstance(executor, output, *args)
        }

        fun start(frame: Any) { frame.javaClass.getMethod("start").invoke(frame) }

        /** 等执行器跑完已入队的段；之后读帧字段与工作线程的写入有先行关系。 */
        fun drain() {
            val ran = CountDownLatch(1)
            val runnable = loader.loadClass("std.Runnable")
            val gate = Proxy.newProxyInstance(runnable.classLoader, arrayOf(runnable)) { _, method, _ ->
                if (method.name == "run") ran.countDown()
                null
            }
            executor.javaClass.getMethod("schedule", runnable).invoke(executor, gate)
            assertTrue(ran.await(30, TimeUnit.SECONDS), "worker did not drain")
        }

        /** 等帧回收：结果发布之后 finally 块才清除并标记 settled。 */
        fun settled(output: Any, label: String) {
            awaitDone(output, label)
            drain()
        }

        fun field(frame: Any, name: String): Any? = frame.javaClass.getField(name).get(frame)
        fun slot(frame: Any, index: Int): Any? = field(frame, "__slot$index")
        fun valid(frame: Any, index: Int): Boolean = field(frame, "__slot${index}_valid") as Boolean
        fun hasFlag(frame: Any, index: Int) = frame.javaClass.fields.any { it.name == "__slot${index}_valid" }

        fun box(value: Int): Any = loader.loadClass("demo.Box")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(value)
        fun bumps(box: Any): Int = box.javaClass.getMethod("get").invoke(box) as Int

        /** 帧不持有引用：所有引用字段为 null，所有有效标记为 false。 */
        fun assertAllCleared(frame: Any, label: String) {
            frame.javaClass.fields.filter { it.name.startsWith("__slot") }.forEach { field ->
                val value = field.get(frame)
                when {
                    field.name.endsWith("_valid") -> assertEquals(false, value, "$label: ${field.name}")
                    field.type.isPrimitive -> Unit
                    else -> assertNull(value, "$label: ${field.name}")
                }
            }
        }

        fun shutdown() {
            executor.javaClass.getMethod("shutdown").invoke(executor)
            assertEquals(true, executor.javaClass.getMethod("awaitTermination", Long::class.javaPrimitiveType)
                .invoke(executor, 30000L))
        }
    }

    private fun withRuntime(name: String, source: String, body: Runtime.() -> Unit) {
        val runtime = Runtime(compile(name, source))
        withCleanup({ runtime.shutdown() }) { runtime.body() }
    }

    private val program = """
        package demo;
        import std.Future;
        import std.FrameSlotError;

        public class Box {
            private var v: int32;
            public constructor(v: int32) { this.v = v; }
            public function get(): int32 { return this.v; }
            public function bump(): int32 { this.v = this.v + 1; return this.v; }
            // 读写槽位不得进入用户代码；被调用就记下来。
            public function equals(other: any): bool {
                Program.equalsCalls = Program.equalsCalls + 1;
                return false;
            }
        }

        public class Program {
            public static var equalsCalls: int32 = 0;

            public static function makeNull(): any? { return null; }

            public static function fresh(gate: Future<int32>): int32 {
                var s: string = "x";
                var maybe: Box? = null;
                var box: Box = new Box(1);
                var n: int32 = await gate;
                return n + box.get();
            }

            // 槽位：0 box 1 a 2 b 3 n 4 x 5 y
            public static function deadAfterRead(box: Box, a: Future<int32>, b: Future<int32>): int32 {
                var n: int32 = box.get();
                var x: int32 = await a;
                var y: int32 = await b;
                return n + x + y;
            }

            // 槽位：0 keep 1 gate 2 n
            public static function explicitNull(keep: Box?, gate: Future<int32>): int32 {
                var n: int32 = await gate;
                if (keep == null) { return n; }
                return n + 1;
            }

            // 槽位：0 a 1 gate 2 local 3 n
            public static function nullableLocal(a: Box?, gate: Future<int32>): bool {
                var local: Box? = a;
                var n: int32 = await gate;
                return local == null;
            }

            // 槽位：0 input 1 gate 2 got 3 n 4 y
            public static function boundThenDead(input: Future<Box>, gate: Future<int32>): int32 {
                var got: Box = await input;
                var n: int32 = got.get();
                var y: int32 = await gate;
                return n + y;
            }

            public static function nonNullAwait(input: Future<Box>): int32 {
                var got: Box = await input;
                return got.get();
            }

            public static function nonNullParam(box: Box, gate: Future<int32>): int32 {
                var n: int32 = await gate;
                return n + box.get();
            }

            public static function nonNullAssign(gate: Future<int32>): int32 {
                var n: int32 = await gate;
                var b: Box = cast(Program.makeNull()) as Box;
                return n + b.get();
            }

            public static function nonNullCaught(input: Future<Box>): int32 {
                try {
                    var got: Box = await input;
                    return got.get();
                } catch (e: FrameSlotError) {
                    return 77;
                }
            }

            public static function nullWithCleanup(input: Future<Box>, marker: Box): int32 {
                try {
                    var got: Box = await input;
                    return got.get();
                } finally {
                    marker.bump();
                }
            }

            // 槽位：0 box 1 gate 2 n
            public static function cleanupKeeps(box: Box, gate: Future<int32>): int32 {
                try {
                    var n: int32 = await gate;
                    return n;
                } finally {
                    box.bump();
                }
            }

            // 槽位：0 box 1 gate 2 n
            public static function deferKeeps(box: Box, gate: Future<int32>): int32 {
                defer { box.bump(); }
                var n: int32 = await gate;
                return n;
            }

            // 槽位：0 box 1 gate，其后是区域局部与 n
            public static function handlerKeeps(box: Box, gate: Future<int32>): int32 {
                try {
                    var n: int32 = await gate;
                    return n;
                } catch (e: std.FutureFailureError) {
                    return box.bump();
                }
            }

            // 槽位：0 box 1 first 2 second 3 last 4 i 5 x 6 z
            public static function loopKeeps(box: Box, first: Future<int32>, second: Future<int32>, last: Future<int32>): int32 {
                var i: int32 = 0;
                while (i < 2) {
                    var x: int32 = 0;
                    if (i == 0) { x = await first; } else { x = await second; }
                    i = i + box.get();
                }
                var z: int32 = await last;
                return z + i;
            }

            // 槽位：0 box 1 c 2 g1 3 g2，其后是局部
            public static function deadOnOneBranch(box: Box, c: bool, g1: Future<int32>, g2: Future<int32>): int32 {
                if (c) {
                    var u: int32 = await g1;
                    return u + box.get();
                }
                var v: int32 = await g2;
                return v;
            }

            public static function reassigned(box: Box, gate: Future<int32>): int32 {
                var b: Box = box;
                var n: int32 = await gate;
                b = new Box(n);
                return b.get();
            }

            public static function usesEquals(box: Box, gate: Future<int32>): int32 {
                var b: Box = box;
                var n: int32 = await gate;
                return n + b.get();
            }

            // 槽位：0 input 1 gate 2 after，其后是区域局部与 t、n、r
            public static function handlerDropsTry(input: Future<Box>, gate: Future<int32>, after: Future<int32>): int32 {
                try {
                    var t: Box = await input;
                    var n: int32 = await gate;
                    return n + t.get();
                } catch (e: std.FutureFailureError) {
                    var r: int32 = await after;
                    return r;
                }
            }

            // 槽位：0 first 1 second 2 b 3 x
            public static function rebound(first: Future<Box>, second: Future<Box>): int32 {
                var b: Box = await first;
                var x: int32 = b.get();
                b = await second;
                return b.get() + x;
            }

            // 槽位：0 unused 1 gate 2 n
            public static function neverRead(unused: Box, gate: Future<int32>): int32 {
                var n: int32 = await gate;
                return n;
            }

            public static function touch(): void { }

            // 槽位：0 box 1 gate，其后是区域局部与 n
            public static function outerFinallyKeeps(box: Box, gate: Future<int32>): int32 {
                try {
                    try {
                        var n: int32 = await gate;
                        return n;
                    } finally {
                        Program.touch();
                    }
                } finally {
                    box.bump();
                }
            }

            // 槽位同上。内层 finally 的区域里，外层 catch 的处理块还要读 box。
            public static function outerHandlerKeeps(box: Box, gate: Future<int32>): int32 {
                try {
                    try {
                        var n: int32 = await gate;
                        return n;
                    } finally {
                        Program.touch();
                    }
                } catch (e: std.FutureFailureError) {
                    return box.bump();
                }
            }

            public static function stringSlot(gate: Future<int32>, text: string): string {
                var s: string = text;
                var n: int32 = await gate;
                return s + "!";
            }

            public static function nullableString(gate: Future<int32>, text: string?): string? {
                var n: int32 = await gate;
                return text;
            }
        }
    """.trimIndent()

    @Test
    fun `reference fields have no implicit default before the first write`() = withRuntime("fresh", program) {
        val output = newFuture()
        val gate = newFuture()
        val frame = frame("fresh", output, gate)
        // 槽位：0 gate 1 s 2 maybe 3 box 4 n
        assertSame(gate, slot(frame, 0))
        assertNull(slot(frame, 1), "string local must not default to the empty string")
        assertNull(slot(frame, 2))
        assertFalse(valid(frame, 2), "nullable local is not valid before its first write")
        assertNull(slot(frame, 3))
        start(frame)
        drain()
        // maybe 与 s 不再被读取，挂起前已清除；box 还要读。
        assertNull(slot(frame, 1))
        assertFalse(valid(frame, 2))
        assertNotNull(slot(frame, 3))
        complete(gate, 4)
        assertEquals(5, value(output, "fresh"))
        settled(output, "fresh")
        assertAllCleared(frame, "fresh")
    }

    @Test
    fun `dead slots are cleared before suspending and live ones are kept`() = withRuntime("dead", program) {
        val output = newFuture()
        val a = newFuture()
        val b = newFuture()
        val box = box(5)
        val frame = frame("deadAfterRead", output, box, a, b)
        start(frame)
        drain()
        assertNull(slot(frame, 0), "box has no later read")
        assertNull(slot(frame, 1), "the awaited future is held by the suspended block, not the slot")
        assertSame(b, slot(frame, 2), "b is read by the next suspension")
        complete(a, 1)
        drain()
        assertNull(slot(frame, 2))
        complete(b, 2)
        assertEquals(8, value(output, "deadAfterRead"))
        settled(output, "deadAfterRead")
        assertAllCleared(frame, "deadAfterRead")
    }

    @Test
    fun `explicit null is valid and distinct from a dead slot`() = withRuntime("explicit", program) {
        val output = newFuture()
        val gate = newFuture()
        val frame = frame("explicitNull", output, null, gate)
        assertTrue(hasFlag(frame, 0))
        assertTrue(valid(frame, 0), "a nullable parameter is initialized by the constructor")
        start(frame)
        drain()
        assertNull(slot(frame, 0))
        assertTrue(valid(frame, 0), "explicit null across a suspension stays valid")
        complete(gate, 3)
        assertEquals(3, value(output, "explicitNull"))
        settled(output, "explicitNull")
        assertFalse(valid(frame, 0), "slot death invalidates the flag")
    }

    @Test
    fun `nullable local written with null becomes valid and non null value keeps identity`() = withRuntime("local", program) {
        val nullOutput = newFuture()
        val nullGate = newFuture()
        val nullFrame = frame("nullableLocal", nullOutput, null, nullGate)
        assertFalse(valid(nullFrame, 2))
        start(nullFrame)
        drain()
        assertTrue(valid(nullFrame, 2))
        assertNull(slot(nullFrame, 2))
        complete(nullGate, 1)
        assertEquals(true, value(nullOutput, "nullableLocal null"))

        val output = newFuture()
        val gate = newFuture()
        val box = box(1)
        val frame = frame("nullableLocal", output, box, gate)
        start(frame)
        drain()
        assertSame(box, slot(frame, 2))
        assertTrue(valid(frame, 2))
        complete(gate, 1)
        assertEquals(false, value(output, "nullableLocal value"))
    }

    @Test
    fun `flags exist only for nullable reference slots`() = withRuntime("flags", program) {
        val frame = frame("deadAfterRead", newFuture(), box(1), newFuture(), newFuture())
        (0..5).forEach { assertFalse(hasFlag(frame, it), "non-null and value slots carry no flag: $it") }
    }

    @Test
    fun `null into a non null await result is a failure naming the slot`() = withRuntime("nullawait", program) {
        val output = newFuture()
        val input = newFuture()
        val frame = frame("nonNullAwait", output, input)
        start(frame)
        complete(input, null)
        awaitDone(output, "nonNullAwait")
        assertEquals("Failed", status(output))
        assertEquals("null written to non-null frame slot 'got'", failureMessage(output))
        settled(output, "nonNullAwait")
        assertAllCleared(frame, "nonNullAwait")
    }

    @Test
    fun `null non null parameter fails through the result not the caller`() = withRuntime("nullparam", program) {
        val output = newFuture()
        val gate = newFuture()
        val frame = frame("nonNullParam", output, null, gate)
        start(frame)
        awaitDone(output, "nonNullParam")
        assertEquals("Failed", status(output))
        assertEquals("null written to non-null frame slot 'box'", failureMessage(output))
    }

    @Test
    fun `null assignment to a non null local fails`() = withRuntime("nullassign", program) {
        val output = newFuture()
        val gate = newFuture()
        start(frame("nonNullAssign", output, gate))
        complete(gate, 1)
        awaitDone(output, "nonNullAssign")
        assertEquals("Failed", status(output))
        assertEquals("null written to non-null frame slot 'b'", failureMessage(output))
    }

    @Test
    fun `slot error is catchable and runs finally`() = withRuntime("nullcaught", program) {
        val caughtOutput = newFuture()
        val caughtInput = newFuture()
        start(frame("nonNullCaught", caughtOutput, caughtInput))
        complete(caughtInput, null)
        assertEquals(77, value(caughtOutput, "nonNullCaught"))

        val output = newFuture()
        val input = newFuture()
        val marker = box(0)
        start(frame("nullWithCleanup", output, input, marker))
        complete(input, null)
        settled(output, "nullWithCleanup")
        assertEquals("Failed", status(output))
        assertEquals(1, bumps(marker), "finally runs before the failure is published")
    }

    @Test
    fun `slots read by finally survive suspension and cancellation`() = withRuntime("cleanup", program) {
        for (function in listOf("cleanupKeeps", "deferKeeps")) {
            val output = newFuture()
            val gate = newFuture()
            val box = box(0)
            val frame = frame(function, output, box, gate)
            start(frame)
            drain()
            assertSame(box, slot(frame, 0), "$function: cleanup still reads box")
            assertNull(slot(frame, 1), "$function: gate has no later read")
            cancel(gate)
            settled(output, function)
            assertEquals("Cancelled", status(output), function)
            assertEquals(1, bumps(box), function)
            assertAllCleared(frame, function)
        }
    }

    @Test
    fun `slots read by a catch handler survive suspension`() = withRuntime("handler", program) {
        val output = newFuture()
        val gate = newFuture()
        val box = box(10)
        val frame = frame("handlerKeeps", output, box, gate)
        start(frame)
        drain()
        assertSame(box, slot(frame, 0), "only the handler reads box")
        fail(gate, "boom")
        assertEquals(11, value(output, "handlerKeeps"))
        settled(output, "handlerKeeps")
        assertAllCleared(frame, "handlerKeeps")
    }

    @Test
    fun `a slot read across the back edge is kept and dies after the loop`() = withRuntime("loop", program) {
        val output = newFuture()
        val first = newFuture()
        val second = newFuture()
        val last = newFuture()
        val box = box(1)
        val frame = frame("loopKeeps", output, box, first, second, last)
        start(frame)
        drain()
        assertSame(box, slot(frame, 0))
        assertSame(last, slot(frame, 3))
        complete(first, 1)
        drain()
        assertSame(box, slot(frame, 0), "the next iteration reads box again")
        complete(second, 1)
        drain()
        assertNull(slot(frame, 0), "no read remains after the loop")
        assertNull(slot(frame, 1))
        assertNull(slot(frame, 2))
        complete(last, 5)
        assertEquals(7, value(output, "loopKeeps"))
    }

    @Test
    fun `a slot read on one branch only dies on the other`() = withRuntime("branch", program) {
        val output = newFuture()
        val g1 = newFuture()
        val g2 = newFuture()
        val box = box(2)
        val frame = frame("deadOnOneBranch", output, box, false, g1, g2)
        start(frame)
        drain()
        assertNull(slot(frame, 0))
        assertNull(slot(frame, 2), "g1 only matters on the other branch")
        complete(g2, 9)
        assertEquals(9, value(output, "false branch"))

        val output2 = newFuture()
        val g3 = newFuture()
        val frame2 = frame("deadOnOneBranch", output2, box, true, g3, newFuture())
        start(frame2)
        drain()
        assertSame(box, slot(frame2, 0))
        assertNull(slot(frame2, 3))
        complete(g3, 9)
        assertEquals(11, value(output2, "true branch"))
    }

    @Test
    fun `the old value of a reassigned slot is not retained across the suspension`() = withRuntime("reassign", program) {
        val output = newFuture()
        val gate = newFuture()
        val frame = frame("reassigned", output, box(1), gate)
        start(frame)
        drain()
        // 槽位：0 box 1 gate 2 b 3 n。b 下一次被写入而不是读取，旧值已死。
        assertNull(slot(frame, 0))
        assertNull(slot(frame, 2))
        complete(gate, 6)
        assertEquals(6, value(output, "reassigned"))
    }

    @Test
    fun `slot checks never enter user equals`() = withRuntime("equals", program) {
        val output = newFuture()
        val gate = newFuture()
        start(frame("usesEquals", output, box(1), gate))
        complete(gate, 2)
        assertEquals(3, value(output, "usesEquals"))
        val calls = loader.loadClass("demo.Program").getField("equalsCalls").get(null)
        assertEquals(0, calls)
    }

    @Test
    fun `string slots follow the reference rules`() = withRuntime("strings", program) {
        val output = newFuture()
        val gate = newFuture()
        val frame = frame("stringSlot", output, gate, "a")
        start(frame)
        drain()
        assertNull(slot(frame, 1), "text has no later read")
        assertEquals("a", slot(frame, 2))
        complete(gate, 1)
        assertEquals("a!", value(output, "stringSlot"))

        val nullOutput = newFuture()
        val nullGate = newFuture()
        start(frame("stringSlot", nullOutput, nullGate, null))
        awaitDone(nullOutput, "stringSlot null")
        assertEquals("null written to non-null frame slot 'text'", failureMessage(nullOutput))

        val nullableOutput = newFuture()
        val nullableGate = newFuture()
        start(frame("nullableString", nullableOutput, nullableGate, null))
        complete(nullableGate, 1)
        assertNull(value(nullableOutput, "nullableString"))
    }

    @Test
    fun `a parameter that is never read is cleared before the first suspension`() = withRuntime("neverread", program) {
        val output = newFuture()
        val gate = newFuture()
        val frame = frame("neverRead", output, box(1), gate)
        start(frame)
        drain()
        assertNull(slot(frame, 0))
        complete(gate, 2)
        assertEquals(2, value(output, "neverRead"))
    }

    @Test
    fun `slots read by an outer finally or handler survive inside an inner region`() = withRuntime("outer", program) {
        val cleanupOutput = newFuture()
        val cleanupGate = newFuture()
        val cleanupBox = box(0)
        val cleanupFrame = frame("outerFinallyKeeps", cleanupOutput, cleanupBox, cleanupGate)
        start(cleanupFrame)
        drain()
        assertSame(cleanupBox, slot(cleanupFrame, 0))
        cancel(cleanupGate)
        settled(cleanupOutput, "outerFinallyKeeps")
        assertEquals("Cancelled", status(cleanupOutput))
        assertEquals(1, bumps(cleanupBox))

        val output = newFuture()
        val gate = newFuture()
        val box = box(5)
        val frame = frame("outerHandlerKeeps", output, box, gate)
        start(frame)
        drain()
        assertSame(box, slot(frame, 0))
        fail(gate, "boom")
        assertEquals(6, value(output, "outerHandlerKeeps"))
    }

    @Test
    fun `entering a catch handler drops what only the try body read`() = withRuntime("handlerdrop", program) {
        val output = newFuture()
        val input = newFuture()
        val gate = newFuture()
        val after = newFuture()
        val payload = box(3)
        val frame = frame("handlerDropsTry", output, input, gate, after)
        start(frame)
        drain()
        complete(input, payload)
        drain()
        drain()
        val slots = frame.javaClass.fields.filter { it.name.matches(Regex("__slot\\d+")) }
        assertTrue(slots.any { it.get(frame) === payload }, "t is read after gate")
        fail(gate, "boom")
        drain()
        drain()
        assertTrue(slots.none { it.get(frame) === payload }, "the handler never reads t")
        complete(after, 8)
        assertEquals(8, value(output, "handlerDropsTry"))
    }

    @Test
    fun `an await rebinding a slot drops the old value while suspended`() = withRuntime("rebound", program) {
        val output = newFuture()
        val first = newFuture()
        val second = newFuture()
        val observed = frame("rebound", output, first, second)
        start(observed)
        drain()
        complete(first, box(4))
        drain()
        drain()
        assertNull(slot(observed, 2), "b is rewritten by the await before any read")
        complete(second, box(5))
        assertEquals(9, value(output, "rebound"))
    }

    @Test
    fun `read checks report invalid slots and pass valid ones`() = withRuntime("direct", program) {
        val slot = loader.loadClass("std.FrameSlot")
        val obj = Any()
        val readNonNull = slot.getMethod("readNonNull", Any::class.java, String::class.java)
        val readNullable = slot.getMethod("readNullable", Any::class.java, Boolean::class.javaPrimitiveType, String::class.java)
        assertSame(obj, readNonNull.invoke(null, obj, "x"))
        assertSame(obj, readNullable.invoke(null, obj, true, "x"))
        assertNull(readNullable.invoke(null, null, true, "x"), "explicit null")
        for (call in listOf({ readNonNull.invoke(null, null, "x") }, { readNullable.invoke(null, null, false, "x") })) {
            val error = kotlin.runCatching { call() }.exceptionOrNull()?.cause
            assertEquals("std.FrameSlotError", error?.javaClass?.name)
            assertEquals("frame slot 'x' read while not valid", error?.message)
        }
    }

    /** 单独成函数：payload 与 input 不留在调用方的 JVM 局部里。 */
    private fun Runtime.startWithPayload(frameOutput: Any, gate: Any): Pair<Any, WeakReference<Any>> {
        val input = newFuture()
        val payload = box(1)
        val frame = frame("boundThenDead", frameOutput, input, gate)
        start(frame)
        complete(input, payload)
        return frame to WeakReference(payload)
    }

    /** 从 [start] 经实例字段与数组走到 [target] 的路径；失败时用来指出谁还持有它。 */
    private fun pathTo(start: Any, target: Any): String? {
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        val queue = ArrayDeque(listOf(start to start.javaClass.simpleName))
        while (queue.isNotEmpty()) {
            val (node, path) = queue.removeFirst()
            if (node === target) return path
            if (seen.put(node, true) != null || !node.javaClass.name.startsWith("std.") && !node.javaClass.name.startsWith("demo.") && node !is Array<*>) continue
            if (node is Array<*>) { node.filterNotNull().forEach { queue += it to "$path[]" }; continue }
            var type: Class<*>? = node.javaClass
            while (type != null && type != Any::class.java) {
                type.declaredFields.filter { !it.type.isPrimitive && !java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { field ->
                    runCatching { field.isAccessible = true; field.get(node) }.getOrNull()?.let { queue += it to "$path.${field.name}" }
                }
                type = type.superclass
            }
        }
        return null
    }

    @Test
    fun `an object held only by a dead slot can be collected while suspended`() = withRuntime("gc", program) {
        val output = newFuture()
        val gate = newFuture()
        val (frame, weak) = startWithPayload(output, gate)
        // 恢复段可能排在第一次 drain 之后；等到帧挂在 gate 上。
        val resumed = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while ((field(frame, "__phase") as Int) < 2) {
            assertTrue(System.nanoTime() < resumed, "frame did not reach the second suspension")
            drain()
        }
        drain()
        assertNull(slot(frame, 0), "input has no later read")
        assertNull(slot(frame, 1), "gate has no later read")
        assertNull(slot(frame, 2), "got has no later read")
        assertNull(field(frame, "__outcome"), "the outcome snapshot must not outlive the binding")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (weak.get() != null && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(10)
        }
        assertNull(weak.get(), "still retained via: " + weak.get()?.let { pathTo(frame, it) })
        complete(gate, 1)
        assertEquals(2, value(output, "boundThenDead"))
    }

    // ------------------------------------------------------------------ 规划器：首次读取前完成初始化

    private val boxType = JasNamedType("Box")
    private val futureOfBox = JasNamedType("std.Future", listOf(boxType))

    private fun realAwait(): JasUnaryOp = JasUnaryOp("await", JasIdentifier("f"), true).also { it.awaitResultType = boxType }

    /** 不经检查器：检查器的词法作用域之外，块表自身也要站得住。 */
    private fun planReason(vararg statements: JasStatement): String? = CoroutineBlockPlanner.plan(
        JasFunction("g", listOf(JasParameter("f", futureOfBox)), boxType, JasBlock(statements.toList()), listOf("static")),
        mapOf("f" to futureOfBox)
    ).reason

    private fun declare(name: String, value: JasExpression) = JasVariableStatement(name, boxType, value, false)

    @Test
    fun `read before the declaration on the same path is rejected`() {
        assertEquals(
            "local 'x' may be read before it is initialized",
            planReason(declare("y", JasIdentifier("x")), declare("x", realAwait()), JasReturn(JasIdentifier("y")))
        )
    }

    @Test
    fun `read after a one armed branch that writes is rejected`() {
        assertEquals(
            "local 'x' may be read before it is initialized",
            planReason(
                JasIf(JasBoolLiteral(true), JasBlock(listOf(declare("x", realAwait()))), null),
                JasReturn(JasIdentifier("x"))
            )
        )
    }

    @Test
    fun `read after a loop body that writes is rejected`() {
        assertEquals(
            "local 'x' may be read before it is initialized",
            planReason(
                JasWhile(JasBoolLiteral(false), JasBlock(listOf(declare("x", realAwait())))),
                JasReturn(JasIdentifier("x"))
            )
        )
    }

    @Test
    fun `catch handler and finally only see what was written before the region`() {
        val tryBody = JasBlock(listOf(declare("x", realAwait()), JasReturn(JasIdentifier("x"))))
        val clause = JasCatchClause(JasParameter("e", JasNamedType("std.Error")), JasBlock(listOf(JasReturn(JasIdentifier("x")))))
        assertEquals("local 'x' may be read before it is initialized", planReason(JasTry(tryBody, listOf(clause), null)))
        val cleanup = JasBlock(listOf(JasExpressionStatement(JasCall(JasPropertyAccess(JasIdentifier("x"), "get"), emptyList()))))
        assertEquals("local 'x' may be read before it is initialized", planReason(JasTry(tryBody, emptyList(), cleanup)))
    }

    @Test
    fun `written on every path and written before the region are accepted`() {
        assertNull(planReason(
            declare("x", realAwait()),
            JasIf(JasBoolLiteral(true), JasBlock(listOf(JasReturn(JasIdentifier("x")))),
                JasBlock(listOf(JasExpressionStatement(JasAssignment(JasIdentifier("x"), realAwait()))))),
            JasReturn(JasIdentifier("x"))
        ))
        val clause = JasCatchClause(JasParameter("e", JasNamedType("std.Error")), JasBlock(listOf(JasReturn(JasIdentifier("x")))))
        assertNull(planReason(
            declare("x", realAwait()),
            JasTry(JasBlock(listOf(JasExpressionStatement(JasAssignment(JasIdentifier("x"), realAwait())), JasReturn(JasIdentifier("x")))), listOf(clause), null)
        ))
        // finally 体内部先声明后读取。
        val cleanup = JasBlock(listOf(
            declare("t", JasIdentifier("f")),
            JasExpressionStatement(JasCall(JasPropertyAccess(JasIdentifier("t"), "get"), emptyList()))
        ))
        assertNull(planReason(JasTry(JasBlock(listOf(declare("x", realAwait()), JasReturn(JasIdentifier("x")))), emptyList(), cleanup)))
    }

    @Test
    fun `source level use before declaration does not produce a frame`() {
        val prefix = """
            package demo;
            import std.Future;
            public class Box { public function get(): int32 { return 1; } }
            public class Program {
                public static function g(f: Future<Box>): int32 {
        """.trimIndent()
        for ((name, body) in listOf(
            "selfinit" to "var x: Box = x; var y: Box = await f; return y.get();",
            "deferlater" to "defer { x.get(); } var x: Box = await f; return x.get();",
            "finallyinner" to "try { var x: Box = await f; return x.get(); } finally { x.get(); }"
        )) {
            val output = root.resolve(name)
            output.toFile().deleteRecursively()
            Files.createDirectories(output)
            val result = JasperCompiler().compileAllWithStdlib(mapOf("Program.jas" to "$prefix $body } }"), output)
            assertTrue(result is JasperCompiler.CompileResult.Error, "$name must be rejected: $result")
        }
    }

    // ------------------------------------------------------------------ 固定种子属性测试

    /** 随机程序的语句；模型解释执行，同时给出独立于活跃性分析的判据。 */
    private sealed class Node {
        class Await : Node()
        class ReadBox(val box: Int) : Node()
        class WriteBox(val box: Int, val value: Int) : Node()
        class SetMaybe(val maybe: Int, val box: Int?) : Node()
        class ReadMaybe(val maybe: Int) : Node()
        class Branch(val thenBody: List<Node>, val elseBody: List<Node>) : Node()
        class Loop(val id: Int, val count: Int, val body: List<Node>) : Node()
        class Finally(val id: Int, val body: List<Node>, val cleanup: List<Node>) : Node()
    }

    private val boxes = 3
    private val maybes = 2

    private class Generator(val random: Random) {
        var nextId = 0
        fun simple(boxes: Int, maybes: Int): Node = when (random.nextInt(5)) {
            0 -> Node.ReadBox(random.nextInt(boxes))
            1 -> Node.WriteBox(random.nextInt(boxes), random.nextInt(100))
            2 -> Node.SetMaybe(random.nextInt(maybes), if (random.nextBoolean()) null else random.nextInt(boxes))
            3 -> Node.ReadMaybe(random.nextInt(maybes))
            else -> Node.Await()
        }
        fun block(depth: Int, boxes: Int, maybes: Int): List<Node> = List(1 + random.nextInt(4)) {
            val roll = random.nextInt(10)
            when {
                depth > 0 && roll == 0 -> Node.Branch(block(depth - 1, boxes, maybes), block(depth - 1, boxes, maybes))
                depth > 0 && roll == 1 -> Node.Loop(nextId++, 1 + random.nextInt(2), block(depth - 1, boxes, maybes))
                depth > 0 && roll == 2 -> Node.Finally(nextId++, block(depth - 1, boxes, maybes),
                    List(1 + random.nextInt(2)) { Node.ReadBox(random.nextInt(boxes)) })
                else -> simple(boxes, maybes)
            }
        }
    }

    /** 渲染成源码；sum 是唯一的可观察结果。 */
    private fun render(nodes: List<Node>, out: StringBuilder) {
        nodes.forEach { node ->
            when (node) {
                is Node.Await -> out.append("sum = sum + await Program.next();\n")
                is Node.ReadBox -> out.append("sum = sum + b${node.box}.get();\n")
                is Node.WriteBox -> out.append("b${node.box} = new Box(${node.value});\n")
                is Node.SetMaybe -> out.append("m${node.maybe} = ${node.box?.let { "b$it" } ?: "null"};\n")
                is Node.ReadMaybe -> out.append("if (m${node.maybe} == null) { sum = sum + 1; } else { sum = sum + 2; }\n")
                is Node.Branch -> {
                    out.append("if (sum % 2 == 0) {\n"); render(node.thenBody, out)
                    out.append("} else {\n"); render(node.elseBody, out); out.append("}\n")
                }
                is Node.Loop -> {
                    out.append("var l${node.id}: int32 = 0;\nwhile (l${node.id} < ${node.count}) {\n")
                    render(node.body, out)
                    out.append("l${node.id} = l${node.id} + 1;\n}\n")
                }
                is Node.Finally -> {
                    out.append("try {\n"); render(node.body, out)
                    out.append("} finally {\n"); render(node.cleanup, out); out.append("}\n")
                }
            }
        }
    }

    /** 节点读取的槽位名（直接读取，含嵌套）。 */
    private fun readsOf(nodes: List<Node>): Set<String> = nodes.flatMap { node ->
        when (node) {
            is Node.ReadBox -> listOf("b${node.box}")
            is Node.SetMaybe -> listOfNotNull(node.box?.let { "b$it" })
            is Node.ReadMaybe -> listOf("m${node.maybe}")
            is Node.Branch -> (readsOf(node.thenBody) + readsOf(node.elseBody)).toList()
            is Node.Loop -> readsOf(node.body).toList()
            is Node.Finally -> (readsOf(node.body) + readsOf(node.cleanup)).toList()
            else -> emptyList()
        }
    }.toSet()

    /** 模型状态：与帧同步推进，每个 await 记下该位置按源码文本之后还会读取的名字。 */
    private inner class Model(val failAt: Int) {
        var sum = 0
        val boxValues = IntArray(boxes) { 10 + it }
        val maybeBoxes = arrayOfNulls<Int>(maybes)
        val suspensions = mutableListOf<Set<String>>()
        var boom = false
        /** 实际执行轨迹：'R' 读取、'W' 写入名字，null 名字表示第 n 个挂起点。 */
        val trace = mutableListOf<Pair<Char, String?>>()

        /** 第 [index] 个挂起点之后，下一次访问是读取的名字：这些槽位必须还有效。 */
        fun readNext(index: Int): Set<String> {
            val start = trace.indices.filter { trace[it].second == null }[index]
            val decided = linkedMapOf<String, Boolean>()
            trace.drop(start + 1).forEach { (kind, name) ->
                if (name != null && name !in decided) decided[name] = kind == 'R'
            }
            return decided.filterValues { it }.keys
        }

        /** [after] 是本块之外按文本仍可能读取的名字。 */
        fun run(nodes: List<Node>, after: Set<String>) {
            nodes.forEachIndexed { index, node ->
                if (boom) return
                val rest = readsOf(nodes.drop(index + 1)) + after
                when (node) {
                    is Node.Await -> {
                        trace += 'S' to null
                        suspensions += rest
                        if (suspensions.size - 1 == failAt) { boom = true; return }
                        sum += suspensions.size
                    }
                    is Node.ReadBox -> { trace += 'R' to "b${node.box}"; sum += boxValues[node.box] }
                    is Node.WriteBox -> { trace += 'W' to "b${node.box}"; boxValues[node.box] = node.value }
                    is Node.SetMaybe -> {
                        node.box?.let { trace += 'R' to "b$it" }
                        trace += 'W' to "m${node.maybe}"
                        maybeBoxes[node.maybe] = node.box
                    }
                    is Node.ReadMaybe -> { trace += 'R' to "m${node.maybe}"; sum += if (maybeBoxes[node.maybe] == null) 1 else 2 }
                    is Node.Branch -> run(if (sum % 2 == 0) node.thenBody else node.elseBody, rest)
                    is Node.Loop -> repeat(node.count) { if (!boom) run(node.body, rest + readsOf(node.body)) }
                    is Node.Finally -> {
                        run(node.body, rest + readsOf(node.cleanup))
                        val failing = boom
                        boom = false
                        run(node.cleanup, rest)
                        boom = failing
                    }
                }
            }
        }
    }

    @Test
    fun `random functions keep every later read valid and hold nothing the text never reads again`() {
        val random = Random(20261180L)
        val count = 200
        val generator = Generator(random)
        val programs = List(count) { generator.block(3, boxes, maybes) + Node.Await() + Node.ReadBox(0) }
        val source = StringBuilder("""
            package demo;
            import std.Future;
            public class Box {
                private var v: int32;
                public constructor(v: int32) { this.v = v; }
                public function get(): int32 { return this.v; }
            }
            public class Program {
                public static var latest: Future<int32>? = null;
                public static function next(): Future<int32> {
                    var made: Future<int32> = new Future<int32>();
                    Program.latest = made;
                    return made;
                }
        """.trimIndent())
        programs.forEachIndexed { index, nodes ->
            // 引用槽在前，编号即 b0.. m0..，sum 紧随其后。
            source.append("\npublic static function f$index(): int32 {\n")
            (0 until boxes).forEach { source.append("var b$it: Box = new Box(${10 + it});\n") }
            (0 until maybes).forEach { source.append("var m$it: Box? = null;\n") }
            source.append("var sum: int32 = 0;\n")
            render(nodes, source)
            source.append("return sum;\n}\n")
        }
        source.append("}\n")

        var checkedSuspensions = 0
        var deadSeen = 0
        var liveSeen = 0
        var failures = 0
        withRuntime("random", source.toString()) {
            val programClass = loader.loadClass("demo.Program")
            programs.forEachIndexed { index, nodes ->
                val probe = Model(-1).also { it.run(nodes, emptySet()) }
                val failAt = if (index % 3 == 0) random.nextInt(probe.suspensions.size) else -1
                val model = Model(failAt).also { it.run(nodes, emptySet()) }
                val output = newFuture()
                val frame = frame("f$index", output)
                start(frame)
                var step = 0
                while (true) {
                    drain()
                    if (future.getMethod("isDone").invoke(output) == true) break
                    assertTrue(step < model.suspensions.size, "f$index suspended more often than the model")
                    // 两个判据都不依赖活跃性分析：实际下一次访问是读取的必须有效；文本上之后再也不读的必须已清除。
                    // 其间的（文本上还读、实际先被覆写或走了别的路径）分析可以保守，不作断言。
                    val rest = model.suspensions[step]
                    val next = model.readNext(step)
                    (0 until boxes).forEach { box ->
                        val held = slot(frame, box) != null
                        if ("b$box" in next) { assertTrue(held, "f$index step $step: b$box is read next"); liveSeen++ }
                        if ("b$box" !in rest) { assertFalse(held, "f$index step $step: b$box is never read again"); deadSeen++ }
                    }
                    (0 until maybes).forEach { maybe ->
                        val slotIndex = boxes + maybe
                        if ("m$maybe" in next) assertTrue(valid(frame, slotIndex), "f$index step $step: m$maybe is read next")
                        if ("m$maybe" !in rest) {
                            assertFalse(valid(frame, slotIndex), "f$index step $step: m$maybe is dead but valid")
                            assertNull(slot(frame, slotIndex), "f$index step $step: m$maybe is dead but held")
                        }
                    }
                    checkedSuspensions++
                    val gate = assertNotNull(programClass.getField("latest").get(null))
                    if (step == failAt) { fail(gate, "boom"); failures++ } else complete(gate, step + 1)
                    step++
                }
                settled(output, "f$index")
                if (failAt >= 0) {
                    assertEquals("Failed", status(output), "f$index")
                    assertEquals("boom", failureMessage(output), "f$index: a cleanup read a cleared slot?")
                } else {
                    assertEquals(model.sum, value(output, "f$index"), "f$index")
                }
                assertAllCleared(frame, "f$index")
            }
        }
        assertTrue(checkedSuspensions >= 300, "suspensions checked: $checkedSuspensions")
        assertTrue(deadSeen >= 100, "dead slots seen: $deadSeen")
        assertTrue(liveSeen >= 100, "live slots seen: $liveSeen")
        assertTrue(failures >= 20, "failure runs: $failures")
    }
}

/** std.FrameSlot 的 Jazzer 目标：任意值 / 有效标记 / 名字组合下只有文档写明的两种结果。 */
class FrameSlotFuzzTest {
    private companion object {
        val slot: Class<*> by lazy {
            val output = Paths.get("build", "tmp", "frame-slot-fuzz")
            output.toFile().deleteRecursively()
            Files.createDirectories(output)
            val result = JasperCompiler().compileAllWithStdlib(mapOf("Empty.jas" to "package demo;\npublic class Empty { }\n"), output)
            assertTrue(result is JasperCompiler.CompileResult.Success, "stdlib must compile: $result")
            URLClassLoader(arrayOf(output.toUri().toURL())).loadClass("std.FrameSlot")
        }
        val written by lazy { slot.getMethod("written", Any::class.java, String::class.java) }
        val readNonNull by lazy { slot.getMethod("readNonNull", Any::class.java, String::class.java) }
        val readNullable by lazy {
            slot.getMethod("readNullable", Any::class.java, Boolean::class.javaPrimitiveType, String::class.java)
        }
    }

    /** 调用 [method]：[fails] 时必须抛 FrameSlotError 且消息含名字，否则原样返回 [value]。 */
    private fun check(method: java.lang.reflect.Method, fails: Boolean, value: Any?, name: String, vararg args: Any?) {
        val outcome = runCatching { method.invoke(null, *args) }
        if (fails) {
            val error = outcome.exceptionOrNull()?.cause
            assertEquals("std.FrameSlotError", error?.javaClass?.name)
            assertTrue(name in error!!.message!!)
        } else {
            assertSame(value, outcome.getOrThrow())
        }
    }

    @com.code_intelligence.jazzer.junit.FuzzTest(maxDuration = "60s")
    fun slotChecks(data: com.code_intelligence.jazzer.api.FuzzedDataProvider) {
        val value: Any? = when (data.consumeInt(0, 2)) {
            0 -> null
            1 -> data.consumeString(16)
            else -> Any()
        }
        val valid = data.consumeBoolean()
        val name = data.consumeRemainingAsString()
        check(written, value == null, value, name, value, name)
        check(readNonNull, value == null, value, name, value, name)
        check(readNullable, !valid, value, name, value, valid, name)
    }
}
