package jucs.runtime

/*
 * ============================================================================
 * RequestContext.kt —— 单次调用的上下文（runtime 层）
 * ============================================================================
 *
 * 职责：
 *   携带一次调用的请求标识、调用方式（人类终端 / 结构化调用）、诊断级别，
 *   并提供唯一的诊断输出通道。
 *
 * 为什么诊断必须走上下文而不是 println：
 *   stdout 只允许数据。所有诊断必须能被静音、能被重定向到 stderr、能在结构化
 *   调用中被收集，因此必须经过一个显式通道。
 *
 * English summary:
 *   Per-invocation context: request id, invocation mode and the only diagnostic sink.
 */

/**
 * 调用方式。
 * English: how the call entered the runtime.
 */
enum class InvocationMode {
    /** 人类在终端输入的命令行。 */
    CLI,

    /** 机器（脚本 / IDE / AI）通过结构化 invoke 进入。 */
    MACHINE,
}

/**
 * 单次调用上下文。
 * English: one invocation context.
 */
class RequestContext(
    /** 请求标识：用于把日志、错误与审计记录串起来。 */
    val requestId: String,
    /** 调用方式。 */
    val mode: InvocationMode,
    /** 是否输出更多诊断。 */
    val verbose: Boolean = false,
    /** 是否抑制非必要诊断。 */
    val quiet: Boolean = false,
    /** 诊断输出通道；默认丢弃，由 CLI 注入 stderr 写入器。 */
    val diagnostics: (String) -> Unit = {},
) {
    /**
     * 输出一条诊断信息。
     * `quiet` 抑制 info 级，`verbose` 放行 debug 级；warn 永远输出。
     * English: emit a diagnostic honouring quiet/verbose levels.
     */
    fun diagnostic(level: DiagnosticLevel, message: String) {
        when (level) {
            DiagnosticLevel.INFO -> if (!quiet) diagnostics(message)
            DiagnosticLevel.DEBUG -> if (verbose && !quiet) diagnostics(message)
            DiagnosticLevel.WARN -> diagnostics(message)
        }
    }
}

/**
 * 诊断级别。
 * English: diagnostic severity levels.
 */
enum class DiagnosticLevel { INFO, DEBUG, WARN }
