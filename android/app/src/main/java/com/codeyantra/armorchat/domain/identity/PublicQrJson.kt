package com.codeyantra.armorchat.domain.identity

/** Bounded flat JSON reader. Rejects duplicate fields, nested values and trailing input. */
internal object PublicQrJson {
    fun read(text: String): Map<String, Any> = Reader(text).read()

    private class Reader(val text: String) {
        var position = 0
        fun whitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
        fun take(c: Char) {
            whitespace()
            require(position < text.length && text[position++] == c) { "Malformed QR JSON" }
        }
        fun string(): String {
            take('"')
            val out = StringBuilder()
            while (position < text.length) {
                val c = text[position++]
                if (c == '"') return out.toString()
                require(c.code >= 32) { "Control character in JSON" }
                if (c != '\\') { out.append(c); continue }
                require(position < text.length)
                when (val escape = text[position++]) {
                    '"', '\\', '/' -> out.append(escape)
                    'b' -> out.append('\b')
                    'f' -> out.append(12.toChar())
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        require(position + 4 <= text.length)
                        val hex = text.substring(position, position + 4)
                        require(hex.all { it in "0123456789abcdefABCDEF" })
                        out.append(hex.toInt(16).toChar()); position += 4
                    }
                    else -> error("Invalid JSON escape")
                }
            }
            error("Unterminated JSON string")
        }
        fun read(): Map<String, Any> {
            take('{')
            val fields = linkedMapOf<String, Any>()
            repeat(4) { index ->
                if (index > 0) take(',')
                val key = string()
                require(key in setOf("p", "v", "id", "pk") && key !in fields) { "Unexpected or duplicate QR field" }
                take(':'); whitespace()
                fields[key] = if (key == "v") {
                    // Version is a JSON integer; floats, strings, signs and future values are rejected.
                    require(position < text.length && text[position++] == '1') { "Unsupported QR version" }
                    1
                } else string()
            }
            take('}'); whitespace()
            require(position == text.length) { "Trailing QR data" }
            return fields
        }
    }
}
