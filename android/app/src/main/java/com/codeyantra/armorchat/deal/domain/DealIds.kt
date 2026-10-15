package com.codeyantra.armorchat.deal.domain

import com.codeyantra.armorchat.domain.identity.Base64Url
import java.security.SecureRandom

/** Stable opaque identifiers. Not derived from names, keys, or document text. */
object DealIds {
    private val PATTERN = Regex("^[A-Za-z0-9_-]{22,43}$")

    fun isValid(value: String): Boolean = PATTERN.matches(value)

    fun require(value: String): String {
        if (!isValid(value)) throw DealFormatException("Identifier is not a stable opaque id.")
        return value
    }

    fun generate(random: SecureRandom = SecureRandom()): String {
        val raw = ByteArray(16)
        random.nextBytes(raw)
        return Base64Url.encode(raw)
    }
}
