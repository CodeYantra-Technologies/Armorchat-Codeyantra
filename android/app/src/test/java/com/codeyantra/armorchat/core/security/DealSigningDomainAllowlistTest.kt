package com.codeyantra.armorchat.core.security

import android.app.Application
import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.VerifiedDealParticipant
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.messaging.ArmorChatContexts
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Phase 5.3H: the real Tink identity signer accepts exactly the Armor Deal messaging domains. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DealSigningDomainAllowlistTest {
    private lateinit var store: TinkEd25519IdentityKeyStore
    private lateinit var publicKey: ByteArray
    private lateinit var participant: VerifiedDealParticipant

    @Before
    fun setUp() = runBlocking {
        val root = Files.createTempDirectory("tink-identity").toFile()
        store = realTinkIdentityStore(RuntimeEnvironment.getApplication(), root)
        publicKey = store.createAndStore()
        participant = VerifiedDealParticipant.verify(ArmorChatId.derive(publicKey), publicKey)
    }

    @Test
    fun allowlistIsExactlyTheFourMessagingDomains() {
        assertEquals(
            listOf(
                "ArmorDeal/identity-binding/v1",
                "ArmorDeal/legal-name/v1",
                "ArmorDeal/name-on-version/v1",
                "ArmorDeal/party-b-review/v1",
            ),
            DealIdentity.MESSAGING_SIGNER_DOMAINS,
        )
        assertEquals(DealIdentity.BINDING_DOMAIN + "\u0000", TinkEd25519IdentityKeyStore.DEAL_BINDING_PREFIX)
    }

    @Test
    fun everyAllowedDealStatementSignsAndVerifiesWithTheRealSigner() = runBlocking {
        val dealId = DealIds.generate()
        val versionId = DealIds.generate()
        val keys = DealKeyMaterial.generate()
        val hash = ByteArray(32) { it.toByte() }
        val payloads = listOf(
            DealIdentity.bindingPayload(dealId, PartyRole.COUNTERPARTY, participant, keys.signingPublic, keys.encryptionPublic),
            DealIdentity.legalNamePayload(dealId, PartyRole.COUNTERPARTY, participant, "Test Party B"),
            DealIdentity.nameOnVersionPayload(dealId, versionId, PartyRole.COUNTERPARTY, participant, "Test Party B", hash),
            DealIdentity.partyBReviewPayload(dealId, versionId, hash),
        )
        for (payload in payloads) {
            val before = payload.copyOf()
            val signature = store.signDealIdentityBinding(payload)
            assertEquals(64, signature.size)
            assertArrayEquals("payload bytes must not be modified", before, payload)
            DealIdentityCrypto.verify(publicKey, signature, payload)
            val tampered = payload.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            assertThrows(Exception::class.java) { DealIdentityCrypto.verify(publicKey, signature, tampered) }
        }
    }

    @Test
    fun chatDeviceUnknownAndMalformedDomainsAreRejected() {
        val body = ByteArray(40) { 7 }
        fun framed(prefix: String) = prefix.toByteArray(Charsets.US_ASCII) + body
        val forbidden = mutableListOf(
            framed("ArmorChat/device/v1\u0000"),
            framed("ArmorDeal/\u0000"),
            framed("ArmorDeal/x/v1\u0000"),
            framed("ArmorDeal/sign/v1\u0000"),
            framed("ArmorDeal/sign/v1"),
            framed("ArmorDeal/proposal-offer/v1\u0000"),
            framed("ArmorDeal/messaging-session/v1\u0000"),
            framed(DealIdentity.TERM_REVISION_DOMAIN + "\u0000"),
            DealIdentity.termRevisionPayload(ByteArray(16) { 3 }),
            framed("ArmorDeal/party-b-review/v1"),
            framed("ArmorDeal/party-b-review/v1\u0001"),
            framed("ArmorDeal/party-b-review/v1x\u0000"),
            framed("ArmorDeal/party-b-review/v2\u0000"),
            framed("armordeal/party-b-review/v1\u0000"),
            framed("\u0000ArmorDeal/party-b-review/v1\u0000"),
            (DealIdentity.PARTY_B_REVIEW_DOMAIN + "\u0000").toByteArray(Charsets.US_ASCII),
            "ArmorDeal/party-b-review".toByteArray(Charsets.US_ASCII),
            ByteArray(0),
            framed(DealIdentity.PARTY_B_REVIEW_DOMAIN + "\u0000") + ByteArray(8192),
        )
        listOf(
            ArmorChatContexts.KEY_PACKAGE,
            ArmorChatContexts.ROUTE,
            ArmorChatContexts.SERVICE_POLICY,
            ArmorChatContexts.CONTACT_V2,
            ArmorChatContexts.CONTACT_BUNDLE_V3,
        ).forEach { forbidden += framed(it + "\u0000") }
        for (payload in forbidden) {
            assertThrows(IllegalArgumentException::class.java) {
                TinkEd25519IdentityKeyStore.requireDealIdentityBinding(payload)
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.signDealIdentityBinding(payload) }
            }
        }
    }
}
