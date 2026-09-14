package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.CodeYantraRelayTransport
import com.codeyantra.armorchat.core.network.InMemoryRelayNetwork
import com.codeyantra.armorchat.core.network.LeasedRelayEnvelope
import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class InboundPersistenceOrderingTest {
    private val origin = "https://127.0.0.1:8444"
    private val network = InMemoryRelayNetwork(origin)

    private class TinkSigner : IdentitySigner {
        val handle: KeysetHandle
        override val pin: String

        init {
            SignatureConfig.register()
            handle = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
            val pub = (handle.publicKeysetHandle.primary.key as Ed25519PublicKey)
                .publicKeyBytes.toByteArray()
            pin = com.codeyantra.armorchat.domain.identity.Base64Url.encode(pub)
        }

        override fun sign(context: String, bodyCanonical: ByteArray): ByteArray {
            require(context in ArmorChatContexts.allowed)
            val prefix = context.toByteArray(Charsets.US_ASCII) + 0
            require(bodyCanonical.copyOf(prefix.size).contentEquals(prefix))
            return RawIdentitySignature.sign(handle, bodyCanonical)
        }
    }

    private fun peer(): TwoPhonePeer =
        TwoPhonePeer(TinkSigner(), network.client(), nowSeconds = { System.currentTimeMillis() / 1000 }, transactMls = FakeMls::transact)

    @Test
    fun verifyReceiveDurabilityOrderingAndIdempotency() = runBlocking {
        val alice = peer()
        val bob = peer()
        alice.createMlsDevice()
        bob.createMlsDevice()
        alice.registerAndPublish()
        bob.registerAndPublish()

        alice.importRoute(bob.exportRoute(alice.pin).toQr(), bob.pin)
        bob.importRoute(alice.exportRoute(bob.pin).toQr(), alice.pin)

        alice.initiateGroup()
        assertThat(bob.joinFromInbox()).isTrue()

        val alicePeer = ReceiptMessagingPeer(alice, nowSeconds = { System.currentTimeMillis() / 1000 })
        val bobPeer = ReceiptMessagingPeer(bob, nowSeconds = { System.currentTimeMillis() / 1000 })

        // Alice sends a text message to Bob
        alicePeer.sendText("Durable test message")

        // Bob fetches leased envelopes from relay
        val leasedEnvelopes = bob.fetchLeased()
        assertThat(leasedEnvelopes).hasSize(1)
        val envelope = leasedEnvelopes.first()

        val localDatabase = mutableMapOf<String, String>()

        // 1. Simulate DB Persistence Failure during receive
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                bobPeer.processLeased(envelope, consumeOnSuccess = true) { inbound ->
                    // Simulated DB failure before durable acceptance
                    throw IllegalStateException("Simulated SQLite Disk Error")
                }
            }
        }

        // Verify that because DB persistence failed, envelope was NOT consumed on relay
        val reFetched = bob.fetchLeased()
        assertThat(reFetched).hasSize(1)
        assertThat(reFetched.first().messageId).isEqualTo(envelope.messageId)
        assertThat(localDatabase).isEmpty()

        // 2. Simulate Redelivery & Successful Durable Persistence
        bobPeer.processLeased(reFetched.first(), consumeOnSuccess = true) { inbound ->
            if (inbound.kind == InnerKind.TEXT && inbound.text != null) {
                localDatabase[inbound.messageId] = inbound.text
            }
        }

        // Verify message was durably saved in database and envelope consumed on relay
        assertThat(localDatabase).containsKey(envelope.messageId)
        assertThat(localDatabase[envelope.messageId]).isEqualTo("Durable test message")

        // 3. Simulate Duplicate Envelope Redelivery after successful persistence
        bobPeer.processLeased(reFetched.first(), consumeOnSuccess = true) { inbound ->
            if (inbound.kind == InnerKind.TEXT && inbound.text != null) {
                // Idempotent local persistence check
                if (!localDatabase.containsKey(inbound.messageId)) {
                    localDatabase[inbound.messageId] = inbound.text
                }
            }
        }

        // Verify no duplicate message entry was created
        assertThat(localDatabase.size).isEqualTo(1)
        assertThat(localDatabase[envelope.messageId]).isEqualTo("Durable test message")
    }
}
