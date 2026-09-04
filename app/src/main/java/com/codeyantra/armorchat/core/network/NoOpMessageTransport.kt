package com.codeyantra.armorchat.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

@Singleton
class NoOpMessageTransport @Inject constructor() : MessageTransport {
    override val isAvailable: Flow<Boolean> = flowOf(false)

    override suspend fun send(envelope: OutboundEnvelope): TransportResult {
        unused(envelope)
        return TransportResult.Unavailable
    }

    override fun incoming(): Flow<InboundEnvelope> = emptyFlow()

    private fun unused(value: Any?) = Unit
}
