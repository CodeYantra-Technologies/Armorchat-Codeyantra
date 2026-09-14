package com.codeyantra.armorchat.core.network

import kotlinx.coroutines.flow.Flow

/**
 * Replaceable transport for future ciphertext relay.
 *
 * Implementations:
 * - NoOpMessageTransport (production binding; chat send disabled)
 * - CodeYantraRelayTransport (Phase 1D.2 HTTPS client; not bound)
 * - PrivateRoutingTransport / MeshTransport (not in this phase)
 *
 * UI and domain must never depend on a concrete transport.
 */
interface MessageTransport {
    val isAvailable: Flow<Boolean>
    suspend fun send(envelope: OutboundEnvelope): TransportResult
    fun incoming(): Flow<InboundEnvelope>

    /**
     * Phase 1D.2 relay operations. Defaults keep [NoOpMessageTransport]
     * and existing tests unchanged. Implementations MUST NOT map any
     * relay response to DELIVERED or READ.
     *
     * [OutboundEnvelope.conversationId] is a local database key and
     * MUST NOT be placed on the wire.
     */
    suspend fun submitEnvelope(envelope: ImmutableRelayEnvelope): RelaySubmitResult =
        RelaySubmitResult.Unavailable

    suspend fun fetchInbox(cursor: String? = null, limit: Int = 10): RelayFetchResult =
        RelayFetchResult.Unavailable

    suspend fun consumeInbox(items: List<RelayConsumeItem>): RelayConsumeResult =
        RelayConsumeResult.Unavailable
}

data class OutboundEnvelope(
    val conversationId: String,
    val ciphertext: ByteArray,
)

data class InboundEnvelope(
    val conversationId: String,
    val ciphertext: ByteArray,
)

sealed interface TransportResult {
    data object Accepted : TransportResult
    data object Unavailable : TransportResult
    data class Failed(val reason: String) : TransportResult
}
