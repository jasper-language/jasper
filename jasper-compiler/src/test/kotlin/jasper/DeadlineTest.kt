// ============================================================================
// DeadlineTest.kt —— S4 Deadline 运行时子片验证
// ============================================================================
// 做什么：验证单调时钟截止点、令牌按父子取更严格、检查点到期以 Deadline 原因取消并沿作用域
//   传播、TaskGroup 作用域与子任务截止点（LANG-S4-DEADLINE-01）。分四部分：
//   - 端到端契约：编译标准库 + 纯 Jasper main，返回码 0 表示全部断言通过；
//   - 固定种子属性测试：反射驱动随机令牌树与事件，时钟从 Long.MAX_VALUE 附近起步以覆盖
//     nanoTime 回绕，每步按精确 oracle 检查；
//   - Lincheck 模型检查：并发检查点与对根的显式取消交错（帮助传播）；
//   - Jazzer 覆盖引导 fuzz：与属性测试共用同一组判定（与 DeadlinePropagation.tla 同名）。
//
// 所属层次：compiler 模块测试层；Kotlin 只提供时钟数值、反射驱动与判定，截止点与取消状态机
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 端到端契约：每个用例是一段完整 Jasper 程序，main 返回 0 表示全部断言成立。 */
class DeadlineTest {

    /** 编译 [source]（含标准库），调用 `std.main`；编译失败带诊断断言失败。 */
    private fun runMain(name: String, source: String): Any? {
        val output = Paths.get("build", "tmp", "deadline", name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Main.jas" to source), output)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + ((result as? JasperCompiler.CompileResult.Error)?.message ?: result.toString())
        )
        return URLClassLoader(arrayOf(output.toUri().toURL())).use { loader ->
            loader.loadClass("std.main").getMethod("main").invoke(null)
        }
    }

    private fun assertOk(name: String, source: String) = assertEquals(0, runMain(name, source), "case $name")

    /** 截止点值：负时长拒绝、0 立即到期、超大时长夹取不溢出、取更早者、回绕处差值比较、剩余时长夹 0。 */
    @Test
    fun `deadline values compare by monotonic difference`() = assertOk(
        "values",
        """
        package std;
        public function main(): int32 {
            try {
                Deadline.afterNanos(-1L);
                return 1;
            } catch (error: DeadlineError) {
            }
            if (!Deadline.afterNanos(0L).isExpired()) { return 2; }
            var far: Deadline = Deadline.afterNanos(9223372036854775807L);
            if (far.isExpired()) { return 3; }
            if (java.lang.Long.compare(far.remainingNanos(), 0L) <= 0) { return 4; }
            var early: Deadline = Deadline.fromNanos(100L);
            var late: Deadline = Deadline.fromNanos(200L);
            if (Deadline.earliest(late, early) != early) { return 5; }
            if (Deadline.earliest(early, late) != early) { return 6; }
            if (Deadline.earliest(null, late) != late) { return 7; }
            if (Deadline.earliest(late, null) != late) { return 8; }
            if (Deadline.earliest(null, null) != null) { return 9; }
            // 回绕：MAX 之后的下一个刻度是 MIN，差值比较仍判 MAX 更早。
            var beforeWrap: Deadline = Deadline.fromNanos(9223372036854775807L);
            var maxNanos: int64 = 9223372036854775807L;
            var minNanos: int64 = maxNanos + 1L;
            var afterWrap: Deadline = Deadline.fromNanos(minNanos);
            if (!beforeWrap.isBefore(afterWrap)) { return 10; }
            if (afterWrap.isBefore(beforeWrap)) { return 11; }
            if (!beforeWrap.isExpiredAt(minNanos)) { return 12; }
            if (afterWrap.isExpiredAt(maxNanos)) { return 13; }
            if (early.remainingNanosAt(40L) != 60L) { return 14; }
            if (early.remainingNanosAt(400L) != 0L) { return 15; }
            if (!early.isExpiredAt(100L)) { return 16; }
            if (early.isExpiredAt(99L)) { return 17; }
            return 0;
        }
        """.trimIndent()
    )

    /**
     * 令牌：只收紧、子不晚于父且实时看到父的后续收紧；到期前检查不取消；父到期取消父并传给全部后代；
     * 子自身到期只取消子；先到的显式取消不被到期覆盖；throwIfCancelled 先检查截止时间；父只能绑定一次。
     */
    @Test
    fun `tokens merge deadlines and cancel the expired owner`() = assertOk(
        "token",
        """
        package std;
        public function at(token: CancellationToken): int64 {
            var d: Deadline? = token.deadline();
            if (d == null) { return -1L; }
            return d.atNanos();
        }
        public function main(): int32 {
            var root: CancellationToken = new CancellationToken();
            var child: CancellationToken = root.createChild();
            var tight: CancellationToken = root.createChildWithDeadline(Deadline.fromNanos(50L));
            var grand: CancellationToken = child.createChild();
            if (root.deadline() != null) { return 1; }
            root.restrictDeadline(Deadline.fromNanos(100L));
            root.restrictDeadline(Deadline.fromNanos(300L));
            if (at(root) != 100L) { return 2; }
            if (at(grand) != 100L) { return 3; }
            if (at(tight) != 50L) { return 4; }
            if (grand.pollDeadlineAt(49L)) { return 5; }
            if (!tight.pollDeadlineAt(50L)) { return 6; }
            if (tight.reason() != CancellationReason.Deadline) { return 7; }
            if (root.isCancelled() || child.isCancelled()) { return 8; }
            if (grand.pollDeadlineAt(99L)) { return 9; }
            if (!grand.pollDeadlineAt(100L)) { return 10; }
            if (root.reason() != CancellationReason.Deadline) { return 11; }
            if (child.reason() != CancellationReason.Deadline) { return 12; }
            var late: CancellationToken = root.createChild();
            if (late.reason() != CancellationReason.Deadline) { return 13; }

            var explicit: CancellationToken = new CancellationToken();
            explicit.restrictDeadline(Deadline.fromNanos(10L));
            explicit.cancel();
            explicit.pollDeadlineAt(20L);
            if (explicit.reason() != CancellationReason.Explicit) { return 14; }

            var checkpoint: CancellationToken = new CancellationToken();
            checkpoint.restrictDeadline(Deadline.afterNanos(0L));
            try {
                checkpoint.throwIfCancelled();
                return 15;
            } catch (error: CancellationException) {
                if (error.reason() != CancellationReason.Deadline) { return 16; }
            }
            try {
                grand.bindParent(root);
                return 17;
            } catch (error: DeadlineError) {
            }
            return 0;
        }
        """.trimIndent()
    )

    /**
     * 帮助传播写的是父的首个原因：根已置位、根到子的传播回调尚未运行时，在根上更早注册的回调里
     * 对孙令牌做检查点。孙令牌返回已取消，孙与子都以根的原因（SiblingFailed）取消，而不是 Parent；
     * 随后到达的传播回调不改原因。单线程 oracle 里回调总是同步先到，这个窗口只有这样才能确定地构造。
     */
    @Test
    fun `checkpoint help propagation copies the ancestor first reason`() = assertOk(
        "help",
        """
        package std;
        public class EarlyProbe implements Action<CancellationToken> {
            private var target: CancellationToken?;
            private var returned: bool;
            private var seen: CancellationReason?;
            private var childBefore: bool;
            public constructor() { this.target = null; this.returned = false; this.seen = null; this.childBefore = true; }
            public function watch(target: CancellationToken): void { this.target = target; }
            public function accept(token: CancellationToken): void {
                var t: CancellationToken = cast this.target as CancellationToken;
                this.childBefore = t.isCancelled();
                this.returned = t.pollDeadlineAt(0L);
                this.seen = t.reason();
            }
            public function returned(): bool { return this.returned; }
            public function seen(): CancellationReason? { return this.seen; }
            public function childBefore(): bool { return this.childBefore; }
        }
        public function main(): int32 {
            var root: CancellationToken = new CancellationToken();
            var probe: EarlyProbe = new EarlyProbe();
            root.onCancel(probe);
            var child: CancellationToken = root.createChild();
            var grand: CancellationToken = child.createChild();
            probe.watch(grand);
            if (!root.cancelWith(CancellationReason.SiblingFailed)) { return 1; }
            if (probe.childBefore()) { return 2; }
            if (!probe.returned()) { return 3; }
            if (probe.seen() != CancellationReason.SiblingFailed) { return 4; }
            if (child.reason() != CancellationReason.SiblingFailed) { return 5; }
            if (grand.reason() != CancellationReason.SiblingFailed) { return 6; }
            return 0;
        }
        """.trimIndent()
    )

    /**
     * TaskGroup：作用域到期 → 任务体开始前检查点取消、结果 Cancelled(Deadline)、close 报 Deadline；
     * 子任务自身期限到期只取消该子任务、不触发普通组兄弟级联；显式期限不能放宽作用域期限；
     * launch 进已到期作用域的帧以 Deadline 取消、不执行业务段。
     */
    @Test
    fun `task groups apply scope and child deadlines at checkpoints`() = assertOk(
        "group",
        """
        package std;
        public class InlineExecutor implements Executor {
            public function schedule(task: Runnable): void { task.run(); }
            public function shutdown(): void { }
        }
        public class SeenBody implements TaskBody<int32> {
            private var cancelledAtStart: bool;
            public constructor() { this.cancelledAtStart = false; }
            public function run(token: CancellationToken): int32 {
                this.cancelledAtStart = token.isCancelled();
                return 7;
            }
            public function sawCancel(): bool { return this.cancelledAtStart; }
        }
        public class HandFrame implements CoroutineTask<int32> {
            private var result: Future<int32>;
            private var ran: bool;
            public constructor() { this.result = new Future<int32>(); this.ran = false; }
            public function resultFuture(): Future<int32> { return this.result; }
            public function bindToken(token: CancellationToken): void { }
            public function start(): void {
                if (this.result.status() == FutureStatus.Pending) {
                    this.ran = true;
                    this.result.complete(1);
                }
                this.result.markTaskSettled();
            }
            public function didRun(): bool { return this.ran; }
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
        public function main(): int32 {
            var direct: InlineExecutor = new InlineExecutor();
            var expired: TaskGroup = new TaskGroup(false);
            expired.restrictDeadline(Deadline.afterNanos(0L));
            var body: SeenBody = new SeenBody();
            var timed: Future<int32> = expired.spawn(direct, body);
            if (!body.sawCancel()) { return 1; }
            if (timed.cancellationReason() != CancellationReason.Deadline) { return 2; }
            var frame: HandFrame = new HandFrame();
            var launched: Future<int32> = expired.launch(frame);
            if (frame.didRun()) { return 3; }
            if (launched.cancellationReason() != CancellationReason.Deadline) { return 4; }
            if (closeReason(expired) != 3) { return 5; }

            var normal: TaskGroup = new TaskGroup(false);
            var future: Deadline = Deadline.afterNanos(3600000000000L);
            normal.restrictDeadline(future);
            var own: Future<int32> = normal.spawnWithDeadline(direct, new SeenBody(), Deadline.afterNanos(0L));
            var sibling: Future<int32> = normal.spawn(direct, new SeenBody());
            if (own.cancellationReason() != CancellationReason.Deadline) { return 6; }
            if (sibling.status() != FutureStatus.Completed) { return 7; }
            if (normal.deadline() != future) { return 8; }
            if (closeReason(normal) != 3) { return 9; }

            var bounded: TaskGroup = new TaskGroup(true);
            bounded.restrictDeadline(Deadline.afterNanos(0L));
            var loose: Future<int32> = bounded.spawnWithDeadline(direct, new SeenBody(), Deadline.afterNanos(3600000000000L));
            if (loose.cancellationReason() != CancellationReason.Deadline) { return 10; }
            if (closeReason(bounded) != 3) { return 11; }

            var open: TaskGroup = new TaskGroup(false);
            var fine: Future<int32> = open.spawn(direct, new SeenBody());
            if (fine.status() != FutureStatus.Completed) { return 12; }
            if (open.deadline() != null) { return 13; }
            if (closeReason(open) != 0) { return 14; }
            return 0;
        }
        """.trimIndent()
    )

    /**
     * 固定种子 20261123：300 轮，每轮至多 30 个事件（建子令牌 / 带期限子令牌 / 收紧 / 检查点 /
     * 时钟前进 / 显式或其他原因取消，按 [opWeights] 加权：检查点与带期限子令牌较多、取消较少，
     * 否则早到的取消会挡住大部分到期路径），时钟起点在 Long.MAX_VALUE 之前 0..16 个刻度，
     * 期限与时钟推进常跨过回绕。
     * 覆盖下限：根因到期被取消、仅子令牌因自身期限被取消、到期与先到的其他原因竞争、跨回绕到期，各超过 20 次。
     */
    private val opWeights = intArrayOf(0, 1, 1, 1, 2, 3, 3, 3, 3, 4, 4, 4, 5, 6)

    @Test
    fun `seeded token trees agree with the deadline oracle`() {
        val seed = 20261123
        val random = Random(seed)
        val coverage = IntArray(4)
        repeat(300) { round ->
            val harness = DeadlineHarness(Long.MAX_VALUE - random.nextLong(0, 17))
            try {
                repeat(random.nextInt(0, 31)) {
                    harness.step(opWeights[random.nextInt(opWeights.size)], random.nextInt(0, 6), random.nextInt(0, 24))
                    harness.checkInvariants()
                }
                if (harness.reasonOf(0) == "Deadline") coverage[0]++
                if (harness.ownExpiryOnly) coverage[1]++
                if (harness.expiryLostRace) coverage[2]++
                if (harness.expiredAcrossWrap) coverage[3]++
            } catch (error: AssertionError) {
                throw AssertionError("seed=$seed round=$round: ${error.message}", error)
            }
        }
        assertTrue(coverage.all { it > 20 }, "coverage too thin: ${coverage.toList()}")
    }
}

/** 一次编译、全进程复用的运行时句柄；只读，线程安全。 */
object DeadlineRuntime {
    val loader: ClassLoader by lazy {
        val probe = "package demo;\npublic function deadlineProbe(): int32 { return 0; }\n"
        CompiledRuntimeLoader.compile("deadline/harness", mapOf("DeadlineProbe.jas" to probe))
    }
    private fun type(name: String): Class<*> = loader.loadClass(name)
    private val token by lazy { type("std.CancellationToken") }
    private val deadline by lazy { type("std.Deadline") }
    private val reasonType by lazy { type("std.CancellationReason") }
    private val longType = Long::class.javaPrimitiveType!!
    val names = listOf("Explicit", "Parent", "Deadline", "ScopeExit", "SiblingFailed")

    fun reason(name: String): Any =
        reasonType.enumConstants?.firstOrNull { it.toString() == name } ?: reasonType.getField(name).get(null)

    fun newRoot(): Any = token.getConstructor().newInstance()
    fun at(nanos: Long): Any = deadline.getMethod("fromNanos", longType).invoke(null, nanos)
    fun createChild(parent: Any): Any = token.getMethod("createChild").invoke(parent)
    fun createChildWithDeadline(parent: Any, nanos: Long): Any =
        token.getMethod("createChildWithDeadline", deadline).invoke(parent, at(nanos))
    fun restrict(t: Any, nanos: Long) { token.getMethod("restrictDeadline", deadline).invoke(t, at(nanos)) }
    fun poll(t: Any, now: Long): Boolean = try {
        token.getMethod("pollDeadlineAt", longType).invoke(t, now) as Boolean
    } catch (e: java.lang.reflect.InvocationTargetException) {
        // 解包反射异常，让失败报告直接显示 Jasper 运行时抛出的异常。
        throw e.targetException
    }
    fun cancel(t: Any, name: String): Boolean = token.getMethod("cancelWith", reasonType).invoke(t, reason(name)) as Boolean
    /**
     * 在当前线程把检查点、帮助传播与各取消原因的路径各走一遍，加载并初始化全部相关类，
     * 避免把类加载与静态初始化纳入模型检查的交错。
     */
    fun warmUp() {
        val root = newRoot()
        restrict(root, 0)
        val child = createChildWithDeadline(createChild(root), 0)
        poll(child, 1)
        for (name in names) cancel(createChild(newRoot()), name)
        reasonOf(child); deadlineOf(child)
    }

    fun reasonOf(t: Any): String? = token.getMethod("reason").invoke(t)?.toString()
    fun deadlineOf(t: Any): Long? = token.getMethod("deadline").invoke(t)?.let { deadline.getMethod("atNanos").invoke(it) as Long }
}

/**
 * 一棵令牌树的事件驱动与判定；不变量与 DeadlinePropagation.tla 同名。
 *
 * oracle：每个节点记父、自身截止点与原因；取消向全部未取消后代传播同一原因（已取消的后代保留
 * 自己的原因，其后代早已随它取消）；检查点自根向下检查路径上的令牌，未取消且自身截止点已到
 * （差值符号判定，覆盖回绕）的以 Deadline 取消。
 */
class DeadlineHarness(private var now: Long) {
    private val rt = DeadlineRuntime
    private val tokens = mutableListOf(rt.newRoot())
    private val parent = mutableListOf(-1)
    private val own = mutableListOf<Long?>(null)
    private val reasons = mutableListOf<String?>(null)
    private val start = now
    var ownExpiryOnly = false
    var expiryLostRace = false
    var expiredAcrossWrap = false

    fun reasonOf(i: Int): String? = reasons[i]
    private fun expired(d: Long, at: Long) = at - d >= 0
    private fun earlier(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        b - a < 0 -> b
        else -> a
    }
    private fun path(i: Int): List<Int> = generateSequence(i) { parent[it].takeIf { p -> p >= 0 } }.toList().reversed()
    private fun effective(i: Int): Long? = path(i).fold(null as Long?) { acc, n -> earlier(acc, own[n]) }

    private fun cancelModel(i: Int, reason: String) {
        if (reasons[i] != null) return
        reasons[i] = reason
        for (c in tokens.indices) if (parent[c] == i) cancelModel(c, reason)
    }

    /**
     * op：0 建子令牌、1 建带期限子令牌、2 收紧、3 检查点、4 时钟前进、5 以 Explicit 取消、6 以其他原因取消。
     * node 取模到现有节点；value 为期限偏移（相对检查开始时刻）或时钟步长。
     */
    fun step(op: Int, node: Int, value: Int) {
        val i = node % tokens.size
        when (op) {
            0, 1 -> {
                if (tokens.size >= 6) return
                val d = start + value
                tokens += if (op == 0) rt.createChild(tokens[i]) else rt.createChildWithDeadline(tokens[i], d)
                parent += i
                own += if (op == 0) null else d
                reasons += reasons[i]
                // createChildWithDeadline 先建子再收紧：父已取消时子沿用父原因，期限不改变它。
            }
            2 -> {
                val d = start + value
                rt.restrict(tokens[i], d)
                own[i] = earlier(own[i], d)
            }
            3 -> {
                val before = reasons.toList()
                for (n in path(i)) {
                    val d = own[n]
                    if (reasons[n] == null && d != null && expired(d, now)) {
                        cancelModel(n, "Deadline")
                        if (n != 0) ownExpiryOnly = ownExpiryOnly || reasons[0] == null
                        if ((d < 0) != (start < 0) || (now < 0) != (start < 0)) expiredAcrossWrap = true
                    }
                }
                val actual = rt.poll(tokens[i], now)
                assertState(actual == (reasons[i] != null), "PollSound node=$i actual=$actual model=${reasons[i]}")
                val eff = effective(i)
                if (eff != null && expired(eff, now) && before[i] != null && before[i] != "Deadline") expiryLostRace = true
            }
            4 -> now += value % 12
            5 -> cancelModel(i, "Explicit").also { rt.cancel(tokens[i], "Explicit") }
            6 -> {
                val name = rt.names[1 + value % 4]
                rt.cancel(tokens[i], name)
                cancelModel(i, name)
            }
        }
    }

    private fun assertState(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    /** 单线程判定：精确原因 + 有效截止点 + ChildNoLaterThanParent / ReasonAgreesWithParent / FirstReasonStable。 */
    fun checkInvariants() {
        for (i in tokens.indices) {
            val actual = rt.reasonOf(tokens[i])
            assertState(actual == reasons[i], "reason node=$i actual=$actual model=${reasons[i]}")
            assertState(rt.deadlineOf(tokens[i]) == effective(i), "deadline node=$i actual=${rt.deadlineOf(tokens[i])} model=${effective(i)}")
            val p = parent[i]
            if (p >= 0) {
                val mine = effective(i)
                val theirs = effective(p)
                if (theirs != null) assertState(mine != null && mine - theirs <= 0, "ChildNoLaterThanParent node=$i")
                if (reasons[p] != null) assertState(reasons[i] != null, "CancelReachesDescendants node=$i")
            }
        }
    }
}

/**
 * Lincheck 模型检查：根（截止点 5）← 子 c1 ← 孙 g，根 ← c2（自身截止点 3），时钟固定在 10（全部到期）。
 * 两线程交错三个检查点与对根的显式取消；@Validate 检查 PollSound（检查点都返回 true）、
 * 原因来源与父子一致性。被检查的是死锁、意外异常与判定（帮助传播缺失时 PollSound 被破坏）。
 */
class DeadlineLincheckTest {
    private val rt = DeadlineRuntime
    private val root = rt.newRoot().also { rt.restrict(it, 5) }
    private val c1 = rt.createChild(root)
    private val g = rt.createChild(c1)
    private val c2 = rt.createChildWithDeadline(root, 3)
    private val polled = AtomicBoolean(false)
    private val explicit = AtomicBoolean(false)
    private val pollFailures = java.util.concurrent.atomic.AtomicInteger(0)

    private fun pollAt(t: Any) {
        polled.set(true)
        if (!rt.poll(t, 10)) pollFailures.incrementAndGet()
    }

    @Operation fun pollGrand() = pollAt(g)
    @Operation fun pollChild() = pollAt(c1)
    @Operation fun pollOwn() = pollAt(c2)
    @Operation fun cancelRoot() { explicit.set(true); rt.cancel(root, "Explicit") }

    @Validate fun validate() {
        if (pollFailures.get() != 0) throw AssertionError("PollSound: a checkpoint past the deadline returned false")
        val r = rt.reasonOf(root)
        if (r == "Explicit" && !explicit.get()) throw AssertionError("ExplicitOrigin")
        if (r == "Deadline" && !polled.get()) throw AssertionError("DeadlineOrigin")
        if (r != null) {
            if (rt.reasonOf(c1) != r || rt.reasonOf(g) != r) throw AssertionError("ReasonAgreesWithParent root=$r c1=${rt.reasonOf(c1)} g=${rt.reasonOf(g)}")
            val own = rt.reasonOf(c2)
            if (own != r && own != "Deadline") throw AssertionError("ReasonAgreesWithParent c2=$own root=$r")
        }
    }

    @Test
    fun `model check concurrent checkpoints and explicit cancel`() {
        DeadlineRuntime.warmUp() // 先在测试线程完成编译与类加载，避免把它们纳入交错。
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
class DeadlineFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun deadlineEvents(data: FuzzedDataProvider) {
        val harness = DeadlineHarness(data.consumeLong())
        repeat(data.consumeInt(0, 40)) {
            harness.step(data.consumeInt(0, 6), data.consumeInt(0, 5), data.consumeInt(0, 23))
            harness.checkInvariants()
        }
    }
}
