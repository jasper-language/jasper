// ============================================================================
// NestedScopeTest.kt —— S4 嵌套作用域 Parent 链接验证
// ============================================================================
// 做什么：验证 TaskGroup.within / CancellationToken.createScopeChild：外层取消传入内层时 Deadline 保持、
//   其余为 Parent，取消不向上，内层结果经外层任务发布，回收后解除链接（LANG-S4-NESTED-01）。分四部分：
//   - 端到端契约：编译标准库 + 纯 Jasper main，返回码 0 表示全部断言通过；
//   - 固定种子属性测试：随机事件驱动一棵真实令牌树，每步与单线程精确 oracle 比对；
//   - Lincheck 模型检查：回调传播与检查点帮助传播的交错写同一个换算结果；
//   - Jazzer 覆盖引导 fuzz：与属性测试共用同一 oracle（与 NestedScopeCancel.tla 同名判定）。
//
// 所属层次：compiler 模块测试层；Kotlin 只提供驱动与判定，取消语义全部在 Jasper 运行时中。
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 端到端契约：每个用例是一段完整 Jasper 程序，main 返回 0 表示全部断言成立。 */
class NestedScopeTest {

    /**
     * 共用的 Jasper 辅助类。Nesting 是外层任务体：打开内层作用域、起一个等待取消的内层子任务、关闭内层；
     * entered 让主线程等到内层已就绪再发起外层取消，否则取消可能早于 within（那是另一个用例的形态）。
     */
    private val prelude = """
        package std;
        public class WaitsForCancel implements TaskBody<int32> {
            private var seen: CancellationToken?;
            private var exitedFlag: bool;
            public constructor() { this.seen = null; this.exitedFlag = false; }
            public function run(token: CancellationToken): int32 {
                this.seen = token;
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
            public function token(): CancellationToken { return cast this.seen as CancellationToken; }
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
            public function run(token: CancellationToken): int32 { throw new Error("inner failure"); }
        }
        public class Nesting implements TaskBody<int32> {
            private var executor: Executor;
            private var innerSupervisor: bool;
            private var innerBody: WaitsForCancel;
            private var entered: java.util.concurrent.Semaphore;
            private var innerChild: Future<int32>?;
            private var innerCloseReason: CancellationReason?;
            private var seenToken: CancellationToken?;
            public constructor(executor: Executor, innerSupervisor: bool) {
                this.executor = executor;
                this.innerSupervisor = innerSupervisor;
                this.innerBody = new WaitsForCancel();
                this.entered = new java.util.concurrent.Semaphore(0);
                this.innerChild = null;
                this.innerCloseReason = null;
                this.seenToken = null;
            }
            public function run(token: CancellationToken): int32 {
                this.seenToken = token;
                var inner: TaskGroup = TaskGroup.within(token, this.innerSupervisor);
                this.innerChild = inner.spawn(this.executor, this.innerBody);
                this.entered.release();
                try {
                    inner.close();
                } catch (cancelled: CancellationException) {
                    this.innerCloseReason = cancelled.reason();
                    throw cancelled;
                }
                return 1;
            }
            public function awaitEntered(): void { this.entered.acquireUninterruptibly(); }
            public function child(): Future<int32> { return cast this.innerChild as Future<int32>; }
            public function closeReason(): CancellationReason? { return this.innerCloseReason; }
            public function body(): WaitsForCancel { return this.innerBody; }
            public function outerToken(): CancellationToken { return cast this.seenToken as CancellationToken; }
        }
    """.trimIndent()

    /** 编译 [source]（含标准库），在有界线程里调用 `std.main` 并断言返回 0；挂起不会拖住测试 JVM。 */
    private fun assertOk(name: String, source: String) {
        val output = Paths.get("build", "tmp", "nested-scope", name)
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

    /** 外层显式取消：内层子任务与内层 close 报 Parent 并真实退出；外层任务结果是自己的首因 Explicit。 */
    @Test
    fun `outer cancel reaches the inner scope as Parent and the outer task keeps its reason`() = assertOk(
        "explicit",
        """
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(4);
            var outer: TaskGroup = new TaskGroup(false);
            var nesting: Nesting = new Nesting(executor, false);
            var task: Future<int32> = outer.spawn(executor, nesting);
            nesting.awaitEntered();
            outer.cancel();
            task.awaitTaskSettled();
            if (nesting.child().cancellationReason() != CancellationReason.Parent) { return 1; }
            if (nesting.closeReason() != CancellationReason.Parent) { return 2; }
            if (task.cancellationReason() != CancellationReason.Explicit) { return 3; }
            if (!nesting.body().exited()) { return 4; }
            try {
                outer.close();
                return 5;
            } catch (cancelled: CancellationException) {
                if (cancelled.reason() != CancellationReason.Explicit) { return 6; }
            }
            executor.shutdown();
            return 0;
        }
        """.trimIndent()
    )

    /** 外层到期（计时线程触发，无检查点）：Deadline 穿过作用域边界，任务与两层 close 都报 Deadline。 */
    @Test
    fun `outer deadline stays Deadline across the scope boundary`() = assertOk(
        "deadline",
        """
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(4);
            var outer: TaskGroup = new TaskGroup(false);
            var nesting: Nesting = new Nesting(executor, true);
            var task: Future<int32> = outer.spawn(executor, nesting);
            nesting.awaitEntered();
            outer.restrictDeadline(Deadline.afterNanos(1000000L));
            task.awaitTaskSettled();
            if (nesting.child().cancellationReason() != CancellationReason.Deadline) { return 1; }
            if (nesting.closeReason() != CancellationReason.Deadline) { return 2; }
            if (task.cancellationReason() != CancellationReason.Deadline) { return 3; }
            try {
                outer.close();
                return 4;
            } catch (cancelled: CancellationException) {
                if (cancelled.reason() != CancellationReason.Deadline) { return 5; }
            }
            executor.shutdown();
            return 0;
        }
        """.trimIndent()
    )

    /** SiblingFailed 与 ScopeExit 的作用范围只到外层子任务：传入内层都是 Parent，外层任务保留自己的原因。 */
    @Test
    fun `sibling failure and scope exit are Parent inside the nested scope`() = assertOk(
        "sibling",
        """
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(4);
            var failing: TaskGroup = new TaskGroup(false);
            var first: Nesting = new Nesting(executor, false);
            var firstTask: Future<int32> = failing.spawn(executor, first);
            first.awaitEntered();
            failing.spawn(executor, new Fails());
            firstTask.awaitTaskSettled();
            if (first.child().cancellationReason() != CancellationReason.Parent) { return 1; }
            if (firstTask.cancellationReason() != CancellationReason.SiblingFailed) { return 2; }
            try {
                failing.close();
                return 3;
            } catch (error: TaskGroupFailure) {
                if (error.message() != "inner failure") { return 4; }
            }

            var exiting: TaskGroup = new TaskGroup(false);
            var second: Nesting = new Nesting(executor, false);
            var secondTask: Future<int32> = exiting.spawn(executor, second);
            second.awaitEntered();
            if (exiting.closeAbruptly() != 0) { return 5; }
            if (second.child().cancellationReason() != CancellationReason.Parent) { return 6; }
            if (secondTask.cancellationReason() != CancellationReason.ScopeExit) { return 7; }
            if (!second.body().exited()) { return 8; }
            executor.shutdown();
            return 0;
        }
        """.trimIndent()
    )

    /** 取消单个外层任务只波及它自己打开的作用域，兄弟任务及其内层都不受影响。 */
    @Test
    fun `cancelling one outer task leaves its sibling scope alone`() = assertOk(
        "single",
        """
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(6);
            var outer: TaskGroup = new TaskGroup(true);
            var victim: Nesting = new Nesting(executor, false);
            var other: Nesting = new Nesting(executor, false);
            var victimTask: Future<int32> = outer.spawn(executor, victim);
            var otherTask: Future<int32> = outer.spawn(executor, other);
            victim.awaitEntered();
            other.awaitEntered();
            victim.outerToken().cancel();
            victimTask.awaitTaskSettled();
            if (victim.child().cancellationReason() != CancellationReason.Parent) { return 1; }
            if (victimTask.status() != FutureStatus.Cancelled) { return 2; }
            if (victimTask.cancellationReason() != CancellationReason.Explicit) { return 3; }
            if (other.body().token().isCancelled()) { return 4; }
            if (otherTask.isDone()) { return 5; }
            outer.cancel();
            try {
                outer.close();
                return 6;
            } catch (cancelled: CancellationException) {
                if (cancelled.reason() != CancellationReason.Explicit) { return 7; }
            }
            if (other.child().cancellationReason() != CancellationReason.Parent) { return 8; }
            executor.shutdown();
            return 0;
        }
        """.trimIndent()
    )

    /** 取消不向上：内层显式取消与内层失败级联都不碰外层令牌；内层失败作为一个子任务失败上报。 */
    @Test
    fun `inner cancellation does not escalate and inner failure surfaces as one child failure`() = assertOk(
        "upward",
        """
        public class InnerCancels implements TaskBody<int32> {
            private var outerToken: CancellationToken?;
            public constructor() { this.outerToken = null; }
            public function run(token: CancellationToken): int32 {
                this.outerToken = token;
                var inner: TaskGroup = TaskGroup.within(token, false);
                inner.cancel();
                if (token.isCancelled()) { return 100; }
                inner.close();
                return 200;
            }
            public function seen(): CancellationToken { return cast this.outerToken as CancellationToken; }
        }
        public class InnerFails implements TaskBody<int32> {
            private var executor: Executor;
            private var outerToken: CancellationToken?;
            public constructor(executor: Executor) { this.executor = executor; this.outerToken = null; }
            public function run(token: CancellationToken): int32 {
                this.outerToken = token;
                var inner: TaskGroup = TaskGroup.within(token, false);
                inner.spawn(this.executor, new Fails());
                inner.spawn(this.executor, new Fails()).awaitTaskSettled();
                inner.close();
                return 1;
            }
            public function seen(): CancellationToken { return cast this.outerToken as CancellationToken; }
        }
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(4);
            var supervisor: TaskGroup = new TaskGroup(true);
            var cancels: InnerCancels = new InnerCancels();
            var cancelled: Future<int32> = supervisor.spawn(executor, cancels);
            cancelled.awaitTaskSettled();
            // 内层自己取消：异常穿出任务体，外层令牌未取消，原因就是异常的 Explicit。
            if (cancelled.cancellationReason() != CancellationReason.Explicit) { return 1; }
            if (cancels.seen().isCancelled()) { return 2; }

            var fails: InnerFails = new InnerFails(executor);
            var failed: Future<int32> = supervisor.spawn(executor, fails);
            var bystander: Future<int32> = supervisor.spawn(executor, new Returns());
            failed.awaitTaskSettled();
            if (failed.status() != FutureStatus.Failed) { return 3; }
            var failure: Failure? = failed.failureOrNull();
            if (failure == null) { return 4; }
            if ((cast failure as Failure).kind() != "error") { return 5; }
            if (fails.seen().isCancelled()) { return 6; }
            bystander.awaitTaskSettled();
            if (bystander.status() != FutureStatus.Completed) { return 7; }
            try {
                supervisor.close();
                return 8;
            } catch (error: TaskGroupFailure) {
                // 内层聚合是一个子任务失败：外层 suppressed 为 0，内层压制数留在消息里。
                if (error.suppressedCount() != 0) { return 9; }
            } catch (other: CancellationException) {
                return 10;
            }
            executor.shutdown();
            return 0;
        }
        """.trimIndent()
    )

    /** 内层自己先取消、外层任务之后才被取消：内层的原因是真实来源，照常发布，不换成外层令牌的原因。 */
    @Test
    fun `inner reason that came first is published for the outer task`() = assertOk(
        "innerfirst",
        """
        public class InnerThenOuter implements TaskBody<int32> {
            public constructor() { }
            public function run(token: CancellationToken): int32 {
                var inner: TaskGroup = TaskGroup.within(token, false);
                inner.cancel();
                token.cancelWith(CancellationReason.SiblingFailed);
                inner.close();
                return 1;
            }
        }
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(1);
            var outer: TaskGroup = new TaskGroup(true);
            var task: Future<int32> = outer.spawn(executor, new InnerThenOuter());
            task.awaitTaskSettled();
            if (task.cancellationReason() != CancellationReason.Explicit) { return 1; }
            try {
                outer.close();
                return 2;
            } catch (cancelled: CancellationException) {
                if (cancelled.reason() != CancellationReason.Explicit) { return 3; }
            }
            executor.shutdown();
            return 0;
        }
        """.trimIndent()
    )

    /** 进入时已取消：within 返回已取消作用域，原因已换算；任务体里 spawn 的子任务拿到同一原因。 */
    @Test
    fun `scope opened under a cancelled token is already cancelled`() = assertOk(
        "already",
        """
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(2);
            var explicit: CancellationToken = new CancellationToken();
            explicit.cancel();
            var underExplicit: TaskGroup = TaskGroup.within(explicit, false);
            var child: Future<int32> = underExplicit.spawn(executor, new WaitsForCancel());
            child.awaitTaskSettled();
            if (child.cancellationReason() != CancellationReason.Parent) { return 1; }
            try {
                underExplicit.close();
                return 2;
            } catch (cancelled: CancellationException) {
                if (cancelled.reason() != CancellationReason.Parent) { return 3; }
            }

            var expired: CancellationToken = new CancellationToken();
            expired.cancelWith(CancellationReason.Deadline);
            var underExpired: TaskGroup = TaskGroup.within(expired, true);
            var late: Future<int32> = underExpired.spawn(executor, new WaitsForCancel());
            late.awaitTaskSettled();
            if (late.cancellationReason() != CancellationReason.Deadline) { return 4; }
            try {
                underExpired.close();
                return 5;
            } catch (cancelled: CancellationException) {
                if (cancelled.reason() != CancellationReason.Deadline) { return 6; }
            }
            executor.shutdown();
            return 0;
        }
        """.trimIndent()
    )

    /** 三层：Parent 逐层仍是 Parent，Deadline 逐层仍是 Deadline；截止点沿链取更严格，内层不能延长外层。 */
    @Test
    fun `reasons and deadlines through several scope boundaries`() = assertOk(
        "chain",
        """
        public function main(): int32 {
            var top: CancellationToken = new CancellationToken();
            var middle: CancellationToken = top.createChild().createScopeChild();
            var bottom: CancellationToken = middle.createChild().createScopeChild();
            var leaf: CancellationToken = bottom.createChild();
            top.cancel();
            if (top.reason() != CancellationReason.Explicit) { return 1; }
            if (middle.reason() != CancellationReason.Parent) { return 2; }
            if (bottom.reason() != CancellationReason.Parent) { return 3; }
            if (leaf.reason() != CancellationReason.Parent) { return 4; }

            var timedTop: CancellationToken = new CancellationToken();
            var timedInner: CancellationToken = timedTop.createChild().createScopeChild();
            var timedLeaf: CancellationToken = timedInner.createChild().createScopeChild();
            timedTop.restrictDeadline(Deadline.fromNanos(100L));
            timedLeaf.restrictDeadline(Deadline.fromNanos(500L));
            var effective: Deadline? = timedLeaf.deadline();
            if (effective == null) { return 5; }
            if ((cast effective as Deadline).atNanos() != 100L) { return 6; }
            if (timedLeaf.pollDeadlineAt(50L)) { return 7; }
            if (!timedLeaf.pollDeadlineAt(100L)) { return 8; }
            if (timedTop.reason() != CancellationReason.Deadline) { return 9; }
            if (timedInner.reason() != CancellationReason.Deadline) { return 10; }
            if (timedLeaf.reason() != CancellationReason.Deadline) { return 11; }

            // 内层自己的期限到期只取消内层，不向上。
            var upper: CancellationToken = new CancellationToken();
            var lower: CancellationToken = upper.createScopeChild();
            lower.restrictDeadline(Deadline.fromNanos(10L));
            if (!lower.pollDeadlineAt(10L)) { return 12; }
            if (lower.reason() != CancellationReason.Deadline) { return 13; }
            if (upper.isCancelled()) { return 14; }
            return 0;
        }
        """.trimIndent()
    )

    /**
     * 帮助传播与回调写同一个换算结果：父上更早注册的回调里，内层根还没收到传播时对内层子令牌做检查点，
     * 走的是 pollReasonAt 的帮助路径（确定性窗口，Lincheck 未必命中）。
     */
    @Test
    fun `checkpoint inside an earlier callback helps with the converted reason`() = assertOk(
        "help",
        """
        public class PollsLeaf implements Action<CancellationToken> {
            private var leaf: CancellationToken?;
            private var observed: CancellationReason?;
            public constructor() { this.leaf = null; this.observed = null; }
            public function aim(leaf: CancellationToken): void { this.leaf = leaf; }
            public function accept(token: CancellationToken): void {
                this.observed = (cast this.leaf as CancellationToken).pollReason();
            }
            public function seen(): CancellationReason? { return this.observed; }
        }
        public function main(): int32 {
            var task: CancellationToken = new CancellationToken();
            var probe: PollsLeaf = new PollsLeaf();
            task.onCancel(probe);
            var root: CancellationToken = task.createScopeChild();
            var leaf: CancellationToken = root.createChild();
            probe.aim(leaf);
            task.cancelWith(CancellationReason.SiblingFailed);
            if (probe.seen() != CancellationReason.Parent) { return 1; }
            if (root.reason() != CancellationReason.Parent) { return 2; }
            if (leaf.reason() != CancellationReason.Parent) { return 3; }
            return 0;
        }
        """.trimIndent()
    )

    /** 回收后解除链接：外层之后的取消不再到达已结束的作用域；重复 close 幂等；close 结果不变。 */
    @Test
    fun `closed nested scope is detached from the outer token`() = assertOk(
        "detach",
        """
        public class Remembers implements TaskBody<int32> {
            private var seen: CancellationToken?;
            public constructor() { this.seen = null; }
            public function run(token: CancellationToken): int32 { this.seen = token; return 1; }
            public function token(): CancellationToken { return cast this.seen as CancellationToken; }
        }
        public function main(): int32 {
            var executor: JvmExecutor = new JvmExecutor(1);
            var task: CancellationToken = new CancellationToken();
            var inner: TaskGroup = TaskGroup.within(task, false);
            var body: Remembers = new Remembers();
            inner.spawn(executor, body).awaitTaskSettled();
            inner.close();
            task.cancel();
            if (body.token().isCancelled()) { return 1; }
            inner.close();
            if (inner.closeAbruptly() != 0) { return 2; }
            if (body.token().isCancelled()) { return 3; }

            var abruptTask: CancellationToken = new CancellationToken();
            var abrupt: TaskGroup = TaskGroup.within(abruptTask, false);
            if (abrupt.closeAbruptly() != 0) { return 4; }
            abruptTask.cancel();
            if (abrupt.deadline() != null) { return 5; }
            abrupt.close();

            // 普通（非跨作用域）令牌上 detachFromParent 是空操作：传播不被解除。
            var parent: CancellationToken = new CancellationToken();
            var plain: CancellationToken = parent.createChild();
            plain.detachFromParent();
            parent.cancel();
            if (plain.reason() != CancellationReason.Explicit) { return 6; }
            executor.shutdown();
            return 0;
        }
        """.trimIndent()
    )

    /** 生成帧放进嵌套作用域：外层取消唤醒挂起帧，结果 Cancelled(Parent)，清理恰好一次，回收后 settled。 */
    @Test
    fun `generated frame in a nested scope is woken with Parent`() {
        val rt = AwaitReasonRuntime
        rt.reset()
        val within = rt.group.getMethod("within", rt.loader.loadClass("std.CancellationToken"), Boolean::class.javaPrimitiveType)
        val task = rt.loader.loadClass("std.CancellationToken").getConstructor().newInstance()
        val inner = within.invoke(null, task, false)
        val executor = AwaitReasonRuntime.QueueExecutor()
        val input = rt.newFuture()
        val result = rt.newFuture()
        val frame = rt.newFrame("plain", executor, result, input, rt.newFuture(), 7)
        rt.launch(inner, frame)
        executor.drain()
        assertEquals("Pending", rt.status(result))
        task.javaClass.getMethod("cancel").invoke(task)
        executor.drain()
        assertEquals("Cancelled", rt.status(result))
        assertEquals("Parent", rt.reason(result))
        assertEquals(1, rt.cleaned(), "cleanup runs exactly once")
        assertTrue(rt.isSettled(result))
        assertEquals("cancel:Parent", rt.close(inner))
    }

    /**
     * 固定种子属性测试：种子 20261135，1000 轮随机事件。覆盖下限防止生成器退化：跨边界换算成 Parent、
     * 跨边界保持 Deadline、自身原因先到被保留、解除后外层取消未到达、解除后检查点帮助传播，各超过 20 次。
     */
    @Test
    fun `seeded event sequences agree with the token tree oracle`() {
        val seed = 20261135
        val random = Random(seed)
        val coverage = IntArray(5)
        repeat(1000) { round ->
            val harness = TokenTreeHarness()
            try {
                repeat(random.nextInt(1, 25)) {
                    harness.step(random.nextInt(0, 6), random.nextInt(0, TokenTreeHarness.SIZE), random.nextInt(0, 5), random.nextInt(0, 16))
                }
                harness.addCoverage(coverage)
            } catch (error: AssertionError) {
                throw AssertionError("seed=$seed round=$round: ${error.message}", error)
            }
        }
        assertTrue(coverage.all { it > 20 }, "coverage too thin: ${coverage.toList()}")
    }
}

/**
 * 令牌树与单线程精确 oracle。树：0 外层根 → 1 外层任务、6 外层兄弟；1 ⇒ 2 内层根（跨作用域）→ 3 内层子；
 * 3 ⇒ 4 第三层根（跨作用域）→ 5 第三层子。每步对真实令牌执行同一操作，再比对全部原因与有效截止点。
 */
class TokenTreeHarness {
    private val rt = ReasonRuntime
    private val deadlineType by lazy { rt.loader.loadClass("std.Deadline") }
    private val fromNanos by lazy { deadlineType.getMethod("fromNanos", Long::class.javaPrimitiveType) }
    private val atNanos by lazy { deadlineType.getMethod("atNanos") }
    private val restrict by lazy { rt.token.getMethod("restrictDeadline", deadlineType) }
    private val pollAt by lazy { rt.token.getMethod("pollReasonAt", Long::class.javaPrimitiveType) }

    val tokens: List<Any>
    private val parent = intArrayOf(-1, 0, 1, 2, 3, 4, 0)
    private val crossing = booleanArrayOf(false, false, true, false, true, false, false)
    private val reasons = arrayOfNulls<String>(SIZE)
    private val own = arrayOfNulls<Long>(SIZE)
    private val linked = BooleanArray(SIZE) { true }
    private val fromOwnSource = BooleanArray(SIZE)
    private var mapped = 0
    private var deadlineCrossed = 0
    private var blocked = 0
    private var helped = 0

    init {
        val root = rt.token.getConstructor().newInstance()
        val child = rt.token.getMethod("createChild")
        val scopeChild = rt.token.getMethod("createScopeChild")
        val task = child.invoke(root)
        val innerRoot = scopeChild.invoke(task)
        val innerChild = child.invoke(innerRoot)
        val thirdRoot = scopeChild.invoke(innerChild)
        val thirdChild = child.invoke(thirdRoot)
        val sibling = child.invoke(root)
        tokens = listOf(root, task, innerRoot, innerChild, thirdRoot, thirdChild, sibling)
    }

    private fun inherited(i: Int, parentReason: String): String =
        if (crossing[i] && parentReason != "Deadline") "Parent" else parentReason

    private fun modelCancel(i: Int, reason: String, source: Boolean): Boolean {
        if (reasons[i] != null) return false
        reasons[i] = reason
        fromOwnSource[i] = source
        if (crossing[i] && !source) {
            if (reason == "Deadline") deadlineCrossed++ else mapped++
        }
        for (c in 0 until SIZE) {
            if (parent[c] != i) continue
            if (crossing[c] && !linked[c]) {
                if (reasons[c] == null) blocked++
                continue
            }
            modelCancel(c, inherited(c, reasons[i]!!), false)
        }
        return true
    }

    private fun modelPoll(i: Int, now: Long): String? {
        val parentIndex = parent[i]
        if (parentIndex >= 0) {
            val parentReason = modelPoll(parentIndex, now)
            if (reasons[i] != null) return reasons[i]
            if (parentReason != null) {
                if (crossing[i] && !linked[i]) helped++
                modelCancel(i, inherited(i, parentReason), false)
                return reasons[i]
            }
        }
        if (reasons[i] != null) return reasons[i]
        val deadline = own[i]
        if (deadline != null && now >= deadline) {
            modelCancel(i, "Deadline", true)
            return reasons[i]
        }
        return null
    }

    private fun modelDeadline(i: Int): Long? {
        val inherited = if (parent[i] >= 0) modelDeadline(parent[i]) else null
        val mine = own[i]
        return if (inherited == null) mine else if (mine == null) inherited else minOf(inherited, mine)
    }

    /** 0、1 取消；2 收紧截止点；3、4 检查点；5 解除跨作用域链接。参数由调用方给出，生成器与 fuzz 共用。 */
    fun step(op: Int, index: Int, reason: Int, time: Int) {
        val target = tokens[index]
        when (op) {
            0, 1 -> {
                val name = ReasonRuntime.names[reason]
                val won = rt.token.getMethod("cancelWith", rt.reasonType).invoke(target, rt.reason(name)) as Boolean
                assertState(won == modelCancel(index, name, true), "cancelWith($index,$name) won=$won")
            }
            2 -> {
                restrict.invoke(target, fromNanos.invoke(null, time.toLong()))
                val current = own[index]
                own[index] = if (current == null) time.toLong() else minOf(current, time.toLong())
            }
            3, 4 -> {
                val observed = pollAt.invoke(target, time.toLong())?.toString()
                val expected = modelPoll(index, time.toLong())
                assertState(observed == expected, "pollReasonAt($index,$time) observed=$observed expected=$expected")
            }
            else -> {
                rt.token.getMethod("detachFromParent").invoke(target)
                if (crossing[index]) linked[index] = false
            }
        }
        check()
    }

    private fun check() {
        for (i in 0 until SIZE) {
            val observed = rt.tokenReason(tokens[i])
            assertState(observed == reasons[i], "token $i reason=$observed expected=${reasons[i]}")
            val deadline = rt.token.getMethod("deadline").invoke(tokens[i])
            val effective = deadline?.let { atNanos.invoke(it) as Long }
            assertState(effective == modelDeadline(i), "token $i deadline=$effective expected=${modelDeadline(i)}")
        }
        // 与 NestedScopeCancel.tla 同名：取消不向上（父已取消或自身来源）。
        for (i in 1 until SIZE) {
            if (reasons[i] != null && !fromOwnSource[i]) {
                assertState(reasons[parent[i]] != null, "NoUpward token $i has no cancelled ancestor")
            }
        }
    }

    fun addCoverage(coverage: IntArray) {
        coverage[0] += mapped
        coverage[1] += deadlineCrossed
        coverage[2] += (0 until SIZE).count { crossing[it] && fromOwnSource[it] && reasons[parent[it]] != null }
        coverage[3] += blocked
        coverage[4] += helped
    }

    private fun assertState(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    companion object {
        const val SIZE = 7
    }
}

/**
 * Lincheck 模型检查：外层根已到期，外层显式取消、内层根显式取消、内层子令牌上的检查点竞争。
 * 无论回调还是帮助传播先到，内层根的首因只能是自身来源或父原因的换算，内层子令牌沿用内层根，
 * 外层不受内层影响。检查点取消的回调都在操作内同步完成，因此操作全部结束后必定已传播到底。
 */
class NestedScopeLincheckTest {
    private val rt = ReasonRuntime
    private val root = rt.token.getConstructor().newInstance()
    private val task = rt.token.getMethod("createChild").invoke(root)
    private val innerRoot = rt.token.getMethod("createScopeChild").invoke(task)
    private val leaf = rt.token.getMethod("createChild").invoke(innerRoot)
    private val pollAt = rt.token.getMethod("pollReasonAt", Long::class.javaPrimitiveType)
    private val cancelWith = rt.token.getMethod("cancelWith", rt.reasonType)
    @Volatile private var innerWon = false
    @Volatile private var rootWon = false

    init {
        val deadlineType = rt.loader.loadClass("std.Deadline")
        rt.token.getMethod("restrictDeadline", deadlineType)
            .invoke(root, deadlineType.getMethod("fromNanos", Long::class.javaPrimitiveType).invoke(null, 10L))
    }

    @Operation fun cancelRoot() { if (cancelWith.invoke(root, rt.reason("Explicit")) as Boolean) rootWon = true }
    @Operation fun cancelInner() { if (cancelWith.invoke(innerRoot, rt.reason("Explicit")) as Boolean) innerWon = true }
    @Operation fun pollLeaf() { pollAt.invoke(leaf, 20L) }

    @Validate fun validate() {
        val rootReason = rt.tokenReason(root)
        val taskReason = rt.tokenReason(task)
        val innerReason = rt.tokenReason(innerRoot)
        val leafReason = rt.tokenReason(leaf)
        check(taskReason == rootReason) { "same scope child task=$taskReason root=$rootReason" }
        if (rootWon) check(rootReason == "Explicit") { "root won but reason=$rootReason" }
        if (innerWon) {
            check(innerReason == "Explicit") { "inner won but reason=$innerReason" }
        } else if (innerReason != null) {
            val expected = if (rootReason == "Deadline") "Deadline" else "Parent"
            check(innerReason == expected) { "crossing inner=$innerReason root=$rootReason" }
        }
        if (rootReason != null) check(innerReason != null) { "cancel did not reach the inner scope" }
        if (innerReason != null) check(leafReason == innerReason) { "same scope leaf=$leafReason inner=$innerReason" }
    }

    @Test
    fun `model check propagation against helping checkpoints`() {
        ReasonRuntime.loader // 先在测试线程完成编译，避免把编译纳入交错。
        ModelCheckingOptions()
            .iterations(0)
            .invocationsPerIteration(1500)
            .verifier(EpsilonVerifier::class.java)
            .addCustomScenario {
                parallel {
                    thread { actor(NestedScopeLincheckTest::cancelRoot) }
                    thread { actor(NestedScopeLincheckTest::cancelInner) }
                    thread { actor(NestedScopeLincheckTest::pollLeaf) }
                }
            }
            .check(this::class)
    }
}

/**
 * Jazzer 覆盖引导 fuzz：字节流选择至多 32 个事件，每步按单线程 oracle 检查。
 * 默认（回归模式）运行入库语料；设置 JAZZER_FUZZ=1 时进入覆盖引导 fuzz。
 */
class NestedScopeFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun nestedScopeEvents(data: FuzzedDataProvider) {
        val harness = TokenTreeHarness()
        repeat(data.consumeInt(0, 32)) {
            harness.step(data.consumeInt(0, 5), data.consumeInt(0, TokenTreeHarness.SIZE - 1), data.consumeInt(0, 4), data.consumeInt(0, 15))
        }
    }
}
