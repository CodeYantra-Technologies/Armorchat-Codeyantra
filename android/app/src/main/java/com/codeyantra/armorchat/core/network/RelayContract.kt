package com.codeyantra.armorchat.core.network

/**
 * Phase 1D.2 relay types. These stay behind [MessageTransport] / [RelayClient].
 * The relay never reports DELIVERED or READ.
 */
enum class RelayQueueState {
    ACCEPTED,
    CONSUMED,
    EXPIRED,
    UNKNOWN,
}

sealed interface RelayAvailability {
    data object Available : RelayAvailability
    data object Offline : RelayAvailability
    data class RetryAfter(val untilEpochSeconds: Long) : RelayAvailability
    data object ServiceSuspended : RelayAvailability
}

data class ImmutableRelayEnvelope(
    val protocolVersion: Int = 1,
    val suite: String = SUITE,
    val messageId: String,
    val recipientMailbox: String,
    val expiresAt: Long,
    val payloadType: String,
    val encryptedPayloadB64: String,
    val senderCredentialCanonical: String? = null,
) {
    fun canonicalJson(): String {
        val fields = linkedMapOf(
            "encrypted_payload" to jsonString(encryptedPayloadB64),
            "expires_at" to expiresAt.toString(),
            "message_id" to jsonString(messageId),
            "payload_type" to jsonString(payloadType),
            "protocol_version" to protocolVersion.toString(),
            "recipient_mailbox" to jsonString(recipientMailbox),
        )
        if (senderCredentialCanonical != null) {
            fields["sender_credential"] = senderCredentialCanonical
        }
        fields["suite"] = jsonString(suite)
        val sorted = fields.toSortedMap()
        return sorted.entries.joinToString(",", "{", "}") { (k, v) ->
            "${jsonString(k)}:$v"
        }
    }

    companion object {
        const val SUITE = "ac-mls-v1"
        const val PAYLOAD_HANDSHAKE = "handshake"
        const val PAYLOAD_APPLICATION = "application"
    }
}

sealed interface RelaySubmitResult {
    data class Accepted(
        val messageId: String,
        val recipientMailbox: String,
        val envelopeSha256: String,
        val acceptedAt: Long,
        val expiresAt: Long,
        val state: RelayQueueState,
        val replay: Boolean,
    ) : RelaySubmitResult

    data object Unavailable : RelaySubmitResult
    data class ServiceSuspended(val retryAfterSeconds: Long) : RelaySubmitResult
    data class Rejected(val reason: String) : RelaySubmitResult
}

data class LeasedRelayEnvelope(
    val messageId: String,
    val envelopeSha256: String,
    val encryptedPayloadB64: String,
    val payloadType: String,
    val expiresAt: Long,
    val leaseToken: String,
    val leaseUntil: Long,
    val senderCredentialCanonical: String? = null,
)

sealed interface RelayFetchResult {
    data class Leased(val envelopes: List<LeasedRelayEnvelope>, val cursor: String) : RelayFetchResult
    data object Unavailable : RelayFetchResult
    data class ServiceSuspended(val retryAfterSeconds: Long) : RelayFetchResult
    data class Rejected(val reason: String) : RelayFetchResult
}

data class RelayConsumeItem(
    val messageId: String,
    val envelopeSha256: String,
    val leaseToken: String,
)

sealed interface RelayConsumeResult {
    data class Done(val states: List<RelayQueueState>) : RelayConsumeResult
    data object Unavailable : RelayConsumeResult
    data class Rejected(val reason: String) : RelayConsumeResult
}

private fun jsonString(value: String): String {
    val out = StringBuilder("\"")
    value.forEach { ch ->
        when (ch) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> out.append(ch)
        }
    }
    return out.append('"').toString()
}

data class MailboxRegistration(
    val mailboxId: String,
    val ownerToken: String,
    val submitCapability: String,
) {
    override fun toString(): String =
        "MailboxRegistration(mailboxId=$mailboxId, ownerToken=[REDACTED], submitCapability=[REDACTED])"
}

data class ClaimedKeyPackage(
    val keyPackageId: String,
    val binding: String,
    val expiresAt: Long,
)

data class SubmitGrant(
    val grantId: String,
    val token: String,
    val kind: String,
    val mailboxId: String,
    val expiresAt: Long,
) {
    override fun toString(): String =
        "SubmitGrant(grantId=$grantId, kind=$kind, mailboxId=$mailboxId, expiresAt=$expiresAt, token=[REDACTED])"
}

const val GRANT_KIND_BOOTSTRAP = "bootstrap"
const val GRANT_KIND_PEER = "peer"
const val PEER_GRANT_TTL_SECONDS = 90L * 24L * 3600L

fun parseRelayQueueState(raw: String): RelayQueueState =
    when (raw) {
        "accepted" -> RelayQueueState.ACCEPTED
        "consumed" -> RelayQueueState.CONSUMED
        "expired" -> RelayQueueState.EXPIRED
        "unknown" -> RelayQueueState.UNKNOWN
        else -> error("relay emitted unsupported queue state")
    }
