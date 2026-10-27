// ============================================================================
// LinearCoroutineScopeTest.kt —— S4-C 第九片「生成帧接入结构化作用域」验收
// ============================================================================
// 做什么：真实编译标准库 + 用户源码，得到编译器生成的单挂起点协程帧，再经 Jasper
//   运行时 TaskGroup.launch 作为子任务登记并运行。覆盖作用域内两帧并发挂起/恢复、
//   普通组失败取消挂起兄弟、监督组不级联、作用域取消唤醒挂起帧、关闭后拒绝启动、
//   首次调度被拒绝的回收，以及固定种子事件序列与独立 oracle 的一致性。
//
// 所属层次：compiler 模块端到端测试层；Kotlin 只做反射驱动、有界等待与 oracle，
//   登记、级联、取消唤醒与 settled 回收全部由生成帧与 Jasper 运行时完成。
//
// 前置条件：构建虚拟机可运行 JDK；scratch 目录都在模块 build/ 下。
// 失败模式：编译诊断、反射签名、聚合结果或超时直接断言失败；所有阻塞调用都在
//   有界线程中执行，实现缺陷不会卡住整个测试套件。
// 为什么用反射启动帧：源码层调用挂起函数的语法/ABI 仍待裁决，本片只验证
//   「编译器生成帧满足运行时作用域入口协议」，不替用户选择公开调用方式。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证生成帧经 TaskGroup.launch 的登记、级联取消、聚合与回收语义。 */
class LinearCoroutineScopeTest {

    /** 单 await 帧源码；静态计数分别记录挂起前进入次数与恢复段执行次数。 */
    private val program = """
        package demo;
        import std.Future;

        public class Program {
            private static var entered: int32 = 0;
            private static var resumed: int32 = 0;

            public static function enter(value: int32): int32 {
                Program.entered = Program.entered + 1;
                return value;
            }

            public static function resume(value: int32): int32 {
                Program.resumed = Program.resumed + 1;
                return value;
            }

            public static function entered(): int32 { return Program.entered; }
            public static function resumed(): int32 { return Program.resumed; }

            public static function calculate(input: Future<int32>, kept: int32): int32 {
                var before: int32 = Program.enter(kept) + 1;
                var got: int32 = await input;
                var after: int32 = Program.resume(before + got);
                return after * 2;
            }
        }
    """.trimIndent()

    /** 帧结果的源码语义：(kept + 1 + 输入) * 2。 */
    private fun expected(kept: Int, input: Int) = (kept + 1 + input) * 2

    /** 一次编译的产物与常用反射句柄；每个测试独立编译，静态计数互不干扰。 */
    private class Runtime(val loader: URLClassLoader, val output: Path) {
        val future: Class<*> = loader.loadClass("std.Future")
        val failure: Class<*> = loader.loadClass("std.Failure")
        val group: Class<*> = loader.loadClass("std.TaskGroup")
        val executorType: Class<*> = loader.loadClass("std.Executor")
        val frameType: Class<*> = loader.loadClass("demo.Program_calculate_Coroutine")
        val program: Class<*> = loader.loadClass("demo.Program")

        fun newFuture(): Any = future.getConstructor().newInstance()
        fun executor(threads: Int): Any = loader.loadClass("std.JvmExecutor")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(threads)
        fun newGroup(supervisor: Boolean): Any =
            group.getConstructor(Boolean::class.javaPrimitiveType).newInstance(supervisor)
        fun newFrame(executor: Any, result: Any, input: Any, kept: Int): Any =
            frameType.getConstructor(executorType, future, future, Int::class.javaPrimitiveType)
                .newInstance(executor, result, input, kept)
        fun launch(group: Any, frame: Any): Any =
            this.group.getMethod("launch", loader.loadClass("std.CoroutineTask")).invoke(group, frame)
        fun complete(target: Any, value: Int) { future.getMethod("complete", Any::class.java).invoke(target, value) }
        fun fail(target: Any, message: String) {
            val snapshot = failure.getConstructor(String::class.java, String::class.java).newInstance(message, "error")
            future.getMethod("fail", failure).invoke(target, snapshot)
        }
        fun cancelInput(target: Any) { future.getMethod("cancel").invoke(target) }
        fun status(target: Any): String = future.getMethod("status").invoke(target).toString()
        fun isDone(target: Any): Boolean = future.getMethod("isDone").invoke(target) as Boolean
        fun value(target: Any): Any? = future.getMethod("get").invoke(target)
        fun counter(name: String): Int = program.getMethod(name).invoke(null) as Int
    }

    /** 编译标准库 + 源码到模块 build 下的独立目录，失败带聚合诊断。 */
    private fun compile(name: String): Runtime {
        val output = Paths.get("build", "tmp", "linear-coroutine-scope", name)
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(mapOf("Program.jas" to program), output)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "compilation failed for $name: " +
                ((result as? JasperCompiler.CompileResult.Error)?.message ?: result.toString())
        )
        return Runtime(URLClassLoader(arrayOf(output.toUri().toURL())), output)
    }

    /** 有界等待条件成立；超时报告 what，避免把异步调度误当成同步完成。 */
    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(condition(), "timed out waiting for $what")
    }

    /**
     * 在有界线程中调用会阻塞的 Jasper 方法（close / awaitTaskSettled），
     * 返回 Jasper 抛出的异常类名（null 表示正常返回）。超时即断言失败。
     */
    private fun boundedCall(target: Any, method: String): String? {
        val pool = Executors.newSingleThreadExecutor()
        try {
            return pool.submit<String?> {
                try {
                    target.javaClass.getMethod(method).invoke(target)
                    null
                } catch (error: InvocationTargetException) {
                    error.targetException.javaClass.name
                }
            }.get(5, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    /** 断言结果 Future 的任务体已回收（settled）。 */
    private fun assertSettled(result: Any) {
        assertEquals(null, boundedCall(result, "awaitTaskSettled"), "awaitTaskSettled must return normally")
    }

    /** 关闭 Jasper 执行器并有界确认终止。 */
    private fun shutdown(executor: Any) {
        executor.javaClass.getMethod("shutdown").invoke(executor)
        assertEquals(
            true,
            executor.javaClass.getMethod("awaitTermination", Long::class.javaPrimitiveType).invoke(executor, 5000L)
        )
    }

    /**
     * 做什么：单工作线程上两帧经作用域启动后都挂起，逆序完成输入后各自恢复，
     *   close 正常返回且两帧均 settled。
     * 什么时候用：修改 launch 登记、帧 start 订阅或恢复段时。
     * 失败模式：第二帧无法在第一帧挂起期间进入、恢复串线、close 漏 join。
     */
    @Test
    fun `scope runs two suspended frames and close joins both`() {
        val rt = compile("two-frames")
        rt.loader.use {
            val executor = rt.executor(1)
            try {
                val group = rt.newGroup(false)
                val inputs = listOf(rt.newFuture(), rt.newFuture())
                val results = listOf(rt.newFuture(), rt.newFuture())
                val launched = (0..1).map { rt.launch(group, rt.newFrame(executor, results[it], inputs[it], it + 2)) }
                assertEquals(results, launched, "launch must return the frame-bound result future")
                // 单工作线程上两帧都执行完挂起前段落，说明第一帧挂起后线程被让出。
                awaitTrue("both frames suspended") { rt.counter("entered") == 2 }
                assertFalse(results.any(rt::isDone), "frames must stay suspended before inputs complete")
                assertEquals(0, rt.counter("resumed"))

                rt.complete(inputs[1], 20)
                rt.complete(inputs[0], 10)
                assertEquals(null, boundedCall(group, "close"), "close must succeed")
                assertEquals(expected(2, 10), rt.value(results[0]))
                assertEquals(expected(3, 20), rt.value(results[1]))
                assertEquals(2, rt.counter("resumed"))
                results.forEach(::assertSettled)
            } finally {
                shutdown(executor)
            }
        }
    }

    /**
     * 做什么：普通组中一帧输入失败，挂起中的兄弟帧被取消（Cancelled 通道）并被回收；
     *   兄弟输入事后完成也不会再执行恢复段；close 抛 TaskGroupFailure。
     * 什么时候用：修改 ChildFailureRelay、帧入口终态检查或 close 聚合时。
     * 失败模式：兄弟永久 Pending 使 close 卡死、取消伪装成失败、恢复段在取消后执行。
     */
    @Test
    fun `normal scope failure cancels the suspended sibling`() {
        val rt = compile("normal-failure")
        rt.loader.use {
            val executor = rt.executor(1)
            try {
                val group = rt.newGroup(false)
                val failing = rt.newFuture()
                val waiting = rt.newFuture()
                val failed = rt.launch(group, rt.newFrame(executor, rt.newFuture(), failing, 1))
                val sibling = rt.launch(group, rt.newFrame(executor, rt.newFuture(), waiting, 2))
                awaitTrue("both frames suspended") { rt.counter("entered") == 2 }

                rt.fail(failing, "boom")
                awaitTrue("failed frame published") { rt.isDone(failed) && rt.isDone(sibling) }
                assertEquals("Failed", rt.status(failed))
                assertEquals("Cancelled", rt.status(sibling))
                assertSettled(sibling)

                rt.complete(waiting, 5)
                assertEquals("std.TaskGroupFailure", boundedCall(group, "close"))
                assertEquals("Cancelled", rt.status(sibling), "late input must not overwrite cancellation")
                assertEquals(0, rt.counter("resumed"), "no resume segment may run after failure/cancel")
                assertSettled(failed)
            } finally {
                shutdown(executor)
            }
        }
    }

    /**
     * 做什么：监督组中一帧失败不取消兄弟；兄弟随后正常完成。确认失败后 close 正常
     *   返回（B 策略），未确认时 close 聚合为 TaskGroupFailure。
     * 什么时候用：修改监督组语义或 ChildFailureRelay 时。
     * 失败模式：监督组误级联、已确认失败仍被聚合、未确认失败被静默丢弃。
     */
    @Test
    fun `supervisor scope does not cascade and honours acknowledgement`() {
        val rt = compile("supervisor")
        rt.loader.use {
            val executor = rt.executor(1)
            try {
                for (acknowledge in listOf(true, false)) {
                    val group = rt.newGroup(true)
                    val failing = rt.newFuture()
                    val waiting = rt.newFuture()
                    val failed = rt.launch(group, rt.newFrame(executor, rt.newFuture(), failing, 1))
                    val sibling = rt.launch(group, rt.newFrame(executor, rt.newFuture(), waiting, 4))
                    rt.fail(failing, "boom")
                    awaitTrue("failed frame published") { rt.isDone(failed) }
                    // 级联若发生，会在失败发布的同一回调批次里同步取消兄弟；帧在批次结束后
                    // 才于 finally 标记 settled，因此等到 settled 再检查兄弟，不会漏看级联。
                    assertSettled(failed)
                    assertFalse(rt.isDone(sibling), "supervisor scope must not cancel siblings")
                    rt.complete(waiting, 7)
                    awaitTrue("sibling completed") { rt.isDone(sibling) }
                    assertEquals(expected(4, 7), rt.value(sibling))
                    if (acknowledge) {
                        assertEquals(true, rt.future.getMethod("acknowledgeFailure").invoke(failed))
                        assertEquals(null, boundedCall(group, "close"), "acknowledged failure is not aggregated")
                    } else {
                        assertEquals("std.TaskGroupFailure", boundedCall(group, "close"))
                    }
                    assertSettled(failed)
                    assertSettled(sibling)
                }
            } finally {
                shutdown(executor)
            }
        }
    }

    /**
     * 做什么：作用域取消把挂起中的两帧都发布为 Cancelled 并回收；输入事后完成不再
     *   执行恢复段；close 抛 CancellationException。
     * 什么时候用：修改帧 start 的结果订阅或入口终态检查时。
     * 失败模式：取消后的帧永不 settled 使 close 卡死，或晚到输入复活已取消的帧。
     */
    @Test
    fun `scope cancellation reaches suspended frames`() {
        val rt = compile("cancel")
        rt.loader.use {
            val executor = rt.executor(1)
            try {
                val group = rt.newGroup(false)
                val inputs = listOf(rt.newFuture(), rt.newFuture())
                val results = inputs.mapIndexed { index, input ->
                    rt.launch(group, rt.newFrame(executor, rt.newFuture(), input, index))
                }
                awaitTrue("both frames suspended") { rt.counter("entered") == 2 }
                group.javaClass.getMethod("cancel").invoke(group)
                results.forEach { assertEquals("Cancelled", rt.status(it)) }
                results.forEach(::assertSettled)
                inputs.forEach { rt.complete(it, 1) }
                assertEquals("std.CancellationException", boundedCall(group, "close"))
                assertEquals(0, rt.counter("resumed"))
            } finally {
                shutdown(executor)
            }
        }
    }

    /**
     * 做什么：作用域已取消（显式 cancel，或普通组失败级联）之后再 launch 的帧，
     *   直接发布 Cancelled 并回收，不执行任何业务段；输入随后完成也不复活。
     * 什么时候用：修改 launch 的根令牌检查时。该缺陷由 CoroutineScopeLaunch 模型
     *   的 NormalCascade 反例发现：缺少检查时新帧在已失败的普通组里照常运行。
     * 失败模式：新帧进入挂起前段、结果为成功、或永不 settled。
     */
    @Test
    fun `launch into an already cancelled scope never runs the frame`() {
        val rt = compile("cancelled-scope")
        rt.loader.use {
            val executor = rt.executor(1)
            try {
                val cancelled = rt.newGroup(false)
                cancelled.javaClass.getMethod("cancel").invoke(cancelled)
                val input = rt.newFuture()
                val late = rt.launch(cancelled, rt.newFrame(executor, rt.newFuture(), input, 0))
                assertEquals("Cancelled", rt.status(late))
                assertSettled(late)
                rt.complete(input, 3)
                assertEquals("std.CancellationException", boundedCall(cancelled, "close"))

                val failedGroup = rt.newGroup(false)
                val failing = rt.newFuture()
                rt.fail(failing, "boom")
                val failed = rt.launch(failedGroup, rt.newFrame(executor, rt.newFuture(), failing, 0))
                awaitTrue("failed frame published") { rt.isDone(failed) }
                assertSettled(failed)
                val afterFailure = rt.newFuture()
                val sibling = rt.launch(failedGroup, rt.newFrame(executor, rt.newFuture(), afterFailure, 5))
                assertEquals("Cancelled", rt.status(sibling))
                assertSettled(sibling)
                rt.complete(afterFailure, 3)
                assertEquals("std.TaskGroupFailure", boundedCall(failedGroup, "close"))
                assertEquals(1, rt.counter("entered"), "only the failing frame may enter its first segment")
                assertEquals(0, rt.counter("resumed"))
            } finally {
                shutdown(executor)
            }
        }
    }

    /**
     * 做什么：关闭后 launch 抛 TaskGroupClosedError 且帧不启动；执行器已关闭时
     *   launch 返回 schedule 失败且已 settled 的结果，close 将其聚合。
     * 什么时候用：修改 launch 的关闭检查或拒绝兜底时。
     * 失败模式：关闭后仍启动帧、拒绝调度后留下 Pending 子任务使 close 卡死。
     */
    @Test
    fun `closed scope and rejected schedule never leak a pending child`() {
        val rt = compile("closed-rejected")
        rt.loader.use {
            val executor = rt.executor(1)
            val closedGroup = rt.newGroup(false)
            assertEquals(null, boundedCall(closedGroup, "close"))
            val orphan = rt.newFuture()
            val error = runCatching {
                rt.launch(closedGroup, rt.newFrame(executor, orphan, rt.newFuture(), 0))
            }.exceptionOrNull()
            assertEquals("std.TaskGroupClosedError", (error as? InvocationTargetException)?.targetException?.javaClass?.name)
            assertFalse(rt.isDone(orphan))

            shutdown(executor)
            val group = rt.newGroup(false)
            val rejected = rt.launch(group, rt.newFrame(executor, rt.newFuture(), rt.newFuture(), 0))
            assertEquals("Failed", rt.status(rejected))
            val failure = rt.future.getMethod("failureOrNull").invoke(rejected)
            assertEquals("schedule", rt.failure.getMethod("kind").invoke(failure))
            assertSettled(rejected)
            assertEquals("std.TaskGroupFailure", boundedCall(group, "close"))
            assertEquals(0, rt.counter("entered"), "rejected frames must never run")
        }
    }

    /**
     * 做什么：固定种子 20261028 生成 64 轮事件序列（3 帧，各自输入以完成/失败/取消
     *   之一终结，随机插入作用域取消，随机普通/监督组），每个事件后等待受影响帧静止，
     *   与独立 Kotlin oracle 比对每帧终态、成功值与 close 结果。
     * 什么时候用：修改作用域入口、级联或帧取消唤醒时锁定组合语义。
     * 失败模式：任一轮不一致即报告种子、轮次与完整事件序列；单轮即最小反例。
     */
    @Test
    fun `seeded scope event sequences match an independent oracle`() {
        val seed = 20261028
        val random = kotlin.random.Random(seed)
        val rt = compile("seeded")
        rt.loader.use {
            val executor = rt.executor(2)
            try {
                repeat(64) { round ->
                    val supervisor = random.nextBoolean()
                    val kinds = List(3) { listOf("complete", "fail", "cancel")[random.nextInt(3)] }
                    val order = (0 until 3).shuffled(random)
                    val cancelAt = random.nextInt(0, 5) // 3 或 4 表示不取消或最后取消
                    val values = List(3) { random.nextInt(-5, 6) }
                    val trace = "seed=$seed round=$round supervisor=$supervisor kinds=$kinds order=$order cancelAt=$cancelAt"

                    val group = rt.newGroup(supervisor)
                    val inputs = List(3) { rt.newFuture() }
                    val results = List(3) { rt.launch(group, rt.newFrame(executor, rt.newFuture(), inputs[it], it)) }
                    val oracle = MutableList(3) { "Pending" }
                    var oracleCancelled = false

                    fun cancelScope() {
                        group.javaClass.getMethod("cancel").invoke(group)
                        oracleCancelled = true
                        for (i in 0 until 3) if (oracle[i] == "Pending") oracle[i] = "Cancelled"
                    }
                    order.forEachIndexed { step, frame ->
                        if (step == cancelAt) cancelScope()
                        val wasPending = oracle[frame] == "Pending"
                        when (kinds[frame]) {
                            "complete" -> rt.complete(inputs[frame], values[frame])
                            "fail" -> rt.fail(inputs[frame], "f$frame")
                            else -> rt.cancelInput(inputs[frame])
                        }
                        if (wasPending) {
                            oracle[frame] = when (kinds[frame]) {
                                "complete" -> "Completed"
                                "fail" -> "Failed"
                                else -> "Cancelled"
                            }
                            if (kinds[frame] == "fail" && !supervisor) {
                                oracleCancelled = true
                                for (i in 0 until 3) if (oracle[i] == "Pending") oracle[i] = "Cancelled"
                            }
                            // 失败帧的 isDone 先于其级联回调批次可见；等所有 oracle 已终结的帧
                            // 都可见再推进下一事件，事件间不存在调度竞争，结果可确定复现。
                            awaitTrue("$trace frame=$frame") {
                                (0 until 3).all { oracle[it] == "Pending" || rt.isDone(results[it]) }
                            }
                            assertSettled(results[frame])
                        }
                    }
                    if (cancelAt == 3) cancelScope()
                    for (i in 0 until 3) {
                        assertEquals(oracle[i], rt.status(results[i]), "$trace frame=$i")
                        if (oracle[i] == "Completed") assertEquals(expected(i, values[i]), rt.value(results[i]), trace)
                    }
                    val failures = oracle.count { it == "Failed" }
                    val expectedClose = when {
                        failures > 0 -> "std.TaskGroupFailure"
                        oracleCancelled || oracle.contains("Cancelled") -> "std.CancellationException"
                        else -> null
                    }
                    assertEquals(expectedClose, boundedCall(group, "close"), trace)
                    results.forEach(::assertSettled)
                }
            } finally {
                shutdown(executor)
            }
        }
    }

    /**
     * 做什么：在独立子 JVM 中以 `-Xverify:all -Xcheck:jni` 加载并初始化本次编译输出的
     *   全部 class（标准库运行时 + 生成帧），并断言生成帧与作用域运行时均为 class 52。
     * 什么时候用：作为 Jasper 运行时源码「未定义行为/内存安全」一步的 JVM 替代证据。
     * 失败模式：任一 class 字节码校验失败（VerifyError）或版本不符即失败。
     * 为什么另起 JVM：测试 JVM 已加载大量类且不一定开启全量校验；子 JVM 显式开启
     *   全量校验，只加载本次产物，结论不受测试进程状态影响。
     */
    @Test
    fun `generated frames and scope runtime pass full bytecode verification`() {
        val rt = compile("verify")
        rt.loader.close()
        for (name in listOf("demo/Program_calculate_Coroutine", "std/TaskGroup", "std/ChildFailureRelay", "std/CoroutineTask")) {
            val bytes = Files.readAllBytes(rt.output.resolve("$name.class"))
            val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
            assertEquals(52, major, "$name must keep JVM class version 52")
        }
        val classes = Files.walk(rt.output).use { paths ->
            paths.filter { it.toString().endsWith(".class") }
                .map { rt.output.relativize(it).toString().removeSuffix(".class").replace('/', '.').replace('\\', '.') }
                .sorted().toList()
        }
        assertTrue(classes.contains("demo.Program_calculate_Coroutine"))
        val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = rt.output.toAbsolutePath().toString() + java.io.File.pathSeparator + System.getProperty("java.class.path")
        val process = ProcessBuilder(
            listOf(javaBin, "-Xverify:all", "-Xcheck:jni", "-cp", classpath, VerifyAllLoader::class.java.name) + classes
        ).redirectErrorStream(true).start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        val log = process.inputStream.bufferedReader().readText()
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "verification JVM timed out")
        assertEquals(0, process.exitValue(), log)
        assertTrue(log.contains("verified ${classes.size} classes"), log)
    }
}

/** 子 JVM 入口：按名称加载并初始化每个 class，触发链接期字节码校验。 */
object VerifyAllLoader {
    @JvmStatic
    fun main(args: Array<String>) {
        val loader = VerifyAllLoader::class.java.classLoader
        for (name in args) Class.forName(name, true, loader)
        println("verified ${args.size} classes")
    }
}
