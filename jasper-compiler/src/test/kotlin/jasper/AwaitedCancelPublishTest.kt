// ============================================================================
// AwaitedCancelPublishTest.kt —— 任务体未捕获被等待 Future 取消时发布 Cancelled 的验证
// ============================================================================
// 做什么：验证 LANG-S4-AWAIT-CANCEL-PUBLISH-01：spawn 任务体与生成帧未捕获 FutureCancelledError 时发布
//   Cancelled，沿用被等待 Future 的首因（Parent 换成令牌首因）；普通组不算兄弟失败；catch 之后可以正常完成；
//   清理抛出的业务错误不被取消吞掉。分四部分：
//   - 手写用例：确定性脚本（内联执行器）、真实线程上的阻塞等待、经真实编译的生成帧；
//   - 固定种子属性测试：随机脚本驱动真实 TaskGroup，每个子任务与 close 结果和 Kotlin oracle 比对；
//   - Lincheck 模型检查：任务体发布与作用域取消竞争（固定场景 + EpsilonVerifier，非线性化证明）；
//   - Jazzer 覆盖引导 fuzz：与属性测试共用同一 oracle（与 TaskAwaitCancelPublish.tla 同名判定）。
//
// 所属层次：compiler 模块测试层；Kotlin 只提供驱动与判定，取消语义全部在 Jasper 运行时中。
// 前置条件：产物只写模块 build/ 下；所有可能阻塞的调用都有界。
// 失败模式：不变量被破坏时抛 AssertionError，消息含脚本（属性测试）或最小化交错 / 输入。
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
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 编译好的脚本运行时。任务体用 Jasper 写：std.Error 继承受检 Throwable，JDK 动态代理抛出它会被包成
 * UndeclaredThrowableException，TaskRunner 会把取消误记为平台失败。
 */
object AwaitedCancelRuntime {
    /**
     * Scripted 的 kind：0 返回 7；1 等待已取消 Future 且不捕获；2 捕获后返回 5（reason 记入 caughtReason）；
     * 3 不捕获但 finally 抛业务错误；4 抛业务错误；5 自身令牌先以 Deadline 取消再等待（Parent 换算）。
     * close 的结果写成字符串：ok / cancel:<原因> / fail:<消息>。
     */
    private val source = """
        package demo;
        import std.CancellationException;
        import std.CancellationReason;
        import std.CancellationToken;
        import std.Error;
        import std.Executor;
        import std.Future;
        import std.FutureCancelledError;
        import std.Runnable;
        import std.TaskBody;
        import std.TaskGroup;
        import std.TaskGroupFailure;

        public class Scripted implements TaskBody<int32> {
            private var kind: int32;
            private var awaited: Future<int32>;
            private var caught: CancellationReason?;
            private var cleaned: int32;
            public constructor(kind: int32, awaited: Future<int32>) {
                this.kind = kind;
                this.awaited = awaited;
                this.caught = null;
                this.cleaned = 0;
            }
            public function run(token: CancellationToken): int32 {
                if (this.kind == 0) { return 7; }
                if (this.kind == 2) {
                    try {
                        return this.awaited.get();
                    } catch (e: FutureCancelledError) {
                        this.caught = e.reason();
                        return 5;
                    }
                }
                if (this.kind == 3) {
                    try {
                        return this.awaited.get();
                    } finally {
                        this.cleaned = this.cleaned + 1;
                        throw new Error("cleanup");
                    }
                }
                if (this.kind == 4) { throw new Error("business"); }
                if (this.kind == 5) { token.cancelWith(CancellationReason.Deadline); }
                try {
                    return this.awaited.get();
                } finally {
                    this.cleaned = this.cleaned + 1;
                }
            }
            public function caughtReason(): string {
                var seen: CancellationReason? = this.caught;
                if (seen == null) { return "none"; }
                return "" + seen;
            }
            public function cleanedCount(): int32 { return this.cleaned; }
        }

        public class Inline implements Executor {
            public constructor() { }
            public function schedule(task: Runnable): void { task.run(); }
            public function shutdown(): void { }
        }

        public class Scopes {
            public static function close(group: TaskGroup): string {
                try {
                    group.close();
                    return "ok";
                } catch (cancelled: CancellationException) {
                    return "cancel:" + cancelled.reason();
                } catch (failure: TaskGroupFailure) {
                    return "fail:" + failure.message();
                }
            }
            public static function cancelled(reason: CancellationReason): Future<int32> {
                var future: Future<int32> = new Future<int32>();
                future.cancelWith(reason);
                return future;
            }
        }
    """.trimIndent()

    val loader: ClassLoader = CompiledRuntimeLoader.compile("awaited-cancel-runtime", mapOf("AwaitedCancelRuntime.jas" to source))
    val future: Class<*> = loader.loadClass("std.Future")
    val group: Class<*> = loader.loadClass("std.TaskGroup")
    val reasonType: Class<*> = loader.loadClass("std.CancellationReason")
    val executorType: Class<*> = loader.loadClass("std.Executor")
    val bodyType: Class<*> = loader.loadClass("std.TaskBody")
    val tokenType: Class<*> = loader.loadClass("std.CancellationToken")
    val scripted: Class<*> = loader.loadClass("demo.Scripted")
    val scopes: Class<*> = loader.loadClass("demo.Scopes")
    val inline: Any = loader.loadClass("demo.Inline").getConstructor().newInstance()

    fun reason(name: String): Any = reasonType.enumConstants.single { it.toString() == name }
    fun newGroup(supervisor: Boolean): Any = group.getConstructor(Boolean::class.javaPrimitiveType).newInstance(supervisor)
    fun cancelledFuture(reason: String): Any = scopes.getMethod("cancelled", reasonType).invoke(null, reason(reason))
    fun newBody(kind: Int, awaited: Any): Any =
        scripted.getConstructor(Int::class.javaPrimitiveType, future).newInstance(kind, awaited)
    fun spawn(group: Any, executor: Any, body: Any): Any =
        this.group.getMethod("spawn", executorType, bodyType).invoke(group, executor, body)
    fun status(target: Any): String = future.getMethod("status").invoke(target).toString()
    fun reasonOf(target: Any): String? = future.getMethod("cancellationReason").invoke(target)?.toString()
    fun failureOf(target: Any): String? = future.getMethod("failureOrNull").invoke(target)
        ?.let { it.javaClass.getMethod("message").invoke(it) as String }
    fun value(target: Any): Any? = future.getMethod("get").invoke(target)
    fun close(group: Any): String = scopes.getMethod("close", this.group).invoke(null, group) as String
    fun cancelGroup(group: Any) { this.group.getMethod("cancel").invoke(group) }
}

/** 单线程 oracle：一个作用域上按顺序内联执行的子任务，判定与 TaskAwaitCancelPublish.tla 同名。 */
class AwaitedCancelHarness(private val supervisor: Boolean) {
    private val rt = AwaitedCancelRuntime
    private val group = rt.newGroup(supervisor)
    private class Child(val future: Any, val status: String, val reason: String?, val failure: String?, val body: Any, val kind: Int, val caught: String)
    private val children = mutableListOf<Child>()
    private var rootReason: String? = null
    var uncaught = 0
    var cascades = 0
    var cleanupSurfaced = 0
    var mapped = 0

    /** 事件 [op]：0..5 起一个 kind=op 的子任务（被等待 Future 的原因由 [reasonIndex] 选），6 取消作用域。 */
    fun step(op: Int, reasonIndex: Int) {
        if (op == 6) {
            rt.cancelGroup(group)
            if (rootReason == null) rootReason = "Explicit"
            return
        }
        val awaitedReason = REASONS[reasonIndex]
        val body = rt.newBody(op, rt.cancelledFuture(awaitedReason))
        val future = rt.spawn(group, rt.inline, body)
        // 本任务令牌的首因：kind 5 自身以 Deadline 取消；否则沿作用域根令牌。
        val token = rootReason ?: if (op == 5) "Deadline" else null
        fun mappedReason(): String {
            if (awaitedReason == "Parent" && token != null) mapped++
            return if (awaitedReason == "Parent") token ?: "Parent" else awaitedReason
        }
        val child = when (op) {
            0 -> if (token != null) Child(future, "Cancelled", token, null, body, op, "none")
                 else Child(future, "Completed", null, null, body, op, "none")
            1, 5 -> {
                uncaught++
                Child(future, "Cancelled", mappedReason(), null, body, op, "none")
            }
            2 -> if (token != null) Child(future, "Cancelled", token, null, body, op, awaitedReason)
                 else Child(future, "Completed", null, null, body, op, awaitedReason)
            3 -> {
                cleanupSurfaced++
                Child(future, "Failed", null, "cleanup", body, op, "none")
            }
            else -> Child(future, "Failed", null, "business", body, op, "none")
        }
        children += child
        if (child.status == "Failed" && !supervisor && rootReason == null) {
            rootReason = "SiblingFailed"
            cascades++
        }
        check()
    }

    private fun check() {
        children.forEachIndexed { index, child ->
            val label = "child $index kind=${child.kind}"
            assertState(rt.status(child.future) == child.status, "$label status ${rt.status(child.future)} != ${child.status}")
            assertState(rt.reasonOf(child.future) == child.reason, "$label reason ${rt.reasonOf(child.future)} != ${child.reason}")
            assertState(rt.failureOf(child.future) == child.failure, "$label failure ${rt.failureOf(child.future)} != ${child.failure}")
            val caught = rt.scripted.getMethod("caughtReason").invoke(child.body)
            assertState(caught == child.caught, "$label caught $caught != ${child.caught}")
            val cleaned = rt.scripted.getMethod("cleanedCount").invoke(child.body)
            val expected = if (child.kind == 1 || child.kind == 3 || child.kind == 5) 1 else 0
            assertState(cleaned == expected, "$label cleaned $cleaned != $expected")
        }
    }

    /** close 的结果：有失败报失败（首个 + 被压制数），否则有取消报取消（根首因优先，否则创建序最早的子任务）。 */
    fun finish() {
        check()
        val failures = children.mapNotNull { it.failure }
        val cancelled = children.firstOrNull { it.status == "Cancelled" }
        val expected = when {
            failures.isNotEmpty() -> "fail:" + failures.first() + if (failures.size > 1) " (+${failures.size - 1} suppressed)" else ""
            rootReason != null -> "cancel:$rootReason"
            cancelled != null -> "cancel:${cancelled.reason}"
            else -> "ok"
        }
        val actual = rt.close(group)
        assertState(actual == expected, "close $actual != $expected")
    }

    private fun assertState(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    companion object {
        val REASONS = listOf("Explicit", "Deadline", "ScopeExit", "SiblingFailed", "Parent")
    }
}

/** 手写用例与固定种子属性测试。 */
class AwaitedCancelPublishTest {
    private val rt = AwaitedCancelRuntime

    @Test
    fun `uncaught awaited cancellation publishes Cancelled with the first reason of every kind`() {
        for (reason in listOf("Explicit", "Deadline", "ScopeExit", "SiblingFailed", "Parent")) {
            val group = rt.newGroup(false)
            val child = rt.spawn(group, rt.inline, rt.newBody(1, rt.cancelledFuture(reason)))
            assertEquals("Cancelled", rt.status(child), reason)
            assertEquals(reason, rt.reasonOf(child))
            assertNull(rt.failureOf(child), "cancellation must not write a Failure")
            assertEquals("cancel:$reason", rt.close(group))
        }
    }

    @Test
    fun `awaited cancellation is not a sibling failure in a normal group`() {
        val group = rt.newGroup(false)
        val first = rt.spawn(group, rt.inline, rt.newBody(1, rt.cancelledFuture("Deadline")))
        val sibling = rt.spawn(group, rt.inline, rt.newBody(0, rt.cancelledFuture("Explicit")))
        assertEquals("Cancelled", rt.status(first))
        assertEquals("Completed", rt.status(sibling), "a later sibling still runs to completion")
        assertEquals(7, rt.value(sibling))
        assertEquals("cancel:Deadline", rt.close(group))
    }

    @Test
    fun `explicit catch can continue and complete normally`() {
        val group = rt.newGroup(false)
        val body = rt.newBody(2, rt.cancelledFuture("ScopeExit"))
        val child = rt.spawn(group, rt.inline, body)
        assertEquals("Completed", rt.status(child))
        assertEquals(5, rt.value(child))
        assertEquals("ScopeExit", rt.scripted.getMethod("caughtReason").invoke(body))
        assertEquals("ok", rt.close(group))
    }

    @Test
    fun `business error from cleanup is not swallowed by the cancellation`() {
        val group = rt.newGroup(false)
        val child = rt.spawn(group, rt.inline, rt.newBody(3, rt.cancelledFuture("Deadline")))
        assertEquals("Failed", rt.status(child))
        assertEquals("cleanup", rt.failureOf(child))
        assertNull(rt.reasonOf(child))
        // 业务失败照常级联：之后的子任务令牌已是 SiblingFailed。
        val later = rt.spawn(group, rt.inline, rt.newBody(0, rt.cancelledFuture("Explicit")))
        assertEquals("Cancelled", rt.status(later))
        assertEquals("SiblingFailed", rt.reasonOf(later))
        assertEquals("fail:cleanup", rt.close(group))
    }

    @Test
    fun `Parent from the awaited future becomes the first reason of the task token`() {
        val group = rt.newGroup(false)
        val untouched = rt.spawn(group, rt.inline, rt.newBody(1, rt.cancelledFuture("Parent")))
        assertEquals("Parent", rt.reasonOf(untouched), "no token reason to map to")
        val mapped = rt.spawn(group, rt.inline, rt.newBody(5, rt.cancelledFuture("Parent")))
        assertEquals("Deadline", rt.reasonOf(mapped))
        val kept = rt.spawn(group, rt.inline, rt.newBody(5, rt.cancelledFuture("ScopeExit")))
        assertEquals("ScopeExit", rt.reasonOf(kept), "only Parent is mapped")
    }

    @Test
    fun `body blocked in get on a real thread publishes the cancel and keeps its sibling running`() {
        val executor = rt.loader.loadClass("std.JvmExecutor").getConstructor(Int::class.javaPrimitiveType).newInstance(2)
        try {
            val group = rt.newGroup(false)
            val awaited = rt.future.getConstructor().newInstance()
            val gate = rt.future.getConstructor().newInstance()
            val child = rt.spawn(group, executor, rt.newBody(1, awaited))
            val sibling = rt.spawn(group, executor, rt.newBody(2, gate))
            rt.future.getMethod("cancelWith", rt.reasonType).invoke(awaited, rt.reason("Deadline"))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (rt.status(child) == "Pending" && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals("Cancelled", rt.status(child))
            assertEquals("Deadline", rt.reasonOf(child))
            assertEquals("Pending", rt.status(sibling), "sibling must not be cancelled")
            rt.future.getMethod("complete", Any::class.java).invoke(gate, 3)
            while (rt.status(sibling) == "Pending" && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(3, rt.value(sibling))
            assertEquals("cancel:Deadline", rt.close(group))
        } finally {
            executor.javaClass.getMethod("shutdown").invoke(executor)
        }
    }

    /**
     * 固定种子属性测试：种子 20261147，600 个随机脚本。覆盖下限防止生成器退化：未捕获取消、Parent 换算、
     * 业务失败级联、清理错误取代取消，各超过 100 次（换算 ≥ 40）。
     */
    @Test
    fun `seeded scripts agree with the oracle`() {
        val random = Random(20261147)
        var uncaught = 0
        var mapped = 0
        var cascades = 0
        var cleanup = 0
        repeat(600) { round ->
            val supervisor = random.nextInt(4) == 0
            val harness = AwaitedCancelHarness(supervisor)
            val script = (0 until random.nextInt(1, 7)).map {
                random.nextInt(7) to random.nextInt(AwaitedCancelHarness.REASONS.size)
            }
            try {
                script.forEach { (op, reason) -> harness.step(op, reason) }
                harness.finish()
            } catch (error: AssertionError) {
                throw AssertionError("seed 20261147 round $round supervisor=$supervisor script=$script: ${error.message}", error)
            }
            uncaught += harness.uncaught
            mapped += harness.mapped
            cascades += harness.cascades
            cleanup += harness.cleanupSurfaced
        }
        assertTrue(uncaught > 100, "generator too weak: uncaught=$uncaught")
        assertTrue(mapped >= 40, "generator too weak: mapped=$mapped")
        assertTrue(cascades > 100, "generator too weak: cascades=$cascades")
        assertTrue(cleanup > 100, "generator too weak: cleanup=$cleanup")
    }

    private val frameProgram = """
        package demo;
        import std.Future;
        import std.CancellationException;
        import std.CancellationReason;
        import std.CancellationToken;
        import std.FutureCancelledError;

        public class Program {
            private static var held: CancellationToken? = null;
            private static var cleaned: int32 = 0;
            public static function hold(token: CancellationToken): void { Program.held = token; }
            public static function cancelHeld(): void {
                (cast Program.held as CancellationToken).cancelWith(CancellationReason.Deadline);
            }
            public static function clean(): void { Program.cleaned = Program.cleaned + 1; }
            public static function cleaned(): int32 {
                var count: int32 = Program.cleaned;
                Program.cleaned = 0;
                return count;
            }
            public static function boom(): void { throw new std.Error("cleanup"); }
            public static function inner(): void { throw new CancellationException("inner", CancellationReason.Deadline); }

            public static function syncGet(input: Future<int32>, other: Future<int32>): int32 {
                await input;
                try {
                    return other.get();
                } finally {
                    Program.clean();
                }
            }
            public static function syncCatch(input: Future<int32>, other: Future<int32>): int32 {
                await input;
                try {
                    return other.get();
                } catch (e: FutureCancelledError) {
                    Program.clean();
                    return 5;
                }
            }
            public static function thrown(input: Future<int32>): int32 {
                await input;
                throw new FutureCancelledError("x", CancellationReason.ScopeExit);
            }
            public static function innerClose(input: Future<int32>): int32 {
                await input;
                Program.inner();
                return 0;
            }
            public static function cleanupFails(input: Future<int32>): int32 {
                try {
                    return await input;
                } finally {
                    Program.boom();
                }
            }
            public static function cleanupFailsRouted(input: Future<int32>): int32 {
                try {
                    try {
                        return await input;
                    } finally {
                        Program.boom();
                    }
                } catch (e: FutureCancelledError) {
                    return 1;
                }
            }
            public static function parentMapped(input: Future<int32>): int32 {
                try {
                    return await input;
                } finally {
                    Program.cancelHeld();
                }
            }
        }
    """.trimIndent()

    /** 编译帧程序，在真实 JvmExecutor 上运行 [body]。 */
    private fun withFrames(name: String, body: FrameRuntime.() -> Unit) {
        val output = Paths.get("build", "tmp", "awaited-cancel-frames", name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Program.jas" to frameProgram), output)
        assertTrue(result is JasperCompiler.CompileResult.Success, "compilation failed: $result")
        URLClassLoader(arrayOf(output.toUri().toURL())).use { loader ->
            val runtime = FrameRuntime(loader)
            try { runtime.body() } finally { runtime.shutdown() }
        }
    }

    private class FrameRuntime(val loader: ClassLoader) {
        val future: Class<*> = loader.loadClass("std.Future")
        val reasonType: Class<*> = loader.loadClass("std.CancellationReason")
        val executor: Any = loader.loadClass("std.JvmExecutor").getConstructor(Int::class.javaPrimitiveType).newInstance(1)
        val program: Class<*> = loader.loadClass("demo.Program")

        fun newFuture(): Any = future.getConstructor().newInstance()
        fun completed(value: Int): Any = newFuture().also { future.getMethod("complete", Any::class.java).invoke(it, value) }
        fun cancelled(reason: String): Any = newFuture().also {
            future.getMethod("cancelWith", reasonType).invoke(it, reasonType.enumConstants.single { c -> c.toString() == reason })
        }
        fun frame(function: String, output: Any, vararg args: Any?): Any =
            loader.loadClass("demo.Program_${function}_Coroutine").constructors.single().newInstance(executor, output, *args)
        fun start(frame: Any) { frame.javaClass.getMethod("start").invoke(frame) }
        fun settle(target: Any, label: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (future.getMethod("isDone").invoke(target) != true && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(true, future.getMethod("isDone").invoke(target), "frame did not finish: $label")
            future.getMethod("awaitTaskSettled").invoke(target)
        }
        fun status(target: Any): String = future.getMethod("status").invoke(target).toString()
        fun reason(target: Any): String? = future.getMethod("cancellationReason").invoke(target)?.toString()
        fun failure(target: Any): String? = future.getMethod("failureOrNull").invoke(target)
            ?.let { it.javaClass.getMethod("message").invoke(it) as String }
        fun cleaned(): Int = program.getMethod("cleaned").invoke(null) as Int
        fun shutdown() { executor.javaClass.getMethod("shutdown").invoke(executor) }
    }

    @Test
    fun `frame publishes Cancelled when a synchronous get in a segment throws uncaught`() = withFrames("sync-get") {
        val output = newFuture()
        start(frame("syncGet", output, completed(1), cancelled("Deadline")))
        settle(output, "syncGet")
        assertEquals("Cancelled", status(output))
        assertEquals("Deadline", reason(output))
        assertNull(failure(output))
        assertEquals(1, cleaned(), "cleanup runs exactly once")
    }

    @Test
    fun `frame catch of a synchronous get completes normally`() = withFrames("sync-catch") {
        val output = newFuture()
        start(frame("syncCatch", output, completed(1), cancelled("Deadline")))
        settle(output, "syncCatch")
        assertEquals("Completed", status(output))
        assertEquals(5, future.getMethod("get").invoke(output))
        assertEquals(1, cleaned())
    }

    @Test
    fun `frame publishes Cancelled for thrown cancel errors with their own reason`() = withFrames("thrown") {
        val explicit = newFuture()
        start(frame("thrown", explicit, completed(1)))
        settle(explicit, "thrown")
        assertEquals("Cancelled", status(explicit))
        assertEquals("ScopeExit", reason(explicit))

        val inner = newFuture()
        start(frame("innerClose", inner, completed(1)))
        settle(inner, "innerClose")
        assertEquals("Cancelled", status(inner))
        assertEquals("Deadline", reason(inner))
    }

    @Test
    fun `frame cleanup error replaces a pending cancellation`() = withFrames("cleanup") {
        for (function in listOf("cleanupFails", "cleanupFailsRouted")) {
            val output = newFuture()
            start(frame(function, output, cancelled("Deadline")))
            settle(output, function)
            assertEquals("Failed", status(output), function)
            assertEquals("cleanup", failure(output), function)
            assertNull(reason(output), function)
        }
    }

    @Test
    fun `frame maps Parent to the first reason of its token`() = withFrames("parent") {
        val tokenType = loader.loadClass("std.CancellationToken")
        val token = tokenType.getConstructor().newInstance()
        program.getMethod("hold", tokenType).invoke(null, token)
        val output = newFuture()
        val input = newFuture()
        val frame = frame("parentMapped", output, input)
        frame.javaClass.getMethod("bindToken", tokenType).invoke(frame, token)
        start(frame)
        // 清理把令牌取消，发布时令牌首因已是 Deadline；没有作用域回调，结果只能由帧自己发布。
        future.getMethod("cancelWith", reasonType).invoke(input, reasonType.enumConstants.single { it.toString() == "Parent" })
        settle(output, "parentMapped")
        assertEquals("Cancelled", status(output))
        assertEquals("Deadline", reason(output))
    }

    @Test
    fun `launched frame cancelled by its await leaves the sibling frame running`() = withFrames("launch") {
        val group = loader.loadClass("std.TaskGroup").getConstructor(Boolean::class.javaPrimitiveType).newInstance(false)
        val launch = group.javaClass.getMethod("launch", loader.loadClass("std.CoroutineTask"))
        val first = newFuture()
        launch.invoke(group, frame("syncGet", first, completed(1), cancelled("SiblingFailed")))
        val siblingOut = newFuture()
        val gate = newFuture()
        launch.invoke(group, frame("cleanupFailsRouted", siblingOut, gate))
        settle(first, "first")
        assertEquals("SiblingFailed", reason(first))
        assertEquals(false, future.getMethod("isDone").invoke(siblingOut), "sibling must not be cancelled")
        assertFalse(group.javaClass.getMethod("isClosed").invoke(group) as Boolean)
        group.javaClass.getMethod("cancel").invoke(group)
        settle(siblingOut, "sibling")
        assertEquals(1, cleaned())
    }
}

/**
 * Lincheck 模型检查：任务体发布（等待已取消 Future，原因 Deadline）与作用域取消竞争。无论谁先写，结果都是
 * Cancelled，原因只能是其中之一，不写 Failure；根令牌的首因只能来自显式取消，不会出现 SiblingFailed（不级联）。
 * 固定场景 + EpsilonVerifier：检查交错下的不变量，不是线性化证明。
 */
class AwaitedCancelLincheckTest {
    // 不把运行时对象存成字段：Lincheck 遍历实例字段插桩，经它会走到类加载器内部的 record 而失败。
    private val group = AwaitedCancelRuntime.newGroup(false)
    private val body = AwaitedCancelRuntime.newBody(1, AwaitedCancelRuntime.cancelledFuture("Deadline"))
    @Volatile private var child: Any? = null

    @Operation fun spawnBody() { child = AwaitedCancelRuntime.spawn(group, AwaitedCancelRuntime.inline, body) }
    @Operation fun cancelGroup() { AwaitedCancelRuntime.cancelGroup(group) }

    @Validate fun validate() {
        val rt = AwaitedCancelRuntime
        val future = child ?: return
        check(rt.status(future) == "Cancelled") { "status=${rt.status(future)}" }
        check(rt.reasonOf(future) in setOf("Deadline", "Explicit")) { "reason=${rt.reasonOf(future)}" }
        check(rt.failureOf(future) == null) { "failure=${rt.failureOf(future)}" }
    }

    @Test
    fun `model check body publish against scope cancel`() {
        AwaitedCancelRuntime.loader // 先在测试线程完成编译，避免把编译纳入交错。
        ModelCheckingOptions()
            .iterations(0)
            .invocationsPerIteration(2000)
            .verifier(EpsilonVerifier::class.java)
            .addCustomScenario {
                parallel {
                    thread { actor(AwaitedCancelLincheckTest::spawnBody) }
                    thread { actor(AwaitedCancelLincheckTest::cancelGroup) }
                }
            }
            .check(this::class)
    }
}

/**
 * Jazzer 覆盖引导 fuzz：字节流选择至多 12 个事件，每步按单线程 oracle 检查。
 * 默认（回归模式）运行入库语料；设置 JAZZER_FUZZ=1 时进入覆盖引导 fuzz。
 */
class AwaitedCancelFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun awaitedCancelScripts(data: FuzzedDataProvider) {
        val harness = AwaitedCancelHarness(data.consumeBoolean())
        repeat(data.consumeInt(0, 12)) {
            harness.step(data.consumeInt(0, 6), data.consumeInt(0, AwaitedCancelHarness.REASONS.size - 1))
        }
        harness.finish()
    }
}
