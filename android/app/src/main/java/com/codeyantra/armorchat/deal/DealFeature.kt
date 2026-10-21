package com.codeyantra.armorchat.deal

/**
 * Armor Deal is a storage and cryptography foundation.
 *
 * Production UI, navigation, and recovery enrollment stay off until a reviewed
 * integration is approved. Technical signatures are evidence of key control
 * and document integrity. They are not Aadhaar eSign, a certificate-authority
 * signature, or a determination of legal identity or enforceability.
 */
object DealFeature {
    const val PRODUCTION_UI_ENABLED = false
    const val PROTOCOL_VERSION = 1
    const val CANONICAL_VERSION = 1

    /** Adds title, human roles, a calendar deadline, and payment conditions. Version 1 bytes do not change. */
    const val CANONICAL_VERSION_V2 = 2

    /**
     * Pins ArmorChat identities, deal keys, binding evidence, and legal-name slots.
     * Version 1 and version 2 bytes do not change. A stored document is never rewritten into version 3.
     */
    const val CANONICAL_VERSION_V3 = 3

    /**
     * Appends purpose and an optional effective date after the version 3 identity section.
     * Version 1, 2, and 3 bytes do not change. A stored document is never rewritten into version 4.
     */
    const val CANONICAL_VERSION_V4 = 4

    /** Versioned Armor Deal envelope carried inside an established one-to-one session. */
    const val MESSAGE_PROTOCOL_VERSION = 1
    const val ENCRYPTION_FORMAT_VERSION = 1
    const val RECOVERY_FORMAT_VERSION = 1
}
