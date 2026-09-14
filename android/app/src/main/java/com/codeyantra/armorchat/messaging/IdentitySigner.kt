package com.codeyantra.armorchat.messaging

/** Signs ArmorChat framed objects with the long-term Ed25519 identity. */
interface IdentitySigner {
    val pin: String
    fun sign(context: String, bodyCanonical: ByteArray): ByteArray
}

object ArmorChatContexts {
    const val KEY_PACKAGE = "ArmorChat/mls-keypackage/v1"
    const val ROUTE = "ArmorChat/route/v1"
    const val SERVICE_POLICY = "ArmorChat/service-policy/v1"
    const val CONTACT_V2 = "ArmorChat/contact-v2/v1"
    const val CONTACT_BUNDLE_V3 = "ArmorChat/contact-bundle-v3/v1"
    val allowed = setOf(KEY_PACKAGE, ROUTE, CONTACT_V2, CONTACT_BUNDLE_V3, "ArmorChat/device/v1")
}
