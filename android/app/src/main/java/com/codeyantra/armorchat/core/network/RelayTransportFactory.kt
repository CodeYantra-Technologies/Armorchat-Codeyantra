package com.codeyantra.armorchat.core.network

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelayTransportFactory @Inject constructor() {
    fun create(origin: String): CodeYantraRelayTransport {
        return CodeYantraRelayTransport(CodeYantraRelayTransport.parseOrigin(origin))
    }
}
