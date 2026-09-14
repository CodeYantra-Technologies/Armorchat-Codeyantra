package com.codeyantra.armorchat.core.json

/** RFC 8785 subset used by ArmorChat: sorted keys, integers, strings, objects, arrays. */
internal object CanonicalJson {
    fun quote(value: String): String {
        val out = StringBuilder("\"")
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) error("control") else out.append(ch)
            }
        }
        return out.append('"').toString()
    }

    fun of(value: Any?): String = when (value) {
        null -> error("null rejected")
        is Boolean -> error("boolean rejected")
        is Double, is Float -> error("float rejected")
        is Int -> value.toString()
        is Long -> {
            require(value >= 0) { "negative rejected" }
            value.toString()
        }
        is String -> quote(value)
        is Map<*, *> -> {
            val keys = value.keys.map { it as String }.sorted()
            keys.joinToString(",", "{", "}") { key ->
                "${quote(key)}:${of(value[key])}"
            }
        }
        is List<*> -> value.joinToString(",", "[", "]") { of(it) }
        else -> error("unsupported json value ${value::class.simpleName}")
    }

    fun frame(context: String, body: Map<String, Any?>): ByteArray {
        return context.toByteArray(Charsets.US_ASCII) + 0.toByte() + of(body).toByteArray(Charsets.UTF_8)
    }

    fun parse(text: String): Any? = Parser(text).parseValue()

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> = parse(text) as Map<String, Any?>

    fun parseObjectStrict(text: String): Map<String, Any?> {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skip()
        require(parser.i == parser.s.length) { "trailing json" }
        require(value is Map<*, *>) { "object required" }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private class Parser(val s: String) {
        var i = 0
        fun parseValue(): Any? {
            skip()
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                in '0'..'9' -> parseNumber()
                else -> error("json value at $i")
            }
        }
        fun parseObject(): Map<String, Any?> {
            i++
            val out = linkedMapOf<String, Any?>()
            skip()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skip()
                val k = parseString()
                skip()
                require(s[i++] == ':')
                require(k !in out) { "duplicate json key" }
                out[k] = parseValue()
                skip()
                when (s[i++]) {
                    '}' -> return out
                    ',' -> continue
                    else -> error("object")
                }
            }
        }
        fun parseArray(): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            skip()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(parseValue())
                skip()
                when (s[i++]) {
                    ']' -> return out
                    ',' -> continue
                    else -> error("array")
                }
            }
        }
        fun parseString(): String {
            require(s[i++] == '"')
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return out.toString()
                if (c != '\\') { out.append(c); continue }
                when (val e = s[i++]) {
                    '"', '\\', '/' -> out.append(e)
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    else -> error("escape")
                }
            }
            error("unterminated")
        }
        fun parseNumber(): Long {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            return s.substring(start, i).toLong()
        }
        fun skip() { while (i < s.length && s[i] in " \t\r\n") i++ }
    }
}
