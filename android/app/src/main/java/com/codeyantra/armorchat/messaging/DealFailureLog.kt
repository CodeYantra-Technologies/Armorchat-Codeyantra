package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.security.IdentityStorageException
import com.codeyantra.armorchat.deal.domain.DealAlreadySignedException
import com.codeyantra.armorchat.deal.domain.DealConflictException
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealException
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealReviewRequiredException
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.ForeignPartyException
import com.codeyantra.armorchat.deal.domain.ImmutableVersionException
import java.security.GeneralSecurityException

/**
 * Sanitized diagnostics for failed debug deal actions. Only a fixed error code
 * and the exception's simple class name are logged. Exception messages,
 * payloads, keys, names and identifiers are never logged.
 */
object DealFailureLog {
    const val SIGNING_CONTEXT_REJECTED = "signing_context_rejected"
    const val DEAL_STATE = "deal_state"
    const val DEAL_FORMAT = "deal_format"
    const val DEAL_CONFLICT = "deal_conflict"
    const val DEAL_CRYPTO = "deal_crypto"
    const val DEAL_FOREIGN_PARTY = "deal_foreign_party"
    const val DEAL_IMMUTABLE = "deal_immutable_version"
    const val DEAL_OTHER = "deal_other"
    const val ALREADY_SIGNED = "already_signed"
    const val REVIEW_REQUIRED = "review_required"
    const val IDENTITY_STORAGE = "identity_storage"
    const val CRYPTO_FAILURE = "crypto_failure"
    const val PROTOCOL = "messaging_protocol"
    const val UNEXPECTED = "unexpected"

    private val SAFE_ACTION = Regex("^[a-z_]{1,32}$")
    private val SAFE_CLASS = Regex("^[A-Za-z0-9]{1,64}$")

    fun code(error: Throwable): String = when (error) {
        is DealAlreadySignedException -> ALREADY_SIGNED
        is DealReviewRequiredException -> REVIEW_REQUIRED
        is ImmutableVersionException -> DEAL_IMMUTABLE
        is ForeignPartyException -> DEAL_FOREIGN_PARTY
        is DealConflictException -> DEAL_CONFLICT
        is DealCryptoException -> DEAL_CRYPTO
        is DealStateException -> DEAL_STATE
        is DealFormatException -> DEAL_FORMAT
        is DealException -> DEAL_OTHER
        is IdentityStorageException -> IDENTITY_STORAGE
        is MessagingProtocolException -> PROTOCOL
        is GeneralSecurityException -> CRYPTO_FAILURE
        is IllegalArgumentException -> SIGNING_CONTEXT_REJECTED
        else -> UNEXPECTED
    }

    /** Simple class name restricted to alphanumerics; anything else becomes "Exception". */
    fun safeType(error: Throwable): String {
        val name = error::class.java.simpleName
        if (!SAFE_CLASS.matches(name)) return "Exception"
        return try {
            SafeLog.assertSafe(name)
            name
        } catch (_: IllegalArgumentException) {
            "Exception"
        }
    }

    fun line(action: String, error: Throwable): String {
        val safeAction = if (SAFE_ACTION.matches(action)) action else "deal_action"
        return "action=$safeAction result=FAIL code=${code(error)} type=${safeType(error)}"
    }

    fun record(action: String, error: Throwable) {
        try {
            SafeLog.event("deal_action_failed", line(action, error))
        } catch (_: Throwable) {
            // Diagnostics must never change the deal result.
        }
    }
}
