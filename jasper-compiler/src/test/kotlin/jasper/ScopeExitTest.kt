// ============================================================================
// ScopeExitTest.kt —— S4 作用域异常退出（TaskGroup.closeAbruptly）验证
// ============================================================================
// 做什么：验证 closeAbruptly 封闭入口、以 ScopeExit 取消未完成子任务、等待回收、不掩盖在途异常，
//   被压制的问题数可观察，其后的 close 不再抛出（LANG-S4-SCOPEEXIT-01）。分四部分：
//   - 端到端契约：编译标准库 + 纯 Jasper main，返回码 0 表示全部断言通过；
//   - 固定种子属性测试：复用 ReasonHarness 驱动随机事件，再按单线程精确 oracle 判定退出结果；
//   - Lincheck 模型检查：退出、执行段、显式取消与单独取消的交错；
//   - Jazzer 覆盖引导 fuzz：与属性测试共用同一组判定（与 ScopeExitClose.tla 同名）。
//
// 所属层次：compiler 模块测试层；Kotlin 只提供驱动与判定，退出语义全部在 Jasper 运行时中。
// 前置条件：产物只写模块 build/ 下；所有可能阻塞的调用都在有界线程中。
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
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 端到端契约：每个用例是一段完整 Jasper 程序，main 返回 0 表示全部断言成立。 */
class ScopeExitTest {

    /** 共用的 Jasper 辅助类：等待令牌取消且记录真实退出的任务体、立即返回 / 失败的任务体、抛错的回调。 */
    private val prelude = """
        package std;
        public class WaitsForCancel implements TaskBody<int32> {
            private var exitedFlag: bool;
            public constructor() { this.exitedFlag = false; }
            public function run(token: CancellationToken): int32 {
                try {
                    var signal: java.util.concurrent.Semaphore = new java.util.concurrent.Semaphore(0);
                    token.onCancel(new ReleaseOnCancel(signal));
                    signal.acquireUninterruptibly();
                    token.throwIfCancelled();
                    return 1;
                } finally {
                    this.exitedFlag = true;
                }
            }
            public function exited(): bool { return this.exitedFlag; }
        }
        public class ReleaseOnCancel implements Action<CancellationToken> {
            private var signal: java.util.concurrent.Semaphore;
            public constructor(signal: java.util.concurrent.Semaphore) { this.signal = signal; }
            public function accept(token: CancellationToken): void { this.signal.release(); }
        }
        public class Returns implements TaskBody<int32> {
            public constructor() { }
            public function run(token: CancellationToken): int32 { return 1; }
        }
        public class Fails implements TaskBody<int32> {
            public constructor() { }
            public function run(token: CancellationToken): int32 { throw new Error("child"); }
        }
        public class ThrowsOnComplete implements Action<FutureOutcome> {
            public constructor() { }
            public function accept(outcome: FutureOutcome): void { throw new Error("callback"); }
        }
    """.trimIndent()

    /** 编译 [source]（含标准库），在有界线程里调用 `std.main` 并断言返回 0；挂起不会拖住测试 JVM。 */
    private fun assertOk(name: String, source: String) {
        val output = Paths.get("build", "tmp", "scope-exit", name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Main.jas" to prelude + "\n" + source), output)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " + ((result as? JasperCompiler.CompileResult.Error)?.message ?: result.toString())
        )
        val returned = AtomicReference<Any?>("did not finish")
        URLClassLoader(arrayOf(output.toUri().toURL())).use { loader ->
            val runner = thread(isDaemon = true) {
                returned.set(
                    try {
                        loader.loadClass("std.main").getMethod("main").invoke(null)
                    } catch (error: InvocationTargetException) {
                        error.targetException.toString()
                    }
                )
            }
            runner.join(30_000)
        }
        assertEquals(0, returned.get(), "case $name")
    }

    /** 未完成子任务得到 ScopeExit 并真实退出；入口已封闭；重复调用同结果；其后 close 不抛。 */
    @Test
    fun `abrupt close cancels unfinished children and joins them`() = assertOk(
        "basic",
        """
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(2);
            var group: TaskGroup = new TaskGroup(false);
            var firstBody: WaitsForCancel = new WaitsForCancel();
            var first: Future<int32> = group.spawn(executor, firstBody);
            var secondBody: WaitsForCancel = new WaitsForCancel();
            var second: Future<int32> = group.spawn(executor, secondBody);
            if (group.closeAbruptly() != 0) { return 1; }
            if (first.cancellationReason() != CancellationReason.ScopeExit) { return 2; }
            if (second.cancellationReason() != CancellationReason.ScopeExit) { return 3; }
            if (!firstBody.exited() || !secondBody.exited()) { return 4; }
            if (!group.isClosed()) { return 5; }
            try {
                group.spawn(executor, new Returns());
                return 6;
            } catch (error: TaskGroupClosedError) {
            }
            if (group.closeAbruptly() != 0) { return 7; }
            group.close();
            group.close();
            executor.shutdown();
            return 0;
        }
        """.trimIndent()
    )

    /** 契约 §5「保留父异常为主异常」：catch 中退出再重抛，子失败不替换它，finally 的 close 不抛。 */
    @Test
    fun `in flight error survives abrupt close and the deferred close`() = assertOk(
        "inflight",
        """
        public function body(group: TaskGroup, executor: JvmExecutor): int32 {
            try {
                try {
                    group.spawn(executor, new Fails()).awaitTaskSettled();
                    group.spawn(executor, new WaitsForCancel());
                    throw new Error("parent");
                } catch (error: Error) {
                    if (group.closeAbruptly() != 1) { return 1; }
                    throw error;
                }
            } finally {
                group.close();
            }
            return 2;
        }
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(2);
            var group: TaskGroup = new TaskGroup(true);
            var code: int32 = 3;
            try {
                code = body(group, executor);
            } catch (error: Error) {
                if (error.message() == "parent") { code = 0; }
            }
            executor.shutdown();
            return code;
        }
        """.trimIndent()
    )

    /** 被压制数：监督组不计已确认失败；普通组失败级联的首因不被 ScopeExit 覆盖。 */
    @Test
    fun `dropped count honours acknowledgement and first reason is stable`() = assertOk(
        "dropped",
        """
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(2);
            var supervisor: TaskGroup = new TaskGroup(true);
            var acked: Future<int32> = supervisor.spawn(executor, new Fails());
            var kept: Future<int32> = supervisor.spawn(executor, new Fails());
            var waiting: Future<int32> = supervisor.spawn(executor, new WaitsForCancel());
            acked.awaitTaskSettled();
            kept.awaitTaskSettled();
            if (!acked.acknowledgeFailure()) { return 1; }
            if (supervisor.closeAbruptly() != 1) { return 2; }
            if (waiting.cancellationReason() != CancellationReason.ScopeExit) { return 3; }
            if (kept.status() != FutureStatus.Failed) { return 4; }

            var normal: TaskGroup = new TaskGroup(false);
            var failed: Future<int32> = normal.spawn(executor, new Fails());
            var sibling: Future<int32> = normal.spawn(executor, new WaitsForCancel());
            failed.awaitTaskSettled();
            sibling.awaitTaskSettled();
            if (normal.closeAbruptly() != 1) { return 5; }
            if (sibling.cancellationReason() != CancellationReason.SiblingFailed) { return 6; }

            var explicit: TaskGroup = new TaskGroup(false);
            var cancelled: Future<int32> = explicit.spawn(executor, new WaitsForCancel());
            explicit.cancel();
            if (explicit.closeAbruptly() != 0) { return 7; }
            if (cancelled.cancellationReason() != CancellationReason.Explicit) { return 8; }
            executor.shutdown();
            return 0;
        }
        """.trimIndent()
    )

    /** 聚合已被 close 冻结：不再取消，根令牌不写 ScopeExit，返回冻结的未观察失败数。 */
    @Test
    fun `abrupt close after a finished close keeps the frozen result`() = assertOk(
        "frozen",
        """
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(1);
            var clean: TaskGroup = new TaskGroup(false);
            var body: WaitsForCancelFree = new WaitsForCancelFree();
            var done: Future<int32> = clean.spawn(executor, body);
            clean.close();
            if (clean.closeAbruptly() != 0) { return 1; }
            if (done.status() != FutureStatus.Completed) { return 2; }
            var token: CancellationToken? = body.observed();
            if (token == null) { return 3; }
            var seen: CancellationToken = cast token as CancellationToken;
            if (seen.reason() != null) { return 4; }

            var failing: TaskGroup = new TaskGroup(false);
            failing.spawn(executor, new Fails()).awaitTaskSettled();
            try {
                failing.close();
                return 5;
            } catch (error: TaskGroupFailure) {
            }
            if (failing.closeAbruptly() != 1) { return 6; }
            // 重复 close 仍报告冻结结果：冻结之后的 closeAbruptly 不改写 close 的行为。
            try {
                failing.close();
                return 7;
            } catch (error: TaskGroupFailure) {
            }
            executor.shutdown();
            return 0;
        }
        public class WaitsForCancelFree implements TaskBody<int32> {
            private var seen: CancellationToken?;
            public constructor() { this.seen = null; }
            public function run(token: CancellationToken): int32 { this.seen = token; return 1; }
            public function observed(): CancellationToken? { return this.seen; }
        }
        """.trimIndent()
    )

    /** 取消回调抛错：计入被压制数、不抛出、不跳过回收。 */
    @Test
    fun `callback failures are counted and do not skip the join`() = assertOk(
        "callback",
        """
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(2);
            var group: TaskGroup = new TaskGroup(true);
            var firstBody: WaitsForCancel = new WaitsForCancel();
            var secondBody: WaitsForCancel = new WaitsForCancel();
            var first: Future<int32> = group.spawn(executor, firstBody);
            var second: Future<int32> = group.spawn(executor, secondBody);
            first.onComplete(new ThrowsOnComplete());
            if (group.closeAbruptly() != 1) { return 1; }
            if (!firstBody.exited() || !secondBody.exited()) { return 2; }
            if (second.cancellationReason() != CancellationReason.ScopeExit) { return 3; }
            if (group.closeAbruptly() != 1) { return 4; }
            group.close();
            executor.shutdown();
            return 0;
        }
        """.trimIndent()
    )

    /**
     * 固定种子属性测试：种子 20261131，300 个随机事件序列（普通 / 监督组、3 个子任务，约三分之一为
     * 不取消作用域、不失败的安静轮次），结束时按 [ScopeExitOracle] 判定。覆盖下限防止生成器退化：
     * 由 ScopeExit 取消了未完成子任务、首因是 Explicit / SiblingFailed、被压制数大于 0、单独取消保留，各超过 20 次。
     */
    @Test
    fun `seeded event sequences agree with the abrupt close oracle`() {
        val seed = 20261131
        val random = Random(seed)
        val coverage = IntArray(5)
        repeat(300) { round ->
            val harness = ReasonHarness(supervisor = random.nextBoolean())
            val quiet = random.nextInt(3) == 0
            try {
                repeat(random.nextInt(0, 21)) {
                    var op = random.nextInt(0, 8)
                    var r = random.nextInt(0, 5)
                    if (quiet && (op == 2 || op == 7)) op = 3
                    if (quiet && r % 4 == 1) r = 0
                    harness.step(op, random.nextInt(0, 3), r)
                    harness.checkInvariants()
                }
                val report = ScopeExitOracle.exitAndCheck(harness) { random.nextBoolean() }
                if (report.exitedPending > 0) coverage[0]++
                if (report.rootBefore == "Explicit") coverage[1]++
                if (report.rootBefore == "SiblingFailed") coverage[2]++
                if (report.dropped > 0) coverage[3]++
                if (harness.individualWins > 0) coverage[4]++
            } catch (error: AssertionError) {
                throw AssertionError("seed=$seed round=$round: ${error.message}", error)
            }
        }
        assertTrue(coverage.all { it > 20 }, "coverage too thin: ${coverage.toList()}")
    }
}

/** 单线程判定：退出前快照 + 驱动 closeAbruptly + 与 ScopeExitClose.tla 同名的结果判定。 */
object ScopeExitOracle {
    private val rt = ReasonRuntime
    private val settledField by lazy { rt.future.getDeclaredField("settled").apply { isAccessible = true } }
    private val acknowledge by lazy { rt.future.getMethod("acknowledgeFailure") }

    class Report(val rootBefore: String?, val exitedPending: Int, val dropped: Int)

    private fun assertState(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    fun closeAbruptly(group: Any): Int = rt.group.getMethod("closeAbruptly").invoke(group) as Int

    /**
     * 在有界线程里运行 closeAbruptly，等子任务结果全部终态后才执行排队的段：单线程 oracle 需要确定性，
     * 否则「业务失败先于取消发布」的竞争会让结果不唯一（竞争本身归 Lincheck 与 TLA+）。
     * 监督组的已失败子任务按 [ack] 决定是否先确认。
     */
    fun exitAndCheck(harness: ReasonHarness, ack: () -> Boolean): Report {
        val futures = harness.futures.filterNotNull()
        val before = futures.map { rt.status(it) to rt.futureReason(it) }
        val rootBefore = harness.rootReason()
        if (harness.supervisor) {
            for (future in futures) {
                if (rt.status(future) == "Failed" && ack()) acknowledge.invoke(future)
            }
        }
        val acked = futures.count { rt.status(it) == "Failed" && rt.future.getMethod("isFailureAcknowledged").invoke(it) as Boolean }
        val expectedRoot = rootBefore ?: "ScopeExit"

        val dropped = AtomicInteger(-1)
        val thrown = AtomicReference<Throwable?>(null)
        val closer = thread(isDaemon = true) {
            try {
                dropped.set(closeAbruptly(harness.group))
            } catch (error: Throwable) {
                thrown.set(error)
            }
        }
        val limit = System.nanoTime() + 30_000_000_000L
        while (futures.any { rt.status(it) == "Pending" } && System.nanoTime() < limit) Thread.onSpinWait()
        while (closer.isAlive && System.nanoTime() < limit) {
            if (!harness.executor.runOne()) Thread.onSpinWait()
        }
        harness.executor.drain()
        closer.join(1_000)
        assertState(!closer.isAlive, "AbruptTerminates: closeAbruptly did not return")
        assertState(thrown.get() == null, "AbruptSilent: closeAbruptly threw ${thrown.get()}")

        assertState(harness.rootReason() == expectedRoot, "FirstReasonStable root=${harness.rootReason()} expected=$expectedRoot")
        var exitedPending = 0
        for ((i, future) in futures.withIndex()) {
            val status = rt.status(future)
            val reason = rt.futureReason(future)
            val (statusBefore, reasonBefore) = before[i]
            if (statusBefore == "Pending") {
                exitedPending++
                assertState(status == "Cancelled" && reason == expectedRoot, "ChildReasonIsRoot child=$i $status($reason) expected $expectedRoot")
            } else {
                assertState(status == statusBefore && reason == reasonBefore, "terminal child=$i changed $statusBefore($reasonBefore) -> $status($reason)")
            }
            assertState(settledField.get(future) as Boolean, "AbruptReturnsAfterJoin child=$i not settled")
        }
        val failures = futures.count { rt.status(it) == "Failed" }
        val expectedDropped = if (harness.supervisor) failures - acked else failures
        assertState(dropped.get() == expectedDropped, "AbruptCountIsFailures dropped=${dropped.get()} expected=$expectedDropped")
        assertState(closeAbruptly(harness.group) == expectedDropped, "repeat closeAbruptly differs")
        val closeResult = rt.close(harness.group)
        assertState(closeResult == null, "AbruptSilencesClose close=$closeResult")
        val rejected = try {
            rt.spawn(harness.group, harness.executor, rt.body(0)); "accepted"
        } catch (error: InvocationTargetException) {
            error.targetException.javaClass.name
        }
        assertState(rejected == "std.TaskGroupClosedError", "SealedHasNoNewChild spawn=$rejected")
        return Report(rootBefore, exitedPending, dropped.get())
    }
}

/**
 * Lincheck 模型检查：普通组预登记「失败体 + 检查点体 + 检查点体」，一个线程退出作用域，一个线程
 * 执行全部段（closeAbruptly 等它们退出），一个线程发起其他取消源。退出会阻塞到段执行完，不存在
 * 顺序规格，因此用 EpsilonVerifier：被检查的是死锁、意外异常与 @Validate 中的结果判定。
 */
class ScopeExitLincheckTest {
    private val harness = ReasonHarness(supervisor = false, kinds = intArrayOf(1, 2, 2)).apply {
        for (i in 0 until 3) spawn(i)
    }
    @Volatile private var dropped = -1

    @Operation fun exitScope() { dropped = ScopeExitOracle.closeAbruptly(harness.group) }
    @Operation fun runSegment() { harness.runOne() }
    @Operation fun cancelScope() = harness.cancelScope()

    @Validate fun validate() {
        if (dropped < 0) return
        harness.checkScopeAgreement()
        val futures = harness.futures.filterNotNull()
        for (future in futures) {
            check(ReasonRuntime.status(future) != "Pending") { "child still pending after closeAbruptly" }
        }
        val failures = futures.count { ReasonRuntime.status(it) == "Failed" }
        check(dropped == failures) { "dropped=$dropped failures=$failures" }
        val closeResult = ReasonRuntime.close(harness.group)
        check(closeResult == null) { "close after closeAbruptly threw $closeResult" }
    }

    @Test
    fun `model check abrupt exit against segments and other cancellation sources`() {
        ReasonRuntime.loader // 先在测试线程完成编译，避免把编译纳入交错。
        ModelCheckingOptions()
            .iterations(0)
            .invocationsPerIteration(1500)
            .verifier(EpsilonVerifier::class.java)
            .addCustomScenario {
                parallel {
                    thread { actor(ScopeExitLincheckTest::exitScope) }
                    thread {
                        actor(ScopeExitLincheckTest::runSegment)
                        actor(ScopeExitLincheckTest::runSegment)
                        actor(ScopeExitLincheckTest::runSegment)
                    }
                    thread { actor(ScopeExitLincheckTest::cancelScope) }
                }
            }
            .check(this::class)
    }
}

/**
 * Jazzer 覆盖引导 fuzz：字节流选择组类型、至多 24 个事件与确认选择，每步按单线程 oracle 检查，
 * 结束时 closeAbruptly 并判定。默认（回归模式）运行入库语料；设置 JAZZER_FUZZ=1 时进入覆盖引导 fuzz。
 */
class ScopeExitFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun scopeExitEvents(data: FuzzedDataProvider) {
        val harness = ReasonHarness(supervisor = data.consumeBoolean())
        repeat(data.consumeInt(0, 24)) {
            harness.step(data.consumeInt(0, 7), data.consumeInt(0, 2), data.consumeInt(0, 4))
            harness.checkInvariants()
        }
        ScopeExitOracle.exitAndCheck(harness) { data.consumeBoolean() }
    }
}
