package com.codeyantra.armorchat.domain.model

enum class VerificationMethod { QR_SCAN }

data class Contact(
    val id: String,
    val armorChatId: String,
    val publicIdentityKey: String,
    val displayName: String,
    val createdAtEpochMs: Long,
    val verificationMethod: VerificationMethod = VerificationMethod.QR_SCAN,
)

sealed interface ContactExchangeResult {
    data class Ready(val payload: com.codeyantra.armorchat.domain.identity.IdentityQrPayload) : ContactExchangeResult
    data class Saved(val contact: Contact) : ContactExchangeResult
    data class Duplicate(val contact: Contact) : ContactExchangeResult
    data object Self : ContactExchangeResult
    data object Conflict : ContactExchangeResult
    data object Invalid : ContactExchangeResult
    data object NoLocalIdentity : ContactExchangeResult
}
