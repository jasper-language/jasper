// ============================================================================
// LinearCoroutineScopeFvTest.kt —— S4-C 第九片作用域入口的 Lincheck / Jazzer 验证
// ============================================================================
// 做什么：对「编译器生成帧 + Jasper 运行时 TaskGroup.launch」做两类补充验证：
//   - Lincheck 模型检查：两线程交错执行 launch / 输入完成或失败 / 作用域取消 /
//     执行队列段，检查死锁、意外异常与终态不变量（Jasper 源码链第 6 步）；
//   - Jazzer 覆盖引导 fuzz：字节流驱动任意事件序列（含执行器拒绝），每步检查
//     与 CoroutineScopeLaunch.tla 同名的不变量，结束时检查 close 聚合（第 4 步）。
//
// 所属层次：compiler 模块测试层；Kotlin 只提供确定性队列执行器（经 JDK 动态代理
//   实现 std.Executor）、反射驱动与不变量判定，状态机全部在生成帧与 Jasper 运行时中。
//
// 前置条件：与 LinearCoroutineScopeTest 相同的源码；产物只写模块 build/ 下。
// 失败模式：不变量被破坏时抛 AssertionError，Lincheck/Jazzer 报告最小化交错或输入。
// 为什么用队列执行器：真实线程池的线程不受 Lincheck 控制、也会让 fuzz 不可复现；
//   队列执行器把「段何时运行」变成显式操作，交错由工具枚举。
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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

/** 一次编译、全进程复用的作用域运行时；只读反射句柄，线程安全。 */
object ScopeFvRuntime {
    private val source = """
        package demo;
        import std.Future;

        public class Program {
            public static function calculate(input: Future<int32>, kept: int32): int32 {
                var before: int32 = kept + 1;
                var got: int32 = await input;
                var after: int32 = before + got;
                return after * 2;
            }
        }
    """.trimIndent()

    val loader: URLClassLoader by lazy {
        val output = Paths.get("build", "tmp", "linear-coroutine-scope", "fv")
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Program.jas" to source), output)
        check(result is JasperCompiler.CompileResult.Success) { "compilation failed: $result" }
        URLClassLoader(arrayOf(output.toUri().toURL()))
    }
    val future: Class<*> by lazy { loader.loadClass("std.Future") }
    private val failure: Class<*> by lazy { loader.loadClass("std.Failure") }
    private val group: Class<*> by lazy { loader.loadClass("std.TaskGroup") }
    private val executorType: Class<*> by lazy { loader.loadClass("std.Executor") }
    private val runnable: Class<*> by lazy { loader.loadClass("std.Runnable") }
    private val frame: Class<*> by lazy { loader.loadClass("demo.Program_calculate_Coroutine") }
    private val settledField by lazy { future.getDeclaredField("settled").apply { isAccessible = true } }

    /** 帧结果的源码语义。 */
    fun expected(kept: Int, input: Int) = (kept + 1 + input) * 2

    /** 确定性队列执行器：schedule 只入队；rejecting 置位后按 JDK 语义拒绝。 */
    class QueueExecutor {
        val queue = ConcurrentLinkedQueue<Any>()
        val rejecting = AtomicBoolean(false)
        val proxy: Any = Proxy.newProxyInstance(loader, arrayOf(executorType)) { self, method, args ->
            when (method.name) {
                "schedule" -> {
                    if (rejecting.get()) throw RejectedExecutionException("executor shut down")
                    queue.add(args[0]); null
                }
                "shutdown" -> { rejecting.set(true); null }
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args[0]
                else -> "QueueExecutor"
            }
        }

        /** 执行一个已入队段；返回是否执行了段。段内异常已由调度门发布到结果，这里吞掉。 */
        fun runOne(): Boolean {
            val task = queue.poll() ?: return false
            try {
                runnable.getMethod("run").invoke(task)
            } catch (_: InvocationTargetException) {
            }
            return true
        }

        fun drain() { while (runOne()) Unit }
    }

    fun newFuture(): Any = future.getConstructor().newInstance()
    fun newGroup(supervisor: Boolean): Any =
        group.getConstructor(Boolean::class.javaPrimitiveType).newInstance(supervisor)
    fun newFrame(executor: QueueExecutor, result: Any, input: Any, kept: Int): Any =
        frame.getConstructor(executorType, future, future, Int::class.javaPrimitiveType)
            .newInstance(executor.proxy, result, input, kept)

    /** launch；关闭或其他 Jasper 异常以类名返回，正常返回 null。 */
    fun launch(group: Any, frame: Any): String? = invoke { this.group.getMethod("launch", loader.loadClass("std.CoroutineTask")).invoke(group, frame) }
    fun cancelGroup(group: Any): String? = invoke { group.javaClass.getMethod("cancel").invoke(group) }
    fun close(group: Any): String? = invoke { group.javaClass.getMethod("close").invoke(group) }
    fun complete(target: Any, value: Int): String? = invoke { future.getMethod("complete", Any::class.java).invoke(target, value) }
    fun fail(target: Any): String? = invoke {
        val snapshot = failure.getConstructor(String::class.java, String::class.java).newInstance("boom", "error")
        future.getMethod("fail", failure).invoke(target, snapshot)
    }
    fun cancelInput(target: Any): String? = invoke { future.getMethod("cancel").invoke(target) }
    fun status(target: Any): String = future.getMethod("status").invoke(target).toString()
    fun value(target: Any): Any? = future.getMethod("get").invoke(target)
    fun isSettled(target: Any): Boolean = settledField.get(target) as Boolean
    fun isDone(target: Any): Boolean = future.getMethod("isDone").invoke(target) as Boolean

    /** 统一把 Jasper 抛出的异常转为类名，便于在不变量里区分预期与意外异常。 */
    private inline fun invoke(block: () -> Unit): String? = try {
        block(); null
    } catch (error: InvocationTargetException) {
        error.targetException.javaClass.name
    }
}

/**
 * 作用域内若干帧的可观测状态与不变量（与 CoroutineScopeLaunch.tla 同名）。
 *
 * 做什么：记录 launch/输入/取消事件，并在任意时刻检查 SettledImpliesTerminal、
 *   NormalCascade、CancelledScopeHasNoPending、SupervisorNoCascade 与成功值正确性。
 * 为什么独立成类：Lincheck 与 Jazzer 共享同一组判定，避免两份不变量漂移。
 */
class ScopeHarness(val supervisor: Boolean, frames: Int) {
    private val rt = ScopeFvRuntime
    val executor = ScopeFvRuntime.QueueExecutor()
    val group: Any = rt.newGroup(supervisor)
    val inputs = List(frames) { rt.newFuture() }
    val results = List(frames) { rt.newFuture() }
    private val frameObjects = List(frames) { rt.newFrame(executor, results[it], inputs[it], it) }
    val launched = List(frames) { AtomicBoolean(false) }
    private val inputValue = IntArray(frames)
    val inputCancelled = List(frames) { AtomicBoolean(false) }
    val groupCancelled = AtomicBoolean(false)

    /** 每帧最多 launch 一次；返回 Jasper 异常类名或 null。 */
    fun launch(i: Int): String? {
        if (!launched[i].compareAndSet(false, true)) return null
        val error = rt.launch(group, frameObjects[i])
        check(error == null || error == "std.TaskGroupClosedError") { "unexpected launch error $error" }
        if (error != null) launched[i].set(false)
        return error
    }

    /** 输入完成；执行器拒绝恢复调度时回调错误以 CallbackDispatchError 汇总，属预期。 */
    fun complete(i: Int, value: Int) {
        if (rt.isDone(inputs[i])) return
        inputValue[i] = value
        expectCallbackError(rt.complete(inputs[i], value))
    }
    fun fail(i: Int) = expectCallbackError(rt.fail(inputs[i]))
    fun cancelInput(i: Int) {
        if (!rt.isDone(inputs[i])) inputCancelled[i].set(true)
        expectCallbackError(rt.cancelInput(inputs[i]))
    }
    fun cancelGroup() {
        groupCancelled.set(true)
        expectCallbackError(rt.cancelGroup(group))
    }

    private fun expectCallbackError(error: String?) =
        check(error == null || error == "std.CallbackDispatchError") { "unexpected callback error $error" }

    /** 检查全部不变量；违反即抛 AssertionError。 */
    fun checkInvariants() {
        val statuses = results.map(rt::status)
        val anyFailed = statuses.indices.any { launched[it].get() && statuses[it] == "Failed" }
        for (i in results.indices) {
            val status = statuses[i]
            if (rt.isSettled(results[i])) assertState(status != "Pending", "SettledImpliesTerminal frame=$i")
            if (!launched[i].get()) {
                assertState(status == "Pending", "UnlaunchedNeverRuns frame=$i status=$status")
                continue
            }
            if (!supervisor && anyFailed) assertState(status != "Pending", "NormalCascade frame=$i")
            if (groupCancelled.get()) assertState(status != "Pending", "CancelledScopeHasNoPending frame=$i")
            if (status == "Cancelled") {
                val allowed = groupCancelled.get() || inputCancelled[i].get() || (!supervisor && anyFailed)
                assertState(allowed, "SupervisorNoCascade/CancelledHasCause frame=$i supervisor=$supervisor")
            }
            if (status == "Completed") {
                assertState(rt.value(results[i]) == ScopeFvRuntime.expected(i, inputValue[i]), "value frame=$i")
            }
        }
    }

    /** 静止后检查：已登记且输入已终结（或结果已终结）的帧必须终态且 settled。 */
    fun checkQuiescent() {
        executor.drain()
        checkInvariants()
        for (i in results.indices) {
            if (!launched[i].get()) continue
            if (rt.isDone(inputs[i]) || rt.isDone(results[i])) {
                assertState(rt.isDone(results[i]) && rt.isSettled(results[i]), "quiescent frame=$i must settle")
            }
        }
    }

    private fun assertState(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}

/**
 * Lincheck 模型检查：两线程各 3 个操作，枚举 launch / 输入 / 取消 / 执行段的交错。
 * 操作均返回 Unit：被检查的是死锁、意外异常与 @Validate 中的终态不变量，而不是返回值。
 */
class LinearCoroutineScopeLincheckTest {
    private val harness = ScopeHarness(supervisor = false, frames = 2)

    @Operation fun launch0() { harness.launch(0) }
    @Operation fun launch1() { harness.launch(1) }
    @Operation fun failInput0() = harness.fail(0)
    @Operation fun completeInput1() = harness.complete(1, 7)
    @Operation fun cancelScope() = harness.cancelGroup()
    @Operation fun runSegment() { harness.executor.runOne() }

    @Validate fun validate() {
        harness.checkInvariants()
        harness.checkQuiescent()
    }

    /** 固定迭代数的模型检查；场景与交错由 Lincheck 生成并在失败时最小化。 */
    @Test
    fun `model check scope launch interleavings`() {
        ScopeFvRuntime.loader // 先在测试线程完成编译，避免把编译本身纳入交错。
        ModelCheckingOptions()
            .iterations(40)
            .invocationsPerIteration(400)
            .threads(2)
            .actorsPerThread(3)
            .check(this::class)
    }
}

/**
 * Jazzer 覆盖引导 fuzz：字节流选择普通/监督组与至多 32 个事件（3 帧，含执行器拒绝），
 * 每步检查不变量；结束时补齐全部输入并静止，close 的聚合结果须与终态一致。
 * 默认（回归模式）运行入库语料；设置 JAZZER_FUZZ=1 时进入覆盖引导 fuzz。
 */
class LinearCoroutineScopeFuzzTest {
    @FuzzTest(maxDuration = "60s")
    fun scopeEvents(data: FuzzedDataProvider) {
        val harness = ScopeHarness(supervisor = data.consumeBoolean(), frames = 3)
        repeat(data.consumeInt(0, 32)) {
            val frame = data.consumeInt(0, 2)
            when (data.consumeInt(0, 7)) {
                0 -> harness.launch(frame)
                1 -> harness.complete(frame, data.consumeInt(-50, 50))
                2 -> harness.fail(frame)
                3 -> harness.cancelInput(frame)
                4 -> harness.cancelGroup()
                5 -> harness.executor.runOne()
                6 -> harness.executor.drain()
                else -> harness.executor.shutdownRejecting()
            }
            harness.checkInvariants()
        }
        for (i in 0 until 3) harness.complete(i, 1)
        harness.checkQuiescent()
        val statuses = harness.results.indices.filter { harness.launched[it].get() }
            .map { ScopeFvRuntime.status(harness.results[it]) }
        val expectedClose = when {
            statuses.contains("Failed") -> "std.TaskGroupFailure"
            harness.groupCancelled.get() || statuses.contains("Cancelled") ||
                (!harness.supervisor && statuses.contains("Failed")) -> "std.CancellationException"
            else -> null
        }
        val actual = ScopeFvRuntime.close(harness.group)
        if (actual != expectedClose) throw AssertionError("close=$actual expected=$expectedClose statuses=$statuses")
    }
}

/** 让 fuzz 可以把执行器切换到拒绝模式（与 JvmExecutor 关闭后的语义一致）。 */
private fun ScopeFvRuntime.QueueExecutor.shutdownRejecting() = rejecting.set(true)
