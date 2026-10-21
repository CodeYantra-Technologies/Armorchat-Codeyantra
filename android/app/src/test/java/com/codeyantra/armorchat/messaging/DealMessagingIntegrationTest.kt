package com.codeyantra.armorchat.messaging

import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.codeyantra.armorchat.core.security.realTinkIdentityStore
import com.codeyantra.armorchat.core.security.TinkEd25519IdentityKeyStore
import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.network.InMemoryRelayNetwork
import com.codeyantra.armorchat.core.network.RelaySubmitResult
import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.SealedDocument
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.domain.DealFinality
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.DealMessagingSigner
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.LegalNameStatus
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.domain.SigningStage
import com.codeyantra.armorchat.deal.domain.SigningWorkflow
import com.codeyantra.armorchat.deal.domain.VerifiedDealParticipant
import com.codeyantra.armorchat.deal.draft.DealDraftEditor
import com.codeyantra.armorchat.deal.draft.DealDraftStore
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealRepository
import com.codeyantra.armorchat.deal.storage.SigningProgressStore
import com.codeyantra.armorchat.deal.testing.SoftwareDealKeyProtector
import com.codeyantra.armorchat.deal.transport.DealEnvelope
import com.codeyantra.armorchat.deal.transport.DealEnvelopes
import com.codeyantra.armorchat.deal.transport.DealFrameChunk
import com.codeyantra.armorchat.deal.transport.DealFrameInbox
import com.codeyantra.armorchat.deal.transport.DealMessagingSession
import com.codeyantra.armorchat.deal.transport.DealOffers
import com.codeyantra.armorchat.deal.transport.DealTransportService
import com.codeyantra.armorchat.deal.transport.DealTransportState
import com.codeyantra.armorchat.deal.transport.DealWireEvent
import com.codeyantra.armorchat.deal.transport.ExportedSessionCipher
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Two identities, two deal databases, and one in-memory relay.
 * Fake MLS ciphertext is sealed again so the relay copy does not contain terms.
 * FakeMls itself is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DealMessagingIntegrationTest {
    @Test
    fun proposalAckBindsPartyBAndLeavesChatAndFinalityAlone() {
        val link = Link()
        val draft = link.draft(SECRET_TERMS)
        blocking {
        link.alice.receipts.sendText("before the deal")
        link.bob.receipts.receiveAll()
        link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
        val proposal = link.bob.peer.fetchLeased()
        assertTrue(proposal.isNotEmpty())
        proposal.forEach { item -> link.assertSealed(item.encryptedPayloadB64) }
        proposal.forEach { item -> link.bob.receipts.processLeased(item) }
        val dealId = link.dealId(draft)
        val bobDeal = link.bob.database.dealDao().findDeal(dealId)
        assertNotNull(bobDeal)
        assertEquals(DealStatus.PROPOSED.name, bobDeal!!.status)
        assertEquals(1, link.bob.database.dealDao().versionCount(dealId))
        val opened = link.open(link.bob, dealId, bobDeal.currentVersionId, PartyRole.COUNTERPARTY)
        assertEquals(DealFeature.CANONICAL_VERSION_V4, opened.canonicalVersion)
        assertEquals(SECRET_TERMS, opened.terms)
        val counterparty = opened.identity!!.pins.first { it.role == PartyRole.COUNTERPARTY }
        val payload = DealIdentity.bindingPayload(
            opened.dealId,
            PartyRole.COUNTERPARTY,
            counterparty.participant,
            counterparty.dealSigningPublicKey,
            counterparty.dealEncryptionPublicKey,
        )
        DealIdentityCrypto.verify(link.bob.publicKey, counterparty.bindingSignature!!, payload)
        assertFalse(counterparty.dealSigningPublicKey.contentEquals(link.bob.publicKey))
        val partyId = link.bob.database.dealDao().parties(dealId)
            .first { it.role == PartyRole.COUNTERPARTY.name }
            .partyId
        assertNull(link.alice.database.dealDao().findKeys(partyId))
        val privateKey = link.privateSigning(link.bob, partyId)
        val ack = link.alice.peer.fetchLeased().filter { it.payloadType != "handshake" }
        assertTrue(ack.isNotEmpty())
        ack.forEach { item ->
            link.assertSealed(item.encryptedPayloadB64)
            assertFalse(contains(Base64Url.decode(item.encryptedPayloadB64), privateKey))
        }
        ack.forEach { item -> link.alice.receipts.processLeased(item) }
        val aliceDeal = link.alice.database.dealDao().findDeal(dealId)
        assertEquals(bobDeal.currentVersionId, aliceDeal!!.currentVersionId)
        assertEquals(
            link.bob.database.dealDao().findVersion(dealId, bobDeal.currentVersionId)!!.documentSha256,
            link.alice.database.dealDao().findVersion(dealId, aliceDeal.currentVersionId)!!.documentSha256,
        )
        assertEquals(0, link.alice.database.dealDao().signatureCount(dealId))
        assertEquals(0, link.bob.database.dealDao().signatureCount(dealId))
        assertFalse(DealLifecycle.claimsFinality(DealStatus.valueOf(aliceDeal.status)))
        val verdict = DealFinality.assess(
            opened,
            emptyList(),
            opened.parties.associate { it.partyId to it.signingPublicKey },
        )
        assertFalse(verdict.proven)
        link.alice.receipts.sendText("after the deal")
        link.bob.receipts.receiveAll()
        assertEquals(listOf("before the deal", "after the deal"), link.bob.receipts.acceptedPlaintexts())
        assertTrue(link.bob.receipts.acceptedPlaintexts().none { it.contains(SECRET_TERMS) })
        assertTrue(link.bob.receipts.store.inbounds().none { it.kind == InnerKind.DEAL && it.text != null })
        }
        link.close()
    }

    @Test
    fun wrongSenderRecipientConversationForgedAckAndSelfDealAreRejected() {
        val link = Link()
        val draft = link.draft(SECRET_TERMS)
        blocking {
            link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
            link.deliver(link.bob)
            link.deliver(link.alice)
        }
        val dealId = link.dealId(draft)
        assertEquals(1, link.bob.database.dealDao().versionCount(dealId))
        val stored = link.alice.protector.open(
            link.alice.database.dealDao()
                .transportForDeal(dealId, DealTransportState.OUT, DealWireEvent.PROPOSAL.wire)
                .single()
                .sealedEnvelope,
            DealTransportService.PURPOSE,
        )
        val original = DealEnvelopes.parse(stored)
        val mallory = messaging()
        val malloryKey = DealIdentityCrypto.publicKey(mallory)
        val malloryId = ArmorChatId.derive(malloryKey)
        val wrongRecipient = original.copyEnvelope(
            envelopeId = DealIds.generate(),
            recipientId = malloryId,
            recipientKey = malloryKey,
        )
        blocking { link.alice.receipts.sendDealPayload(DealEnvelopes.encode(wrongRecipient)) }
        val recipientError = assertThrows(DealFormatException::class.java) {
            blocking { link.deliverLast(link.bob) }
        }
        assertTrue(recipientError.message!!.contains("not this device"))
        val wrongConversation = original.copyEnvelope(
            envelopeId = DealIds.generate(),
            conversationId = "conversation-other",
        )
        blocking { link.alice.receipts.sendDealPayload(DealEnvelopes.encode(wrongConversation)) }
        val conversationError = assertThrows(DealFormatException::class.java) {
            blocking { link.deliverLast(link.bob) }
        }
        assertTrue(conversationError.message!!.contains("different conversation"))
        val parsed = DealEnvelopes.parseProposal(original.payload)
        val forgedKeys = DealKeyMaterial.generate()
        val forged = DealEnvelope(
            event = DealWireEvent.ACK,
            envelopeId = DealIds.generate(),
            dealId = original.dealId,
            versionId = original.versionId,
            senderId = link.bob.user.armorChatId,
            recipientId = link.alice.user.armorChatId,
            senderKey = link.bob.publicKey,
            recipientKey = link.alice.publicKey,
            conversationId = link.alice.conversation.id,
            createdAtEpochMs = link.now,
            payload = DealEnvelopes.ackPayload(
                offerHash = DealOffers.hash(parsed.offerBytes),
                signingPublic = forgedKeys.signingPublic,
                encryptionPublic = forgedKeys.encryptionPublic,
                binding = ByteArray(64) { 7 },
                publicEncryptionKeyset = forgedKeys.serializedPublicEncryption(),
            ),
        )
        blocking { link.bob.receipts.sendDealPayload(DealEnvelopes.encode(forged)) }
        val ackError = assertThrows(DealFormatException::class.java) {
            blocking { link.deliverLast(link.alice) }
        }
        assertTrue(ackError.message!!.contains("Signature"))
        assertEquals(1, link.alice.database.dealDao().versionCount(dealId))
        assertEquals(0, link.alice.database.dealDao().signatureCount(dealId))
        assertEquals(
            DealTransportState.REJECTED,
            link.alice.database.dealDao().findTransport(forged.envelopeId)!!.status,
        )
        val session = link.alice.peer.sessionInfo()
        val route = link.alice.peer.peerRoute!!
        val now = link.now / 1000
        val impersonated = InnerRecord(
            kind = InnerKind.DEAL,
            messageId = Base64Url.encode(ByteArray(16) { 9 }),
            sessionId = session.sessionId,
            senderDeviceId = link.alice.peer.deviceId,
            senderGeneration = link.alice.peer.generation,
            recipientDeviceId = route.deviceId,
            recipientGeneration = route.generation,
            senderIdentityKey = Base64Url.encode(malloryKey),
            recipientIdentityKey = link.bob.peer.pin,
            recipientMailbox = route.mailboxId,
            createdAt = now,
            expiresAt = now + 3600,
            epoch = session.epoch,
            body = InnerBody.Deal(
                protocol = 1,
                envelopeId = DealIds.generate(),
                chunkIndex = 0,
                chunkCount = 1,
                frameSha256 = Base64Url.encode(ByteArray(32) { 3 }),
                chunk = Base64Url.encode(byteArrayOf(1, 2, 3)),
            ),
        )
        blocking { link.alice.receipts.submitInner(impersonated) }
        val senderError = assertThrows(MessagingProtocolException::class.java) {
            blocking { link.deliverLast(link.bob) }
        }
        assertTrue(senderError.message!!.contains("sender pin"))
        val reflected = VerifiedDealParticipant.verify(link.alice.user.armorChatId, link.alice.publicKey)
        val self = assertThrows(DealFormatException::class.java) {
            DealMessagingSession.establish("conversation-alice", reflected, reflected)
        }
        assertTrue(self.message!!.contains("themself"))
        assertEquals(1, link.bob.database.dealDao().versionCount(dealId))
        assertNull(link.bob.database.dealDao().findDeal("missing-deal"))
        link.close()
    }

    @Test
    fun replayAndDuplicateDeliveryDoNotCreateAnotherVersion() {
        val link = Link()
        val draft = link.draft(SECRET_TERMS)
        blocking {
        link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
        val leased = link.bob.peer.fetchLeased()
        leased.forEach { item -> link.bob.receipts.processLeased(item, consumeOnSuccess = false) }
        val dealId = link.dealId(draft)
        assertEquals(1, link.bob.database.dealDao().versionCount(dealId))
        leased.forEach { item -> link.bob.receipts.processLeased(item, consumeOnSuccess = false) }
        assertEquals(1, link.bob.database.dealDao().versionCount(dealId))
        assertEquals(0, link.bob.database.dealDao().signatureCount(dealId))
        }
        link.close()
    }

    @Test
    fun offlineRetryResendsTheSameCiphertextWithoutASecondVersion() {
        val link = Link()
        val draft = link.draft(SECRET_TERMS)
        var fails = 1
        val seen = mutableListOf<String>()
        link.alice.receipts.submitInterceptor = { env ->
            seen += env.canonicalJson()
            if (fails-- > 0) RelaySubmitResult.Rejected("TIMEOUT") else null
        }
        val waiting = assertThrows(MessagingProtocolException::class.java) {
            blocking {
                link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
            }
        }
        assertTrue(waiting.message!!.contains("waiting"))
        val dealId = link.dealId(draft)
        val queued = link.alice.database.dealDao()
            .transportForDeal(dealId, DealTransportState.OUT, DealWireEvent.PROPOSAL.wire)
            .single()
        assertEquals(DealTransportState.QUEUED, queued.status)
        blocking {
            link.alice.receipts.flushOutbox()
            link.deliver(link.bob)
            link.deliver(link.alice)
        }
        assertEquals(2, seen.size)
        assertEquals(seen[0], seen[1])
        assertEquals(1, link.bob.database.dealDao().versionCount(dealId))
        assertEquals(1, link.alice.database.dealDao().versionCount(dealId))
        blocking {
            link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
            link.deliver(link.bob)
        }
        assertEquals(1, link.bob.database.dealDao().versionCount(dealId))
        assertEquals(0, link.bob.database.dealDao().signatureCount(dealId))
        link.close()
    }

    @Test
    fun outOfOrderChunksAndInterruptedReceiveApplyOnce() {
        val link = Link()
        val draft = link.draft(SECRET_TERMS + "x".repeat(20_000))
        blocking {
        link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
        val leased = link.bob.peer.fetchLeased()
        assertTrue(leased.size >= 2)
        leased.forEach { item -> link.assertSealed(item.encryptedPayloadB64) }
        link.bob.receipts.processLeased(leased.last())
        val dealId = link.dealId(draft)
        assertNull(link.bob.database.dealDao().findDeal(dealId))
        leased.dropLast(1).forEach { item -> link.bob.receipts.processLeased(item) }
        assertEquals(1, link.bob.database.dealDao().versionCount(dealId))
        }
        link.close()

        val interrupted = Link()
        val second = interrupted.draft(SECRET_TERMS)
        blocking {
        interrupted.alice.bridge.sendDraft(
            second,
            interrupted.alice.user,
            interrupted.alice.conversation,
            interrupted.alice.contact,
        )
        val inbox = DealFrameInbox(
            interrupted.bob.database,
            interrupted.bob.protector,
            interrupted.bob.conversation.id,
            interrupted.clock,
        )
        interrupted.bob.receipts.dealInbound = { inbound ->
            val body = inbound.deal ?: error("deal")
            inbox.offer(
                inbound.messageId,
                DealFrameChunk(
                    envelopeId = body.envelopeId,
                    index = body.chunkIndex.toInt(),
                    count = body.chunkCount.toInt(),
                    frameSha256 = body.frameSha256,
                    bytes = Base64Url.decode(body.chunk),
                ),
            )
        }
        interrupted.deliver(interrupted.bob)
        val secondId = interrupted.dealId(second)
        assertNull(interrupted.bob.database.dealDao().findDeal(secondId))
        interrupted.bob.bridge.recover()
        assertEquals(1, interrupted.bob.database.dealDao().versionCount(secondId))
        interrupted.bob.bridge.recover()
        assertEquals(1, interrupted.bob.database.dealDao().versionCount(secondId))
        assertEquals(0, interrupted.bob.database.dealDao().signatureCount(secondId))
        }
        interrupted.close()
    }

    @Test
    fun meshCourierAndTamperedCiphertextDoNotApplyADeal() {
        val link = Link()
        val draft = link.draft(SECRET_TERMS)
        val leased = blocking {
            link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
            link.bob.peer.fetchLeased()
        }
        val mesh = leased.first().copy(envelopeSha256 = "mesh")
        val refused = assertThrows(MessagingProtocolException::class.java) {
            blocking { link.bob.receipts.processLeased(mesh, consumeOnSuccess = false) }
        }
        assertTrue(refused.message!!.contains("mesh"))
        val dealId = link.dealId(draft)
        assertNull(link.bob.database.dealDao().findDeal(dealId))
        val tampered = leased.first().copy(
            messageId = Base64Url.encode(ByteArray(16) { 4 }),
            encryptedPayloadB64 = Base64Url.encode(ByteArray(32) { 4 }),
        )
        val unauthenticated = assertThrows(MessagingProtocolException::class.java) {
            blocking { link.bob.receipts.processLeased(tampered, consumeOnSuccess = false) }
        }
        assertTrue(unauthenticated.message!!.contains("unauthenticated"))
        blocking { leased.forEach { item -> link.bob.receipts.processLeased(item) } }
        assertEquals(1, link.bob.database.dealDao().versionCount(dealId))
        link.close()
    }

    @Test
    fun realTinkKeystoreSignerCompletesReviewNamesAndSignOnBothPhones() {
        // Phase 5.3H regression: the device signer previously refused the
        // Party B review payload ("The review was not sent.").
        val link = Link(realKeystore = true)
        val draft = link.draft(SECRET_TERMS)
        blocking {
            link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
            link.drain()
            assertEquals(PartyRole.COUNTERPARTY, link.bob.bridge.signingSnapshot()!!.role)
            link.bob.bridge.reviewWithConsent()
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Test Party A")
            link.drain()
            link.bob.bridge.confirmNameWithConsent("Test Party B")
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Test Party A")
            link.drain()
            assertEquals(SigningStage.READY_TO_SIGN, link.alice.bridge.signingSnapshot()!!.stage)
            assertEquals(SigningStage.READY_TO_SIGN, link.bob.bridge.signingSnapshot()!!.stage)
            link.alice.bridge.signWithConsent()
            link.drain()
            assertEquals(SigningStage.PARTIALLY_SIGNED, link.bob.bridge.signingSnapshot()!!.stage)
            link.bob.bridge.signWithConsent()
            link.drain()
        }
        val dealId = link.dealId(draft)
        val aliceDeal = link.alice.database.dealDao().findDeal(dealId)!!
        val bobDeal = link.bob.database.dealDao().findDeal(dealId)!!
        assertEquals(aliceDeal.currentVersionId, bobDeal.currentVersionId)
        assertEquals(
            link.alice.database.dealDao().findVersion(dealId, aliceDeal.currentVersionId)!!.documentSha256,
            link.bob.database.dealDao().findVersion(dealId, bobDeal.currentVersionId)!!.documentSha256,
        )
        assertEquals(2, link.alice.database.dealDao().signatureCount(dealId))
        assertEquals(2, link.bob.database.dealDao().signatureCount(dealId))
        val aliceParty = link.partyId(link.alice, dealId, PartyRole.PROPOSER)
        val bobParty = link.partyId(link.bob, dealId, PartyRole.COUNTERPARTY)
        val aliceSnap = link.alice.bridge.signingSnapshot()!!
        val bobSnap = link.bob.bridge.signingSnapshot()!!
        assertEquals(SigningStage.FULLY_SIGNED, aliceSnap.stage)
        assertEquals(SigningStage.FULLY_SIGNED, bobSnap.stage)
        assertEquals(link.alice.user.armorChatId, bobSnap.proposerArmorChatId)
        assertEquals(link.bob.user.armorChatId, bobSnap.counterpartyArmorChatId)
        assertEquals(aliceSnap.proposerArmorChatId, bobSnap.proposerArmorChatId)
        assertTrue(DealRepository(link.alice.database, link.clock).assessStored(dealId, aliceParty, link.alice.protector).proven)
        assertTrue(DealRepository(link.bob.database, link.clock).assessStored(dealId, bobParty, link.bob.protector).proven)
        val bobOpened = link.open(link.bob, dealId, bobDeal.currentVersionId, PartyRole.COUNTERPARTY)
        assertEquals("Test Party B", bobOpened.identity!!.pin(bobParty).legalName)
        assertEquals("Test Party A", bobOpened.identity!!.pin(aliceParty).legalName)
        link.close()
    }

    @Test
    fun bothPhonesSignOnlyWithTheirOwnKeysAndFinalityFollowsTheHash() {
        val link = Link()
        val draft = link.draft(SECRET_TERMS)
        blocking {
            link.alice.receipts.sendText("before the deal")
            link.bob.receipts.receiveAll()
            link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
            link.drain()
            link.bob.bridge.reviewWithConsent()
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Neeraj Sharma")
            link.drain()
            link.bob.bridge.confirmNameWithConsent("Asha Menon")
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Neeraj Sharma")
            link.drain()
            assertEquals(SigningStage.READY_TO_SIGN, link.alice.bridge.signingSnapshot()!!.stage)
            assertEquals(SigningStage.READY_TO_SIGN, link.bob.bridge.signingSnapshot()!!.stage)
            link.alice.bridge.signWithConsent()
            link.drain()
            assertEquals(SigningStage.PARTIALLY_SIGNED, link.bob.bridge.signingSnapshot()!!.stage)
            assertFalse(link.bob.bridge.signingSnapshot()!!.proven)
            link.bob.bridge.signWithConsent()
            link.drain()
        }
        val dealId = link.dealId(draft)
        val aliceDeal = link.alice.database.dealDao().findDeal(dealId)!!
        val bobDeal = link.bob.database.dealDao().findDeal(dealId)!!
        assertEquals(aliceDeal.currentVersionId, bobDeal.currentVersionId)
        assertEquals(
            link.alice.database.dealDao().findVersion(dealId, aliceDeal.currentVersionId)!!.documentSha256,
            link.bob.database.dealDao().findVersion(dealId, bobDeal.currentVersionId)!!.documentSha256,
        )
        val aliceOpened = link.open(link.alice, dealId, aliceDeal.currentVersionId, PartyRole.PROPOSER)
        val bobOpened = link.open(link.bob, dealId, bobDeal.currentVersionId, PartyRole.COUNTERPARTY)
        assertEquals(SECRET_TERMS, aliceOpened.terms)
        assertEquals(SECRET_TERMS, bobOpened.terms)
        assertEquals("Neeraj Sharma", aliceOpened.identity!!.pin(link.partyId(link.alice, dealId, PartyRole.PROPOSER)).legalName)
        assertEquals("Asha Menon", bobOpened.identity!!.pin(link.partyId(link.bob, dealId, PartyRole.COUNTERPARTY)).legalName)
        assertEquals(2, link.alice.database.dealDao().signatureCount(dealId))
        assertEquals(2, link.bob.database.dealDao().signatureCount(dealId))
        val aliceParty = link.partyId(link.alice, dealId, PartyRole.PROPOSER)
        val bobParty = link.partyId(link.bob, dealId, PartyRole.COUNTERPARTY)
        assertNull(link.bob.database.dealDao().findKeys(aliceParty))
        assertNull(link.alice.database.dealDao().findKeys(bobParty))
        val aliceVerdict = DealRepository(link.alice.database, link.clock).assessStored(dealId, aliceParty, link.alice.protector)
        val bobVerdict = DealRepository(link.bob.database, link.clock).assessStored(dealId, bobParty, link.bob.protector)
        assertTrue(aliceVerdict.proven)
        assertTrue(bobVerdict.proven)
        val recovered = SigningProgressStore(link.alice.database.dealDao()).recover(aliceOpened)
        assertEquals(SigningStage.FULLY_SIGNED, recovered.stage)
        assertTrue(recovered.verdict().proven)
        assertEquals(2, recovered.signatures.map { it.partyId }.distinct().size)
        assertTrue(recovered.signatures.all { it.action == SignAction.SIGN })
        assertTrue(DealLifecycle.isFinalized(DealStatus.FULLY_SIGNED, recovered.verdict()))
        assertEquals(4, link.alice.database.openHelper.readableDatabase.version)
        assertFalse(DealFeature.PRODUCTION_UI_ENABLED)
        assertTrue(link.alice.database.dealDao().findVersion(dealId, aliceDeal.currentVersionId)!!.immutable)
        val proposal = link.open(link.alice, dealId, link.alice.database.dealDao().versions(dealId).first { it.parentVersionId == null }.versionId, PartyRole.PROPOSER)
        assertEquals(SECRET_TERMS, proposal.terms)
        assertEquals(LegalNameStatus.UNSET, proposal.identity!!.pins.first().nameStatus)
        assertStateRefused { link.alice.bridge.reviseBoundDeal("Changed terms") }
        assertStateRefused { link.bob.bridge.rejectBoundDeal("Declined.") }
        blocking {
            link.alice.receipts.sendText("after the deal")
            link.bob.receipts.receiveAll()
        }
        assertEquals(listOf("before the deal", "after the deal"), link.bob.receipts.acceptedPlaintexts())
        assertTrue(link.bob.receipts.acceptedPlaintexts().none { it.contains(SECRET_TERMS) || it.contains("Neeraj Sharma") })
        assertTrue(link.alice.database.dealDao().audits(dealId).none {
            it.outcome.contains(SECRET_TERMS) || it.outcome.contains("Neeraj Sharma") || it.outcome.contains("Asha Menon")
        })
        link.close()
    }

    @Test
    fun tamperReplayAndInvalidTransitionsLeaveTheCanonicalVersionAlone() {
        val link = Link()
        val draft = link.draft(SECRET_TERMS)
        blocking {
            link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
            link.drain()
        }
        assertStateRefused { link.alice.bridge.signWithConsent() }
        assertStateRefused { link.bob.bridge.signWithConsent() }
        val dealId = link.dealId(draft)
        assertEquals(0, link.alice.database.dealDao().signatureCount(dealId))
        val versionBefore = link.alice.database.dealDao().findDeal(dealId)!!.currentVersionId
        val forged = DealEnvelopes.encode(
            DealEnvelope(
                event = DealWireEvent.SIGNATURE,
                envelopeId = DealIds.generate(),
                dealId = dealId,
                versionId = versionBefore,
                senderId = link.alice.user.armorChatId,
                recipientId = link.bob.user.armorChatId,
                senderKey = link.alice.publicKey,
                recipientKey = link.bob.publicKey,
                conversationId = "conversation-alice",
                createdAtEpochMs = link.now,
                payload = DealEnvelopes.signaturePayload(
                    link.partyId(link.alice, dealId, PartyRole.PROPOSER),
                    ByteArray(64) { 9 },
                    link.dealSigningPublic(link.alice, dealId, PartyRole.PROPOSER),
                ),
            ),
        )
        val rejected = assertThrows(DealFormatException::class.java) {
            blocking { link.bob.bridge.acceptEnvelope(forged) }
        }
        assertTrue(rejected.message!!.contains("rejected") || rejected.message!!.isNotEmpty())
        assertEquals(0, link.bob.database.dealDao().signatureCount(dealId))
        assertEquals(
            DealTransportState.REJECTED,
            link.bob.database.dealDao().transportForDeal(dealId, DealTransportState.IN, DealWireEvent.SIGNATURE.wire).single().status,
        )
        blocking {
            link.bob.bridge.reviewWithConsent()
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Neeraj Sharma")
            link.drain()
        }
        assertTrue(link.alice.database.dealDao().versionCount(dealId) > 1)
        val current = link.alice.database.dealDao().findDeal(dealId)!!.currentVersionId
        assertNotEquals(versionBefore, current)
        val opened = link.open(link.alice, dealId, current, PartyRole.PROPOSER)
        assertEquals(SECRET_TERMS, opened.terms)
        assertEquals(versionBefore, opened.parentVersionId)
        val staleSign = DealEnvelopes.encode(
            DealEnvelope(
                event = DealWireEvent.SIGNATURE,
                envelopeId = DealIds.generate(),
                dealId = dealId,
                versionId = versionBefore,
                senderId = link.alice.user.armorChatId,
                recipientId = link.bob.user.armorChatId,
                senderKey = link.alice.publicKey,
                recipientKey = link.bob.publicKey,
                conversationId = "conversation-alice",
                createdAtEpochMs = link.now,
                payload = DealEnvelopes.signaturePayload(
                    link.partyId(link.alice, dealId, PartyRole.PROPOSER),
                    ByteArray(64) { 3 },
                    link.dealSigningPublic(link.alice, dealId, PartyRole.PROPOSER),
                ),
            ),
        )
        blocking { link.bob.bridge.acceptEnvelope(staleSign) }
        assertEquals(current, link.bob.database.dealDao().findDeal(dealId)!!.currentVersionId)
        assertEquals(0, link.bob.database.dealDao().signatureCount(dealId))
        blocking {
            link.bob.bridge.confirmNameWithConsent("Asha Menon")
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Neeraj Sharma")
            link.drain()
            link.alice.bridge.signWithConsent()
            link.drain()
        }
        assertEquals(1, link.bob.database.dealDao().signatureCount(dealId))
        val stored = link.bob.database.dealDao()
            .transportForDeal(dealId, DealTransportState.IN, DealWireEvent.SIGNATURE.wire)
            .first { it.status == DealTransportState.APPLIED }
        val plain = link.bob.protector.open(stored.sealedEnvelope, DealTransportService.PURPOSE)
        blocking { link.bob.bridge.acceptEnvelope(plain) }
        assertEquals(1, link.bob.database.dealDao().signatureCount(dealId))
        val partial = link.open(link.bob, dealId, link.bob.database.dealDao().findDeal(dealId)!!.currentVersionId, PartyRole.COUNTERPARTY)
        val crashed = SigningProgressStore(link.bob.database.dealDao()).recover(partial)
        assertEquals(SigningStage.PARTIALLY_SIGNED, crashed.stage)
        assertEquals(1, crashed.signatureCount)
        link.bob.database.dealDao().insertAudit(
            com.codeyantra.armorchat.deal.storage.DealAuditRow(
                eventId = DealIds.generate(),
                dealId = dealId,
                action = SigningWorkflow.JOURNAL_ACTION,
                outcome = "ok:!!!!",
                createdAtEpochMs = link.now,
            ),
        )
        val stillPartial = SigningProgressStore(link.bob.database.dealDao()).recover(partial)
        assertEquals(1, stillPartial.signatureCount)
        assertFalse(stillPartial.verdict().proven)
        blocking {
            link.bob.bridge.signWithConsent()
            link.drain()
        }
        val full = link.open(link.alice, dealId, link.alice.database.dealDao().findDeal(dealId)!!.currentVersionId, PartyRole.PROPOSER)
        val proven = SigningProgressStore(link.alice.database.dealDao()).recover(full)
        assertTrue(proven.verdict().proven)
        val cancel = SigningWorkflow.journalOutcome(SigningWorkflow.start(full)).let { outcome ->
            val bytes = Base64Url.decode(outcome.removePrefix("ok:"))
            bytes[bytes.size - 1] = 2
            "ok:" + Base64Url.encode(bytes)
        }
        link.alice.database.dealDao().insertAudit(
            com.codeyantra.armorchat.deal.storage.DealAuditRow(
                eventId = DealIds.generate(),
                dealId = dealId,
                action = SigningWorkflow.JOURNAL_ACTION,
                outcome = cancel,
                createdAtEpochMs = link.now + 1,
            ),
        )
        val kept = SigningProgressStore(link.alice.database.dealDao()).recover(full)
        assertEquals(SigningStage.FULLY_SIGNED, kept.stage)
        assertEquals(2, kept.signatureCount)
        assertTrue(kept.verdict().proven)
        link.close()
    }

    @Test
    fun revisionKeepsThePreviousVersionAndClearsSignatures() {
        val link = Link()
        val draft = link.draft(SECRET_TERMS)
        blocking {
            link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
            link.drain()
            link.bob.bridge.reviewWithConsent()
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Neeraj Sharma")
            link.drain()
            link.bob.bridge.confirmNameWithConsent("Asha Menon")
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Neeraj Sharma")
            link.drain()
        }
        val dealId = link.dealId(draft)
        val beforeId = link.alice.database.dealDao().findDeal(dealId)!!.currentVersionId
        val beforeHash = link.alice.database.dealDao().findVersion(dealId, beforeId)!!.documentSha256
        val beforeBytes = link.alice.database.dealDao().findVersion(dealId, beforeId)!!.sealedDocument.copyOf()
        blocking {
            link.alice.bridge.reviseBoundDeal("Replacement terms that stay sealed")
            link.drain()
        }
        assertEquals(beforeHash, link.alice.database.dealDao().findVersion(dealId, beforeId)!!.documentSha256)
        assertTrue(beforeBytes.contentEquals(link.alice.database.dealDao().findVersion(dealId, beforeId)!!.sealedDocument))
        val afterId = link.bob.database.dealDao().findDeal(dealId)!!.currentVersionId
        assertNotEquals(beforeId, afterId)
        val revised = link.open(link.bob, dealId, afterId, PartyRole.COUNTERPARTY)
        assertEquals("Replacement terms that stay sealed", revised.terms)
        assertEquals("Neeraj Sharma", revised.identity!!.pins.first { it.role == PartyRole.PROPOSER }.legalName)
        assertEquals("Asha Menon", revised.identity!!.pins.first { it.role == PartyRole.COUNTERPARTY }.legalName)
        assertEquals(0, link.bob.database.dealDao().signatures(dealId, afterId).size)
        assertEquals(SigningStage.PROPOSED, link.bob.bridge.signingSnapshot()!!.stage)
        val previous = link.open(link.alice, dealId, beforeId, PartyRole.PROPOSER)
        assertEquals(SECRET_TERMS, previous.terms)
        assertStateRefused { link.alice.bridge.signWithConsent() }
        assertEquals(0, link.alice.database.dealDao().signatureCount(dealId))
        link.close()
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    private fun assertStateRefused(block: suspend () -> Unit) {
        assertThrows(DealStateException::class.java) {
            runBlocking { block() }
        }
    }

    private class SealingMls(seed: ByteArray) {
        private val key = seed.copyOf()
        private val random = SecureRandom()

        fun transact(state: ByteArray, request: String): ByteArray {
            val req = CanonicalJson.parseObject(request).toMutableMap()
            val op = req["op"] as String
            val forwarded = if (op == "Decrypt") {
                req["ciphertext"] = unseal(req["ciphertext"] as String)
                CanonicalJson.of(req)
            } else {
                request
            }
            val raw = FakeMls.transact(state, forwarded)
            if (op != "Encrypt") return raw
            val staged = CanonicalJson.parseObject(String(raw, Charsets.UTF_8)).toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val output = (staged["output"] as Map<String, Any?>).toMutableMap()
            output["ciphertext"] = seal(output["ciphertext"] as String)
            staged["output"] = output
            return CanonicalJson.of(staged).toByteArray(Charsets.UTF_8)
        }

        private fun seal(fake: String): String {
            val nonce = ByteArray(12)
            random.nextBytes(nonce)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val body = cipher.doFinal(fake.toByteArray(Charsets.UTF_8))
            return Base64Url.encode(nonce + body)
        }

        fun unseal(sealed: String): String {
            val raw = Base64Url.decode(sealed)
            val nonce = raw.copyOfRange(0, 12)
            val body = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            return String(cipher.doFinal(body), Charsets.UTF_8)
        }
    }

    private class Identity(private val handle: KeysetHandle) : IdentitySigner {
        val keyHandle = handle
        override val pin: String = Base64Url.encode(DealIdentityCrypto.publicKey(handle))
        override fun sign(context: String, bodyCanonical: ByteArray): ByteArray {
            require(context in ArmorChatContexts.allowed)
            val prefix = context.toByteArray(Charsets.US_ASCII) + 0
            require(bodyCanonical.copyOf(prefix.size).contentEquals(prefix))
            return RawIdentitySignature.sign(handle, bodyCanonical)
        }
    }

    private class KeystoreSigner(
        private val store: TinkEd25519IdentityKeyStore,
        override val publicKey: ByteArray,
    ) : DealMessagingSigner {
        var signatures = 0
        override fun sign(payload: ByteArray): ByteArray =
            runBlocking { store.signDealIdentityBinding(payload) }.also { signatures++ }
        override fun toString(): String = "KeystoreSigner"
    }

    private class HandleSigner(private val handle: KeysetHandle) : DealMessagingSigner {
        override val publicKey: ByteArray = DealIdentityCrypto.publicKey(handle)
        override fun sign(payload: ByteArray): ByteArray = DealIdentityCrypto.sign(handle, payload)
        override fun toString(): String = "HandleSigner"
    }

    private class Phone(
        val database: DealDatabase,
        val protector: SoftwareDealKeyProtector,
        val user: UserIdentity,
        val contact: Contact,
        val conversation: Conversation,
        val publicKey: ByteArray,
        val peer: TwoPhonePeer,
        val receipts: ReceiptMessagingPeer,
        val bridge: DealChatBridge,
    )

    private class Link(private val realKeystore: Boolean = false) {
        var now: Long = 1_700_000_000_000L
        val clock: () -> Long = { now }
        private val network = InMemoryRelayNetwork("https://127.0.0.1:8444")
        private val sealer = SealingMls(ByteArray(32) { 9 })

        /** Payload check bound to the live FakeMls session; see assertSecretAbsent. */
        fun assertSealed(payloadB64: String) {
            val sessionId = checkNotNull(alice.peer.sessionId) { "alice session" }
            assertEquals(sessionId, bob.peer.sessionId)
            assertSecretAbsent(payloadB64, sealer::unseal, sessionId)
        }
        private val aliceHandle = messaging()
        private val bobHandle = messaging()
        private val aliceSigner: DealMessagingSigner = signerFor(aliceHandle, "alice")
        private val bobSigner: DealMessagingSigner = signerFor(bobHandle, "bob")
        val alice: Phone
        val bob: Phone

        init {
            val aliceKey = DealIdentityCrypto.publicKey(aliceHandle)
            val bobKey = DealIdentityCrypto.publicKey(bobHandle)
            val aliceUser = user(aliceKey, "Neeraj")
            val bobUser = user(bobKey, "Recipient")
            alice = phone(
                handle = aliceHandle,
                local = aliceUser,
                peer = bobUser,
                conversationId = "conversation-alice",
                storageSeed = ByteArray(32) { 4 },
            )
            bob = phone(
                handle = bobHandle,
                local = bobUser,
                peer = aliceUser,
                conversationId = "conversation-bob",
                storageSeed = ByteArray(32) { 5 },
            )
            runBlocking {
                alice.peer.createMlsDevice()
                bob.peer.createMlsDevice()
                alice.peer.registerAndPublish()
                bob.peer.registerAndPublish()
                alice.peer.importRoute(bob.peer.exportRoute(alice.peer.pin).toQr(), bob.peer.pin)
                bob.peer.importRoute(alice.peer.exportRoute(bob.peer.pin).toQr(), alice.peer.pin)
                alice.peer.initiateGroup()
                check(bob.peer.joinFromInbox())
            }
            alice.receipts.dealInbound = { alice.bridge.accept(it) }
            bob.receipts.dealInbound = { bob.bridge.accept(it) }
        }

        fun draft(terms: String): String {
            val opened = DealDraftStore(alice.database, alice.protector, clock = clock, debugBuild = true)
                .createFromChat(
                    local = alice.user,
                    conversation = alice.conversation,
                    contact = alice.contact,
                    editor = editor(terms),
                    localDealKeys = DealKeyMaterial.generate(),
                    localSigner = aliceSigner,
                )
            return opened.draftId
        }

        fun dealId(draftId: String): String =
            DealDraftStore(alice.database, alice.protector, clock = clock, debugBuild = true).open(draftId).ids.dealId

        suspend fun deliver(phone: Phone) {
            phone.peer.fetchLeased().forEach { item ->
                if (item.payloadType != "handshake") phone.receipts.processLeased(item)
            }
        }

        suspend fun deliverLast(phone: Phone) {
            val item = phone.peer.fetchLeased().last { it.payloadType != "handshake" }
            phone.receipts.processLeased(item)
        }

        /** Exchanges queued deal envelopes until both inboxes are idle. Terms stay sealed. */
        suspend fun drain(limit: Int = 12) {
            repeat(limit) {
                val aliceItems = alice.peer.fetchLeased().filter { it.payloadType != "handshake" }
                val bobItems = bob.peer.fetchLeased().filter { it.payloadType != "handshake" }
                if (aliceItems.isEmpty() && bobItems.isEmpty()) return
                for (item in aliceItems) {
                    assertSealed(item.encryptedPayloadB64)
                    alice.receipts.processLeased(item)
                }
                for (item in bobItems) {
                    assertSealed(item.encryptedPayloadB64)
                    bob.receipts.processLeased(item)
                }
            }
            val waiting = alice.peer.fetchLeased().any { it.payloadType != "handshake" } ||
                bob.peer.fetchLeased().any { it.payloadType != "handshake" }
            check(!waiting) { "deal exchange did not settle" }
        }

        fun partyId(phone: Phone, dealId: String, role: PartyRole): String =
            phone.database.dealDao().parties(dealId).first { it.role == role.name }.partyId

        fun dealSigningPublic(phone: Phone, dealId: String, role: PartyRole): ByteArray =
            Base64Url.decode(
                phone.database.dealDao().parties(dealId).first { it.role == role.name }.signingPublicKey,
            )

        fun open(phone: Phone, dealId: String, versionId: String, role: PartyRole): CanonicalDeal {
            val row = phone.database.dealDao().findVersion(dealId, versionId)!!
            val partyId = phone.database.dealDao().parties(dealId).first { it.role == role.name }.partyId
            val keys = keys(phone, partyId)
            val plain = DealCipher.decrypt(SealedDocument.parse(row.sealedDocument), partyId, keys.encryptionHandle())
            return CanonicalEncoding.parse(plain)
        }

        fun privateSigning(phone: Phone, partyId: String): ByteArray = keys(phone, partyId).serializedSigning()

        fun close() {
            alice.database.close()
            bob.database.close()
        }

        private fun keys(phone: Phone, partyId: String): DealKeyMaterial {
            val row = phone.database.dealDao().findKeys(partyId)!!
            val signing = phone.protector.open(row.sealedSigningKeyset, DealRepository.SIGNING_PURPOSE)
            val encryption = phone.protector.open(row.sealedEncryptionKeyset, DealRepository.ENCRYPTION_PURPOSE)
            return DealKeyMaterial.fromSerialized(signing, encryption)
        }

        private fun phone(
            handle: KeysetHandle,
            local: UserIdentity,
            peer: UserIdentity,
            conversationId: String,
            storageSeed: ByteArray,
        ): Phone {
            val database = Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                DealDatabase::class.java,
            ).allowMainThreadQueries().build()
            val protector = SoftwareDealKeyProtector(storageSeed)
            val localKey = Base64Url.decode(local.publicIdentityKey)
            val peerKey = Base64Url.decode(peer.publicIdentityKey)
            val localParticipant = VerifiedDealParticipant.verify(local.armorChatId, localKey)
            val peerParticipant = VerifiedDealParticipant.verify(peer.armorChatId, peerKey)
            val conversation = Conversation(conversationId, "contact-$conversationId", null, now)
            val contact = Contact(
                id = conversation.contactId,
                armorChatId = peer.armorChatId,
                publicIdentityKey = peer.publicIdentityKey,
                displayName = peer.displayName,
                createdAtEpochMs = now,
            )
            val identity = Identity(handle)
            val device = TwoPhonePeer(
                identity,
                network.client(),
                nowSeconds = { now / 1000 },
                transactMls = sealer::transact,
            )
            val receipts = ReceiptMessagingPeer(
                device,
                nowSeconds = { now / 1000 },
                jitter = { 0L },
            )
            val bridge = DealChatBridge(
                database = database,
                protector = protector,
                drafts = DealDraftStore(database, protector, clock = clock, debugBuild = true),
                cipher = ExportedSessionCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
                signer = if (handle === aliceHandle) aliceSigner else bobSigner,
                local = localParticipant,
                peerParticipant = peerParticipant,
                peer = receipts,
                debugBuild = true,
                clock = clock,
                localConversationId = conversationId,
            )
            return Phone(database, protector, local, contact, conversation, localKey, device, receipts, bridge)
        }

        /**
         * With [realKeystore], each phone signs through a real
         * TinkEd25519IdentityKeyStore holding that phone's messaging keyset,
         * exactly like ChatMessagingService.dealSigner on a device.
         */
        private fun signerFor(handle: KeysetHandle, name: String): DealMessagingSigner {
            if (!realKeystore) return HandleSigner(handle)
            val root = java.nio.file.Files.createTempDirectory("identity-$name").toFile()
            val store = realTinkIdentityStore(RuntimeEnvironment.getApplication(), root)
            val serialized = TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())
            val publicKey = runBlocking { store.importSerializedKeyset(serialized) }
            serialized.fill(0)
            check(publicKey.contentEquals(DealIdentityCrypto.publicKey(handle)))
            return KeystoreSigner(store, publicKey)
        }

        private fun user(publicKey: ByteArray, name: String) = UserIdentity(
            armorChatId = ArmorChatId.derive(publicKey),
            publicIdentityKey = Base64Url.encode(publicKey),
            createdAtEpochMs = now,
            displayName = name,
        )
    }

}

/**
 * Relay payloads must never carry the terms and must be the authenticated SealingMls form.
 * The former raw[0..1] != "E|" check was random: the sealed bytes begin with a random 12-byte
 * AES-GCM nonce, which matched "E|" with probability 2^-16 (Mesh-1K-S flake). The deterministic
 * check is that AES-GCM opening succeeds (a bare FakeMls "E|..." payload cannot authenticate)
 * and that the inner FakeMls ciphertext is bound to the expected session.
 */
private fun assertSecretAbsent(payloadB64: String, open: (String) -> String, sessionId: String) {
    assertFalse(payloadB64.contains(SECRET_TERMS))
    val raw = Base64Url.decode(payloadB64)
    assertFalse(contains(raw, SECRET_TERMS))
    val inner = try {
        open(payloadB64)
    } catch (e: java.security.GeneralSecurityException) {
        throw AssertionError("relay payload is not AES-GCM sealed", e)
    } catch (e: IllegalArgumentException) {
        throw AssertionError("relay payload is not AES-GCM sealed", e)
    }
    val fake = String(Base64Url.decode(inner), Charsets.UTF_8)
    assertTrue("inner FakeMls ciphertext not bound to session", fake.startsWith("E|$sessionId|"))
}

private fun messaging(): KeysetHandle {
    SignatureConfig.register()
    return KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
}

private fun editor(terms: String) = DealDraftEditor(
    title = "Service agreement",
    purpose = "Keep the purpose sealed",
    proposerRole = "Client",
    counterpartyRole = "Provider",
    terms = terms,
    paymentConditions = "Pay on acceptance of the work.",
    currency = "INR",
    amountMinor = 150050L,
    deadlineDate = "2026-12-31",
    effectiveDate = "2026-11-01",
    partyALegalName = "",
)

private fun DealEnvelope.copyEnvelope(
    envelopeId: String = this.envelopeId,
    senderId: String = this.senderId,
    recipientId: String = this.recipientId,
    senderKey: ByteArray = this.senderKey,
    recipientKey: ByteArray = this.recipientKey,
    conversationId: String = this.conversationId,
): DealEnvelope = DealEnvelope(
    event = event,
    envelopeId = envelopeId,
    dealId = dealId,
    versionId = versionId,
    senderId = senderId,
    recipientId = recipientId,
    senderKey = senderKey,
    recipientKey = recipientKey,
    conversationId = conversationId,
    createdAtEpochMs = createdAtEpochMs,
    payload = payload,
)

private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty() || haystack.size < needle.size) return false
    for (start in 0..haystack.size - needle.size) {
        if (needle.indices.all { haystack[start + it] == needle[it] }) return true
    }
    return false
}

private fun contains(haystack: ByteArray, needle: String): Boolean =
    contains(haystack, needle.toByteArray(Charsets.UTF_8))

private const val SECRET_TERMS = "UNIQUE_TERMS_MARKER_9421_NOT_PLAINTEXT"
