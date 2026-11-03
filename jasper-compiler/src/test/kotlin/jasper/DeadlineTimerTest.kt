// ============================================================================
// DeadlineTimerTest.kt —— S4 计时唤醒验证（LANG-S4-TIMER-01）
// ============================================================================
// 做什么：验证作用域计时器在没有任何其他检查点时也让截止点到期生效：
//   - 端到端契约（线程计时器、真实时钟）：作用域 / 子任务期限到期唤醒在 onCancel 上阻塞的任务体，
//     close 报 Deadline；取消先到时登记撤销、计时线程退出；空闲退出后再登记会重新启动线程；
//     shutdown 之后登记为空操作；
//   - 固定种子属性测试（宿主驱动计时器）：随机令牌树上登记 / 取消 / 触发 / 时钟前进 / shutdown，
//     每步按精确 oracle 检查各令牌原因、有效登记数、累计触发数与 fireDueAt 的返回值（时钟从
//     Long.MAX_VALUE 附近起步覆盖回绕）；
//   - Lincheck：登记、取消与触发并发交错，检查每项至多触发一次、不丢到期、终态无残留登记；
//   - Jazzer：与属性测试共用同一组判定。
//   计时线程「退出 / 新登记」的竞争由 verification/tla/DeadlineTimer.tla 覆盖（Lincheck 不管理
//   被测代码自行创建的线程）。
//
// 所属层次：compiler 模块测试层；Kotlin 只提供时钟数值、反射驱动与判定，计时器与取消状态机
//   全部在 Jasper 运行时中。
// 前置条件：产物只写模块 build/ 下。
// 失败模式：不变量被破坏时抛 AssertionError，消息含种子与轮次（属性测试）或最小化交错 / 输入。
// ============================================================================

package jasper

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import jasper.compiler.JasperCompiler
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 端到端契约：每个用例是一段完整 Jasper 程序，main 返回 0 表示全部断言通过。 */
class DeadlineTimerTest {

    private fun runMain(name: String, source: String): Any? {
        val output = Paths.get("build", "tmp", "deadline-timer", name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Main.jas" to source), output)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + ((result as? JasperCompiler.CompileResult.Error)?.message ?: result.toString())
        )
        // 限时：丢失唤醒的回归表现为 close 永远等待，要失败而不是挂住整个测试 JVM。
        return URLClassLoader(arrayOf(output.toUri().toURL())).use { loader ->
            val main = loader.loadClass("std.main").getMethod("main")
            java.util.concurrent.CompletableFuture.supplyAsync { main.invoke(null) }
                .get(30, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun assertOk(name: String, source: String) = assertEquals(0, runMain(name, source), "case $name")

    /** 共用的 Jasper 设施：在 onCancel 上阻塞的任务体、close 结果分类、限时等待计时线程退出。 */
    private val support = """
        package std;
        public class GateRelease implements Action<CancellationToken> {
            private var gate: java.util.concurrent.Semaphore;
            public constructor(gate: java.util.concurrent.Semaphore) { this.gate = gate; }
            public function accept(token: CancellationToken): void { this.gate.release(); }
        }
        /** 只有取消才能放行的任务体：令牌被取消前一直阻塞（不调用任何检查点）。 */
        public class GateBody implements TaskBody<int32> {
            private var gate: java.util.concurrent.Semaphore;
            private var started: java.util.concurrent.Semaphore;
            public constructor() {
                this.gate = new java.util.concurrent.Semaphore(0);
                this.started = new java.util.concurrent.Semaphore(0);
            }
            public function run(token: CancellationToken): int32 {
                token.onCancel(new GateRelease(this.gate));
                this.started.release();
                this.gate.acquireUninterruptibly();
                return 1;
            }
            public function awaitStarted(): void { this.started.acquireUninterruptibly(); }
            public function open(): void { this.gate.release(); }
        }
        public function closeReason(group: TaskGroup): int32 {
            try {
                group.close();
                return 0;
            } catch (error: CancellationException) {
                if (error.reason() == CancellationReason.Deadline) { return 3; }
                return 4;
            } catch (error: TaskGroupFailure) {
                return 5;
            }
        }
        /** 最多等 5 秒让计时线程退出。 */
        public function awaitIdle(timer: DeadlineTimer): bool {
            var spins: int32 = 0;
            while (timer.isRunning()) {
                if (spins >= 500) { return false; }
                java.lang.Thread.sleep(10L);
                spins = spins + 1;
            }
            return true;
        }
    """.trimIndent()

    private fun program(main: String) = support + "\n" + main.trimIndent()

    /** 作用域到期：没有任何检查点，阻塞的任务体被计时器唤醒，结果与 close 均为 Deadline，线程随后退出。 */
    @Test
    fun `scope expiry wakes blocked task bodies without checkpoints`() = assertOk(
        "scope",
        program(
            """
            public function main(): int32 {
                var pool: JvmExecutor = new JvmExecutor(2);
                var group: TaskGroup = new TaskGroup(false);
                var first: GateBody = new GateBody();
                var second: GateBody = new GateBody();
                var a: Future<int32> = group.spawn(pool, first);
                var b: Future<int32> = group.spawn(pool, second);
                first.awaitStarted();
                second.awaitStarted();
                group.restrictDeadline(Deadline.afterNanos(30000000L));
                if (closeReason(group) != 3) { return 1; }
                if (a.cancellationReason() != CancellationReason.Deadline) { return 2; }
                if (b.cancellationReason() != CancellationReason.Deadline) { return 3; }
                var timer: DeadlineTimer = group.deadlineTimer();
                // 累计触发数在检查点返回后才更新，close 可能先返回：等线程退出后再读。
                if (!awaitIdle(timer)) { return 5; }
                if (timer.firedCount() != 1) { return 4; }
                if (timer.threadsStartedCount() != 1) { return 6; }
                if (timer.callbackFailureCount() != 0) { return 7; }
                pool.shutdown();
                return 0;
            }
            """
        )
    )

    /** 子任务自身期限：只唤醒并取消该子任务，兄弟不受影响（不是业务失败，不级联）。 */
    @Test
    fun `child deadline wakes only that task`() = assertOk(
        "child",
        program(
            """
            public function main(): int32 {
                var pool: JvmExecutor = new JvmExecutor(2);
                var group: TaskGroup = new TaskGroup(false);
                var timed: GateBody = new GateBody();
                var sibling: GateBody = new GateBody();
                var own: Future<int32> = group.spawnWithDeadline(pool, timed, Deadline.afterNanos(20000000L));
                var other: Future<int32> = group.spawn(pool, sibling);
                timed.awaitStarted();
                sibling.awaitStarted();
                var spins: int32 = 0;
                while (!own.isDone()) {
                    if (spins >= 500) { return 1; }
                    java.lang.Thread.sleep(10L);
                    spins = spins + 1;
                }
                if (own.cancellationReason() != CancellationReason.Deadline) { return 2; }
                if (other.isDone()) { return 3; }
                sibling.open();
                if (closeReason(group) != 3) { return 4; }
                if (other.status() != FutureStatus.Completed) { return 5; }
                if (!awaitIdle(group.deadlineTimer())) { return 6; }
                pool.shutdown();
                return 0;
            }
            """
        )
    )

    /** 先到的取消撤销登记：一小时后的期限不再占线程，原因保持 Explicit，计时器从未触发。 */
    @Test
    fun `cancellation before expiry disarms and the thread exits`() = assertOk(
        "disarm",
        program(
            """
            public function main(): int32 {
                var group: TaskGroup = new TaskGroup(false);
                group.restrictDeadline(Deadline.afterNanos(3600000000000L));
                var timer: DeadlineTimer = group.deadlineTimer();
                if (timer.armedCount() != 1) { return 1; }
                if (!timer.isRunning()) { return 2; }
                group.cancel();
                if (timer.armedCount() != 0) { return 3; }
                if (!awaitIdle(timer)) { return 4; }
                if (timer.firedCount() != 0) { return 5; }
                if (closeReason(group) != 4) { return 6; }
                return 0;
            }
            """
        )
    )

    /** 线程生命周期：空闲退出后再登记会重新启动；shutdown 之后登记为空操作。 */
    @Test
    fun `idle thread restarts on demand and shutdown rejects arming`() = assertOk(
        "lifecycle",
        program(
            """
            public function main(): int32 {
                var timer: DeadlineTimer = new DeadlineTimer(true);
                var first: CancellationToken = new CancellationToken();
                var soon: Deadline = Deadline.afterNanos(1000000L);
                first.restrictDeadline(soon);
                timer.arm(first, soon);
                if (!awaitIdle(timer)) { return 1; }
                if (first.reason() != CancellationReason.Deadline) { return 2; }
                var second: CancellationToken = new CancellationToken();
                var again: Deadline = Deadline.afterNanos(1000000L);
                second.restrictDeadline(again);
                timer.arm(second, again);
                if (!awaitIdle(timer)) { return 3; }
                if (second.reason() != CancellationReason.Deadline) { return 4; }
                if (timer.threadsStartedCount() != 2) { return 5; }
                if (timer.firedCount() != 2) { return 6; }
                timer.shutdown();
                var third: CancellationToken = new CancellationToken();
                timer.arm(third, Deadline.afterNanos(0L));
                if (timer.armedCount() != 0) { return 7; }
                if (timer.isRunning()) { return 8; }
                if (timer.threadsStartedCount() != 2) { return 9; }
                if (third.isCancelled()) { return 10; }
                return 0;
            }
            """
        )
    )

    /** 不设期限的作用域不创建计时器（信号量、登记表、线程都不存在）；close 之后收紧期限也不再登记。 */
    @Test
    fun `scope without deadlines owns no timer`() {
        val loader = TimerRuntime.loader
        val groupType = loader.loadClass("std.TaskGroup")
        val field = groupType.getDeclaredField("timer").apply { isAccessible = true }
        val group = groupType.getConstructor(Boolean::class.javaPrimitiveType).newInstance(false)
        assertEquals(null, field.get(group))
        groupType.getMethod("close").invoke(group)
        assertEquals(null, field.get(group))
        groupType.getMethod("restrictDeadline", loader.loadClass("std.Deadline")).invoke(group, TimerRuntime.at(0))
        assertEquals(null, field.get(group))
    }

    /**
     * 登记失败（注入：唤醒信号量首次 release 抛平台异常，arm 在入表后失败，等价于计时线程启动失败）：子任务已进入
     * children，必须以 schedule 失败终结且 settled，close 不得永远等待。
     */
    @Test
    fun `arming failure settles the spawned child so close cannot hang`() {
        val output = Paths.get("build", "tmp", "deadline-timer", "arm-failure")
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val source = program(
            """
            public class FaultCase {
                public static function newGroup(timer: DeadlineTimer): TaskGroup { return TaskGroup.withDeadlineTimer(false, timer); }
                public static function spawn(group: TaskGroup, pool: Executor): Future<int32> {
                    return group.spawnWithDeadline(pool, new GateBody(), Deadline.afterNanos(3600000000000L));
                }
                public static function close(group: TaskGroup): int32 { return closeReason(group); }
            }
            """
        )
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Main.jas" to source), output)
        assertTrue(result is JasperCompiler.CompileResult.Success, result.toString())
        URLClassLoader(arrayOf(output.toUri().toURL())).use { loader ->
            val case = loader.loadClass("std.FaultCase")
            fun call(name: String, vararg args: Any?): Any? =
                case.methods.first { it.name == name }.invoke(null, *args)
            val timerType = loader.loadClass("std.DeadlineTimer")
            val timer = timerType.getConstructor(Boolean::class.javaPrimitiveType).newInstance(false)
            timerType.getDeclaredField("wakeup").apply { isAccessible = true }.set(timer, FirstReleaseFails())
            val pool = loader.loadClass("std.JvmExecutor").getConstructor(Int::class.javaPrimitiveType).newInstance(1)
            val group = call("newGroup", timer)
            val future = call("spawn", group, pool)
            assertEquals("Failed", future!!.javaClass.getMethod("status").invoke(future).toString())
            val closing = java.util.concurrent.CompletableFuture.supplyAsync { call("close", group) }
            assertEquals(5, closing.get(30, java.util.concurrent.TimeUnit.SECONDS), "close aggregates the failed child")
            pool.javaClass.getMethod("shutdown").invoke(pool)
        }
    }

    /**
     * 固定种子 20261129：300 轮，每轮至多 40 个事件（按 [opWeights] 加权），宿主驱动计时器。
     * 覆盖下限：触发以 Deadline 取消登记令牌、触发时由祖先到期取消或已被同批更早的项经祖先取消、取消先到撤销登记、
     * fireDueAt 返回正等待值、跨回绕触发，各超过 20 次。
     */
    private val opWeights = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 5, 6, 7, 7, 7, 7)

    @Test
    fun `seeded timer events agree with the oracle`() {
        val seed = 20261129
        val random = Random(seed)
        val coverage = IntArray(5)
        repeat(300) { round ->
            val harness = TimerHarness(Long.MAX_VALUE - random.nextLong(0, 17))
            try {
                repeat(random.nextInt(0, 41)) {
                    harness.step(opWeights[random.nextInt(opWeights.size)], random.nextInt(0, 6), random.nextInt(0, 24))
                    harness.checkInvariants()
                }
                if (harness.firedOwnExpiry) coverage[0]++
                if (harness.firedByAncestor) coverage[1]++
                if (harness.disarmedByCancel) coverage[2]++
                if (harness.positiveWait) coverage[3]++
                if (harness.firedAcrossWrap) coverage[4]++
            } catch (error: AssertionError) {
                throw AssertionError("seed=$seed round=$round: ${error.message}", error)
            }
        }
        assertTrue(coverage.all { it > 20 }, "coverage too thin: ${coverage.toList()}")
    }
}

/** 故障注入：首次 release 抛平台异常，之后正常（令牌撤销回调等后续路径不受影响）。 */
class FirstReleaseFails : java.util.concurrent.Semaphore(0) {
    private val armed = java.util.concurrent.atomic.AtomicBoolean(true)
    override fun release() {
        if (armed.getAndSet(false)) throw IllegalStateException("injected arming failure")
        super.release()
    }
}

/** 一次编译、全进程复用的计时器运行时句柄；只读，线程安全。 */
object TimerRuntime {
    val loader: ClassLoader by lazy {
        val probe = "package demo;\npublic function timerProbe(): int32 { return 0; }\n"
        CompiledRuntimeLoader.compile("deadline-timer/harness", mapOf("TimerProbe.jas" to probe))
    }
    private fun type(name: String): Class<*> = loader.loadClass(name)
    private val token by lazy { type("std.CancellationToken") }
    private val deadline by lazy { type("std.Deadline") }
    private val timer by lazy { type("std.DeadlineTimer") }
    private val reasonType by lazy { type("std.CancellationReason") }
    private val longType = Long::class.javaPrimitiveType!!
    val names = listOf("Explicit", "Parent", "Deadline", "ScopeExit", "SiblingFailed")

    private fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: java.lang.reflect.InvocationTargetException) {
        // 解包反射异常，让失败报告直接显示 Jasper 运行时抛出的异常。
        throw e.targetException
    }

    fun reason(name: String): Any = reasonType.enumConstants!!.first { it.toString() == name }
    fun newRoot(): Any = token.getConstructor().newInstance()
    fun at(nanos: Long): Any = deadline.getMethod("fromNanos", longType).invoke(null, nanos)
    fun createChild(parent: Any): Any = token.getMethod("createChild").invoke(parent)
    fun restrict(t: Any, nanos: Long) { token.getMethod("restrictDeadline", deadline).invoke(t, at(nanos)) }
    fun cancel(t: Any, name: String) { unwrap { token.getMethod("cancelWith", reasonType).invoke(t, reason(name)) } }
    fun reasonOf(t: Any): String? = token.getMethod("reason").invoke(t)?.toString()

    fun newTimer(): Any = timer.getConstructor(Boolean::class.javaPrimitiveType).newInstance(false)
    fun arm(tm: Any, t: Any, nanos: Long) { unwrap { timer.getMethod("arm", token, deadline).invoke(tm, t, at(nanos)) } }
    fun fire(tm: Any, now: Long): Long = unwrap { timer.getMethod("fireDueAt", longType).invoke(tm, now) as Long }
    fun shutdown(tm: Any) { timer.getMethod("shutdown").invoke(tm) }
    fun armedCount(tm: Any): Int = timer.getMethod("armedCount").invoke(tm) as Int
    fun firedCount(tm: Any): Int = timer.getMethod("firedCount").invoke(tm) as Int
    fun failures(tm: Any): Int = timer.getMethod("callbackFailureCount").invoke(tm) as Int
    fun isRunning(tm: Any): Boolean = timer.getMethod("isRunning").invoke(tm) as Boolean
    fun threadsStarted(tm: Any): Int = timer.getMethod("threadsStartedCount").invoke(tm) as Int

    /** 在当前线程走一遍登记 / 撤销 / 触发 / shutdown，避免把类加载纳入模型检查的交错。 */
    fun warmUp() {
        val tm = newTimer()
        val root = newRoot()
        restrict(root, 0)
        arm(tm, root, 0)
        val other = createChild(newRoot())
        arm(tm, other, 5)
        for (name in names) cancel(createChild(newRoot()), name)
        cancel(other, "Explicit")
        fire(tm, 1)
        shutdown(tm)
        armedCount(tm); firedCount(tm); reasonOf(root)
    }
}

/**
 * 宿主驱动计时器 + 一棵令牌树的事件驱动与判定；不变量与 DeadlineTimer.tla 同名。
 *
 * oracle：令牌节点记父、自身截止点与原因（取消向未取消后代传播同一原因）；登记项记令牌与截止点，
 * 「有效」⇔ 未触发 ∧ 未 shutdown ∧ 令牌未取消（TimerDisarm）。fireDueAt(now)：在锁内快照里把有效且
 * 到期的项按登记顺序取出（各触发一次），对每项令牌做检查点（自根向下，未取消且自身截止点已到的以
 * Deadline 取消）；返回 -1（快照时无任何有效项）、0（本次有触发）或最早有效截止点的剩余时长。
 */
class TimerHarness(private var now: Long) {
    private val rt = TimerRuntime
    private val timer = rt.newTimer()
    private val tokens = mutableListOf(rt.newRoot())
    private val parent = mutableListOf(-1)
    private val own = mutableListOf<Long?>(null)
    private val reasons = mutableListOf<String?>(null)
    private val entryToken = mutableListOf<Int>()
    private val entryAt = mutableListOf<Long>()
    private val entryFired = mutableListOf<Boolean>()
    private var stopped = false
    private var fired = 0
    private val start = now
    var firedOwnExpiry = false
    var firedByAncestor = false
    var disarmedByCancel = false
    var positiveWait = false
    var firedAcrossWrap = false

    private fun expired(d: Long, at: Long) = at - d >= 0
    private fun earlier(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        b - a < 0 -> b
        else -> a
    }
    private fun path(i: Int): List<Int> = generateSequence(i) { parent[it].takeIf { p -> p >= 0 } }.toList().reversed()
    private fun armed(e: Int) = !entryFired[e] && !stopped && reasons[entryToken[e]] == null
    private fun armedTotal() = entryToken.indices.count { armed(it) }

    private fun cancelModel(i: Int, reason: String) {
        if (reasons[i] != null) return
        reasons[i] = reason
        for (c in tokens.indices) if (parent[c] == i) cancelModel(c, reason)
    }

    private fun pollModel(i: Int) {
        for (n in path(i)) {
            val d = own[n]
            if (reasons[n] == null && d != null && expired(d, now)) cancelModel(n, "Deadline")
        }
    }

    /**
     * op：0 建子令牌、1 收紧并登记（与 TaskGroup 同序）、2 触发、3 时钟前进、4 以 Explicit 取消、
     * 5 以其他原因取消、6 shutdown（value 为 8 的倍数时）、7 只收紧不登记（期限只在检查点观察，
     * 用于构造「祖先期限未登记、由子令牌的登记项触发时帮助观察」）。node 取模到现有节点；value 为期限偏移（相对起点）或时钟步长。
     */
    fun step(op: Int, node: Int, value: Int) {
        val i = node % tokens.size
        when (op) {
            0 -> {
                if (tokens.size >= 6) return
                tokens += rt.createChild(tokens[i])
                parent += i
                own += null
                reasons += reasons[i]
            }
            1 -> {
                if (entryToken.size >= 12) return
                val d = start + value
                rt.restrict(tokens[i], d)
                own[i] = earlier(own[i], d)
                rt.arm(timer, tokens[i], d)
                if (!stopped) {
                    entryToken += i; entryAt += d; entryFired += false
                    if (reasons[i] != null) disarmedByCancel = true
                }
            }
            2 -> {
                val due = entryToken.indices.filter { armed(it) && expired(entryAt[it], now) }
                val rest = entryToken.indices.filter { armed(it) && !expired(entryAt[it], now) }
                val expected = when {
                    due.isEmpty() && rest.isEmpty() -> -1L
                    due.isNotEmpty() -> 0L
                    else -> rest.map { entryAt[it] }.reduce { a, b -> earlier(a, b)!! } - now
                }
                for (e in due) {
                    entryFired[e] = true
                    fired++
                    val t = entryToken[e]
                    // 不是因自身期限取消：触发时祖先期限已到（由检查点帮助观察），或同一批里更早的项已经
                    // 经祖先取消了它（快照取出后才被撤销的项照样触发一次，检查点只返回既有原因）。
                    val ancestorDue = reasons[t] != null ||
                        path(t).dropLast(1).any { n -> reasons[n] == null && own[n]?.let { expired(it, now) } == true }
                    pollModel(t)
                    if (ancestorDue) firedByAncestor = true else firedOwnExpiry = true
                    if ((entryAt[e] < 0) != (start < 0) || (now < 0) != (start < 0)) firedAcrossWrap = true
                }
                val actual = rt.fire(timer, now)
                assertState(actual == expected, "fireDueAt now=$now actual=$actual model=$expected")
                if (expected > 0) positiveWait = true
            }
            3 -> now += value % 12
            4, 5 -> {
                val name = if (op == 4) "Explicit" else rt.names[1 + value % 4]
                if (reasons[i] == null && entryToken.indices.any { armed(it) && path(entryToken[it]).contains(i) }) disarmedByCancel = true
                rt.cancel(tokens[i], name)
                cancelModel(i, name)
            }
            6 -> if (value % 8 == 0) {
                rt.shutdown(timer)
                stopped = true
            }
            7 -> {
                // 一半落在根上：根是所有令牌的祖先，最容易构造「祖先期限未登记」。
                val target = if (node % 2 == 0) 0 else i
                val d = start + value
                rt.restrict(tokens[target], d)
                own[target] = earlier(own[target], d)
            }
        }
    }

    private fun assertState(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    /** 精确原因 + FireOnce（累计触发数）+ DisarmOnCancel / NoArmAfterStop（有效登记数）+ 宿主模式从不起线程。 */
    fun checkInvariants() {
        for (i in tokens.indices) {
            val actual = rt.reasonOf(tokens[i])
            assertState(actual == reasons[i], "reason node=$i actual=$actual model=${reasons[i]}")
        }
        assertState(rt.firedCount(timer) == fired, "FireOnce actual=${rt.firedCount(timer)} model=$fired")
        assertState(rt.armedCount(timer) == armedTotal(), "armed actual=${rt.armedCount(timer)} model=${armedTotal()}")
        assertState(rt.failures(timer) == 0, "callback failures")
        assertState(!rt.isRunning(timer) && rt.threadsStarted(timer) == 0, "host-driven timer started a thread")
    }
}

/**
 * Lincheck 模型检查（宿主驱动计时器，时钟固定在 10，全部期限已到）：根 r（截止点 5）← 子 c
 * （截止点 3）。两线程交错登记 r / c、显式取消 r 与触发（触发只在一个线程上，同 fireDueAt 前置条件）；@Validate 在最后补一次触发后检查：
 * 每项至多触发一次（FireOnce）、凡登记过的令牌都已取消（NoLostExpiry）、无残留有效登记、
 * Deadline 原因只来自触发（任一项触发都会先检查祖先）、Explicit 只来自显式取消。
 */
class DeadlineTimerLincheckTest {
    private val rt = TimerRuntime
    private val timer = rt.newTimer()
    private val root = rt.newRoot().also { rt.restrict(it, 5) }
    private val child = rt.createChild(root).also { rt.restrict(it, 3) }
    private val arms = java.util.concurrent.atomic.AtomicInteger(0)
    private val armedRoot = java.util.concurrent.atomic.AtomicBoolean(false)
    private val armedChild = java.util.concurrent.atomic.AtomicBoolean(false)
    private val explicit = java.util.concurrent.atomic.AtomicBoolean(false)

    @Operation fun armRoot() { armedRoot.set(true); arms.incrementAndGet(); rt.arm(timer, root, 5) }
    @Operation fun armChild() { armedChild.set(true); arms.incrementAndGet(); rt.arm(timer, child, 3) }
    @Operation fun cancelRoot() { explicit.set(true); rt.cancel(root, "Explicit") }
    // fireDueAt 的前置条件是同一时刻只有一个调用者（计时线程或宿主的单个驱动者），触发不与自身并行。
    // 返回值（-1 / 0 / 等待时长）只是调度提示，不参与线性化判定：输掉竞争的 cancel 在赢家把取消传到
    // 后代之前就会返回（令牌层既有性质，检查点的帮助传播正是为此），其后一次触发仍可能把尚未收到传播
    // 的后代项当作到期项、返回 0；那次检查点只会帮助传播既有原因。触发的效果由 @Validate 判定。
    @Operation(nonParallelGroup = "firer") fun fire() { rt.fire(timer, 10) }

    @Validate fun validate() {
        rt.fire(timer, 10)
        if (rt.firedCount(timer) > arms.get()) throw AssertionError("FireOnce fired=${rt.firedCount(timer)} arms=${arms.get()}")
        if (armedRoot.get() && rt.reasonOf(root) == null) throw AssertionError("NoLostExpiry root")
        if (armedChild.get() && rt.reasonOf(child) == null) throw AssertionError("NoLostExpiry child")
        if (rt.armedCount(timer) != 0) throw AssertionError("residual armed entries ${rt.armedCount(timer)}")
        val r = rt.reasonOf(root)
        if (r == "Explicit" && !explicit.get()) throw AssertionError("ExplicitOrigin")
        // 任何一项触发都是检查点：子令牌的检查点先查父，根的期限（5）已过时由它以 Deadline 取消根。
        val anyArmed = armedRoot.get() || armedChild.get()
        if (r == "Deadline" && !anyArmed) throw AssertionError("DeadlineOrigin root")
        val c = rt.reasonOf(child)
        if (c == "Deadline" && !anyArmed) throw AssertionError("DeadlineOrigin child")
        if (r != null && c != r && c != "Deadline") throw AssertionError("ReasonAgreesWithParent root=$r child=$c")
    }

    @Test
    fun `model check concurrent arming cancellation and firing`() {
        TimerRuntime.warmUp() // 先在测试线程完成编译与类加载，避免把它们纳入交错。
        ModelCheckingOptions()
            .iterations(40)
            .invocationsPerIteration(400)
            .threads(2)
            .actorsPerThread(3)
            .check(this::class)
    }
}

/**
 * Jazzer 覆盖引导 fuzz：字节流选择时钟起点与至多 40 个事件，每步按单线程 oracle 检查。
 * 默认（回归模式）运行入库语料；设置 JAZZER_FUZZ=1 时进入覆盖引导 fuzz。
 */
class DeadlineTimerFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun timerEvents(data: FuzzedDataProvider) {
        val harness = TimerHarness(data.consumeLong())
        repeat(data.consumeInt(0, 40)) {
            harness.step(data.consumeInt(0, 7), data.consumeInt(0, 5), data.consumeInt(0, 23))
            harness.checkInvariants()
        }
    }
}
