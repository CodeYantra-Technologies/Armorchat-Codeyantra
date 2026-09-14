package com.codeyantra.armorchat.domain.model

enum class VerificationMethod { QR_SCAN }

data class Contact(
    val id: String,
    val armorChatId: String,
    val publicIdentityKey: String,
    val displayName: String,
    val createdAtEpochMs: Long,
    val verificationMethod: VerificationMethod = VerificationMethod.QR_SCAN,
    val handle: String? = null,
    val previousHandle: String? = null,
    val handleChangedAtEpochMs: Long? = null,
) {
    val handleDisplay: String?
        get() = handle?.takeIf { it.isNotBlank() }?.let { "@$it" }
}

sealed interface ContactExchangeResult {
    data class Ready(val parsed: com.codeyantra.armorchat.domain.identity.ParsedContactQr) : ContactExchangeResult
    data class Saved(val contact: Contact, val parsed: com.codeyantra.armorchat.domain.identity.ParsedContactQr? = null) : ContactExchangeResult
    data class Duplicate(val contact: Contact, val parsed: com.codeyantra.armorchat.domain.identity.ParsedContactQr? = null) : ContactExchangeResult
    data class HandleChanged(
        val contact: Contact,
        val previousHandle: String?,
        val parsed: com.codeyantra.armorchat.domain.identity.ParsedContactQr? = null,
    ) : ContactExchangeResult
    data object Self : ContactExchangeResult
    data object Conflict : ContactExchangeResult
    data object Invalid : ContactExchangeResult
    data object NoLocalIdentity : ContactExchangeResult
    data object InviteExpired : ContactExchangeResult
    data object InviteUnknown : ContactExchangeResult
    data object InviteUnavailable : ContactExchangeResult
    data object NetworkUnavailable : ContactExchangeResult
    data object SecurityVerificationFailed : ContactExchangeResult
}
