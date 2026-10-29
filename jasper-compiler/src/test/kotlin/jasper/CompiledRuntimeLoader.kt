// ============================================================================
// CompiledRuntimeLoader.kt —— 测试用编译产物的类加载位置
// ============================================================================
// 做什么：把「标准库 + 测试用 Jasper 源码」编译到目录并返回能加载它们的类加载器。
//
// 为什么需要：Lincheck 2.39 判定 final 字段时只经系统类加载器读类文件，快照静态字段时用
//   Class.forName 按自己的加载器找类。编译产物若放在测试自建的 URLClassLoader 里，std 运行时
//   类要么插桩失败（静默不插桩、交错只发生在 Kotlin 驱动层，模型检查形同虚设），要么在运行中
//   抛 ClassNotFoundException。lincheckTest 任务因此把一个目录放上测试 JVM 的类路径，并通过系统
//   属性 jasper.lincheck.runtimeDir 告知；本加载器在该属性存在时编译到该目录、返回系统类加载器，
//   使 Lincheck 看到并插桩真实的 Jasper 运行时类。
//
// 前置条件：同一 JVM 内各调用方编译的是同一份标准库；用户源码的类名互不冲突（共享目录里
//   std 类文件被重复写入相同内容，用户类按名字并存）。
// 失败模式：编译失败抛 IllegalStateException，消息含编译结果。
// ============================================================================

package jasper

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths

object CompiledRuntimeLoader {
    /** lincheckTest 任务放上类路径的目录；普通 test 任务中为 null。 */
    private val sharedDir: String? = System.getProperty("jasper.lincheck.runtimeDir")

    /**
     * 编译 [sources]（含标准库）并返回加载器。普通测试 JVM：写到 build/tmp/<name>（先清空），
     * 返回只看该目录的 URLClassLoader；lincheckTest JVM：写到共享目录，返回系统类加载器。
     * 编译串行化：共享目录里的标准库类文件不能被两个编译同时写。
     */
    @Synchronized
    fun compile(name: String, sources: Map<String, String>): ClassLoader {
        val shared = sharedDir
        val output = if (shared != null) Paths.get(shared) else Paths.get("build", "tmp", name)
        if (shared == null) output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val result = JasperCompiler().compileAllWithStdlib(sources, output)
        check(result is JasperCompiler.CompileResult.Success) { "compilation failed for $name: $result" }
        return if (shared != null) ClassLoader.getSystemClassLoader() else URLClassLoader(arrayOf(output.toUri().toURL()))
    }
}
