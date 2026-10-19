package jucs.commands

/*
 * ============================================================================
 * SystemCommands.kt —— system 域原生命令（commands 层）
 * ============================================================================
 *
 * 职责：
 *   提供 `system info get`：返回宿主与运行时信息（只读）。
 *
 * 为什么选它作为第一个跨领域命令：
 *   它不触碰文件系统、不需要权限作用域、结果稳定，能完整走通
 *   "Schema → 解析 → 门 → 执行 → 渲染"整条链，适合作为最小闭环的对照实现。
 *
 * English summary:
 *   The `system info get` read-only command.
 */

import jucs.registry.CommandHandler
import jucs.registry.CommandRegistry
import jucs.registry.RegistrationResult
import jucs.runtime.CommandData
import jucs.runtime.CommandOutcome
import jucs.schema.CommandSchema
import jucs.schema.Effects
import jucs.schema.OfficialVocabulary
import jucs.schema.Permission

/**
 * system 域命令集合。
 * English: commands in the `system` domain.
 */
object SystemCommands {

    /** 命令契约：`system info get`。 */
    val infoGetSchema: CommandSchema = CommandSchema(
        id = "system.info.get",
        publisher = "org.jasper.toolchain",
        version = "0.1.0",
        summaryZh = "读取宿主操作系统与 Jasper 运行环境的基本信息",
        summaryEn = "Read basic host and Jasper runtime information.",
        params = emptyList(),
        outputType = "Record<SystemInfo>",
        effects = Effects(readOnly = true),
        permissions = listOf(Permission("system.info.read")),
        idempotent = false,
        retryable = true,
    )

    /**
     * 处理器：读取真实宿主属性。
     * 所有字段都来自 `System.getProperty` / `Runtime`，不做任何硬编码伪造。
     * English: the handler reads real host properties; nothing is hard-coded.
     */
    val infoGetHandler: CommandHandler = CommandHandler { _, _ ->
        val runtime = Runtime.getRuntime()
        CommandOutcome(
            data = CommandData.Record(
                listOf(
                    "jucsApiVersion" to OfficialVocabulary.VERSION,
                    "osName" to System.getProperty("os.name"),
                    "osVersion" to System.getProperty("os.version"),
                    "osArch" to System.getProperty("os.arch"),
                    "javaVersion" to System.getProperty("java.version"),
                    "javaVendor" to System.getProperty("java.vendor"),
                    "cpuCount" to runtime.availableProcessors().toLong(),
                    "maxMemoryBytes" to runtime.maxMemory(),
                    "implementation" to "jucs (Kotlin reference implementation)",
                    "workingDirectory" to System.getProperty("user.dir"),
                ),
            ),
        )
    }

    /** 注册本域全部命令。 */
    fun register(registry: CommandRegistry): RegistrationResult =
        registry.register(infoGetSchema, infoGetHandler)
}
