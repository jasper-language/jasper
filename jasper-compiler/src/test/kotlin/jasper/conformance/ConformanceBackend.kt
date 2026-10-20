// ============================================================================
// ConformanceBackend.kt —— 一致性用例的可替换执行后端
// ----------------------------------------------------------------------------
// 职责：定义后端无关的编译/执行契约（ConformanceBackend + ConformanceOutcome），
//   并提供基于当前 JVM 实现的 JvmConformanceBackend。
// 边界：本文件不解析用例头部、不做期望比较；只负责把源码变成「拒绝诊断」或
//   「main 退出值」。临时产物只写入仓库内 build/ 目录。
// English: Defines the replaceable backend contract and the JVM implementation.
// ============================================================================

package jasper.conformance

import jasper.compiler.JasperCompiler
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * 后端执行结果：封闭成「编译拒绝」与「成功退出」两支。
 *
 * 做什么：运行器只需按这两支比较期望，不需要知道编译器内部错误类型或退出机制。
 * 什么时候用：[ConformanceBackend.compileAndRun] 的返回值。
 * 失败模式：本类型不表达「后端自身崩溃」；那类问题以异常抛出，直接让测试失败，
 *   避免把基础设施故障误判成语义拒绝。
 * 为什么这样设计：显式两支隔离了「语言拒绝」与「程序成功运行」这两种本质不同
 *   的观察结果，也隔离了未来新增后端的变化点。
 */
sealed class ConformanceOutcome {
    /** 编译被拒绝；[diagnostic] 是聚合后的完整诊断文本。 */
    data class Rejected(val diagnostic: String) : ConformanceOutcome()

    /** 编译成功并执行 main；[exitCode] 是 main 的整数返回值。 */
    data class Exited(val exitCode: Int) : ConformanceOutcome()
}

/**
 * 一致性用例执行后端。
 *
 * 做什么：约定「给定完整源码与稳定用例名，产出一个可比较结果」。
 * 什么时候用：一致性套件与运行器自检都通过本接口执行用例。
 * 前置条件：[source] 是完整 Jasper 源文件；[name] 在一次运行内唯一且可安全用于
 *   输出目录命名（实现仍应自行清洗）。
 * 失败模式：编译拒绝返回 [ConformanceOutcome.Rejected]；后端自身故障应抛异常，
 *   不应伪装成拒绝。
 * 为什么抽象成接口：将来加入新后端只需新增实现类，用例与断言层零改动
 *   （Strategy 模式隔离的变化点 = 执行后端）。
 */
interface ConformanceBackend {
    /** 编译并执行 [source]，用 [name] 标识本次运行的产物。 */
    fun compileAndRun(source: String, name: String): ConformanceOutcome
}

/**
 * 基于现有 JVM 编译管线的后端实现。
 *
 * 做什么：调用 `JasperCompiler.compileToDirWithIr` 写盘，再用 URLClassLoader
 *   加载 `main` 并反射调用；拒绝时把聚合诊断原样返回。
 * 什么时候用：本地与 CI 的默认一致性执行器。
 * 前置条件：运行环境提供可用的 JVM 编译器管线；用例的入口约定为包内 `main`。
 * 失败模式：编译错误返回 Rejected；类/方法缺失、调用抛异常或返回值不是数字时
 *   抛 [IllegalStateException]，附带用例名与产物目录，便于定位。
 * 为什么复用编译管线：一致性测试必须观察真实语义，而不是另写一套解释器；
 *   反射调用沿用仓库既有测试的验证方式，避免引入新的运行机制。
 *
 * @param workRoot 产物根目录，默认位于模块 build 下，保证不写项目外路径。
 */
class JvmConformanceBackend(
    private val workRoot: Path = Paths.get("build", "conformance", "jvm")
) : ConformanceBackend {

    override fun compileAndRun(source: String, name: String): ConformanceOutcome {
        val outputDir = outputDirFor(name)
        try {
            if (Files.exists(outputDir)) outputDir.toFile().deleteRecursively()
            Files.createDirectories(outputDir)

            val result = JasperCompiler().compileToDirWithIr(source, outputDir)
            if (result is JasperCompiler.CompileResult.Error) {
                return ConformanceOutcome.Rejected(result.message)
            }

            val className = mainClassName(source)
            URLClassLoader(arrayOf(outputDir.toUri().toURL())).use { loader ->
                val clazz = try {
                    loader.loadClass(className)
                } catch (e: ClassNotFoundException) {
                    throw IllegalStateException(
                        "用例 '$name' 编译成功但找不到入口类 '$className'（产物: $outputDir）",
                        e
                    )
                }
                val main = clazz.methods.firstOrNull { it.name == "main" && it.parameterCount == 0 }
                    ?: throw IllegalStateException(
                        "用例 '$name' 的入口类 '$className' 缺少无参 main（产物: $outputDir）"
                    )
                val raw = try {
                    main.invoke(null)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw IllegalStateException(
                        "用例 '$name' 的 main 执行失败: ${e.targetException.message}",
                        e.targetException
                    )
                }
                val number = raw as? Number
                    ?: throw IllegalStateException(
                        "用例 '$name' 的 main 返回值不是数字: ${raw?.javaClass?.name ?: "null"}"
                    )
                return ConformanceOutcome.Exited(number.toInt())
            }
        } finally {
            // 无论成功、拒绝还是异常，都不在仓库里留下本次产物。
            outputDir.toFile().deleteRecursively()
        }
    }

    /**
     * 为用例名生成确定且互不冲突的产物目录。
     *
     * 做什么：把名称中不适合做目录名的字符替换为下划线，并追加名称摘要。
     * 前置条件：[name] 非空。
     * 失败模式：无；摘要使用 SHA-256，不依赖随机数。
     * 为什么加摘要：仅清洗字符可能让两个不同用例落到同一目录，摘要保证区分。
     */
    private fun outputDirFor(name: String): Path {
        val sanitized = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(96)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
        return workRoot.resolve("$sanitized-$digest")
    }

    /**
     * 从源码的 package 声明推导入口类名。
     *
     * 前置条件：源码最多有一个 package 声明；没有 package 时入口类名按 `main` 处理。
     * 失败模式：不抛异常；正则未命中时使用无包名约定。
     * 为什么需要：用例允许显式包名，硬编码 `test.main` 会把后端与用例包名耦合。
     */
    private fun mainClassName(source: String): String {
        val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;""")
            .find(source)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        return if (packageName.isEmpty()) "main" else "$packageName.main"
    }
}
