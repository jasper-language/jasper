package jucs.commands

/*
 * ============================================================================
 * Builtins.kt —— 内建命令装配（commands 层）
 * ============================================================================
 *
 * 职责：
 *   把各领域的内建命令注册到同一个注册表，并保证"注册失败立即暴露"。
 *
 * 为什么注册失败要抛异常：
 *   内建命令的契约是源码里的常量。如果它不合法，那是实现缺陷而不是运行时
 *   输入问题；静默跳过会导致"命令莫名消失"。这里直接让程序在启动阶段失败。
 *
 * English summary:
 *   Assemble the built-in command registry; a bad built-in contract fails fast.
 */

import jucs.registry.CommandRegistry
import jucs.registry.RegistrationResult

/**
 * 内建命令装配器。
 * English: built-in command assembly.
 */
object Builtins {

    /**
     * 构造一个装好全部内建命令的注册表。
     * English: build a registry with all built-in commands.
     */
    fun registry(): CommandRegistry {
        val registry = CommandRegistry()
        listOf(
            SystemCommands.register(registry),
            FsCommands.register(registry),
        ).forEach { result ->
            if (result is RegistrationResult.Rejected) {
                throw IllegalStateException("内建命令契约不合法：${result.problems.joinToString("；")}")
            }
        }
        return registry
    }
}
