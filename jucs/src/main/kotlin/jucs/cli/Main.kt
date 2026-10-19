package jucs.cli

/*
 * ============================================================================
 * Main.kt —— jucs 进程入口与命令行分派（cli 层）
 * ============================================================================
 *
 * 职责：
 *   把一段 argv 推进到唯一执行者 [jucs.runtime.Invoker]，并把结果渲染到
 *   stdout / stderr，最后返回进程退出码。
 *
 * 处理顺序（顺序本身就是契约）：
 *   1. 全局元命令（`jucs --help` 等）；
 *   2. 三段身份 `<program> <resource> <action>` 的格式校验与命令查找；
 *   3. 命令级元命令（`--help` / `--version` / `--schema`）——先于业务参数校验，
 *      且绝不执行处理器；
 *   4. 业务参数解析 → 权限门 → 处理器 → 渲染。
 *
 * 为什么 stdout/stderr 分开注入：
 *   测试需要在不 fork 进程的情况下断言"stdout 只有数据"。构造函数接受两个
 *   PrintWriter，`main` 只是把 System.out / System.err 接进来。
 *
 * English summary:
 *   Process entry point and argv dispatch; all execution goes through one invoker.
 */

import jucs.commands.Builtins
import jucs.error.JucsCode
import jucs.error.JucsError
import jucs.error.JucsException
import jucs.registry.CommandRegistry
import jucs.render.OutputFormat
import jucs.render.Renderers
import jucs.runtime.ArgvParser
import jucs.runtime.InvocationMode
import jucs.runtime.Invoker
import jucs.runtime.RequestContext
import jucs.schema.JucsNames
import jucs.schema.OfficialVocabulary
import jucs.schema.SchemaCatalogue
import jucs.schema.SchemaJson
import java.io.PrintWriter
import java.util.UUID
import kotlin.system.exitProcess

/**
 * 进程入口。
 * English: process entry point.
 */
fun main(args: Array<String>) {
    val out = PrintWriter(System.out, true)
    val err = PrintWriter(System.err, true)
    exitProcess(JucsCli(out, err).run(args.toList()))
}

/**
 * 命令行分派器。
 * English: the CLI dispatcher.
 */
class JucsCli(
    private val out: PrintWriter,
    private val err: PrintWriter,
    private val registry: CommandRegistry = Builtins.registry(),
) {
    private val invoker = Invoker(registry)

    /** 实现版本；出现在全局 `--version`。 */
    private val implementationVersion: String = "0.1.0"

    /**
     * 执行一次命令行调用，返回退出码。
     * 不抛异常：所有失败都被转换成结构化错误 + 退出码。
     * English: run one invocation, returning the process exit code.
     */
    fun run(argv: List<String>): Int {
        val requestId = "req-" + UUID.randomUUID()
        var format = inferFormat(argv)
        try {
            if (argv.isEmpty()) {
                return fail(
                    JucsError(
                        JucsCode.COMMAND_MISSING,
                        "没有给出命令；用法：<program> <resource> <action> [--参数 取值]...",
                        requestId,
                    ),
                    format,
                )
            }
            if (argv[0].startsWith("--")) {
                return globalMeta(argv, requestId, format)
            }
            if (argv.size < 3) {
                return fail(
                    JucsError(
                        JucsCode.COMMAND_MISSING,
                        "命令必须由三段组成：<program> <resource> <action>",
                        requestId,
                        linkedMapOf("received" to argv),
                    ),
                    format,
                )
            }
            val (program, resource, action) = argv
            listOf("program" to program, "resource" to resource, "action" to action).forEach { (role, part) ->
                if (!JucsNames.isKebab(part)) {
                    throw JucsException(
                        JucsError(
                            JucsCode.ARGUMENT_FORMAT,
                            "命令的 $role 段必须是 ASCII 小写 kebab-case，实际为 \"$part\"",
                            requestId,
                            linkedMapOf("role" to role, "value" to part),
                        ),
                    )
                }
            }
            val id = "$program.$resource.$action"
            val registration = registry.find(id)
                ?: throw JucsException(
                    JucsError(
                        JucsCode.COMMAND_UNKNOWN,
                        "没有注册命令 $id；运行 jucs --help 查看已注册命令",
                        requestId,
                        linkedMapOf("id" to id, "known" to registry.all().map { it.schema.id }),
                    ),
                )
            val rest = argv.drop(3)
            val meta = ArgvParser.scanMeta(registration.schema, rest)
            if (meta.isNotEmpty()) {
                val distinct = meta.distinct()
                if (distinct.size > 1) {
                    throw JucsException(
                        JucsError(
                            JucsCode.ARGUMENT_CONFLICT,
                            "元命令不能同时使用：${distinct.joinToString(" ")}",
                            requestId,
                            linkedMapOf("meta" to distinct),
                        ),
                    )
                }
                when (distinct.single()) {
                    "help" -> out.print(HelpRenderer.command(registration.schema))
                    "version" -> out.print(HelpRenderer.version(registration.schema))
                    else -> out.print(SchemaJson.of(registration.schema))
                }
                out.flush()
                return 0
            }

            val parsed = ArgvParser.parse(registration.schema, rest, requestId)
            format = parsed.output
            val ctx = RequestContext(
                requestId = requestId,
                mode = InvocationMode.CLI,
                verbose = parsed.verbose,
                quiet = parsed.quiet,
                diagnostics = { line -> err.println(line) },
            )
            val outcome = invoker.invokeCall(registration, parsed.toCall(id), ctx)
            out.print(Renderers.success(outcome, parsed.output, requestId))
            out.flush()
            return outcome.status.exitCode
        } catch (e: JucsException) {
            return fail(e.error, format)
        } catch (e: Throwable) {
            return fail(
                JucsError(
                    JucsCode.INTERNAL,
                    "未预期的内部错误：${e.message ?: e.javaClass.simpleName}",
                    requestId,
                    linkedMapOf("type" to e.javaClass.name),
                ),
                format,
            )
        }
    }

    /** 全局元命令：不涉及任何具体命令的只读输出。 */
    private fun globalMeta(argv: List<String>, requestId: String, format: OutputFormat): Int {
        if (argv.size > 1) {
            return fail(
                JucsError(
                    JucsCode.ARGUMENT_UNKNOWN,
                    "全局元命令不接受附加参数；收到 ${argv.drop(1)}",
                    requestId,
                    linkedMapOf("arguments" to argv.drop(1)),
                ),
                format,
            )
        }
        when (argv[0]) {
            "--help" -> out.print(HelpRenderer.global(registry))
            "--version" -> out.print("jucs $implementationVersion (JUCS ${OfficialVocabulary.VERSION})\n")
            "--schema" -> out.print(SchemaCatalogue.of(registry.all().map { it.schema }))
            else -> return fail(
                JucsError(
                    JucsCode.ARGUMENT_UNKNOWN,
                    "未知的全局选项 ${argv[0]}；可用：--help / --version / --schema",
                    requestId,
                    linkedMapOf("option" to argv[0]),
                ),
                format,
            )
        }
        out.flush()
        return 0
    }

    /** 统一的失败出口：错误对象写 stderr，返回其退出码。 */
    private fun fail(error: JucsError, format: OutputFormat): Int {
        err.println(Renderers.error(error, format))
        err.flush()
        return error.exitCode
    }

    /**
     * 从 argv 推断输出格式，仅用于"还没解析成功"时决定错误怎么渲染。
     * 规则：找到 `--output` 且其后紧跟合法取值时采用；否则 table。
     * English: best-effort output format used before parsing succeeds.
     */
    private fun inferFormat(argv: List<String>): OutputFormat {
        val index = argv.indexOf("--output")
        if (index >= 0 && index + 1 < argv.size) {
            val candidate = argv[index + 1]
            OutputFormat.entries.firstOrNull { it.wireName == candidate }?.let { return it }
        }
        return OutputFormat.TABLE
    }
}
