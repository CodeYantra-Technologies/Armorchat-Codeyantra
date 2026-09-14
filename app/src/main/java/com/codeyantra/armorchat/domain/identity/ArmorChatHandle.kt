package com.codeyantra.armorchat.domain.identity

/**
 * User-facing ArmorChat handle. Not a cryptographic identity.
 *
 * Canonical value is stored without `@` (example: `neeraj-veltrix`).
 * Display form is `@neeraj-veltrix`. The Ed25519 public key remains the
 * trust anchor; a handle may change without changing `ac1` or the pin.
 */
@JvmInline
value class ArmorChatHandle private constructor(val canonical: String) {
    override fun toString(): String = display()

    fun display(): String = "@$canonical"

    companion object {
        const val MIN_LENGTH = 3
        const val MAX_LENGTH = 30

        private val REGEX = Regex("^[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){1,28}[a-z0-9]$")

        private val RESERVED = setOf(
            "armorchat", "admin", "administrator", "support", "help", "official",
            "veltrix", "codeyantra", "system", "root", "security", "everyone",
            "here", "channel", "relay", "invite", "contact", "contacts", "mls",
            "null", "undefined", "me", "self", "you", "add", "qr", "android",
            "grok", "owner", "moderator", "staff", "team", "service",
        )

        fun isValid(canonical: String): Boolean {
            if (canonical.length !in MIN_LENGTH..MAX_LENGTH) return false
            if (canonical.any { it.code > 0x7F }) return false
            if ("--" in canonical) return false
            if (!REGEX.matches(canonical)) return false
            if (canonical in RESERVED) return false
            return true
        }

        fun parse(raw: String): ArmorChatHandle? {
            val normalized = normalize(raw) ?: return null
            return if (isValid(normalized)) ArmorChatHandle(normalized) else null
        }

        fun parseOrThrow(raw: String): ArmorChatHandle =
            parse(raw) ?: error("Invalid ArmorChat handle")

        fun normalize(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withoutAt = if (trimmed.startsWith("@")) trimmed.substring(1) else trimmed
            if (withoutAt.any { it.code > 0x7F }) return null
            return withoutAt.lowercase()
        }

        fun suggest(displayName: String): String? {
            val builder = StringBuilder()
            var pendingHyphen = false
            for (ch in displayName.trim().lowercase()) {
                when {
                    ch in 'a'..'z' || ch in '0'..'9' -> {
                        if (pendingHyphen && builder.isNotEmpty()) builder.append('-')
                        pendingHyphen = false
                        builder.append(ch)
                    }
                    ch == ' ' || ch == '-' || ch == '_' || ch == '.' -> {
                        pendingHyphen = builder.isNotEmpty()
                    }
                }
                if (builder.length >= MAX_LENGTH) break
            }
            var candidate = builder.toString().trim('-')
            if (candidate.length > MAX_LENGTH) candidate = candidate.take(MAX_LENGTH).trim('-')
            if (candidate.length < MIN_LENGTH) return null
            if (candidate in RESERVED) {
                val suffixed = "$candidate-user"
                return if (isValid(suffixed)) suffixed else null
            }
            return if (isValid(candidate)) candidate else null
        }
    }
}
