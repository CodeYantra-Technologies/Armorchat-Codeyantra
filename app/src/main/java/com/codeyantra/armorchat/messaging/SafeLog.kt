package com.codeyantra.armorchat.messaging

import java.security.MessageDigest

/**
 * Messaging diagnostics must never include identity private keys, MLS HPKE
 * secrets, mailbox owner tokens, grant/submit tokens, plaintext, or recovery data.
 */
object SafeLog {
    private const val TAG = "ArmorChat"
    private val FORBIDDEN = listOf(
        "private_key",
        "privatekey",
        "identity_sk",
        "owner_token",
        "submit_capability",
        "bootstrap_grant",
        "grant_token",
        "hpke",
        "seed",
        "secret_key",
        "secretkey",
        "authorization",
        "bearer ",
        "recovery",
        "keystore",
        "plaintext",
        "localplaintext",
        "fcm_token",
        "fcmtoken",
        "registration_token",
    )

    fun qr(details: String) {
        if (com.codeyantra.armorchat.BuildConfig.DEBUG) event("qr_prepare", details)
    }

    fun assertSafe(message: String) {
        val lower = message.lowercase().replace("owner_token_available", "")
        for (token in FORBIDDEN) {
            require(!lower.contains(token)) { "refusing to log secret material" }
        }
    }

    fun redact(message: String): String {
        assertSafe(message)
        return message
    }

    fun shortRef(value: String?): String {
        if (value.isNullOrEmpty()) return "-"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(4).joinToString("") { b ->
            val v = b.toInt() and 0xff
            v.toString(16).padStart(2, '0')
        }
    }

    fun event(name: String, details: String = "") {
        val message = if (details.isBlank()) name else "$name $details"
        assertSafe(message)
        write(message)
    }

    fun failure(name: String, reason: String) {
        val safe = reason.replace(Regex("[\\r\\n]"), " ").take(120)
        event("$name fail", "reason=$safe")
    }

    private fun write(message: String) {
        try {
            android.util.Log.i(TAG, message)
        } catch (_: Throwable) {
            System.err.println("$TAG $message")
        }
    }
}
