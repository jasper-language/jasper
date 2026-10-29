// ============================================================================
// FrameDeadlineCoroutinePipelineTest.kt —— 生成帧截止检查点与作用域令牌接入验收（Q130）
// ============================================================================
// 做什么：真实编译标准库 + 用户源码得到协程帧，经 TaskGroup.launch / launchWithDeadline 运行，
//   验证 LANG-CONT-DEADLINE-01：
//   - 每趟分派开头与每个挂起点入口检查截止时间，到期后不再执行其后的业务段、先展开清理再回收；
//   - 作用域到期被任一检查点（帧自身、launch、子任务体开始前）观察时，经帧的作用域子令牌
//     唤醒并取消挂起中的全部帧（原因 Deadline）；
//   - launchWithDeadline 的帧自身期限只取消它自己，不触发普通组兄弟级联；
//   - 挂起中到期而无检查点时帧保持挂起（本片无计时器，边界如实锁定）；
//   - 固定种子 20261125 的 300 轮随机事件序列，与独立 oracle 逐步比对状态、原因、业务段与
//     清理次数、settled 与 close 聚合。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只提供确定性队列执行器（JDK 动态代理实现
//   std.Executor）、单调时钟上的过去 / 未来截止点与 oracle，截止检查与传播全部在生成帧与
//   Jasper 运行时中。
// 前置条件：产物只写模块 build/ 下。
// 失败模式：断言失败消息带种子、轮次与完整事件序列（属性测试）或场景名。
// 为什么用反射启动帧：源码层调用挂起函数的语法 / ABI 仍待裁决（同 LinearCoroutineScopeTest）。
// ============================================================================

package jasper

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 一次编译、全类复用的运行时；帧共享的静态计数在每个场景开始前清零。 */
private object FrameDeadlineRuntime {
    private val source = """
        package demo;
        import std.Future;
        import std.TaskGroup;
        import std.TaskBody;
        import std.CancellationToken;
        import std.Deadline;

        public class Program {
            private static var business: int32 = 0;
            private static var cleaned: int32 = 0;
            private static var armedAt: int32 = 0;
            private static var scope: TaskGroup = new TaskGroup(false);

            /** 业务段计数；第 armedAt 次进入时把作用域期限收紧到过去（模拟段内到期）。 */
            public static function step(value: int32): int32 {
                Program.business = Program.business + 1;
                if (Program.business == Program.armedAt) {
                    Program.scope.restrictDeadline(Deadline.fromNanos(java.lang.System.nanoTime() - 1L));
                }
                return value;
            }

            public static function clean(): int32 {
                Program.cleaned = Program.cleaned + 1;
                return 0;
            }

            public static function reset(): int32 {
                Program.business = 0;
                Program.cleaned = 0;
                Program.armedAt = 0;
                return 0;
            }

            public static function arm(group: TaskGroup, at: int32): int32 {
                Program.scope = group;
                Program.armedAt = at;
                return 0;
            }

            public static function business(): int32 { return Program.business; }
            public static function cleaned(): int32 { return Program.cleaned; }

            public static function calculate(first: Future<int32>, second: Future<int32>, kept: int32): int32 {
                var total: int32 = Program.step(kept);
                try {
                    var a: int32 = await first;
                    total = Program.step(total + a);
                    var b: int32 = await second;
                    total = Program.step(total + b);
                } finally {
                    Program.clean();
                }
                return total;
            }
        }

        public class Probe implements TaskBody<int32> {
            public constructor() { }
            public function run(token: CancellationToken): int32 { return 0; }
        }
    """.trimIndent()

    val loader: ClassLoader by lazy {
        CompiledRuntimeLoader.compile("frame-deadline", mapOf("Program.jas" to source))
    }
    private fun type(name: String): Class<*> = loader.loadClass(name)
    val future by lazy { type("std.Future") }
    private val failure by lazy { type("std.Failure") }
    private val group by lazy { type("std.TaskGroup") }
    private val executorType by lazy { type("std.Executor") }
    private val runnable by lazy { type("std.Runnable") }
    private val task by lazy { type("std.CoroutineTask") }
    private val deadline by lazy { type("std.Deadline") }
    private val frame by lazy { type("demo.Program_calculate_Coroutine") }
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

        /** 执行到队列为空；段内异常已由调度门发布到结果。 */
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
    fun arm(target: Any, at: Int) { program.getMethod("arm", group, intType).invoke(null, target, at) }
    fun business(): Int = program.getMethod("business").invoke(null) as Int
    fun cleaned(): Int = program.getMethod("cleaned").invoke(null) as Int

    fun newFuture(): Any = future.getConstructor().newInstance()
    fun newGroup(): Any = group.getConstructor(Boolean::class.javaPrimitiveType).newInstance(false)
    fun newFrame(executor: QueueExecutor, result: Any, first: Any, second: Any, kept: Int): Any =
        frame.getConstructor(executorType, future, future, future, intType)
            .newInstance(executor.proxy, result, first, second, kept)
    /** 单调时钟上已过去 / 一小时后的截止点。 */
    fun past(): Any = deadline.getMethod("fromNanos", Long::class.javaPrimitiveType).invoke(null, System.nanoTime() - 1)
    fun later(): Any = deadline.getMethod("fromNanos", Long::class.javaPrimitiveType)
        .invoke(null, System.nanoTime() + 3_600_000_000_000L)

    fun launch(target: Any, frame: Any) { group.getMethod("launch", task).invoke(target, frame) }
    fun launchWithDeadline(target: Any, frame: Any, at: Any) {
        group.getMethod("launchWithDeadline", task, deadline).invoke(target, frame, at)
    }
    fun restrict(target: Any, at: Any) { group.getMethod("restrictDeadline", deadline).invoke(target, at) }
    fun cancelGroup(target: Any) { group.getMethod("cancel").invoke(target) }
    fun spawnProbe(target: Any, executor: QueueExecutor): Any =
        group.getMethod("spawn", executorType, type("std.TaskBody"))
            .invoke(target, executor.proxy, type("demo.Probe").getConstructor().newInstance())
    /** close：正常返回 "ok"，否则返回异常类名，CancellationException 附原因。 */
    fun close(target: Any): String = try {
        group.getMethod("close").invoke(target); "ok"
    } catch (error: InvocationTargetException) {
        val thrown = error.targetException
        if (thrown.javaClass.name == "std.CancellationException") {
            "cancel:" + thrown.javaClass.getMethod("reason").invoke(thrown)
        } else thrown.javaClass.name
    }

    fun complete(target: Any, value: Int) { future.getMethod("complete", Any::class.java).invoke(target, value) }
    fun fail(target: Any) {
        val snapshot = failure.getConstructor(String::class.java, String::class.java).newInstance("boom", "error")
        future.getMethod("fail", failure).invoke(target, snapshot)
    }
    fun cancel(target: Any) { future.getMethod("cancel").invoke(target) }
    fun status(target: Any): String = future.getMethod("status").invoke(target).toString()
    fun reason(target: Any): String? = future.getMethod("cancellationReason").invoke(target)?.toString()
    fun value(target: Any): Any? = future.getMethod("get").invoke(target)
    fun isDone(target: Any): Boolean = future.getMethod("isDone").invoke(target) as Boolean
    fun isSettled(target: Any): Boolean = settledField.get(target) as Boolean
}

/** 端到端场景 + 固定种子属性测试。 */
class FrameDeadlineCoroutinePipelineTest {
    private val rt = FrameDeadlineRuntime

    /** 一个作用域内若干帧的句柄；kept 取帧序号。 */
    private inner class Scope(frames: Int) {
        val executor = FrameDeadlineRuntime.QueueExecutor()
        val group: Any = rt.newGroup()
        val first = List(frames) { rt.newFuture() }
        val second = List(frames) { rt.newFuture() }
        val results = List(frames) { rt.newFuture() }
        val frames = List(frames) { rt.newFrame(executor, results[it], first[it], second[it], it) }

        fun launch(i: Int) { rt.launch(group, frames[i]); executor.drain() }
        fun outcome(i: Int): String = rt.status(results[i]) + (rt.reason(results[i])?.let { "($it)" } ?: "")
    }

    @Test
    fun `scope expiry before the first pass runs no business segment`() {
        rt.reset()
        val scope = Scope(2)
        rt.restrict(scope.group, rt.later())
        rt.launch(scope.group, scope.frames[0])
        rt.launch(scope.group, scope.frames[1])
        rt.restrict(scope.group, rt.past())
        scope.executor.drain()
        assertEquals("Cancelled(Deadline)", scope.outcome(0))
        assertEquals("Cancelled(Deadline)", scope.outcome(1))
        assertEquals(0, rt.business(), "no business segment after expiry")
        assertEquals(0, rt.cleaned(), "try region was never entered")
        assertTrue(rt.isSettled(scope.results[0]) && rt.isSettled(scope.results[1]))
        assertEquals("cancel:Deadline", rt.close(scope.group))
    }

    @Test
    fun `expiry while suspended is observed when the frame resumes and cleanup runs`() {
        rt.reset()
        val scope = Scope(1)
        scope.launch(0)
        assertEquals(1, rt.business())
        rt.restrict(scope.group, rt.past())
        scope.executor.drain()
        // 本片无计时器：挂起中到期、没有检查点时帧保持挂起。
        assertEquals("Pending", scope.outcome(0))
        rt.complete(scope.first[0], 5)
        scope.executor.drain()
        assertEquals("Cancelled(Deadline)", scope.outcome(0))
        assertEquals(1, rt.business(), "the resumed segment must not run after expiry")
        assertEquals(1, rt.cleaned())
        assertTrue(rt.isSettled(scope.results[0]))
        assertEquals("cancel:Deadline", rt.close(scope.group))
    }

    @Test
    fun `expiry observed by another checkpoint wakes suspended frames`() {
        for (observer in listOf("launch", "spawn")) {
            rt.reset()
            val scope = Scope(3)
            scope.launch(0)
            scope.launch(1)
            rt.restrict(scope.group, rt.past())
            if (observer == "launch") scope.launch(2) else rt.spawnProbe(scope.group, scope.executor)
            scope.executor.drain()
            // 输入永不完成：只有经作用域子令牌的取消传播才能唤醒并回收挂起帧。
            assertEquals("Cancelled(Deadline)", scope.outcome(0), observer)
            assertEquals("Cancelled(Deadline)", scope.outcome(1), observer)
            assertEquals(2, rt.business(), observer)
            assertEquals(2, rt.cleaned(), observer)
            assertTrue(rt.isSettled(scope.results[0]) && rt.isSettled(scope.results[1]), observer)
            assertEquals("cancel:Deadline", rt.close(scope.group), observer)
        }
    }

    @Test
    fun `own deadline cancels only that frame without sibling cascade`() {
        rt.reset()
        val scope = Scope(2)
        rt.launchWithDeadline(scope.group, scope.frames[0], rt.past())
        rt.launchWithDeadline(scope.group, scope.frames[1], rt.later())
        scope.executor.drain()
        assertEquals("Cancelled(Deadline)", scope.outcome(0))
        assertEquals("Pending", scope.outcome(1))
        rt.complete(scope.first[1], 10)
        rt.complete(scope.second[1], 20)
        scope.executor.drain()
        assertEquals("Completed", scope.outcome(1))
        assertEquals(31, rt.value(scope.results[1]))
        assertEquals(3, rt.business())
        assertEquals(1, rt.cleaned())
        assertEquals("cancel:Deadline", rt.close(scope.group))
    }

    /** 段内到期：第二个业务段收紧作用域期限，紧接着的挂起点入口必须观察到并不再挂起。 */
    @Test
    fun `suspension point entry observes expiry within the same pass`() {
        rt.reset()
        val scope = Scope(1)
        rt.arm(scope.group, 2)
        rt.complete(scope.first[0], 4)
        scope.launch(0)
        assertEquals("Cancelled(Deadline)", scope.outcome(0))
        assertEquals(2, rt.business())
        assertEquals(1, rt.cleaned())
        assertTrue(rt.isSettled(scope.results[0]))
        assertEquals("cancel:Deadline", rt.close(scope.group))
    }

    @Test
    fun `seeded event sequences agree with the frame deadline oracle`() {
        val seed = 20261125L
        val random = Random(seed)
        val coverage = IntArray(4)
        repeat(300) { round ->
            rt.reset()
            val size = 2 + random.nextInt(3)
            val scope = Scope(size)
            val model = Oracle(size)
            val events = mutableListOf<String>()
            var launched = 0
            fun check() {
                val where = "seed $seed round $round events $events"
                for (i in 0 until launched) {
                    assertEquals(model.outcome(i), scope.outcome(i), "frame $i outcome; $where")
                    if (model.status[i] == "Completed") assertEquals(model.total[i], rt.value(scope.results[i]), "value $i; $where")
                    if (model.phase[i] == Oracle.DONE) assertTrue(rt.isSettled(scope.results[i]), "frame $i settled; $where")
                }
                assertEquals(model.business, rt.business(), "business segments; $where")
                assertEquals(model.cleaned, rt.cleaned(), "cleanups; $where")
            }
            repeat(4 + random.nextInt(20)) {
                val i = random.nextInt(size)
                when (random.nextInt(9)) {
                    0, 1 -> if (launched < size) {
                        val own = random.nextInt(4) == 0
                        events += "launch$launched${if (own) "!" else ""}"
                        if (own) rt.launchWithDeadline(scope.group, scope.frames[launched], rt.past())
                        else rt.launch(scope.group, scope.frames[launched])
                        model.launch(launched, own)
                        launched++
                    }
                    2 -> if (random.nextInt(3) == 0) {
                        events += "expire"; rt.restrict(scope.group, rt.past()); model.expired = true
                    }
                    3, 4, 5, 6 -> {
                        val input = random.nextInt(2)
                        val target = if (input == 0) scope.first[i] else scope.second[i]
                        if (!rt.isDone(target)) {
                            when (random.nextInt(6)) {
                                0 -> { events += "fail$i.$input"; rt.fail(target); model.input(i, input, Oracle.Input.FAIL, 0) }
                                1 -> { events += "cancel$i.$input"; rt.cancel(target); model.input(i, input, Oracle.Input.CANCEL, 0) }
                                else -> {
                                    val v = random.nextInt(50)
                                    events += "ok$i.$input=$v"; rt.complete(target, v); model.input(i, input, Oracle.Input.OK, v)
                                }
                            }
                        }
                    }
                    7 -> if (random.nextInt(4) == 0) { events += "groupCancel"; rt.cancelGroup(scope.group); model.groupCancel() }
                    else -> Unit
                }
                scope.executor.drain()
                check()
            }
            // 收尾：补齐全部输入，所有帧必终结，再按 oracle 判定 close 聚合。
            for (i in 0 until launched) for (input in 0..1) {
                val target = if (input == 0) scope.first[i] else scope.second[i]
                if (!rt.isDone(target)) { rt.complete(target, 1); model.input(i, input, Oracle.Input.OK, 1) }
                scope.executor.drain()
            }
            check()
            assertEquals(model.closeOutcome(launched), rt.close(scope.group), "close; seed $seed round $round events $events")
            if (model.rootReason == "Deadline") coverage[0]++
            if (model.ownExpiredCount > 0) coverage[1]++
            if (model.resumedIntoExpiry) coverage[2]++
            if (model.wokenByRelay) coverage[3]++
        }
        // 覆盖下限：作用域到期、帧自身到期、挂起中到期于恢复时观察、到期由别处观察并唤醒挂起帧。
        coverage.forEachIndexed { index, count -> assertTrue(count >= 20, "coverage[$index] = $count below 20") }
    }
}

/**
 * 单线程精确 oracle：按规范语义模拟作用域与帧（不经生成帧与 Jasper 运行时）。
 *
 * 帧源码：business(kept) → try { await first; business; await second; business } finally { clean }。
 * 每次帧运行（启动、被等待输入完成、结果被作用域取消唤醒）先过检查点：作用域到期且未取消则以
 * Deadline 取消作用域（全部未终态帧随之取消并被唤醒），自身期限到期则只取消自己；结果已终态则
 * 退出、在 try 内时清理一次。队列执行到空才比对，因此唤醒顺序不影响可观察结果。
 */
private class Oracle(size: Int) {
    enum class Input { OK, FAIL, CANCEL }
    companion object { const val NEW = 0; const val WAIT_A = 1; const val WAIT_B = 2; const val DONE = 3 }

    val phase = IntArray(size) { NEW }
    val status = Array(size) { "Pending" }
    val reason = arrayOfNulls<String>(size)
    val total = IntArray(size)
    private val own = BooleanArray(size)
    private val launched = BooleanArray(size)
    private val order = mutableListOf<Int>()
    private val inputs = Array(size) { arrayOfNulls<Pair<Input, Int>>(2) }
    var expired = false
    var rootReason: String? = null
    var business = 0
    var cleaned = 0
    var ownExpiredCount = 0
    var resumedIntoExpiry = false
    var wokenByRelay = false
    private val woken = ArrayDeque<Int>()

    fun outcome(i: Int): String = status[i] + (reason[i]?.let { "($it)" } ?: "")

    private fun pendingCancel(i: Int, why: String) {
        if (status[i] == "Pending") { status[i] = "Cancelled"; reason[i] = why }
    }

    fun cancelRoot(why: String) {
        if (rootReason != null) return
        rootReason = why
        for (i in order) if (status[i] == "Pending") {
            pendingCancel(i, why)
            if (phase[i] == WAIT_A || phase[i] == WAIT_B) {
                woken += i
                if (why == "Deadline") wokenByRelay = true
            }
        }
    }

    /** TaskGroup.cancel：不是检查点，只以 Explicit 取消作用域并唤醒挂起帧。 */
    fun groupCancel() {
        cancelRoot("Explicit")
        settle()
    }

    fun launch(i: Int, ownExpired: Boolean) {
        // launch 本身是检查点：作用域已到期先取消作用域，新帧随之以根原因取消。
        if (expired) cancelRoot("Deadline")
        launched[i] = true; own[i] = ownExpired; order += i
        rootReason?.let { pendingCancel(i, it) }
        run(i)
        settle()
    }

    fun input(i: Int, which: Int, kind: Input, value: Int) {
        inputs[i][which] = kind to value
        if (launched[i] && ((phase[i] == WAIT_A && which == 0) || (phase[i] == WAIT_B && which == 1))) {
            if (expired && rootReason == null) resumedIntoExpiry = true
            run(i)
        }
        settle()
    }

    private fun settle() { while (woken.isNotEmpty()) run(woken.removeFirst()) }

    private fun run(i: Int) {
        if (phase[i] == DONE) return
        if (!checkpoint(i)) return
        if (phase[i] == NEW) {
            business++; total[i] = i
            if (!suspendOn(i, 0)) return
        }
        if (phase[i] == WAIT_A) {
            val (kind, v) = inputs[i][0] ?: return
            if (!consume(i, kind)) return
            business++; total[i] += v
            phase[i] = WAIT_B
            if (!checkpoint(i)) return
        }
        val (kind, v) = inputs[i][1] ?: return
        if (!consume(i, kind)) return
        business++; total[i] += v
        cleaned++
        status[i] = "Completed"; phase[i] = DONE
    }

    /** 进入挂起点：挂起点检查；输入已完成则立即继续（实际为一次新的分派，检查结果相同）。 */
    private fun suspendOn(i: Int, which: Int): Boolean {
        phase[i] = if (which == 0) WAIT_A else WAIT_B
        return checkpoint(i) && inputs[i][which] != null
    }

    /** 检查点；返回 false 表示帧已退出（结果已终态，在 try 内时已清理）。 */
    private fun checkpoint(i: Int): Boolean {
        if (expired) cancelRoot("Deadline")
        if (own[i] && status[i] == "Pending") { pendingCancel(i, "Deadline"); ownExpiredCount++ }
        if (status[i] == "Pending") return true
        if (phase[i] == WAIT_A || phase[i] == WAIT_B) cleaned++
        phase[i] = DONE
        return false
    }

    /** 消费输入终态；失败 / 取消先清理再发布，普通组失败以 SiblingFailed 级联。 */
    private fun consume(i: Int, kind: Input): Boolean {
        if (kind == Input.OK) return true
        cleaned++; phase[i] = DONE
        if (kind == Input.CANCEL) { status[i] = "Cancelled"; reason[i] = "Explicit"; return false }
        status[i] = "Failed"
        cancelRoot("SiblingFailed")
        return false
    }

    /** close 聚合：有失败 → TaskGroupFailure；否则根已取消报根原因；否则创建序最早的已取消子任务原因。 */
    fun closeOutcome(count: Int): String {
        if ((0 until count).any { status[it] == "Failed" }) return "std.TaskGroupFailure"
        rootReason?.let { return "cancel:$it" }
        order.firstOrNull { status[it] == "Cancelled" }?.let { return "cancel:${reason[it]}" }
        return "ok"
    }
}
