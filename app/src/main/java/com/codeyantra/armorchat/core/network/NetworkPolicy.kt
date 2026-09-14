package com.codeyantra.armorchat.core.network

import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.google.crypto.tink.subtle.Ed25519Verify

enum class NetworkPolicyState {
    ACTIVE,
    SUSPENDED,
}

data class NetworkPolicy(
    val version: Long,
    val network: String,
    val state: NetworkPolicyState,
    val appliesTo: String,
    val policyId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val signature: String,
) {
    val blocksNetwork: Boolean get() = state == NetworkPolicyState.SUSPENDED
}

data class NetworkAvailability(
    val policyState: NetworkPolicyState = NetworkPolicyState.ACTIVE,
    val policyVerified: Boolean = false,
    val transportSuspended: Boolean = false,
    val policyId: String? = null,
) {
    val blocksNetwork: Boolean
        get() = policyState == NetworkPolicyState.SUSPENDED || transportSuspended

    val userMessage: String?
        get() = if (blocksNetwork) USER_MESSAGE else null

    companion object {
        const val USER_MESSAGE = "ArmorChat network temporarily unavailable"
    }
}

class UnsignedNetworkPolicyException(message: String) : SecurityException(message)

object ServicePolicyDocuments {
    const val PRODUCT = "armorchat-service-policy"
    const val CONTEXT = "ArmorChat/service-policy/v1"
    const val NETWORK = "codeyantra"
    const val APPLIES_TO = "codeyantra-authorized-relays"
    const val MAX_BYTES = 8192

    /**
     * Test-build trust anchor for CodeYantra-signed network policy.
     * Rotate before any production release; the matching seed is not packaged
     * in the APK.
     */
    const val TEST_BUILD_PUBLIC_KEY =
        "eJwljBNr82WGdrdf_-nZzIzuVepvt42IE6iSDMIgYu4"
}

data class ServicePolicyTrust(
    val publicKeyB64: String = ServicePolicyDocuments.TEST_BUILD_PUBLIC_KEY,
)

object NetworkPolicyVerifier {
    fun verify(
        raw: String,
        trust: ServicePolicyTrust,
        nowEpochSeconds: Long,
    ): NetworkPolicy {
        if (raw.toByteArray(Charsets.UTF_8).size > ServicePolicyDocuments.MAX_BYTES) {
            throw UnsignedNetworkPolicyException("policy too large")
        }
        val root = try {
            CanonicalJson.parseObjectStrict(raw)
        } catch (_: Exception) {
            throw UnsignedNetworkPolicyException("policy rejected")
        }
        if (root["p"] != ServicePolicyDocuments.PRODUCT) {
            throw UnsignedNetworkPolicyException("not a service policy")
        }
        if (root["v"] != 1L) throw UnsignedNetworkPolicyException("unsupported policy version")
        @Suppress("UNCHECKED_CAST")
        val signed = root["policy"] as? Map<String, Any?>
            ?: throw UnsignedNetworkPolicyException("policy envelope")
        if (signed.keys != setOf("body", "signature")) {
            throw UnsignedNetworkPolicyException("policy envelope fields")
        }
        @Suppress("UNCHECKED_CAST")
        val body = signed["body"] as? Map<String, Any?>
            ?: throw UnsignedNetworkPolicyException("policy body")
        val expectedKeys = setOf(
            "applies_to",
            "expires_at",
            "issued_at",
            "network",
            "policy_id",
            "state",
            "v",
        )
        if (body.keys != expectedKeys) throw UnsignedNetworkPolicyException("policy fields")
        if (body["v"] != 1L) throw UnsignedNetworkPolicyException("policy body version")
        if (body["network"] != ServicePolicyDocuments.NETWORK) {
            throw UnsignedNetworkPolicyException("unexpected network")
        }
        if (body["applies_to"] != ServicePolicyDocuments.APPLIES_TO) {
            throw UnsignedNetworkPolicyException("unexpected policy scope")
        }
        val stateRaw = body["state"] as? String ?: throw UnsignedNetworkPolicyException("state")
        val state = when (stateRaw) {
            "ACTIVE" -> NetworkPolicyState.ACTIVE
            "SUSPENDED" -> NetworkPolicyState.SUSPENDED
            else -> throw UnsignedNetworkPolicyException("unknown policy state")
        }
        val issuedAt = body["issued_at"] as? Long ?: throw UnsignedNetworkPolicyException("issued_at")
        val expiresAt = body["expires_at"] as? Long ?: throw UnsignedNetworkPolicyException("expires_at")
        val policyId = body["policy_id"] as? String ?: throw UnsignedNetworkPolicyException("policy_id")
        if (policyId.isEmpty() || policyId.length > 128) {
            throw UnsignedNetworkPolicyException("policy_id")
        }
        if (issuedAt < 0 || expiresAt <= issuedAt) throw UnsignedNetworkPolicyException("validity")
        if (expiresAt <= nowEpochSeconds) throw UnsignedNetworkPolicyException("policy expired")
        val signature = signed["signature"] as? String ?: throw UnsignedNetworkPolicyException("signature")
        val framed = CanonicalJson.frame(ServicePolicyDocuments.CONTEXT, body)
        val publicKey = try {
            Base64Url.decode(trust.publicKeyB64)
        } catch (_: Exception) {
            throw UnsignedNetworkPolicyException("trust anchor")
        }
        if (publicKey.size != 32) throw UnsignedNetworkPolicyException("trust anchor")
        val sigBytes = try {
            Base64Url.decode(signature)
        } catch (_: Exception) {
            throw UnsignedNetworkPolicyException("signature encoding")
        }
        if (sigBytes.size != 64) throw UnsignedNetworkPolicyException("signature encoding")
        try {
            Ed25519Verify(publicKey).verify(sigBytes, framed)
        } catch (_: Exception) {
            throw UnsignedNetworkPolicyException("policy signature rejected")
        }
        return NetworkPolicy(
            version = 1,
            network = ServicePolicyDocuments.NETWORK,
            state = state,
            appliesTo = ServicePolicyDocuments.APPLIES_TO,
            policyId = policyId,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            signature = signature,
        )
    }

    fun encode(body: Map<String, Any?>, signatureB64: String): String {
        return CanonicalJson.of(
            mapOf(
                "p" to ServicePolicyDocuments.PRODUCT,
                "policy" to mapOf(
                    "body" to body,
                    "signature" to signatureB64,
                ),
                "v" to 1L,
            ),
        )
    }

    fun body(
        state: NetworkPolicyState,
        policyId: String,
        issuedAt: Long,
        expiresAt: Long,
    ): Map<String, Any?> = mapOf(
        "applies_to" to ServicePolicyDocuments.APPLIES_TO,
        "expires_at" to expiresAt,
        "issued_at" to issuedAt,
        "network" to ServicePolicyDocuments.NETWORK,
        "policy_id" to policyId,
        "state" to state.name,
        "v" to 1L,
    )
}
