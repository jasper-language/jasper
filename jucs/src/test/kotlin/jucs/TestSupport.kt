package jucs

/*
 * ============================================================================
 * TestSupport.kt —— 测试基础设施（test 源集）
 * ============================================================================
 *
 * 职责：
 *   提供测试用的严格 JSON 读取器、命令行运行包装与临时目录管理。
 *
 * 为什么测试要自带严格 JSON 解析器：
 *   "stdout 是一个合法 JSON 对象"这条断言必须独立于被测代码的序列化实现，
 *   否则用自己的写法验证自己的输出，等于没验证。这里实现一个只支持
 *   JSON 子集（对象/数组/字符串/整数/布尔/null）的递归下降解析器，
 *   遇到任何多余字符、尾随逗号、非法转义一律失败。
 *
 * English summary:
 *   Test-only strict JSON reader plus CLI/temp-directory helpers.
 */

import jucs.cli.JucsCli
import jucs.registry.CommandRegistry
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * 一次命令行运行的结果。
 * English: captured result of one CLI run.
 */
data class CliRun(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * 用内存流运行命令。
 * English: run the CLI against in-memory streams.
 */
fun runCli(vararg argv: String): CliRun = runCliWith(null, *argv)

/**
 * 用指定注册表运行命令（测试自定义命令时使用）。
 * English: run the CLI with an explicit registry.
 */
fun runCliWith(registry: CommandRegistry?, vararg argv: String): CliRun {
    val out = StringWriter()
    val err = StringWriter()
    val cli = if (registry == null) {
        JucsCli(PrintWriter(out, true), PrintWriter(err, true))
    } else {
        JucsCli(PrintWriter(out, true), PrintWriter(err, true), registry)
    }
    val code = cli.run(argv.toList())
    return CliRun(code, out.toString(), err.toString())
}

/**
 * 创建一个临时目录，并在测试结束时删除。
 * English: create a temp directory that is removed after the test.
 */
fun withTempDir(block: (Path) -> Unit) {
    val dir = Files.createTempDirectory("jucs-test")
    try {
        block(dir)
    } finally {
        deleteRecursively(dir)
    }
}

/** 递归删除（仅用于测试创建的临时目录）。 */
private fun deleteRecursively(root: Path) {
    Files.walk(root).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { path ->
            runCatching { Files.deleteIfExists(path) }
        }
    }
}

/**
 * 严格 JSON 读取器（仅测试使用）。
 * English: strict JSON reader used only by tests.
 */
object TestJson {

    /**
     * 解析一个完整的 JSON 文档。
     * 失败模式：多余字符、尾随逗号、非法转义、非法数字表达式一律抛 [IllegalArgumentException]。
     * English: parse a whole document; strict about trailing content.
     */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        require(reader.atEnd()) { "JSON 文档后存在多余字符：\"${reader.rest()}\"" }
        return value
    }

    /** 解析并断言顶层是对象。 */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> {
        val value = parse(text)
        require(value is Map<*, *>) { "顶层不是 JSON 对象" }
        return value as Map<String, Any?>
    }

    /** 递归下降读取器。 */
    private class Reader(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun rest(): String = text.substring(index)

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            require(!atEnd()) { "JSON 意外结束" }
            return when (val ch = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    require(ch == '-' || ch.isDigit()) { "非法 JSON 字符 '$ch'（偏移 $index）" }
                    readNumber()
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                require(!result.containsKey(key)) { "重复的 JSON 键 \"$key\"" }
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                        require(peek() != '}') { "对象里出现尾随逗号" }
                    }
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("对象内应为 ',' 或 '}'（偏移 $index）")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                result += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                        require(peek() != ']') { "数组里出现尾随逗号" }
                    }
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("数组内应为 ',' 或 ']'（偏移 $index）")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sink = StringBuilder()
            while (true) {
                require(!atEnd()) { "字符串未闭合" }
                when (val ch = text[index]) {
                    '"' -> {
                        index++
                        return sink.toString()
                    }
                    '\\' -> {
                        index++
                        require(!atEnd()) { "转义序列未完成" }
                        when (val escape = text[index]) {
                            '"' -> sink.append('"')
                            '\\' -> sink.append('\\')
                            '/' -> sink.append('/')
                            'b' -> sink.append('\b')
                            'f' -> sink.append('\u000C')
                            'n' -> sink.append('\n')
                            'r' -> sink.append('\r')
                            't' -> sink.append('\t')
                            'u' -> {
                                require(index + 4 < text.length) { "\\u 转义长度不足" }
                                val hex = text.substring(index + 1, index + 5)
                                sink.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("非法转义 \\$escape（偏移 $index）")
                        }
                        index++
                    }
                    else -> {
                        require(ch >= ' ') { "字符串内出现未转义控制字符（偏移 $index）" }
                        sink.append(ch)
                        index++
                    }
                }
            }
        }

        private fun readNumber(): Long {
            val start = index
            if (peek() == '-') index++
            require(!atEnd() && text[index].isDigit()) { "数字缺少整数部分" }
            while (!atEnd() && text[index].isDigit()) index++
            require(atEnd() || text[index] !in ".eE") { "测试解析器只接受整数，拒绝浮点表示" }
            return text.substring(start, index).toLong()
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            require(text.startsWith(literal, index)) { "期望字面量 $literal（偏移 $index）" }
            index += literal.length
            return value
        }

        private fun peek(): Char {
            require(!atEnd()) { "JSON 意外结束" }
            return text[index]
        }

        private fun expect(ch: Char) {
            require(!atEnd() && text[index] == ch) { "期望 '$ch'（偏移 $index）" }
            index++
        }
    }
}
