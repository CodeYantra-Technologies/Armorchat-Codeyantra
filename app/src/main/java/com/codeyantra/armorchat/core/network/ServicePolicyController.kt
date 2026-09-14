package com.codeyantra.armorchat.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Official clients accept network policy only when it verifies under the
 * CodeYantra service-policy public key. Unsigned or tampered documents are
 * rejected and the previous verified state is kept. Missing policy leaves
 * the default ACTIVE view; relay HTTP 503 remains a second enforcement layer.
 */
@Singleton
class ServicePolicyController @Inject constructor(
    private val trust: ServicePolicyTrust,
) {
    private val view = MutableStateFlow(NetworkAvailability())
    val availability: StateFlow<NetworkAvailability> = view.asStateFlow()

    @Volatile
    private var lastVerified: NetworkPolicy? = null

    fun current(): NetworkAvailability = view.value

    fun applySignedDocument(raw: String, nowEpochSeconds: Long): NetworkPolicy {
        val verified = NetworkPolicyVerifier.verify(raw, trust, nowEpochSeconds)
        lastVerified = verified
        view.update {
            it.copy(
                policyState = verified.state,
                policyVerified = true,
                policyId = verified.policyId,
            )
        }
        return verified
    }

    fun rejectUnsignedOrTampered(): NetworkAvailability {
        // Keep the last verified policy. Do not apply ACTIVE/SUSPENDED from garbage.
        return view.value
    }

    fun noteTransportSuspended() {
        view.update { it.copy(transportSuspended = true) }
    }

    fun noteTransportAvailable() {
        view.update { it.copy(transportSuspended = false) }
    }

    fun blocksNetwork(): Boolean = view.value.blocksNetwork
}
