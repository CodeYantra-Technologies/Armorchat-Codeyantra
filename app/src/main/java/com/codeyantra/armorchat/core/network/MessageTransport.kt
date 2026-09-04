package com.codeyantra.armorchat.core.network

import kotlinx.coroutines.flow.Flow

/**
 * Replaceable transport for future ciphertext relay.
 *
 * Planned implementations (not in this phase):
 * - CodeYantraRelayTransport
 * - PrivateRoutingTransport
 * - MeshTransport
 *
 * UI and domain must never depend on a concrete transport.
 */
interface MessageTransport {
    val isAvailable: Flow<Boolean>
    suspend fun send(envelope: OutboundEnvelope): TransportResult
    fun incoming(): Flow<InboundEnvelope>
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
