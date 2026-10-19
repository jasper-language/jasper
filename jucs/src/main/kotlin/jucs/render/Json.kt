package jucs.render

/*
 * ============================================================================
 * Json.kt —— 严格的 JSON 序列化器（render 层）
 * ============================================================================
 *
 * 职责：
 *   把 String / Long / Int / Boolean / null / List / Map / JucsDuration 序列化成
 *   合法 JSON 文本。
 *
 * 为什么自己写而不是引入第三方库：
 *   P1 只需要"输出"这一个方向，且可用类型集合是封闭的。自己写可以把"遇到
 *   不支持的类型就报内部错误"变成硬规则，避免某天悄悄把对象 toString() 塞进
 *   机器协议。将来 P2 需要解析时再评估引入经过审计的库。
 *
 * 正确性要点：
 *   - 转义 `"`、`\` 与全部 U+0000..U+001F 控制字符；
 *   - 数字只用十进制整数，不输出浮点；
 *   - Map 保持插入顺序，输出稳定，便于测试与人工比对。
 *
 * English summary:
 *   A strict, dependency-free JSON writer for the closed set of value types.
 */

import jucs.schema.JucsDuration

/**
 * JSON 序列化器。
 * English: JSON writer for the closed JUCS value set.
 */
object Json {

    /**
     * 序列化任意受支持的值。
     * 不支持的类型抛出 [IllegalArgumentException]；调用方（执行器）会把它转成
     * `JUCS.INTERNAL`，绝不把不可靠的文本写进 stdout。
     * English: serialize a supported value; unsupported types throw.
     */
    fun write(value: Any?, sink: StringBuilder) {
        when (value) {
            null -> sink.append("null")
            is String -> writeString(value, sink)
            is Boolean -> sink.append(if (value) "true" else "false")
            is Int -> sink.append(value.toString())
            is Long -> sink.append(value.toString())
            is Short -> sink.append(value.toString())
            is Byte -> sink.append(value.toString())
            is JucsDuration -> writeString(value.toString(), sink)
            is List<*> -> {
                sink.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) sink.append(',')
                    write(element, sink)
                }
                sink.append(']')
            }
            is Map<*, *> -> {
                sink.append('{')
                var first = true
                value.forEach { (key, element) ->
                    require(key is String) { "JSON 对象的键必须是字符串，实际为 ${key?.javaClass?.name}" }
                    if (!first) sink.append(',')
                    first = false
                    writeString(key, sink)
                    sink.append(':')
                    write(element, sink)
                }
                sink.append('}')
            }
            else -> throw IllegalArgumentException("不支持的 JSON 取值类型：${value.javaClass.name}")
        }
    }

    /** 便捷入口：返回序列化后的字符串。 */
    fun toText(value: Any?): String {
        val sink = StringBuilder()
        write(value, sink)
        return sink.toString()
    }

    /** 写出一个 JSON 字符串字面量。 */
    private fun writeString(text: String, sink: StringBuilder) {
        sink.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> sink.append("\\\"")
                '\\' -> sink.append("\\\\")
                '\n' -> sink.append("\\n")
                '\r' -> sink.append("\\r")
                '\t' -> sink.append("\\t")
                '\b' -> sink.append("\\b")
                '\u000C' -> sink.append("\\f")
                else -> if (ch < ' ') {
                    sink.append("\\u")
                    sink.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sink.append(ch)
                }
            }
        }
        sink.append('"')
    }
}
