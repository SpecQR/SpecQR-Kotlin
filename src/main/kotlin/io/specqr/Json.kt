package io.specqr

/** Bounded CLI codec. Not a general-purpose JSON API. */
internal object Json {
    private fun bad(message: String) = SpecQrException("INVALID_INPUT", message)
    @JvmStatic fun write(value: Any?): String = buildString { appendValue(value) }
    private fun StringBuilder.appendValue(value: Any?, depth: Int = 0, nodes: IntArray = intArrayOf(0)) {
        if (depth > 64 || ++nodes[0] > 1_000_000 || length > 32 * 1024 * 1024)
            throw bad("JSON output exceeds nesting, item, or size budget")
        fun array(values: Iterable<*>) {
            append('[')
            var first = true
            for (item in values) {
                if (!first) append(',')
                first = false
                appendValue(item, depth + 1, nodes)
            }
            append(']')
        }
        when (value) {
            null -> append("null")
            is String -> quote(value)
            is Boolean -> append(value)
            is Number -> {
                if (value is Double && !value.isFinite() || value is Float && !value.isFinite()) throw bad("Non-finite JSON number")
                append(value)
            }
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((key, item) in value) {
                    if (key !is String) throw bad("JSON keys must be strings")
                    if (!first) append(',')
                    first = false
                    quote(key)
                    append(':')
                    appendValue(item, depth + 1, nodes)
                }
                append('}')
            }
            is Iterable<*> -> array(value)
            is Array<*> -> array(value.asIterable())
            is BooleanArray -> array(value.asIterable())
            is ByteArray -> array(value.asSequence().map { it.toInt() and 255 }.asIterable())
            is ShortArray -> array(value.asIterable())
            is IntArray -> array(value.asIterable())
            is LongArray -> array(value.asIterable())
            is FloatArray -> array(value.asIterable())
            is DoubleArray -> array(value.asIterable())
            else -> throw bad("Unsupported JSON value")
        }
        if (length > 32 * 1024 * 1024) throw bad("JSON output exceeds size budget")
    }
    private fun StringBuilder.quote(value: String) {
        var required = length.toLong() + 2
        if (required + value.length > 32 * 1024 * 1024) throw bad("JSON string output exceeds size budget")
        for (c in value) {
            required += when (c) {
                '"', '\\', '\b', '\u000c', '\n', '\r', '\t' -> 2
                else -> if (c < ' ' || c.isSurrogate()) 6 else 1
            }
            if (required > 32 * 1024 * 1024) throw bad("JSON string output exceeds size budget")
        }
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ' || c.isSurrogate()) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
    @JvmStatic fun parse(text: String?): Any? {
        if (text == null || text.length > 4_000_000) throw bad("JSON input exceeds 4 MB")
        val parser = Parser(text)
        val result = parser.value(0)
        parser.space()
        if (parser.index != text.length) throw bad("Trailing JSON input")
        return result
    }
    private class Parser(val source: String) {
        var index = 0
        var nodes = 0
        fun space() { while (index < source.length && source[index] in " \t\r\n") index++ }
        fun take(c: Char): Boolean {
            if (index < source.length && source[index] == c) { index++; return true }
            return false
        }
        fun value(depth: Int): Any? {
            space()
            if (depth > 64 || ++nodes > 100_000) throw bad("JSON nesting or item budget exceeded")
            if (index == source.length) throw bad("Incomplete JSON")
            when (source[index]) {
                '"' -> return string()
                '[' -> {
                    index++
                    val values = mutableListOf<Any?>()
                    space()
                    if (take(']')) return values
                    do {
                        values.add(value(depth + 1))
                        space()
                        if (take(']')) return values
                    } while (take(','))
                    throw bad("Expected comma or bracket")
                }
                '{' -> {
                    index++
                    val values = linkedMapOf<String, Any?>()
                    space()
                    if (take('}')) return values
                    do {
                        space()
                        if (index == source.length || source[index] != '"') throw bad("Expected JSON key")
                        val key = string()
                        space()
                        if (!take(':')) throw bad("Expected colon")
                        if (values.containsKey(key)) throw bad("Duplicate JSON key")
                        values[key] = value(depth + 1)
                        space()
                        if (take('}')) return values
                    } while (take(','))
                    throw bad("Expected comma or brace")
                }
            }
            for (token in listOf("true", "false", "null")) if (source.startsWith(token, index)) {
                index += token.length
                return if (token == "null") null else token == "true"
            }
            val start = index
            take('-')
            if (index == source.length) throw bad("Invalid number")
            if (!take('0')) {
                val begin = index
                while (index < source.length && source[index] in '0'..'9') index++
                if (index == begin) throw bad("Invalid JSON value")
            }
            var decimal = false
            if (take('.')) {
                decimal = true
                val begin = index
                while (index < source.length && source[index] in '0'..'9') index++
                if (index == begin) throw bad("Invalid decimal")
            }
            if (index < source.length && source[index] in "eE") {
                decimal = true
                index++
                if (index < source.length && source[index] in "+-") index++
                val begin = index
                while (index < source.length && source[index] in '0'..'9') index++
                if (index == begin) throw bad("Invalid exponent")
            }
            val number = source.substring(start, index)
            if (decimal) {
                val result = number.toDoubleOrNull() ?: throw bad("JSON number out of range")
                if (!result.isFinite()) throw bad("Non-finite number")
                return result
            }
            return number.toLongOrNull() ?: throw bad("JSON number out of range")
        }
        fun string(): String {
            index++
            val out = StringBuilder()
            while (index < source.length) {
                val c = source[index++]
                if (c == '"') return out.toString()
                if (c < ' ') throw bad("Control character in JSON string")
                if (c != '\\') { out.append(c); continue }
                if (index == source.length) throw bad("Incomplete escape")
                when (val escape = source[index++]) {
                    '"', '\\', '/' -> out.append(escape)
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000c')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (index + 4 > source.length) throw bad("Incomplete Unicode escape")
                        var n = 0
                        repeat(4) {
                            val hex = source[index++]
                            val digit = when (hex) { in '0'..'9' -> hex - '0'; in 'A'..'F' -> hex - 'A' + 10; in 'a'..'f' -> hex - 'a' + 10; else -> -1 }
                            if (digit < 0) throw bad("Invalid Unicode escape")
                            n = n * 16 + digit
                        }
                        out.append(n.toChar())
                    }
                    else -> throw bad("Unknown JSON escape")
                }
            }
            throw bad("Unterminated string")
        }
    }
}
