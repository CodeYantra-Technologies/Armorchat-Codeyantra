package com.codeyantra.armorchat.domain.identity

import java.util.Base64

object Base64Url {
    fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(encoded: String): ByteArray =
        Base64.getUrlDecoder().decode(encoded.trim())
}
