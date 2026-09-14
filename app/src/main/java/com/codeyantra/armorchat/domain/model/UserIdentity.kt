package com.codeyantra.armorchat.domain.model

/**
 * Public-only local user identity.
 *
 * Private key material never appears here — only Room-safe metadata and the
 * shareable ArmorChat ID / public key encoding.
 */
data class UserIdentity(
    val armorChatId: String,
    /** Base64url (unpadded) encoding of the 32-byte Ed25519 public key. */
    val publicIdentityKey: String,
    val createdAtEpochMs: Long,
    val version: Int = 1,
    /** Local display name. Not a cryptographic identity. */
    val displayName: String = "",
    /** Canonical handle without `@`. Not a cryptographic identity. */
    val handle: String = "",
) {
    val handleDisplay: String
        get() = handle.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
}
