// ============================================================================
// S4RuntimeJasperTest —— S4-B(J) 协程运行时骨架的真实编译 + 执行验收
// ============================================================================
// 所属层次：compiler 模块的端到端测试层。
// 做什么：每一 case 都把 Jasper 标准库与一段纯 Jasper 测试源码一起编译，加载生成的
//   `std.main` 并真实执行；断言最终返回码，从而覆盖状态机、取消、作用域聚合、上下文
//   白名单、清理预算与 JVM 适配器的运行期行为，而不是只看编译是否通过。
// 前置条件：构建虚拟机可运行 JDK 标准库；所有 scratch 目录都位于模块 `build/` 下。
// 失败模式：编译失败会把聚合诊断带进断言信息；运行期异常/错误返回码由 main 的
//   `int32` 返回值表达，避免测试进程被未捕获错误吞掉。
// 为什么用纯 Jasper 测试源码：任务书要求运行时与标准库逻辑用 Jasper 编写；Kotlin 只
//   承担测试驱动与类加载，不实现任何 Future/TaskGroup 状态迁移。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 做什么：为 S4-B(J) 运行时骨架提供「编译标准库 + 执行纯 Jasper main」的测试夹具。
 *
 * 什么时候用：修改 Future/TaskGroup/Context/CleanupBudget/CancellationToken/
 *   JvmExecutor 的状态语义、并发顺序或平台桥接时。
 * 前置条件：每个用例的 Jasper 源码都以 `package std;` 开头并声明 `main(): int32`。
 * 失败模式：编译错误、类加载错误或 main 返回非 0 都由断言报告。
 * 为什么 main 返回 int32：Jasper 的 `Error` 是 throw-only 控制流载体；测试用返回码
 *   区分具体失败分支，不需要把错误对象塞进返回值。
 */
class S4RuntimeJasperTest {

    private companion object {
        /** 本次测试 JVM 内第一次写 evidence 时清空旧文件，避免跨 VM 运行累积。 */
        val resultsCleared = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    /** 创建并清空模块内的 scratch 目录（绝不使用仓库外临时路径）。 */
    private fun freshOutputDir(name: String): Path {
        val base = Paths.get("build", "tmp", "s4-runtime-tests", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    /**
     * 做什么：以标准库为基线编译 [source]，加载 `std.main` 并调用 `main()`。
     * 什么时候用：所有 S4-B(J) 运行时用例。
     * 前置条件：source 是完整 Jasper 程序；输出目录可写。
     * 失败模式：编译失败时以聚合诊断断言失败；运行期异常原样传播到测试。
     * 为什么先加载再调用：只有真正链接 `Future`/`TaskGroup` 等生成类，才能暴露
     *   描述符、桥接或泛型擦除错误。
     */
    private fun runMain(name: String, source: String): Any? {
        val outputDir = freshOutputDir(name)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Main.jas" to source), outputDir)
        if (result is JasperCompiler.CompileResult.Error) {
            record(name, "COMPILE: " + result.message)
        }
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " +
                ((result as? JasperCompiler.CompileResult.Error)?.message ?: result.toString())
        )
        return try {
            URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
                val target = loader.loadClass("std.main").getMethod("main")
                target.invoke(null)
            }
        } catch (t: Throwable) {
            val sw = java.io.StringWriter()
            (t.cause ?: t).printStackTrace(java.io.PrintWriter(sw))
            record(name, "THROWN: $t" + System.lineSeparator() + sw.toString())
            throw t
        }
    }

    /** 把每个用例的真实返回码写到 evidence，便于在构建虚拟机上定位非 0 分支。 */
    private fun record(name: String, value: Any?) {
        val file = Paths.get("..", "evidence", "s4-runtime-results.txt")
        Files.createDirectories(file.parent)
        if (resultsCleared.compareAndSet(false, true)) {
            Files.deleteIfExists(file)
        }
        Files.writeString(
            file,
            "$name => $value" + System.lineSeparator(),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND
        )
    }

    /** 断言 main 返回 0（成功），失败时附带用例名便于定位。 */
    private fun assertOk(name: String, source: String) {
        val actual = runMain(name, source)
        record(name, actual)
        assertEquals(0, actual, "runtime acceptance failed for $name")
    }

    /**
     * 做什么：验证 Future 的终态恰好发布一次，且成功值快照稳定。
     * 什么时候用：修改 Future.finish 或 complete 时。
     * 前置条件：无。
     * 失败模式：第二次 complete 覆盖结果、状态未进入 Completed 或 get 读错值。
     */
    @Test
    fun `future publishes exactly one successful terminal state`() {
        assertOk(
            "future-double-completion",
            """
            package std;
            public function main(): int32 {
                var f: Future<int32> = new Future<int32>();
                var first: bool = f.complete(41);
                var second: bool = f.complete(99);
                if (!first) { return 1; }
                if (second) { return 2; }
                if (f.status() != FutureStatus.Completed) { return 3; }
                if (f.get() != 41) { return 4; }
                if (!f.isDone()) { return 5; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证失败/取消两条终态通道互斥，失败可确认且 get 抛对应错误。
     * 什么时候用：修改 Future.fail/cancel/acknowledgeFailure/get 时。
     * 前置条件：无。
     * 失败模式：失败被后续 complete 覆盖、acknowledgeFailure 可重复成功、错误类型混淆。
     */
    @Test
    fun `future failure and cancellation are distinct terminal channels`() {
        assertOk(
            "future-failure-cancel",
            """
            package std;
            public function main(): int32 {
                var failed: Future<int32> = new Future<int32>();
                if (!failed.fail(new Failure("boom", "error"))) { return 1; }
                if (failed.complete(1)) { return 2; }
                if (failed.failureOrNull() == null) { return 3; }
                if (!failed.acknowledgeFailure()) { return 4; }
                if (failed.acknowledgeFailure()) { return 5; }
                try {
                    var ignoredFirst: int32 = failed.get();
                    return 6;
                } catch (e: FutureFailureError) {
                    var observed: int32 = 0;
                }

                var cancelled: Future<int32> = new Future<int32>();
                if (!cancelled.cancel()) { return 7; }
                if (cancelled.complete(1)) { return 8; }
                if (cancelled.failureOrNull() != null) { return 9; }
                try {
                    var ignoredSecond: int32 = cancelled.get();
                    return 10;
                } catch (e: FutureCancelledError) {
                    var observed: int32 = 0;
                }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证 onComplete 在 Pending 时登记、终态后立即回调，且只回调一次。
     * 什么时候用：修改 Future.onComplete/finish 的回调顺序时。
     * 前置条件：无。
     * 失败模式：回调丢失、重复、或在注册时已终态却不立即执行。
     */
    @Test
    fun `future completion callbacks run once for pending and late registration`() {
        assertOk(
            "future-callbacks",
            """
            package std;

            public class Capture implements Action<FutureOutcome> {
                private var countValue: int32;
                private var lastStatus: FutureStatus;
                public constructor() {
                    this.countValue = 0;
                    this.lastStatus = FutureStatus.Pending;
                }
                public function accept(outcome: FutureOutcome): void {
                    this.countValue = this.countValue + 1;
                    this.lastStatus = outcome.status();
                }
                public function count(): int32 { return this.countValue; }
                public function last(): FutureStatus { return this.lastStatus; }
            }

            public function main(): int32 {
                var f: Future<int32> = new Future<int32>();
                var early: Capture = new Capture();
                f.onComplete(early);
                if (early.count() != 0) { return 1; }
                f.complete(3);
                if (early.count() != 1) { return 2; }
                if (early.last() != FutureStatus.Completed) { return 3; }
                var late: Capture = new Capture();
                f.onComplete(late);
                if (late.count() != 1) { return 4; }
                if (late.last() != FutureStatus.Completed) { return 5; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /** 调度拒绝后的级联通知失败仍须给从未运行的子任务标记 settled。 */
    @Test
    fun `rejected task settles even when sibling cancellation callback throws`() {
        assertOk(
            "task-group-rejection-callback-failure",
            """
            package std;
            public class RejectSecond implements Executor {
                private var task: Runnable?;
                private var count: int32;
                public constructor() { this.task = null; this.count = 0; }
                public function schedule(task: Runnable): void {
                    this.count = this.count + 1;
                    if (this.count > 1) { throw new Error("schedule rejected"); }
                    this.task = task;
                }
                public function shutdown(): void { }
                public function drain(): void {
                    var queued: Runnable = cast this.task as Runnable;
                    queued.run();
                }
            }
            public class RejectedSiblingHandler implements Action<FutureOutcome> {
                public function accept(value: FutureOutcome): void { throw new Error("notification failed"); }
            }
            public class EmptyBody implements TaskBody<int32> {
                public function run(token: CancellationToken): int32 { return 7; }
            }
            public function main(): int32 {
                var executor: RejectSecond = new RejectSecond();
                var group: TaskGroup = new TaskGroup(false);
                var first: Future<int32> = group.spawn(executor, new EmptyBody());
                first.onComplete(new RejectedSiblingHandler());
                try {
                    group.spawn(executor, new EmptyBody());
                    return 1;
                } catch (error: CallbackDispatchError) {
                    if (first.status() != FutureStatus.Cancelled) { return 2; }
                }
                executor.drain();
                try {
                    group.close();
                    return 3;
                } catch (error: TaskGroupFailure) {
                    if (error.suppressedCount() != 0) { return 4; }
                }
                return 0;
            }
            """.trimIndent()
        )
    }

    /** 失败通知抛错仍须取消兄弟，并在最终标记 settled 后允许 close 返回。 */
    @Test
    fun `task group settles and cancels siblings despite callback failures`() {
        assertOk(
            "task-group-callback-failure",
            """
            package std;
            public class ManualExecutor implements Executor {
                private var tasks: MutableList<Runnable>;
                public constructor() { this.tasks = new MutableList<Runnable>(); }
                public function schedule(task: Runnable): void { this.tasks.add(task); }
                public function shutdown(): void { }
                public function runAt(index: int32): void { this.tasks.get(index).run(); }
            }
            public class BadOutcome implements Action<FutureOutcome> {
                public function accept(value: FutureOutcome): void { throw new Error("bad outcome"); }
            }
            public class BadCancellation implements Action<CancellationToken> {
                public function accept(value: CancellationToken): void { throw new Error("bad cancellation"); }
            }
            public class FailWithCancellationHook implements TaskBody<int32> {
                public function run(token: CancellationToken): int32 {
                    token.onCancel(new BadCancellation());
                    throw new Error("business failure");
                }
            }
            public class ObserveCancelled implements TaskBody<int32> {
                public function run(token: CancellationToken): int32 {
                    if (!token.isCancelled()) { throw new Error("sibling missed cancellation"); }
                    return 7;
                }
            }
            public function main(): int32 {
                var executor: ManualExecutor = new ManualExecutor();
                var group: TaskGroup = new TaskGroup(false);
                var first: Future<int32> = group.spawn(executor, new FailWithCancellationHook());
                var second: Future<int32> = group.spawn(executor, new ObserveCancelled());
                if (first.isDone()) { return 6; }
                if (second.isDone()) { return 7; }
                first.onComplete(new BadOutcome());
                second.onComplete(new BadOutcome());
                try {
                    executor.runAt(0);
                    return 1;
                } catch (error: CallbackDispatchError) {
                    if (first.status() != FutureStatus.Failed) { return 2; }
                    if (second.status() != FutureStatus.Cancelled) { return 3; }
                }
                executor.runAt(1);
                try {
                    group.close();
                    return 4;
                } catch (error: TaskGroupFailure) {
                    if (error.message() != "business failure") { return 5; }
                }
                return 0;
            }
            """.trimIndent()
        )
    }

    /** 回调失败不能中断同批后续通知，也不能阻断父子取消传播。 */
    @Test
    fun `throwing callbacks do not drop completion or cancellation notifications`() {
        assertOk(
            "callback-failure-isolation",
            """
            package std;
            public class OutcomeCounter implements Action<FutureOutcome> {
                public var count: int32 = 0;
                public function accept(value: FutureOutcome): void { this.count = this.count + 1; }
            }
            public class ThrowOutcome implements Action<FutureOutcome> {
                public function accept(value: FutureOutcome): void {
                    throw new Error("completion callback failed");
                }
            }
            public class ThrowCancel implements Action<CancellationToken> {
                public function accept(value: CancellationToken): void {
                    throw new Error("cancellation callback failed");
                }
            }
            public function main(): int32 {
                var f: Future<int32> = new Future<int32>();
                var counter: OutcomeCounter = new OutcomeCounter();
                f.onComplete(new ThrowOutcome());
                f.onComplete(counter);
                f.onComplete(new ThrowOutcome());
                try {
                    f.complete(42);
                    return 1;
                } catch (error: CallbackDispatchError) {
                    if (counter.count != 1) { return 2; }
                    if (error.failureCount() != 2) { return 10; }
                }
                if (f.get() != 42) { return 3; }
                if (f.complete(99)) { return 4; }
                if (counter.count != 1) { return 5; }
                var parent: CancellationToken = new CancellationToken();
                parent.onCancel(new ThrowCancel());
                var child: CancellationToken = parent.createChild();
                parent.onCancel(new ThrowCancel());
                try {
                    parent.cancel();
                    return 6;
                } catch (error: CallbackDispatchError) {
                    if (error.failureCount() != 2) { return 11; }
                    if (!parent.isCancelled()) { return 7; }
                    if (!child.isCancelled()) { return 8; }
                }
                if (parent.cancel()) { return 9; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证并发 complete 竞争下恰好一个生产者成功、终态唯一。
     * 什么时候用：修改 Future 的互斥/终态判定时。
     * 前置条件：JVM 固定线程池可用。
     * 失败模式：两个 complete 都返回 true、结果被覆盖或终态仍为 Pending。
     */
    @Test
    fun `future double completion race has exactly one winner`() {
        assertOk(
            "future-completion-race",
            """
            package std;

            public class RaceResult {
                private var mutex: java.util.concurrent.Semaphore;
                private var wins: int32;
                public constructor() {
                    this.mutex = new java.util.concurrent.Semaphore(1);
                    this.wins = 0;
                }
                public function mark(): void {
                    this.mutex.acquireUninterruptibly();
                    this.wins = this.wins + 1;
                    this.mutex.release();
                }
                public function wins(): int32 {
                    this.mutex.acquireUninterruptibly();
                    var value: int32 = this.wins;
                    this.mutex.release();
                    return value;
                }
            }

            public class Signal {
                private var semaphore: java.util.concurrent.Semaphore;
                public constructor() {
                    this.semaphore = new java.util.concurrent.Semaphore(0);
                }
                public function fire(): void { this.semaphore.release(); }
                public function waitOne(): void { this.semaphore.acquireUninterruptibly(); }
            }

            public class CompleteTask implements Runnable {
                private var future: Future<int32>;
                private var value: int32;
                private var result: RaceResult;
                private var done: Signal;
                public constructor(future: Future<int32>, value: int32, result: RaceResult, done: Signal) {
                    this.future = future;
                    this.value = value;
                    this.result = result;
                    this.done = done;
                }
                public function run(): void {
                    if (this.future.complete(this.value)) {
                        this.result.mark();
                    }
                    this.done.fire();
                }
            }

            public function main(): int32 {
                var future: Future<int32> = new Future<int32>();
                var result: RaceResult = new RaceResult();
                var done: Signal = new Signal();
                var executor: JvmExecutor = new JvmExecutor(2);
                executor.schedule(new CompleteTask(future, 1, result, done));
                executor.schedule(new CompleteTask(future, 2, result, done));
                done.waitOne();
                done.waitOne();
                executor.shutdown();
                if (!executor.awaitTermination(2000L)) { return 1; }
                if (result.wins() != 1) { return 2; }
                if (future.status() != FutureStatus.Completed) { return 3; }
                var observed: int32 = future.get();
                if (observed != 1 && observed != 2) { return 4; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证 cancel 与 complete 竞争时只有一个终态获胜，且取消通道不被伪装成成功。
     * 什么时候用：修改 Future.finish 的终态互斥或取消优先级时。
     * 前置条件：JVM 固定线程池可用。
     * 失败模式：两个操作都成功、状态仍 Pending、或取消后 get 返回成功值。
     */
    @Test
    fun `future cancel and complete race has one observable outcome`() {
        assertOk(
            "future-cancel-complete-race",
            """
            package std;

            public class RaceResult {
                private var mutex: java.util.concurrent.Semaphore;
                private var wins: int32;
                public constructor() {
                    this.mutex = new java.util.concurrent.Semaphore(1);
                    this.wins = 0;
                }
                public function mark(): void {
                    this.mutex.acquireUninterruptibly();
                    this.wins = this.wins + 1;
                    this.mutex.release();
                }
                public function wins(): int32 {
                    this.mutex.acquireUninterruptibly();
                    var value: int32 = this.wins;
                    this.mutex.release();
                    return value;
                }
            }

            public class Signal {
                private var semaphore: java.util.concurrent.Semaphore;
                public constructor() { this.semaphore = new java.util.concurrent.Semaphore(0); }
                public function fire(): void { this.semaphore.release(); }
                public function waitOne(): void { this.semaphore.acquireUninterruptibly(); }
            }

            public class CancelOrComplete implements Runnable {
                private var future: Future<int32>;
                private var completeValue: bool;
                private var value: int32;
                private var result: RaceResult;
                private var done: Signal;
                public constructor(future: Future<int32>, completeValue: bool, value: int32, result: RaceResult, done: Signal) {
                    this.future = future;
                    this.completeValue = completeValue;
                    this.value = value;
                    this.result = result;
                    this.done = done;
                }
                public function run(): void {
                    if (this.completeValue) {
                        if (this.future.complete(this.value)) { this.result.mark(); }
                    } else {
                        if (this.future.cancel()) { this.result.mark(); }
                    }
                    this.done.fire();
                }
            }

            public function main(): int32 {
                var future: Future<int32> = new Future<int32>();
                var result: RaceResult = new RaceResult();
                var done: Signal = new Signal();
                var executor: JvmExecutor = new JvmExecutor(2);
                executor.schedule(new CancelOrComplete(future, true, 7, result, done));
                executor.schedule(new CancelOrComplete(future, false, 0, result, done));
                done.waitOne();
                done.waitOne();
                executor.shutdown();
                var firstWait: bool = executor.awaitTermination(2000L);
                if (!firstWait) {
                    var secondWait: bool = executor.awaitTermination(2000L);
                    if (secondWait) { return 7; }
                    return 1;
                }
                if (result.wins() != 1) { return 2; }
                var status: FutureStatus = future.status();
                if (status == FutureStatus.Pending) { return 3; }
                if (status == FutureStatus.Completed) {
                    if (future.get() != 7) { return 4; }
                    return 0;
                }
                if (status == FutureStatus.Cancelled) {
                    try {
                        var ignored: int32 = future.get();
                        return 5;
                    } catch (e: FutureCancelledError) {
                        return 0;
                    }
                }
                return 6;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证取消令牌只触发一次、父取消传播到子、子取消不反传、晚注册立即回调。
     * 什么时候用：修改 CancellationToken 的锁协议或 createChild 时。
     * 前置条件：无。
     * 失败模式：重复触发、传播方向错误、或已取消后注册不回放。
     */
    @Test
    fun `cancellation token fires once and propagates parent to child`() {
        assertOk(
            "cancellation-token",
            """
            package std;

            public class CountAction implements Action<CancellationToken> {
                private var countValue: int32;
                public constructor() { this.countValue = 0; }
                public function accept(token: CancellationToken): void {
                    this.countValue = this.countValue + 1;
                }
                public function count(): int32 { return this.countValue; }
            }

            public function main(): int32 {
                var parent: CancellationToken = new CancellationToken();
                var child: CancellationToken = parent.createChild();
                var counter: CountAction = new CountAction();
                parent.onCancel(counter);
                if (parent.isCancelled()) { return 1; }
                if (!parent.cancel()) { return 2; }
                if (counter.count() != 1) { return 3; }
                if (!child.isCancelled()) { return 4; }
                if (parent.cancel()) { return 5; }
                if (counter.count() != 1) { return 6; }
                var late: CountAction = new CountAction();
                parent.onCancel(late);
                if (late.count() != 1) { return 7; }
                var laterChild: CancellationToken = parent.createChild();
                if (!laterChild.isCancelled()) { return 8; }

                var root2: CancellationToken = new CancellationToken();
                var child2: CancellationToken = root2.createChild();
                child2.cancel();
                if (root2.isCancelled()) { return 9; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证普通 TaskGroup 的首个业务失败取消兄弟，close 等待执行体退出并聚合失败。
     * 什么时候用：修改 TaskGroup.spawn/close/onChildFailure 或 TaskRunner 时。
     * 前置条件：JvmExecutor 固定线程池可调度至少 2 个任务。
     * 失败模式：兄弟未被取消、close 提前返回、失败未聚合、任务体未回收。
     */
    @Test
    fun `task group failure cancels siblings and joins their bodies`() {
        assertOk(
            "task-group-normal",
            """
            package std;

            public class Flag {
                private var seenValue: bool;
                public constructor() { this.seenValue = false; }
                public function mark(): void { this.seenValue = true; }
                public function seen(): bool { return this.seenValue; }
            }

            public class FailBody implements TaskBody<int32> {
                public function run(token: CancellationToken): int32 {
                    throw new RecoverableError("boom");
                }
            }

            public class WaitBody implements TaskBody<int32> {
                private var flag: Flag;
                public constructor(flag: Flag) { this.flag = flag; }
                public function run(token: CancellationToken): int32 {
                    var spins: int32 = 0;
                    while (!token.isCancelled()) {
                        spins = spins + 1;
                        if (spins > 100000000) { return -1; }
                    }
                    this.flag.mark();
                    return 0;
                }
            }

            public function main(): int32 {
                var executor: JvmExecutor = new JvmExecutor(2);
                var group: TaskGroup = new TaskGroup(false);
                var flag: Flag = new Flag();
                var failing: TaskBody<int32> = new FailBody();
                var waiting: TaskBody<int32> = new WaitBody(flag);
                var failedFuture: Future<int32> = group.spawn(executor, failing);
                var waitingFuture: Future<int32> = group.spawn(executor, waiting);
                var closeThrew: bool = false;
                try {
                    group.close();
                } catch (e: TaskGroupFailure) {
                    closeThrew = true;
                }
                executor.shutdown();
                var terminated: bool = executor.awaitTermination(2000L);
                if (!closeThrew) { return 1; }
                if (!terminated) { return 2; }
                if (!flag.seen()) { return 3; }
                if (failedFuture.status() != FutureStatus.Failed) { return 4; }
                if (waitingFuture.status() != FutureStatus.Cancelled) { return 5; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证监督组 B 策略——已确认失败被排除，兄弟不被取消。
     * 什么时候用：修改 TaskGroup.close 的聚合过滤或 acknowledgeFailure 语义时。
     * 前置条件：JvmExecutor 固定线程池可调度 2 个任务。
     * 失败模式：确认后仍聚合、兄弟被取消、成功值丢失。
     */
    @Test
    fun `supervisor excludes acknowledged failure and keeps siblings running`() {
        assertOk(
            "task-group-supervisor-ack",
            """
            package std;

            public class Flag {
                private var seenValue: bool;
                public constructor() { this.seenValue = false; }
                public function mark(): void { this.seenValue = true; }
                public function seen(): bool { return this.seenValue; }
            }

            public class FailBody implements TaskBody<int32> {
                public function run(token: CancellationToken): int32 {
                    throw new RecoverableError("boom");
                }
            }

            public class SuccessBody implements TaskBody<int32> {
                private var flag: Flag;
                public constructor(flag: Flag) { this.flag = flag; }
                public function run(token: CancellationToken): int32 {
                    this.flag.mark();
                    return 5;
                }
            }

            public function main(): int32 {
                var executor: JvmExecutor = new JvmExecutor(2);
                var group: TaskGroup = new TaskGroup(true);
                var flag: Flag = new Flag();
                var failing: TaskBody<int32> = new FailBody();
                var succeeding: TaskBody<int32> = new SuccessBody(flag);
                var failedFuture: Future<int32> = group.spawn(executor, failing);
                var successFuture: Future<int32> = group.spawn(executor, succeeding);
                var sawFailure: bool = false;
                try {
                    var ignored: int32 = failedFuture.get();
                } catch (e: FutureFailureError) {
                    sawFailure = true;
                }
                if (!sawFailure) { return 1; }
                if (!failedFuture.acknowledgeFailure()) { return 2; }
                var closeThrew: bool = false;
                try {
                    group.close();
                } catch (e: TaskGroupFailure) {
                    closeThrew = true;
                }
                executor.shutdown();
                var firstWait: bool = executor.awaitTermination(2000L);
                if (!firstWait) {
                    var secondWait: bool = executor.awaitTermination(2000L);
                    if (secondWait) { return 7; }
                    return 3;
                }
                if (closeThrew) { return 4; }
                if (!flag.seen()) { return 5; }
                if (successFuture.get() != 5) { return 6; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证未确认失败被聚合，且首次 close 冻结的聚合结果不会被事后确认改写。
     * 什么时候用：修改 TaskGroup 聚合快照或 acknowledgeFailure 的排序约束时。
     * 前置条件：JvmExecutor 固定线程池可调度 2 个任务。
     * 失败模式：未确认失败被静默丢弃、第二次 close 因确认而不再抛错。
     */
    @Test
    fun `supervisor aggregates unacknowledged failure and freezes the snapshot`() {
        assertOk(
            "task-group-supervisor-freeze",
            """
            package std;

            public class FailBody implements TaskBody<int32> {
                public function run(token: CancellationToken): int32 {
                    throw new RecoverableError("boom");
                }
            }

            public class SuccessBody implements TaskBody<int32> {
                public function run(token: CancellationToken): int32 { return 5; }
            }

            public function main(): int32 {
                var executor: JvmExecutor = new JvmExecutor(2);
                var group: TaskGroup = new TaskGroup(true);
                var failing: TaskBody<int32> = new FailBody();
                var succeeding: TaskBody<int32> = new SuccessBody();
                var failedFuture: Future<int32> = group.spawn(executor, failing);
                var successFuture: Future<int32> = group.spawn(executor, succeeding);
                var sawFailure: bool = false;
                try {
                    var ignored: int32 = failedFuture.get();
                } catch (e: FutureFailureError) {
                    sawFailure = true;
                }
                if (!sawFailure) { return 1; }
                var firstThrew: bool = false;
                try {
                    group.close();
                } catch (e: TaskGroupFailure) {
                    firstThrew = true;
                }
                if (!firstThrew) { return 2; }
                if (!failedFuture.acknowledgeFailure()) { return 3; }
                var secondThrew: bool = false;
                try {
                    group.close();
                } catch (e: TaskGroupFailure) {
                    secondThrew = true;
                }
                executor.shutdown();
                if (!executor.awaitTermination(2000L)) { return 4; }
                if (!secondThrew) { return 5; }
                if (successFuture.get() != 5) { return 6; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证 Context 的不可变快照、白名单派生、SnapshotCopy 深复制与未知键拒绝。
     * 什么时候用：修改 Context.with/derive/get 的传播判定时。
     * 前置条件：无。
     * 失败模式：父快照被修改、未授权键泄漏、复制策略仍共享同一对象、缺失键抛错。
     */
    @Test
    fun `context snapshots enforce whitelist propagation and copy policy`() {
        assertOk(
            "context-whitelist",
            """
            package std;

            public class MyValue implements ContextValue {
                private var policyValue: ContextSharePolicy;
                private var numberValue: int32;
                public constructor(policyValue: ContextSharePolicy, numberValue: int32) {
                    this.policyValue = policyValue;
                    this.numberValue = numberValue;
                }
                public function sharePolicy(): ContextSharePolicy { return this.policyValue; }
                public function copyForChild(): ContextValue {
                    return new MyValue(this.policyValue, this.numberValue);
                }
                public function number(): int32 { return this.numberValue; }
                public function bump(): void { this.numberValue = this.numberValue + 1; }
            }

            public function main(): int32 {
                var sharedKey: ContextKey<MyValue> = new ContextKey<MyValue>("shared", true);
                var secretKey: ContextKey<MyValue> = new ContextKey<MyValue>("secret", false);
                var copyKey: ContextKey<MyValue> = new ContextKey<MyValue>("copy", true);
                var unknownKey: ContextKey<MyValue> = new ContextKey<MyValue>("unknown", true);

                var base: Context = new Context();
                var original: MyValue = new MyValue(ContextSharePolicy.Immutable, 7);
                var copySource: MyValue = new MyValue(ContextSharePolicy.SnapshotCopy, 11);
                var bound: Context = base.with(sharedKey, original);
                bound = bound.with(secretKey, new MyValue(ContextSharePolicy.Immutable, 9));
                bound = bound.with(copyKey, copySource);
                if (base.size() != 0) { return 1; }
                if (bound.size() != 3) { return 2; }

                var readBack: MyValue? = bound.get(sharedKey);
                if (readBack == null) { return 3; }
                if (readBack.number() != 7) { return 4; }
                if (bound.get(unknownKey) != null) { return 5; }

                var child: Context = bound.derive();
                if (child.has(secretKey)) { return 6; }
                if (!child.has(sharedKey)) { return 7; }
                var childShared: MyValue? = child.get(sharedKey);
                if (childShared == null) { return 8; }
                var childCopy: MyValue? = child.get(copyKey);
                if (childCopy == null) { return 9; }
                if (childCopy.number() != 11) { return 10; }

                copySource.bump();
                var parentCopy: MyValue? = bound.get(copyKey);
                if (parentCopy == null) { return 11; }
                if (parentCopy.number() != 12) { return 12; }
                if (childCopy.number() != 11) { return 13; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证 Unsafe 值与非 ContextValue 值在写入阶段就被拒绝。
     * 什么时候用：修改 Context.with 的安全检查时。
     * 前置条件：无。
     * 失败模式：不安全值进入快照、或错误类型不是可捕获的 ContextError。
     */
    @Test
    fun `context rejects unsafe and non context values`() {
        assertOk(
            "context-rejection",
            """
            package std;

            public class MyValue implements ContextValue {
                private var policyValue: ContextSharePolicy;
                public constructor(policyValue: ContextSharePolicy) { this.policyValue = policyValue; }
                public function sharePolicy(): ContextSharePolicy { return this.policyValue; }
                public function copyForChild(): ContextValue { return new MyValue(this.policyValue); }
            }

            public function main(): int32 {
                var base: Context = new Context();
                var unsafeKey: ContextKey<MyValue> = new ContextKey<MyValue>("unsafe", true);
                var unsafeRejected: bool = false;
                try {
                    var ignoredUnsafe: Context = base.with(unsafeKey, new MyValue(ContextSharePolicy.Unsafe));
                } catch (e: ContextError) {
                    unsafeRejected = true;
                }
                if (!unsafeRejected) { return 1; }

                var numericKey: ContextKey<int32> = new ContextKey<int32>("numeric", true);
                var numericRejected: bool = false;
                try {
                    var ignoredNumeric: Context = base.with(numericKey, 3);
                } catch (e: ContextError) {
                    numericRejected = true;
                }
                if (!numericRejected) { return 2; }
                if (base.size() != 0) { return 3; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：用固定种子在 Jasper 层循环生成 64 组键/策略，验证白名单传播只保留
     *   定义处声明可传播的键，且未授权键不会因循环偶然命中而泄漏。
     * 什么时候用：修改 Context.with/derive 的传播判定或键权限来源时。
     * 前置条件：无；种子与轮次固定，失败可复现。
     * 失败模式：派生快照键数不符、未授权键出现、授权键缺失或读回值错位。
     * 为什么用循环生成：单点用例容易只覆盖手写组合；固定种子在 Jasper 内生成
     *   多组键名、布尔权限与共享策略，能更早暴露传播分支的漏判。
     */
    @Test
    fun `context whitelist property holds for seeded keys`() {
        assertOk(
            "context-whitelist-property",
            """
            package std;

            public class SeedValue implements ContextValue {
                private var policyValue: ContextSharePolicy;
                private var numberValue: int32;
                public constructor(policyValue: ContextSharePolicy, numberValue: int32) {
                    this.policyValue = policyValue;
                    this.numberValue = numberValue;
                }
                public function sharePolicy(): ContextSharePolicy { return this.policyValue; }
                public function copyForChild(): ContextValue {
                    return new SeedValue(this.policyValue, this.numberValue);
                }
                public function number(): int32 { return this.numberValue; }
            }

            public function main(): int32 {
                var state: int32 = 20261024;
                var keys: MutableList<ContextKey<SeedValue>> =
                    new MutableList<ContextKey<SeedValue>>();
                var bound: Context = new Context();
                var allowed: int32 = 0;
                var i: int32 = 0;
                while (i < 64) {
                    state = state * 1103515245 + 12345;
                    var bucket: int32 = state % 17;
                    if (bucket < 0) { bucket = 0 - bucket; }
                    var propagates: bool = bucket != 0;
                    var policy: ContextSharePolicy = ContextSharePolicy.Immutable;
                    if (bucket == 3) { policy = ContextSharePolicy.SnapshotCopy; }
                    var key: ContextKey<SeedValue> =
                        new ContextKey<SeedValue>("k" + i, propagates);
                    var value: SeedValue = new SeedValue(policy, i);
                    keys.add(key);
                    bound = bound.with(key, value);
                    if (propagates) { allowed = allowed + 1; }
                    i = i + 1;
                }
                if (bound.size() != 64) { return 1; }
                var child: Context = bound.derive();
                if (child.size() != allowed) { return 2; }
                i = 0;
                while (i < 64) {
                    var lookupKey: ContextKey<SeedValue> = keys.get(i);
                    if (child.has(lookupKey) != lookupKey.propagates()) { return 3; }
                    if (lookupKey.propagates()) {
                        var readBack: SeedValue? = child.get(lookupKey);
                        if (readBack == null) { return 4; }
                        if (readBack.number() != i) { return 5; }
                    }
                    i = i + 1;
                }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证清理预算取全局/局部较小值、嵌套只能收紧、负数配置被拒绝。
     * 什么时候用：修改 CleanupBudget.start/nested/remainingNanos 时。
     * 前置条件：JVM 单调时钟可用。
     * 失败模式：局部上限突破全局、嵌套重置计时、负数被静默接受。
     */
    @Test
    fun `cleanup budget takes minimum and never extends on nesting`() {
        assertOk(
            "cleanup-budget",
            """
            package std;
            public function main(): int32 {
                var zero: int64 = 0L;
                var oneSecond: int64 = 1000000000L;
                var fiveSeconds: int64 = 5000000000L;
                var negative: int64 = -1L;

                var expired: CleanupBudget = CleanupBudget.start(oneSecond, zero);
                if (!expired.isExpired()) { return 1; }
                if (expired.hasRemaining()) { return 2; }
                if (expired.remainingNanos() != zero) { return 3; }

                var capped: CleanupBudget = CleanupBudget.start(fiveSeconds, oneSecond);
                var span: int64 = capped.deadlineNanos() - capped.startedAtNanos();
                if (java.lang.Long.compare(span, oneSecond) > 0) { return 4; }
                var child: CleanupBudget = capped.nested(zero);
                if (!child.isExpired()) { return 5; }
                var tightChild: CleanupBudget = capped.nested(fiveSeconds);
                var childSpan: int64 = tightChild.deadlineNanos() - tightChild.startedAtNanos();
                if (java.lang.Long.compare(childSpan, span) > 0) { return 6; }

                var startRejected: bool = false;
                try {
                    var ignoredStart: CleanupBudget = CleanupBudget.start(negative, zero);
                } catch (e: CleanupBudgetError) {
                    startRejected = true;
                }
                if (!startRejected) { return 7; }
                var nestedRejected: bool = false;
                try {
                    var ignoredNested: CleanupBudget = capped.nested(negative);
                } catch (e: CleanupBudgetError) {
                    nestedRejected = true;
                }
                if (!nestedRejected) { return 8; }
                if (CleanupBudget.defaultGlobalNanos() != fiveSeconds) { return 9; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：用固定种子在 Jasper 层循环生成 64 组全局/局部/嵌套上限，验证
     *   根 deadline 恰为 min(全局, 局部)、子 deadline 不晚于父、也不突破子局部上限。
     * 什么时候用：修改 CleanupBudget.start/nested 的 deadline 公式时。
     * 前置条件：JVM 单调时钟可用；种子与轮次固定，失败可复现。
     * 失败模式：根上限没有取最小、嵌套延长父截止点、嵌套突破自身上限。
     * 为什么用循环生成：时间类边界若只测 0/1s/5s 容易漏掉“父剩余小于子局部上限”
     *   的组合；固定种子覆盖两者大小关系交替出现的分支。
     */
    @Test
    fun `cleanup budget property holds for seeded limits`() {
        assertOk(
            "cleanup-budget-property",
            """
            package std;
            public function main(): int32 {
                var state: int32 = 424242;
                var i: int32 = 0;
                while (i < 64) {
                    state = state * 1103515245 + 12345;
                    var globalSeed: int32 = state % 101;
                    if (globalSeed < 0) { globalSeed = 0 - globalSeed; }
                    state = state * 1103515245 + 12345;
                    var localSeed: int32 = state % 101;
                    if (localSeed < 0) { localSeed = 0 - localSeed; }

                    var global: int64 = globalSeed;
                    global = (global + 1L) * 1000000L;
                    var local: int64 = localSeed;
                    local = (local + 1L) * 1000000L;

                    var budget: CleanupBudget = CleanupBudget.start(global, local);
                    var expected: int64 = java.lang.Math.min(global, local);
                    var span: int64 =
                        budget.deadlineNanos() - budget.startedAtNanos();
                    if (java.lang.Long.compare(span, expected) != 0) { return 1; }
                    if (java.lang.Long.compare(span, global) > 0) { return 2; }
                    if (java.lang.Long.compare(span, local) > 0) { return 3; }

                    state = state * 1103515245 + 12345;
                    var nestedSeed: int32 = state % 53;
                    if (nestedSeed < 0) { nestedSeed = 0 - nestedSeed; }
                    var nestedLimit: int64 = nestedSeed;
                    nestedLimit = (nestedLimit + 1L) * 1000L;
                    var child: CleanupBudget = budget.nested(nestedLimit);
                    var afterNested: int64 = java.lang.System.nanoTime();
                    var childDeadline: int64 = child.deadlineNanos();
                    var parentDeadline: int64 = budget.deadlineNanos();
                    var latestDeadline: int64 = afterNested + nestedLimit;
                    var childSpan: int64 =
                        childDeadline - child.startedAtNanos();
                    if (java.lang.Long.compare(childSpan, span) > 0) { return 4; }
                    if (java.lang.Long.compare(childDeadline, parentDeadline) > 0) { return 5; }
                    if (java.lang.Long.compare(childDeadline, latestDeadline) > 0) { return 6; }

                    var zeroChild: CleanupBudget = budget.nested(0L);
                    if (!zeroChild.isExpired()) { return 7; }
                    i = i + 1;
                }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证 JvmExecutor 真正把 Runnable 交给线程池，并能 shutdown/awaitTermination。
     * 什么时候用：修改 JvmExecutor 的平台桥接或线程池生命周期时。
     * 前置条件：JVM 固定线程池可用。
     * 失败模式：任务未执行、shutdown 后无法终止、isTerminated 失真。
     */
    @Test
    fun `jvm executor runs a runnable and terminates cleanly`() {
        assertOk(
            "jvm-executor",
            """
            package std;

            public class Signal {
                private var semaphore: java.util.concurrent.Semaphore;
                public constructor() { this.semaphore = new java.util.concurrent.Semaphore(0); }
                public function fire(): void { this.semaphore.release(); }
                public function waitOne(): void { this.semaphore.acquireUninterruptibly(); }
            }

            public class RecordTask implements Runnable {
                private var signal: Signal;
                public constructor(signal: Signal) { this.signal = signal; }
                public function run(): void { this.signal.fire(); }
            }

            public function main(): int32 {
                var executor: JvmExecutor = new JvmExecutor(1);
                var signal: Signal = new Signal();
                executor.schedule(new RecordTask(signal));
                signal.waitOne();
                executor.shutdown();
                if (!executor.awaitTermination(2000L)) { return 1; }
                if (!executor.isTerminated()) { return 2; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证空线程池在 shutdown 后可被 awaitTermination 观察到终止。
     * 什么时候用：排查 JvmExecutor 的 Java 8 生命周期桥接时。
     * 前置条件：无任务提交。
     * 失败模式：awaitTermination 返回 false 或 isTerminated 与等待结果矛盾。
     */
    @Test
    fun `jvm executor lifecycle is observable after shutdown`() {
        assertOk(
            "jvm-executor-lifecycle",
            """
            package std;
            public function main(): int32 {
                var executor: JvmExecutor = new JvmExecutor(1);
                executor.shutdown();
                var waited: bool = executor.awaitTermination(2000L);
                if (!waited) {
                    if (executor.isTerminated()) { return 2; }
                    return 3;
                }
                if (!executor.isTerminated()) { return 4; }
                return 0;
            }
            """.trimIndent()
        )
    }

    /**
     * 做什么：验证有界队列在满容量时立即拒绝，而不是无界增长或静默丢弃。
     * 什么时候用：评估 S4-B 背压边界或替换 Executor 队列实现时。
     * 前置条件：JVM 提供 `ArrayBlockingQueue`（Java 8）。
     * 失败模式：第二个元素被接受、take 返回错误元素、size 未回落。
     * 为什么单独测：运行时当前没有引入新队列类型；该用例锁定平台适配层可选用的
     *   显式背压原语行为，防止未来把「有界」误写成无界缓存。
     */
    @Test
    fun `bounded platform queue rejects excess items`() {
        assertOk(
            "bounded-queue",
            """
            package std;
            public function main(): int32 {
                var queue: java.util.concurrent.ArrayBlockingQueue<int32> =
                    new java.util.concurrent.ArrayBlockingQueue<int32>(1);
                if (!queue.offer(1)) { return 1; }
                if (queue.offer(2)) { return 2; }
                var taken: any = queue.take();
                var first: int32 = cast taken as int32;
                if (first != 1) { return 3; }
                if (queue.size() != 0) { return 4; }
                return 0;
            }
            """.trimIndent()
        )
    }
}
