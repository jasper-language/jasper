// ============================================================================
// AwaitCancelReasonCoroutinePipelineTest.kt —— 帧因被等待 Future 取消时的原因（Q134）
// ============================================================================
// 做什么：真实编译标准库 + 用户源码得到协程帧，验证 LANG-S4-AWAIT-REASON-01：
//   - 帧因被等待 Future 取消而发布 Cancelled 时沿用该 Future 的首个原因（不再固定 Explicit），
//     没有 catch 区域、有 catch 但不匹配、处理块内再次等待三条发射路径一致；
//   - 原因沿帧链传递，close 报告同一原因；作用域取消先于帧消费快照时根原因优先（首因稳定）；
//   - 被 catch 接住的取消不影响其后结果；同步 Future.get 的 FutureCancelledError 带 reason()；
//   - 固定种子 20261133 的 600 轮随机序列与 Jazzer 覆盖引导共用一个 oracle，比对状态、原因、清理次数与 settled。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只提供确定性队列执行器与 oracle，原因传递全部在生成帧
//   与 Jasper 运行时中。
// 前置条件：产物只写模块 build/ 下。
// 失败模式：断言失败消息带种子、轮次与事件序列（属性测试）或场景名。
// 为什么用反射启动帧：源码层调用挂起函数的语法 / ABI 仍待裁决（同 LinearCoroutineScopeTest）。
// ============================================================================

package jasper

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 一次编译、全类复用的运行时；清理与处理块计数在每轮开始前清零。 */
internal object AwaitReasonRuntime {
    private val source = """
        package demo;
        import std.CancellationReason;
        import std.Future;
        import std.FutureCancelledError;
        import std.FutureFailureError;

        public class Program {
            private static var cleaned: int32 = 0;
            private static var handled: int32 = 0;

            public static function reset(): int32 {
                Program.cleaned = 0;
                Program.handled = 0;
                return 0;
            }
            public static function clean(): int32 {
                Program.cleaned = Program.cleaned + 1;
                return 0;
            }
            public static function noteHandled(): int32 {
                Program.handled = Program.handled + 1;
                return 0;
            }
            public static function cleaned(): int32 { return Program.cleaned; }
            public static function handled(): int32 { return Program.handled; }

            /** 没有 catch 区域：取消直接跳出分派循环。 */
            public static function plain(input: Future<int32>, gate: Future<int32>, kept: int32): int32 {
                var total: int32 = kept;
                try {
                    var a: int32 = await input;
                    total = total + a;
                } finally {
                    Program.clean();
                }
                return total;
            }

            /** 有 catch 区域但类型不匹配：取消经抛出路由，未被接住时发布的仍是取消。 */
            public static function unmatched(input: Future<int32>, gate: Future<int32>, kept: int32): int32 {
                var total: int32 = kept;
                try {
                    try {
                        var a: int32 = await input;
                        total = total + a;
                    } catch (e: FutureFailureError) {
                        total = total + Program.noteHandled();
                    }
                } finally {
                    Program.clean();
                }
                return total;
            }

            /** 处理块接住 input 的取消后再等待 gate：结果只取决于 gate。 */
            public static function caught(input: Future<int32>, gate: Future<int32>, kept: int32): int32 {
                var total: int32 = kept;
                try {
                    var a: int32 = await input;
                    total = total + a;
                } catch (e: FutureCancelledError) {
                    total = total + Program.noteHandled();
                    var b: int32 = await gate;
                    total = total + b + 100;
                } finally {
                    Program.clean();
                }
                return total;
            }

            /** 同步路径：get 抛出的错误带原因，1 = Deadline，2 = 其他取消原因，0 = 未抛出。 */
            public static function syncReason(input: Future<int32>): int32 {
                try {
                    input.get();
                    return 0;
                } catch (e: FutureCancelledError) {
                    if (e.reason() == CancellationReason.Deadline) {
                        return 1;
                    }
                    return 2;
                }
            }
        }
    """.trimIndent()

    val loader: ClassLoader by lazy {
        CompiledRuntimeLoader.compile("await-cancel-reason", mapOf("Program.jas" to source))
    }
    private fun type(name: String): Class<*> = loader.loadClass(name)
    val future by lazy { type("std.Future") }
    val group by lazy { type("std.TaskGroup") }
    private val executorType by lazy { type("std.Executor") }
    private val runnable by lazy { type("std.Runnable") }
    private val task by lazy { type("std.CoroutineTask") }
    private val reasonType by lazy { type("std.CancellationReason") }
    private val program by lazy { type("demo.Program") }
    private val settledField by lazy { future.getDeclaredField("settled").apply { isAccessible = true } }
    private val intType = Int::class.javaPrimitiveType!!

    /** 确定性队列执行器：schedule 只入队，由测试显式执行。 */
    class QueueExecutor {
        val queue = ConcurrentLinkedQueue<Any>()
        val proxy: Any = Proxy.newProxyInstance(loader, arrayOf(executorType)) { self, method, args ->
            when (method.name) {
                "schedule" -> { queue.add(args[0]); null }
                "shutdown" -> null
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args[0]
                else -> "QueueExecutor"
            }
        }

        fun drain() {
            while (true) {
                val next = queue.poll() ?: return
                try {
                    runnable.getMethod("run").invoke(next)
                } catch (_: InvocationTargetException) {
                }
            }
        }
    }

    fun reset() { program.getMethod("reset").invoke(null) }
    fun cleaned(): Int = program.getMethod("cleaned").invoke(null) as Int
    fun handled(): Int = program.getMethod("handled").invoke(null) as Int
    fun syncReason(input: Any): Int = program.getMethod("syncReason", future).invoke(null, input) as Int

    fun newFuture(): Any = future.getConstructor().newInstance()
    fun newGroup(): Any = group.getConstructor(Boolean::class.javaPrimitiveType).newInstance(false)
    fun newFrame(kind: String, executor: QueueExecutor, result: Any, input: Any, gate: Any, kept: Int): Any =
        type("demo.Program_${kind}_Coroutine").getConstructor(executorType, future, future, future, intType)
            .newInstance(executor.proxy, result, input, gate, kept)
    fun launch(target: Any, frame: Any) { group.getMethod("launch", task).invoke(target, frame) }
    fun cancelGroup(target: Any) { group.getMethod("cancel").invoke(target) }
    /** close：正常返回 "ok"，CancellationException 附原因，其他返回异常类名。 */
    fun close(target: Any): String = try {
        group.getMethod("close").invoke(target); "ok"
    } catch (error: InvocationTargetException) {
        val thrown = error.targetException
        if (thrown.javaClass.name == "std.CancellationException") {
            "cancel:" + thrown.javaClass.getMethod("reason").invoke(thrown)
        } else thrown.javaClass.name
    }

    private fun reasonValue(name: String): Any = reasonType.enumConstants.first { it.toString() == name }
    fun cancelWith(target: Any, reason: String) {
        future.getMethod("cancelWith", reasonType).invoke(target, reasonValue(reason))
    }
    fun complete(target: Any, value: Int) { future.getMethod("complete", Any::class.java).invoke(target, value) }
    fun status(target: Any): String = future.getMethod("status").invoke(target).toString()
    fun reason(target: Any): String? = future.getMethod("cancellationReason").invoke(target)?.toString()
    fun value(target: Any): Any? = future.getMethod("get").invoke(target)
    fun isSettled(target: Any): Boolean = settledField.get(target) as Boolean
}

/** 端到端场景 + 固定种子属性测试。 */
class AwaitCancelReasonCoroutinePipelineTest {
    private val rt = AwaitReasonRuntime
    private val reasons = AWAIT_REASONS

    /** 一个作用域内一个帧的句柄。 */
    private inner class Scope(kind: String, val executor: AwaitReasonRuntime.QueueExecutor = AwaitReasonRuntime.QueueExecutor()) {
        val group: Any = rt.newGroup()
        val input: Any = rt.newFuture()
        val gate: Any = rt.newFuture()
        val result: Any = rt.newFuture()
        val frame: Any = rt.newFrame(kind, executor, result, input, gate, 7)
        fun launch() { rt.launch(group, frame); executor.drain() }
        fun outcome(): String = rt.status(result) + (rt.reason(result)?.let { "($it)" } ?: "")
    }

    @Test
    fun `every emission path publishes the first reason of the awaited future`() {
        for (kind in listOf("plain", "unmatched")) {
            for (reason in reasons) {
                rt.reset()
                val scope = Scope(kind)
                scope.launch()
                rt.cancelWith(scope.input, reason)
                scope.executor.drain()
                assertEquals("Cancelled($reason)", scope.outcome(), kind)
                assertEquals(1, rt.cleaned(), "$kind cleanup runs exactly once")
                assertEquals(0, rt.handled(), "$kind cancellation is not a FutureFailureError")
                assertTrue(rt.isSettled(scope.result), kind)
                assertEquals("cancel:$reason", rt.close(scope.group), kind)
            }
        }
    }

    @Test
    fun `reason of an already cancelled input survives the frame`() {
        rt.reset()
        val scope = Scope("plain")
        rt.cancelWith(scope.input, "Deadline")
        scope.launch()
        scope.executor.drain()
        assertEquals("Cancelled(Deadline)", scope.outcome())
    }

    @Test
    fun `handler consumes the input reason and the gate decides the result`() {
        rt.reset()
        val completed = Scope("caught")
        completed.launch()
        rt.cancelWith(completed.input, "Deadline")
        completed.executor.drain()
        assertEquals(1, rt.handled())
        assertEquals("Pending", rt.status(completed.result), "handler suspends on the gate")
        rt.complete(completed.gate, 1)
        completed.executor.drain()
        assertEquals("Completed", rt.status(completed.result))
        assertEquals(null, rt.reason(completed.result))
        assertEquals(108, rt.value(completed.result))

        rt.reset()
        val cancelled = Scope("caught")
        cancelled.launch()
        rt.cancelWith(cancelled.input, "Deadline")
        cancelled.executor.drain()
        rt.cancelWith(cancelled.gate, "SiblingFailed")
        cancelled.executor.drain()
        assertEquals("Cancelled(SiblingFailed)", cancelled.outcome(), "gate reason replaces the caught one")
        assertEquals(1, rt.cleaned())
        assertTrue(rt.isSettled(cancelled.result))
    }

    @Test
    fun `reason travels along a chain of frames and to close`() {
        rt.reset()
        val inner = Scope("plain")
        val outerResult = rt.newFuture()
        val outerGroup = rt.newGroup()
        val outer = rt.newFrame("unmatched", inner.executor, outerResult, inner.result, rt.newFuture(), 1)
        inner.launch()
        rt.launch(outerGroup, outer)
        inner.executor.drain()
        rt.cancelWith(inner.input, "Deadline")
        inner.executor.drain()
        assertEquals("Cancelled", rt.status(outerResult))
        assertEquals("Deadline", rt.reason(outerResult))
        assertEquals(2, rt.cleaned())
        assertEquals("cancel:Deadline", rt.close(outerGroup))
    }

    @Test
    fun `scope cancellation before the frame consumes the snapshot keeps the root reason`() {
        rt.reset()
        val scope = Scope("plain")
        scope.launch()
        rt.cancelWith(scope.input, "Deadline")
        rt.cancelGroup(scope.group)
        scope.executor.drain()
        assertEquals("Cancelled(Explicit)", scope.outcome())
        assertEquals(1, rt.cleaned())
        assertTrue(rt.isSettled(scope.result))
    }

    @Test
    fun `synchronous get reports the reason on the error`() {
        val deadline = rt.newFuture()
        rt.cancelWith(deadline, "Deadline")
        assertEquals(1, rt.syncReason(deadline))
        val explicit = rt.newFuture()
        rt.cancelWith(explicit, "Explicit")
        assertEquals(2, rt.syncReason(explicit))
        val done = rt.newFuture()
        rt.complete(done, 1)
        assertEquals(0, rt.syncReason(done))
    }

    /**
     * 做什么：随机帧形态、随机时机（登记前 / 挂起后取消输入、消费前 / 后取消作用域、处理块内门的终态），
     * 与不依赖实现的 oracle 比较结果状态、原因、处理块与清理次数、settled。
     */
    @Test
    fun `seeded sequences agree with the await reason oracle`() {
        val seed = 20261133
        val random = Random(seed)
        val covered = mutableMapOf<String, Int>()
        repeat(600) { round ->
            val case = AwaitReasonCase(
                listOf("plain", "unmatched", "caught").random(random),
                AWAIT_REASONS.random(random),
                random.nextBoolean(),
                listOf("never", "beforeConsume", "afterConsume").random(random),
                random.nextBoolean(),
                AWAIT_REASONS.random(random)
            )
            val expected = checkAwaitReasonCase(case, "seed=$seed round=$round")
            covered.merge(expected.substringBefore('('), 1, Int::plus)
            covered.merge(case.scopeCancel, 1, Int::plus)
            covered.merge(case.kind, 1, Int::plus)
        }
        for (key in listOf("Cancelled", "Completed", "never", "beforeConsume", "afterConsume", "plain", "unmatched", "caught")) {
            assertTrue((covered[key] ?: 0) >= 20, "coverage floor missed for $key: $covered")
        }
    }
}

/** Jazzer 覆盖引导：与属性测试共用同一 oracle；回归语料在普通测试中回放。 */
class AwaitCancelReasonFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun awaitReasonOracle(data: FuzzedDataProvider) {
        val case = AwaitReasonCase(
            data.pickValue(arrayOf("plain", "unmatched", "caught")),
            data.pickValue(AWAIT_REASONS.toTypedArray()),
            data.consumeBoolean(),
            data.pickValue(arrayOf("never", "beforeConsume", "afterConsume")),
            data.consumeBoolean(),
            data.pickValue(AWAIT_REASONS.toTypedArray())
        )
        checkAwaitReasonCase(case, "fuzz")
    }
}

private val AWAIT_REASONS = listOf("Explicit", "Parent", "Deadline", "ScopeExit", "SiblingFailed")

/** 一轮事件：帧形态、输入原因、是否登记前取消、作用域取消时机、门是否成功、门的取消原因。 */
private data class AwaitReasonCase(
    val kind: String,
    val inputReason: String,
    val cancelBeforeLaunch: Boolean,
    val scopeCancel: String,
    val gateCompletes: Boolean,
    val gateReason: String
)

/** 执行一轮并按 oracle 判定，返回预期结果；断言消息带 [label] 与完整事件。 */
private fun checkAwaitReasonCase(case: AwaitReasonCase, label: String): String {
    val rt = AwaitReasonRuntime
    val trace = "$label $case"
    rt.reset()
    val executor = AwaitReasonRuntime.QueueExecutor()
    val group = rt.newGroup()
    val input = rt.newFuture()
    val gate = rt.newFuture()
    val result = rt.newFuture()
    val frame = rt.newFrame(case.kind, executor, result, input, gate, 7)
    // 输入已取消时帧尚未运行：此时取消作用域，帧连首段都不会执行。
    if (case.cancelBeforeLaunch) {
        rt.cancelWith(input, case.inputReason)
        rt.launch(group, frame)
    } else {
        rt.launch(group, frame)
        executor.drain()
        rt.cancelWith(input, case.inputReason)
    }
    if (case.scopeCancel == "beforeConsume") rt.cancelGroup(group)
    executor.drain()
    if (case.scopeCancel == "afterConsume") {
        rt.cancelGroup(group)
        executor.drain()
    }
    // 走到这里 plain / unmatched 已经发布；caught 若未被作用域取消则停在门上。
    if (case.kind == "caught") {
        if (case.gateCompletes) rt.complete(gate, 1) else rt.cancelWith(gate, case.gateReason)
        executor.drain()
    }

    val expected = when {
        // 消费之前已取消作用域：根原因先写入结果。
        case.scopeCancel == "beforeConsume" -> "Cancelled(Explicit)"
        case.kind != "caught" -> "Cancelled(${case.inputReason})"
        // 消费之后取消作用域：帧挂在门上，根原因先写入结果。
        case.scopeCancel == "afterConsume" -> "Cancelled(Explicit)"
        case.gateCompletes -> "Completed"
        else -> "Cancelled(${case.gateReason})"
    }
    val expectedHandled = if (case.kind == "caught" && case.scopeCancel != "beforeConsume") 1 else 0
    // 只有首段都没执行的帧才不必清理：try 尚未进入。
    val expectedCleaned = if (case.cancelBeforeLaunch && case.scopeCancel == "beforeConsume") 0 else 1
    assertEquals(expected, rt.status(result) + (rt.reason(result)?.let { "($it)" } ?: ""), trace)
    assertEquals(expectedHandled, rt.handled(), trace)
    assertEquals(expectedCleaned, rt.cleaned(), trace)
    assertTrue(rt.isSettled(result), trace)
    return expected
}
