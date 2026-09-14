package com.codeyantra.armorchat.domain.identity

/**
 * Crockford Base32 (no padding), uppercase output.
 * Alphabet excludes I, L, O, U to reduce transcription errors.
 */
object CrockfordBase32 {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val DECODE: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c ->
            table[c.code] = index
            table[c.lowercaseChar().code] = index
        }
        // Crockford aliases
        table['i'.code] = ALPHABET.indexOf('1')
        table['I'.code] = ALPHABET.indexOf('1')
        table['l'.code] = ALPHABET.indexOf('1')
        table['L'.code] = ALPHABET.indexOf('1')
        table['o'.code] = ALPHABET.indexOf('0')
        table['O'.code] = ALPHABET.indexOf('0')
    }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val output = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                bitsLeft -= 5
                output.append(ALPHABET[index])
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            output.append(ALPHABET[index])
        }
        return output.toString()
    }

    fun decode(encoded: String): ByteArray {
        val cleaned = encoded.trim().replace("-", "").replace(" ", "")
        if (cleaned.isEmpty()) return ByteArray(0)
        val out = ArrayList<Byte>((cleaned.length * 5) / 8)
        var buffer = 0
        var bitsLeft = 0
        for (ch in cleaned) {
            val code = ch.code
            if (code >= DECODE.size) {
                throw IllegalArgumentException("Invalid Crockford Base32 character: $ch")
            }
            val value = DECODE[code]
            if (value < 0) {
                throw IllegalArgumentException("Invalid Crockford Base32 character: $ch")
            }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}
