// =============================================================================
// R2dbcProbeTest.kt —— R2DBC 形状探针的验收测试
// =============================================================================
// 职责：
//   把 probes/r2dbc/ 下的 Jasper 探针源码交给**真实编译管线**
//   （ANTLR -> AST -> 注解 -> 符号表 -> 类型检查 -> Kotlin IR -> JVM 字节码），
//   写盘后用 URLClassLoader 加载并**真正执行**，对可观察结果做断言。
//
// 所属层次 / 与其他模块关系：
//   测试层。只依赖 jasper.compiler.JasperCompiler 的公开入口
//   （compileAllWithStdlib + CompileResult），不触碰编译器内部实现，保证测试
//   「描述行为」而非「锁定实现」。所有产物写入本模块 build/ 下，绝不用 /tmp 或
//   仓库外路径。
//
// 为什么必须真跑而不是只编译：
//   本探针要压的是**运行期**形状——订阅协议的回调顺序、取消后资源是否释放、
//   错误是否走 onError 通道。这些只有真加载真执行才能观察到；编译器缺陷多数
//   表现为 VerifyError/NoSuchMethodError，也必须真跑才会暴露。
//
// 对应关系：
//   与 JdbcProbeTest 同构（同步阻塞 IO 版本）。本测试是异步/挂起形状版本。
// =============================================================================

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
 * 编译并运行 `probes/r2dbc/` 下的 R2DBC 形状探针。
 *
 * 做什么：走完整编译管线并把内存假实现跑起来，断言异步形状的可观察结果。
 * 什么时候用：改动类型映射、接口分派、泛型擦除或字节码后端后运行。
 * 前置条件：`probes/r2dbc/` 相对模块工作目录可见（Gradle 测试 cwd 为模块目录）。
 * 失败模式：编译失败时以编译诊断断言失败；运行期由 JVM 抛出的
 *   VerifyError/NoSuchMethodError 直接使测试失败——这正是探针要暴露的信号。
 * 为什么集中在一个测试：整条订阅链路是一个端到端场景，拆开反而看不到
 *   取消/错误传播与正常投递之间的相互作用。
 */
class R2dbcProbeTest {

    /** 定位探针目录；从模块目录（Gradle 测试 cwd）向上回退一层也能找到。 */
    private fun probesDir(): Path {
        val candidates = listOf(Paths.get("../probes/r2dbc"), Paths.get("probes/r2dbc"))
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("probes/r2dbc not found from ${Paths.get("").toAbsolutePath()}")
    }

    /** 读取探针目录下全部 `.jas` 源文件，按文件名排序，保证编译输入确定。 */
    private fun probeSources(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Files.list(probesDir()).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jas") }
                .sorted()
                .forEach { p -> out[p.fileName.toString()] = String(Files.readAllBytes(p)) }
        }
        return out
    }

    /** 为本用例准备干净的输出目录；路径固定在模块 build/ 下。 */
    private fun freshOutputDir(name: String): Path {
        val base = Paths.get("build", "scratch", "r2dbc-probe", name)
        if (Files.exists(base)) base.toFile().deleteRecursively()
        Files.createDirectories(base)
        return base
    }

    @Test
    fun `r2dbc probe compiles and the in-memory reactive pipeline runs`() {
        val sources = probeSources()
        assertTrue(sources.isNotEmpty(), "probes/r2dbc should contain at least one .jas file")

        val outputDir = freshOutputDir("run")
        val result = JasperCompiler().compileAllWithStdlib(sources, outputDir)
        assertTrue(
            result is JasperCompiler.CompileResult.Success,
            "R2DBC probe failed to compile: " +
                (result as? JasperCompiler.CompileResult.Error)?.message.orEmpty()
        )

        val loader = URLClassLoader(arrayOf(outputDir.toUri().toURL()))

        // 端到端场景：begin -> query -> take(2) -> cancel -> onError -> rollback -> 游标。
        val mainValue = loader.loadClass("std.main").getMethod("main").invoke(null)
        assertEquals(
            35, mainValue,
            "聚合结果应为 sum(6)+count(3)+completed(1)+take.count(2)+take.completed(1)" +
                "+cancelled(0)+released(10)+error(1)+rollback(1)+resultRows(3)+task(7)"
        )

        // 「任务/挂起」的同步占位闭环。
        val taskValue = loader.loadClass("std.taskValue").getMethod("taskValue").invoke(null)
        assertEquals(7, taskValue, "Task 同步占位应返回其完成值")

        // 错误层次 + 跨包链接：probes.r2dbc.TransientR2dbcException 继承 R2dbcException。
        val errorState = loader.loadClass("std.errorState").getMethod("errorState").invoke(null)
        assertEquals("40001", errorState, "R2dbcException 应携带构造时传入的 SQLSTATE")

        // 子类实例化 + 继承自 std.Error 的 message()：验证二级错误层次的跨包链接。
        val transientMessage = loader.loadClass("std.transientMessage").getMethod("transientMessage").invoke(null)
        assertEquals("deadlock", transientMessage, "TransientR2dbcException 应可实例化并读到继承的消息")

        // 接口常量确实被发射（getstatic 可分派）。作为形状证据记录。
        val fluxFields = loader.loadClass("std.Flux").declaredFields.joinToString(",") { it.name }
        assertTrue(fluxFields.contains("FETCH_SIZE"), "Flux 接口常量 FETCH_SIZE 应被发射，实际字段=[$fluxFields]")

        // 把反射观测写入 build/ 下的报告文件，便于人工核对（不使用外部临时目录）。
        Files.createDirectories(Paths.get("build", "scratch"))
        Files.write(
            Paths.get("build", "scratch", "r2dbc-probe-report.txt"),
            buildString {
                appendLine("probe-files=${sources.keys.sorted().joinToString(",")}")
                appendLine("main-result=$mainValue")
                appendLine("task-value=$taskValue")
                appendLine("error-state=$errorState")
                appendLine("transient-message=$transientMessage")
                appendLine("Flux.declaredFields=[$fluxFields]")
            }.toByteArray()
        )
    }
}
