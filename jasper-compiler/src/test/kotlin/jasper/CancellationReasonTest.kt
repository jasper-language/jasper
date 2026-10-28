// ============================================================================
// CancellationReasonTest.kt —— S4 取消原因（CancellationReason）运行时子片验证
// ============================================================================
// 做什么：验证令牌 / Future / TaskGroup 记录首个取消原因、沿作用域传播、close 与
//   CancellationException 携带原因（LANG-S4-REASON-01）。分四部分：
//   - 端到端契约：编译标准库 + 纯 Jasper main，返回码 0 表示全部断言通过；
//   - 固定种子属性测试：反射驱动随机事件序列，单线程下按精确 oracle 逐步检查；
//   - Lincheck 模型检查：并发显式取消、兄弟失败级联、单独取消与执行段的交错；
//   - Jazzer 覆盖引导 fuzz：与属性测试共用同一组判定（与 CancellationReasonPropagation.tla 同名）。
//
// 所属层次：compiler 模块测试层；Kotlin 只提供确定性队列执行器、任务体（JDK 动态代理实现
//   std.Executor / std.TaskBody）、反射驱动与判定，取消状态机全部在 Jasper 运行时中。
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 端到端契约：每个用例是一段完整 Jasper 程序，main 返回 0 表示全部断言成立。 */
class CancellationReasonTest {

    /** 编译 [source]（含标准库），调用 `std.main`；编译失败带诊断断言失败。 */
    private fun runMain(name: String, source: String): Any? {
        val output = Paths.get("build", "tmp", "cancellation-reason", name)
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

    /** 令牌：未取消无原因；首因稳定；子令牌沿用父原因；已先取消的子令牌保留自己的原因；异常带原因。 */
    @Test
    fun `token keeps first reason and children inherit it`() = assertOk(
        "token",
        """
        package std;
        public function main(): int32 {
            var parent: CancellationToken = new CancellationToken();
            var child: CancellationToken = parent.createChild();
            var early: CancellationToken = parent.createChild();
            if (parent.reason() != null) { return 1; }
            if (parent.reasonOr(CancellationReason.Parent) != CancellationReason.Parent) { return 2; }
            if (!early.cancelWith(CancellationReason.Deadline)) { return 3; }
            if (!parent.cancelWith(CancellationReason.SiblingFailed)) { return 4; }
            if (parent.cancelWith(CancellationReason.Explicit)) { return 5; }
            if (parent.cancel()) { return 6; }
            if (parent.reason() != CancellationReason.SiblingFailed) { return 7; }
            if (child.reason() != CancellationReason.SiblingFailed) { return 8; }
            if (early.reason() != CancellationReason.Deadline) { return 9; }
            var late: CancellationToken = parent.createChild();
            if (late.reason() != CancellationReason.SiblingFailed) { return 10; }
            try {
                child.throwIfCancelled();
                return 11;
            } catch (error: CancellationException) {
                if (error.reason() != CancellationReason.SiblingFailed) { return 12; }
            }
            var plain: CancellationToken = new CancellationToken();
            plain.cancel();
            if (plain.reason() != CancellationReason.Explicit) { return 13; }
            return 0;
        }
        """.trimIndent()
    )

    /** Future：原因只属于 Cancelled 终态，首因稳定，回调快照带原因；成功 / 失败后取消不写原因。 */
    @Test
    fun `future records reason with the cancelled terminal state`() = assertOk(
        "future",
        """
        package std;
        public class Capture implements Action<FutureOutcome> {
            private var seen: CancellationReason?;
            private var calls: int32;
            public constructor() { this.seen = null; this.calls = 0; }
            public function accept(outcome: FutureOutcome): void {
                this.seen = outcome.cancellationReason();
                this.calls = this.calls + 1;
            }
            public function reason(): CancellationReason? { return this.seen; }
            public function count(): int32 { return this.calls; }
        }
        public function main(): int32 {
            var future: Future<int32> = new Future<int32>();
            var capture: Capture = new Capture();
            future.onComplete(capture);
            if (future.cancellationReason() != null) { return 1; }
            if (!future.cancelWith(CancellationReason.Deadline)) { return 2; }
            if (future.cancel()) { return 3; }
            if (future.cancelWith(CancellationReason.ScopeExit)) { return 4; }
            if (future.cancellationReason() != CancellationReason.Deadline) { return 5; }
            if (capture.count() != 1 || capture.reason() != CancellationReason.Deadline) { return 6; }
            var late: Capture = new Capture();
            future.onComplete(late);
            if (late.reason() != CancellationReason.Deadline) { return 7; }
            var done: Future<int32> = new Future<int32>();
            done.complete(3);
            if (done.cancelWith(CancellationReason.Explicit)) { return 8; }
            if (done.cancellationReason() != null) { return 9; }
            var failed: Future<int32> = new Future<int32>();
            failed.fail(new Failure("boom", "error"));
            if (failed.cancel() || failed.cancellationReason() != null) { return 10; }
            var plain: Future<int32> = new Future<int32>();
            plain.cancel();
            if (plain.cancellationReason() != CancellationReason.Explicit) { return 11; }
            return 0;
        }
        """.trimIndent()
    )

    /**
     * TaskGroup：显式取消 → Explicit；普通组首个失败 → 兄弟 SiblingFailed（令牌与结果一致）；
     * 仅有单独取消的子任务时 close 取创建序最早者的原因；任务体自身的 Deadline 原因经异常进入结果。
     */
    @Test
    fun `task group propagates and reports the scope reason`() = assertOk(
        "group",
        """
        package std;
        public class ManualExecutor implements Executor {
            private var queue: MutableList<Runnable>;
            public constructor() { this.queue = new MutableList<Runnable>(); }
            public function schedule(task: Runnable): void { this.queue.add(task); }
            public function shutdown(): void { }
            public function drain(): void {
                var index: int32 = 0;
                while (index < this.queue.size()) {
                    this.queue.get(index).run();
                    index = index + 1;
                }
                this.queue = new MutableList<Runnable>();
            }
        }
        public class InlineExecutor implements Executor {
            public function schedule(task: Runnable): void { task.run(); }
            public function shutdown(): void { }
        }
        public class TokenProbe {
            private var seen: CancellationReason?;
            public constructor() { this.seen = null; }
            public function record(token: CancellationToken): void { this.seen = token.reason(); }
            public function reason(): CancellationReason? { return this.seen; }
        }
        public class FailBody implements TaskBody<int32> {
            public function run(token: CancellationToken): int32 { throw new Error("boom"); }
        }
        public class CheckBody implements TaskBody<int32> {
            private var probe: TokenProbe;
            public constructor(probe: TokenProbe) { this.probe = probe; }
            public function run(token: CancellationToken): int32 {
                this.probe.record(token);
                token.throwIfCancelled();
                return 1;
            }
        }
        public class DeadlineBody implements TaskBody<int32> {
            public function run(token: CancellationToken): int32 {
                token.cancelWith(CancellationReason.Deadline);
                token.throwIfCancelled();
                return 1;
            }
        }
        public class ValueBody implements TaskBody<int32> {
            public function run(token: CancellationToken): int32 { return 5; }
        }
        public function closeReason(group: TaskGroup): int32 {
            try {
                group.close();
                return 0;
            } catch (error: CancellationException) {
                if (error.reason() == CancellationReason.Explicit) { return 1; }
                if (error.reason() == CancellationReason.SiblingFailed) { return 2; }
                if (error.reason() == CancellationReason.Deadline) { return 3; }
                return 4;
            } catch (error: TaskGroupFailure) {
                return 5;
            }
        }
        public function main(): int32 {
            var manual: ManualExecutor = new ManualExecutor();
            var explicit: TaskGroup = new TaskGroup(false);
            var probe: TokenProbe = new TokenProbe();
            var waiting: Future<int32> = explicit.spawn(manual, new CheckBody(probe));
            explicit.cancel();
            if (waiting.cancellationReason() != CancellationReason.Explicit) { return 1; }
            manual.drain();
            if (probe.reason() != CancellationReason.Explicit) { return 2; }
            if (closeReason(explicit) != 1) { return 3; }

            var normal: TaskGroup = new TaskGroup(false);
            var siblingProbe: TokenProbe = new TokenProbe();
            var failing: Future<int32> = normal.spawn(manual, new FailBody());
            var sibling: Future<int32> = normal.spawn(manual, new CheckBody(siblingProbe));
            manual.drain();
            if (failing.status() != FutureStatus.Failed) { return 4; }
            if (sibling.cancellationReason() != CancellationReason.SiblingFailed) { return 5; }
            if (siblingProbe.reason() != CancellationReason.SiblingFailed) { return 6; }
            normal.cancel();
            var afterFailure: Future<int32> = normal.spawn(manual, new ValueBody());
            manual.drain();
            if (afterFailure.cancellationReason() != CancellationReason.SiblingFailed) { return 7; }
            if (closeReason(normal) != 5) { return 8; }

            var direct: InlineExecutor = new InlineExecutor();
            var individual: TaskGroup = new TaskGroup(true);
            var first: Future<int32> = individual.spawn(manual, new ValueBody());
            var second: Future<int32> = individual.spawn(manual, new ValueBody());
            second.cancelWith(CancellationReason.ScopeExit);
            first.cancelWith(CancellationReason.Deadline);
            manual.drain();
            if (closeReason(individual) != 3) { return 9; }

            var deadline: TaskGroup = new TaskGroup(true);
            var timed: Future<int32> = deadline.spawn(direct, new DeadlineBody());
            if (timed.cancellationReason() != CancellationReason.Deadline) { return 10; }
            if (closeReason(deadline) != 3) { return 11; }
            return 0;
        }
        """.trimIndent()
    )

    /** launch：已取消作用域里的新帧以作用域的首个原因取消，不执行业务段。 */
    @Test
    fun `launch into cancelled scope inherits the scope reason`() = assertOk(
        "launch",
        """
        package std;
        public class InlineExecutor implements Executor {
            public function schedule(task: Runnable): void { task.run(); }
            public function shutdown(): void { }
        }
        public class FailBody implements TaskBody<int32> {
            public function run(token: CancellationToken): int32 { throw new Error("boom"); }
        }
        public class HandFrame implements CoroutineTask<int32> {
            private var result: Future<int32>;
            private var ran: bool;
            public constructor() { this.result = new Future<int32>(); this.ran = false; }
            public function resultFuture(): Future<int32> { return this.result; }
            public function start(): void {
                if (!this.result.isDone()) {
                    this.ran = true;
                    this.result.complete(9);
                }
                this.result.markTaskSettled();
            }
            public function didRun(): bool { return this.ran; }
        }
        public function main(): int32 {
            var group: TaskGroup = new TaskGroup(false);
            group.spawn(new InlineExecutor(), new FailBody());
            var frame: HandFrame = new HandFrame();
            var launched: Future<int32> = group.launch(frame);
            if (frame.didRun()) { return 1; }
            if (launched.cancellationReason() != CancellationReason.SiblingFailed) { return 2; }
            var explicit: TaskGroup = new TaskGroup(true);
            explicit.cancel();
            var other: HandFrame = new HandFrame();
            if (explicit.launch(other).cancellationReason() != CancellationReason.Explicit) { return 3; }
            try {
                explicit.close();
                return 4;
            } catch (error: CancellationException) {
                if (error.reason() != CancellationReason.Explicit) { return 5; }
            }
            return 0;
        }
        """.trimIndent()
    )

    /**
     * 固定种子属性测试：种子 20261121，300 个随机事件序列（普通 / 监督组、3 个子任务、
     * 至多 24 个事件，约三分之一为不取消作用域、不失败的安静轮次），每步检查 [ReasonHarness.checkInvariants]，结束时检查 close 报告。
     * 覆盖下限防止生成器退化：根原因 Explicit / SiblingFailed、单独取消、任务体 Deadline、
     * close 抛 CancellationException 且原因来自子任务，各超过 20 次。
     */
    @Test
    fun `seeded event sequences keep reasons consistent`() {
        val seed = 20261121
        val random = Random(seed)
        val coverage = IntArray(5)
        repeat(300) { round ->
            val harness = ReasonHarness(supervisor = random.nextBoolean())
            // 安静轮次：不取消作用域、不产生业务失败，只剩单独取消与任务体 Deadline，
            // 才能覆盖「close 原因来自创建序最早的已取消子任务」。
            val quiet = random.nextInt(3) == 0
            try {
                repeat(random.nextInt(0, 25)) {
                    var op = random.nextInt(0, 8)
                    var r = random.nextInt(0, 5)
                    if (quiet && (op == 2 || op == 7)) op = 3
                    if (quiet && r % 4 == 1) r = 0
                    harness.step(op, random.nextInt(0, 3), r)
                    harness.checkInvariants()
                }
                val report = harness.closeAndCheck()
                when (harness.rootReason()) { "Explicit" -> coverage[0]++; "SiblingFailed" -> coverage[1]++ }
                if (harness.individualWins > 0) coverage[2]++
                if (harness.bodyDeadlineWins > 0) coverage[3]++
                if (report == "child") coverage[4]++
            } catch (error: AssertionError) {
                throw AssertionError("seed=$seed round=$round: ${error.message}", error)
            }
        }
        assertTrue(coverage.all { it > 20 }, "coverage too thin: ${coverage.toList()}")
    }
}

/** 一次编译、全进程复用的运行时句柄；只读，线程安全。 */
object ReasonRuntime {
    /**
     * 任务体用 Jasper 写：std.Error 继承受检 Throwable，JDK 动态代理抛出它会被包成
     * UndeclaredThrowableException，TaskRunner 会把检查点取消误记为平台失败。
     * kind 0 返回值、1 抛业务 Error、2 检查点 throwIfCancelled、3 自身以 Deadline 取消令牌后检查。
     */
    private val bodySource = """
        package demo;
        import std.CancellationReason;
        import std.CancellationToken;
        import std.Error;
        import std.TaskBody;

        public class ProbeBody implements TaskBody<int32> {
            private var kind: int32;
            private var seen: CancellationToken?;
            private var selfWon: bool;
            public constructor(kind: int32) { this.kind = kind; this.seen = null; this.selfWon = false; }
            public function run(token: CancellationToken): int32 {
                this.seen = token;
                if (this.kind == 3) { this.selfWon = token.cancelWith(CancellationReason.Deadline); }
                if (this.kind == 1) { throw new Error("boom"); }
                if (this.kind >= 2) { token.throwIfCancelled(); }
                return 1;
            }
            public function observedToken(): CancellationToken? { return this.seen; }
            public function wonDeadline(): bool { return this.selfWon; }
        }
    """.trimIndent()

    val loader: URLClassLoader by lazy {
        val output = Paths.get("build", "tmp", "cancellation-reason", "harness")
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("ProbeBody.jas" to bodySource), output)
        check(result is JasperCompiler.CompileResult.Success) { "compilation failed: $result" }
        URLClassLoader(arrayOf(output.toUri().toURL()))
    }
    private fun type(name: String): Class<*> = loader.loadClass(name)
    val token by lazy { type("std.CancellationToken") }
    val future by lazy { type("std.Future") }
    val group by lazy { type("std.TaskGroup") }
    val reasonType by lazy { type("std.CancellationReason") }
    private val executorType by lazy { type("std.Executor") }
    private val bodyType by lazy { type("std.TaskBody") }
    private val runnable by lazy { type("std.Runnable") }
    private val rootTokenField by lazy { group.getDeclaredField("rootToken").apply { isAccessible = true } }
    val names = listOf("Explicit", "Parent", "Deadline", "ScopeExit", "SiblingFailed")

    /** 按名取枚举值：Jasper 枚举编译为 JVM 枚举或静态字段，两种形状都接受。 */
    fun reason(name: String): Any =
        reasonType.enumConstants?.firstOrNull { it.toString() == name } ?: reasonType.getField(name).get(null)

    /** 确定性队列执行器：schedule 只入队，runOne 显式执行一个段。 */
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
        fun runOne(): Boolean {
            val task = queue.poll() ?: return false
            runnable.getMethod("run").invoke(task)
            return true
        }
        fun drain() { while (runOne()) Unit }
    }

    private val probeBody by lazy { type("demo.ProbeBody") }
    fun body(kind: Int): Any = probeBody.getConstructor(Int::class.javaPrimitiveType).newInstance(kind)
    fun bodyToken(body: Any): Any? = probeBody.getMethod("observedToken").invoke(body)
    fun bodyWonDeadline(body: Any): Boolean = probeBody.getMethod("wonDeadline").invoke(body) as Boolean

    fun newGroup(supervisor: Boolean): Any = group.getConstructor(Boolean::class.javaPrimitiveType).newInstance(supervisor)
    fun spawn(g: Any, executor: QueueExecutor, body: Any): Any =
        group.getMethod("spawn", executorType, bodyType).invoke(g, executor.proxy, body)
    fun cancelGroup(g: Any) { group.getMethod("cancel").invoke(g) }
    fun cancelFuture(f: Any, name: String): Boolean = future.getMethod("cancelWith", reasonType).invoke(f, reason(name)) as Boolean
    fun status(f: Any): String = future.getMethod("status").invoke(f).toString()
    fun futureReason(f: Any): String? = future.getMethod("cancellationReason").invoke(f)?.toString()
    fun tokenReason(t: Any): String? = token.getMethod("reason").invoke(t)?.toString()
    fun rootReason(g: Any): String? = tokenReason(rootTokenField.get(g))

    /** close：返回异常类名与（取消时）原因；正常返回 null。 */
    fun close(g: Any): Pair<String, String?>? = try {
        group.getMethod("close").invoke(g); null
    } catch (e: InvocationTargetException) {
        val thrown = e.targetException
        val reason = if (thrown.javaClass.name == "std.CancellationException") thrown.javaClass.getMethod("reason").invoke(thrown).toString() else null
        thrown.javaClass.name to reason
    }
}

/**
 * 一个作用域（3 个子任务）的事件驱动与判定；不变量与 CancellationReasonPropagation.tla 同名。
 *
 * 单线程使用时 [checkInvariants] 额外按精确 oracle 检查根原因；并发（Lincheck）只用作用域一致性判定。
 */
class ReasonHarness(val supervisor: Boolean, kinds: IntArray? = null) {
    private val rt = ReasonRuntime
    val executor = ReasonRuntime.QueueExecutor()
    val group: Any = rt.newGroup(supervisor)
    private val kinds = kinds ?: IntArray(3) { it }
    val futures = arrayOfNulls<Any>(3)
    private val bodies = arrayOfNulls<Any>(3)
    private val spawnOrder = mutableListOf<Int>()
    /** 子 Future 被单独取消——不受作用域一致性约束（任务体自身以 Deadline 首个取消令牌同理，见 [exempt]）。 */
    val individual = List(3) { AtomicBoolean(false) }
    private val seenFuture = arrayOfNulls<String>(3)
    private val seenToken = arrayOfNulls<String>(3)
    private var seenRoot: String? = null
    private var expectedRoot: String? = null
    val explicitRequested = AtomicBoolean(false)
    var individualWins = 0
    var bodyDeadlineWins = 0

    fun rootReason(): String? = rt.rootReason(group)
    private fun token(i: Int): Any? = bodies[i]?.let(rt::bodyToken)
    private fun bodyWon(i: Int): Boolean = bodies[i]?.let(rt::bodyWonDeadline) ?: false
    private fun exempt(i: Int): Boolean = individual[i].get() || bodyWon(i)

    /** 子任务 i 以预设 kind 登记；已登记则忽略。 */
    fun spawn(i: Int, kind: Int = kinds[i]) {
        if (futures[i] != null) return
        val body = rt.body(kind)
        bodies[i] = body
        futures[i] = rt.spawn(group, executor, body)
        synchronized(spawnOrder) { spawnOrder.add(i) }
    }

    fun cancelScope() {
        explicitRequested.set(true)
        ignoreCallbackErrors { rt.cancelGroup(group) }
    }

    fun cancelIndividual(i: Int, name: String) {
        val f = futures[i] ?: return
        if (rt.cancelFuture(f, name)) individual[i].set(true)
    }

    fun runOne() { ignoreCallbackErrors { executor.runOne() } }
    private fun runAll() { while (executor.queue.isNotEmpty()) runOne() }

    private inline fun ignoreCallbackErrors(block: () -> Unit) {
        try { block() } catch (e: InvocationTargetException) {
            check(e.targetException.javaClass.name == "std.CallbackDispatchError") { "unexpected ${e.targetException}" }
        }
    }

    /** 单线程事件：op 选动作、i 选子任务、r 选单独取消的原因。 */
    fun step(op: Int, i: Int, r: Int) {
        val rootBefore = rootReason()
        val failedBefore = futures.count { it != null && rt.status(it) == "Failed" }
        when (op) {
            0, 1 -> spawn(i, kind = r % 4)
            2 -> cancelScope()
            3 -> cancelIndividual(i, rt.names[r])
            4, 5 -> runOne()
            6 -> runAll()
            else -> spawn(i, kind = 1)
        }
        if (rootBefore == null && expectedRoot == null) {
            val failedAfter = futures.count { it != null && rt.status(it) == "Failed" }
            expectedRoot = when {
                op == 2 -> "Explicit"
                !supervisor && failedAfter > failedBefore -> "SiblingFailed"
                else -> null
            }
        }
        individualWins = individual.count { it.get() }
        bodyDeadlineWins = (0 until 3).count(::bodyWon)
    }

    private fun assertState(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    /** 作用域一致性判定（并发安全：只读 Jasper 状态与原子标志）。 */
    fun checkScopeAgreement() {
        val root = rootReason()
        for (i in 0 until 3) {
            val f = futures[i] ?: continue
            val status = rt.status(f)
            val reason = rt.futureReason(f)
            assertState((reason != null) == (status == "Cancelled"), "ReasonIffCancelled child=$i status=$status reason=$reason")
            val tokenReason = token(i)?.let(rt::tokenReason)
            if (!exempt(i)) {
                if (tokenReason != null) assertState(tokenReason == root, "TokenAgreesWithRoot child=$i token=$tokenReason root=$root")
                if (reason != null) assertState(reason == root, "FutureAgreesWithRoot child=$i future=$reason root=$root")
            }
        }
        if (root == "Explicit") assertState(explicitRequested.get(), "Explicit root without explicit request")
        if (root == "SiblingFailed") {
            assertState(!supervisor, "supervisor scope must not cascade")
            assertState(futures.any { it != null && rt.status(it) == "Failed" }, "SiblingFailed root without failed child")
        }
    }

    /** 单线程判定：作用域一致性 + 首因稳定 + 精确根原因。 */
    fun checkInvariants() {
        checkScopeAgreement()
        val root = rootReason()
        assertState(seenRoot == null || seenRoot == root, "FirstReasonStable root $seenRoot -> $root")
        seenRoot = root
        assertState(root == expectedRoot, "root=$root expected=$expectedRoot")
        for (i in 0 until 3) {
            val f = futures[i] ?: continue
            val reason = rt.futureReason(f)
            assertState(seenFuture[i] == null || seenFuture[i] == reason, "FirstReasonStable future=$i ${seenFuture[i]} -> $reason")
            seenFuture[i] = reason
            val tokenReason = token(i)?.let(rt::tokenReason)
            assertState(seenToken[i] == null || seenToken[i] == tokenReason, "FirstReasonStable token=$i ${seenToken[i]} -> $tokenReason")
            seenToken[i] = tokenReason
        }
    }

    /**
     * 跑完全部段后 close，检查报告：有未处理失败 → TaskGroupFailure；否则根已取消 → 根原因；
     * 否则创建序最早的已取消子任务的原因。返回 "root" / "child" / "failure" / "none" 供覆盖统计。
     */
    fun closeAndCheck(exact: Boolean = true): String {
        if (exact) {
            step(6, 0, 0)
            checkInvariants()
        } else {
            runAll()
            checkScopeAgreement()
        }
        val root = rootReason()
        val ordered = synchronized(spawnOrder) { spawnOrder.toList() }
        val anyFailed = ordered.any { rt.status(futures[it]!!) == "Failed" }
        val firstCancelled = ordered.firstOrNull { rt.status(futures[it]!!) == "Cancelled" }
        val actual = rt.close(group)
        return when {
            anyFailed -> { assertState(actual?.first == "std.TaskGroupFailure", "close=$actual expected failure"); "failure" }
            root != null -> { assertState(actual == ("std.CancellationException" to root), "close=$actual root=$root"); "root" }
            firstCancelled != null -> {
                val expected = rt.futureReason(futures[firstCancelled]!!)
                assertState(actual == ("std.CancellationException" to expected), "close=$actual expected child reason $expected")
                "child"
            }
            else -> { assertState(actual == null, "close=$actual expected success"); "none" }
        }
    }
}

/**
 * Lincheck 模型检查：普通组预登记「失败体 + 检查点体 + 检查点体」，两线程交错执行段、显式取消
 * 作用域与单独取消；@Validate 检查作用域一致性与 close 报告。被检查的是死锁、意外异常与判定。
 */
class CancellationReasonLincheckTest {
    private val harness = ReasonHarness(supervisor = false, kinds = intArrayOf(1, 2, 2)).apply {
        for (i in 0 until 3) spawn(i)
    }

    @Operation fun runSegment() { harness.runOne() }
    @Operation fun cancelScope() = harness.cancelScope()
    @Operation fun cancelSecondWithDeadline() = harness.cancelIndividual(2, "Deadline")

    @Validate fun validate() {
        harness.checkScopeAgreement()
        harness.closeAndCheck(exact = false)
    }

    @Test
    fun `model check concurrent cancellation sources`() {
        ReasonRuntime.loader // 先在测试线程完成编译，避免把编译纳入交错。
        ModelCheckingOptions()
            .iterations(40)
            .invocationsPerIteration(400)
            .threads(2)
            .actorsPerThread(3)
            .check(this::class)
    }
}

/**
 * Jazzer 覆盖引导 fuzz：字节流选择组类型与至多 32 个事件，每步按单线程 oracle 检查，结束时检查 close。
 * 默认（回归模式）运行入库语料；设置 JAZZER_FUZZ=1 时进入覆盖引导 fuzz。
 */
class CancellationReasonFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun reasonEvents(data: FuzzedDataProvider) {
        val harness = ReasonHarness(supervisor = data.consumeBoolean())
        repeat(data.consumeInt(0, 32)) {
            harness.step(data.consumeInt(0, 7), data.consumeInt(0, 2), data.consumeInt(0, 4))
            harness.checkInvariants()
        }
        harness.closeAndCheck()
    }
}
