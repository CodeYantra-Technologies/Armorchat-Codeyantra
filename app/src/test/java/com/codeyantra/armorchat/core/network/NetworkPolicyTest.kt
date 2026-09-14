package com.codeyantra.armorchat.core.network

import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkPolicyTest {
    private class PolicyKey {
        val handle: KeysetHandle
        val publicB64: String

        init {
            SignatureConfig.register()
            handle = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
            val pub = (handle.publicKeysetHandle.primary.key as Ed25519PublicKey)
                .publicKeyBytes.toByteArray()
            publicB64 = Base64Url.encode(pub)
        }

        fun sign(body: Map<String, Any?>): String {
            val framed = CanonicalJson.frame(ServicePolicyDocuments.CONTEXT, body)
            return Base64Url.encode(RawIdentitySignature.sign(handle, framed))
        }
    }

    private val now = 1_800_000_000L
    private val key = PolicyKey()
    private val trust = ServicePolicyTrust(key.publicB64)

    private fun document(state: NetworkPolicyState, mutator: (MutableMap<String, Any?>) -> Unit = {}): String {
        val body = NetworkPolicyVerifier.body(state, "pol-1", now, now + 86_400).toMutableMap()
        mutator(body)
        return NetworkPolicyVerifier.encode(body, key.sign(body))
    }

    @Test
    fun signedActivePolicyIsAccepted() {
        val policy = NetworkPolicyVerifier.verify(document(NetworkPolicyState.ACTIVE), trust, now)
        assertThat(policy.state).isEqualTo(NetworkPolicyState.ACTIVE)
        assertThat(policy.appliesTo).isEqualTo(ServicePolicyDocuments.APPLIES_TO)
        assertThat(policy.blocksNetwork).isFalse()
    }

    @Test
    fun signedSuspendedPolicyIsAccepted() {
        val policy = NetworkPolicyVerifier.verify(document(NetworkPolicyState.SUSPENDED), trust, now)
        assertThat(policy.state).isEqualTo(NetworkPolicyState.SUSPENDED)
        assertThat(policy.blocksNetwork).isTrue()
    }

    @Test
    fun unsignedAndTamperedPoliciesAreRejected() {
        val signed = document(NetworkPolicyState.ACTIVE)
        assertThrows(UnsignedNetworkPolicyException::class.java) {
            NetworkPolicyVerifier.verify(signed.replace("ACTIVE", "SUSPENDED"), trust, now)
        }
        val unsigned = signed.replace(Regex("\"signature\":\"[^\"]+\""), "\"signature\":\"\"")
        assertThrows(UnsignedNetworkPolicyException::class.java) {
            NetworkPolicyVerifier.verify(unsigned, trust, now)
        }
        val other = PolicyKey()
        assertThrows(UnsignedNetworkPolicyException::class.java) {
            NetworkPolicyVerifier.verify(signed, ServicePolicyTrust(other.publicB64), now)
        }
    }

    @Test
    fun controllerKeepsPreviousVerifiedPolicyWhenGarbageArrives() {
        val controller = ServicePolicyController(trust)
        controller.applySignedDocument(document(NetworkPolicyState.ACTIVE), now)
        assertThat(controller.current().policyState).isEqualTo(NetworkPolicyState.ACTIVE)
        controller.applySignedDocument(document(NetworkPolicyState.SUSPENDED), now)
        assertThat(controller.current().blocksNetwork).isTrue()
        controller.rejectUnsignedOrTampered()
        assertThat(controller.current().policyState).isEqualTo(NetworkPolicyState.SUSPENDED)
        assertThrows(UnsignedNetworkPolicyException::class.java) {
            controller.applySignedDocument("""{"p":"nope","v":1}""", now)
        }
        assertThat(controller.current().policyState).isEqualTo(NetworkPolicyState.SUSPENDED)
    }

    @Test
    fun sameSignedPolicyAppliesAcrossRelayNodes() {
        val doc = document(NetworkPolicyState.ACTIVE)
        val a = NetworkPolicyVerifier.verify(doc, trust, now)
        val b = NetworkPolicyVerifier.verify(doc, trust, now)
        assertThat(a.policyId).isEqualTo(b.policyId)
        assertThat(a.appliesTo).isEqualTo("codeyantra-authorized-relays")
    }
}
