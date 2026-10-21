package com.codeyantra.armorchat.deal

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealTink
import com.codeyantra.armorchat.deal.crypto.SealedDocument
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.domain.DealFinality
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.DealMessagingSigner
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.VerifiedDealParticipant
import com.codeyantra.armorchat.deal.draft.DealDraftEditor
import com.codeyantra.armorchat.deal.draft.DealDraftStore
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealRepository
import com.codeyantra.armorchat.deal.testing.SoftwareDealKeyProtector
import com.codeyantra.armorchat.deal.transport.DealEnvelope
import com.codeyantra.armorchat.deal.transport.DealEnvelopes
import com.codeyantra.armorchat.deal.transport.DealMessagingSession
import com.codeyantra.armorchat.deal.transport.DealOffer
import com.codeyantra.armorchat.deal.transport.DealOffers
import com.codeyantra.armorchat.deal.transport.DealTransportPreview
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
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DealTransportTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun partyADeliversToPartyBAndPartyBBindsItsOwnKey() {
        val link = Link()
        val draft = link.draft()
        val frame = link.capture(link.alice) {
            link.alice.service().queueProposal(draft, link.alice.user, link.conversation, link.alice.contact)
        }
        assertFalse(contains(frame, SECRET_TERMS))
        assertFalse(contains(frame, SECRET_PURPOSE))
        assertEquals(DealTransportPreview.LABEL, link.bob.service().previewLabel())
        assertFalse(link.bob.service().previewLabel().contains(SECRET_TERMS))
        val received = link.bob.service().receive(frame)
        assertEquals(DealTransportState.APPLIED, received.status)
        assertFalse(received.acceptedAgreement)
        assertFalse(received.finalized)
        assertFalse(received.toString().contains(SECRET_TERMS))
        link.flush(link.bob, link.alice)
        val aliceDeal = link.alice.database.dealDao().findDeal(link.dealId(draft))
        val bobDeal = link.bob.database.dealDao().findDeal(link.dealId(draft))
        assertEquals(DealStatus.PROPOSED.name, aliceDeal!!.status)
        assertEquals(aliceDeal.currentVersionId, bobDeal!!.currentVersionId)
        assertEquals(
            link.alice.database.dealDao().findVersion(aliceDeal.dealId, aliceDeal.currentVersionId)!!.documentSha256,
            link.bob.database.dealDao().findVersion(bobDeal.dealId, bobDeal.currentVersionId)!!.documentSha256,
        )
        assertEquals(0, link.alice.database.dealDao().signatureCount(aliceDeal.dealId))
        assertEquals(0, link.bob.database.dealDao().signatureCount(bobDeal.dealId))
        assertFalse(DealLifecycle.claimsFinality(DealStatus.valueOf(bobDeal.status)))
        val opened = link.openBob(bobDeal.dealId, bobDeal.currentVersionId)
        assertEquals(DealFeature.CANONICAL_VERSION_V4, opened.canonicalVersion)
        assertEquals(SECRET_PURPOSE, opened.annex!!.purpose)
        assertEquals("2026-11-01", opened.annex!!.effectiveDate)
        assertEquals("Pay on acceptance of the work.", opened.details!!.paymentConditions)
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
        assertNull(link.alice.database.dealDao().findKeys(opened.parties.first { it.role == PartyRole.COUNTERPARTY }.partyId))
        val bobPrivate = link.bobPrivate(opened.parties.first { it.role == PartyRole.COUNTERPARTY }.partyId)
        assertFalse(storedBlobs(link.alice).any { contains(it, bobPrivate) })
        assertFalse(contains(frame, bobPrivate))
        val verdict = DealFinality.assess(
            opened,
            emptyList(),
            opened.parties.associate { it.partyId to it.signingPublicKey },
        )
        assertFalse(verdict.proven)
        val stored = link.bob.database.dealDao().findVersion(bobDeal.dealId, bobDeal.currentVersionId)!!.sealedDocument
        assertFalse(contains(stored, SECRET_TERMS))
        assertFalse(contains(stored, SECRET_PURPOSE))
        link.close()
    }

    @Test
    fun forgedSenderRecipientWrongChatAndSelfDealsAreRejected() {
        val link = Link()
        val draft = link.draft()
        link.alice.service().queueProposal(draft, link.alice.user, link.conversation, link.alice.contact)
        val stored = link.alice.protector.open(
            link.alice.database.dealDao().transportByStatus(DealTransportState.OUT, DealTransportState.QUEUED).single().sealedEnvelope,
            DealTransportService.PURPOSE,
        )
        val original = DealEnvelopes.parse(stored)
        val malloryKey = DealIdentityCrypto.publicKey(link.mallory)
        val malloryId = ArmorChatId.derive(malloryKey)
        val forgedSender = original.copyEnvelope(senderId = malloryId, senderKey = malloryKey, envelopeId = DealIds.generate())
        assertTrue(
            assertThrows(DealFormatException::class.java) {
                link.bob.service().acceptPlain(DealEnvelopes.encode(forgedSender))
            }.message!!.contains("authenticated contact"),
        )
        val forgedRecipient = original.copyEnvelope(
            recipientId = malloryId,
            recipientKey = malloryKey,
            envelopeId = DealIds.generate(),
        )
        assertTrue(
            assertThrows(DealFormatException::class.java) {
                link.bob.service().acceptPlain(DealEnvelopes.encode(forgedRecipient))
            }.message!!.contains("not this device"),
        )
        val wrongChat = original.copyEnvelope(conversationId = "conversation-2", envelopeId = DealIds.generate())
        assertTrue(
            assertThrows(DealFormatException::class.java) {
                link.bob.service().acceptPlain(DealEnvelopes.encode(wrongChat))
            }.message!!.contains("different conversation"),
        )
        val participant = VerifiedDealParticipant.verify(link.alice.user.armorChatId, link.alice.publicKey)
        assertTrue(
            assertThrows(DealFormatException::class.java) {
                DealMessagingSession.establish("conversation-1", participant, participant)
            }.message!!.contains("themself"),
        )
        assertEquals(0, link.bob.database.dealDao().versionCount(original.dealId))
        assertNull(link.bob.database.dealDao().findDeal(original.dealId))
        link.close()
    }

    @Test
    fun replayAndDuplicateEnvelopesDoNotCreateAnotherVersion() {
        val link = Link()
        val draft = link.draft()
        val frame = link.capture(link.alice) {
            link.alice.service().queueProposal(draft, link.alice.user, link.conversation, link.alice.contact)
        }
        val first = link.bob.service().receive(frame)
        val second = link.bob.service().receive(frame.copyOf())
        assertEquals(DealTransportState.APPLIED, first.status)
        assertEquals(DealTransportState.DUPLICATE, second.status)
        val dealId = link.dealId(draft)
        assertEquals(1, link.bob.database.dealDao().versionCount(dealId))
        assertEquals(1, link.bob.database.dealDao().transportForDeal(dealId, DealTransportState.IN, DealWireEvent.PROPOSAL.wire).size)
        link.close()
    }

    @Test
    fun staleAndConflictingVersionsDoNotReplaceTheNewerOne() {
        val link = Link()
        val draft = link.draft()
        val original = link.capture(link.alice) {
            link.alice.service().queueProposal(draft, link.alice.user, link.conversation, link.alice.contact)
        }
        link.bob.service().receive(original)
        link.flush(link.bob, link.alice)
        val dealId = link.dealId(draft)
        val firstVersion = link.bob.database.dealDao().findDeal(dealId)!!.currentVersionId
        link.now += 5
        val revised = link.capture(link.alice) { link.alice.service().queueRevision(dealId, "Revised obligations.") }
        link.bob.service().receive(revised)
        link.flush(link.bob, link.alice)
        val current = link.bob.database.dealDao().findDeal(dealId)!!.currentVersionId
        assertNotEquals(firstVersion, current)
        val replay = link.bob.service().receive(original.copyOf())
        assertEquals(DealTransportState.DUPLICATE, replay.status)
        assertEquals(current, link.bob.database.dealDao().findDeal(dealId)!!.currentVersionId)
        assertEquals(2, link.bob.database.dealDao().versionCount(dealId))
        assertTrue(link.bob.database.dealDao().findVersion(dealId, firstVersion)!!.superseded)
        val sibling = link.siblingRevision(dealId, firstVersion, "A conflicting revision.")
        val conflict = link.bob.service().receive(sibling)
        assertEquals(DealTransportState.CONFLICT, conflict.status)
        assertEquals(current, link.bob.database.dealDao().findDeal(dealId)!!.currentVersionId)
        assertEquals(2, link.bob.database.dealDao().versionCount(dealId))
        val stale = link.bob.service().receive(link.secondGenesis(dealId))
        assertEquals(DealTransportState.STALE, stale.status)
        assertEquals(current, link.bob.database.dealDao().findDeal(dealId)!!.currentVersionId)
        link.close()
    }

    @Test
    fun outOfOrderRevisionWaitsAndDoesNotEraseTheParent() {
        val link = Link()
        val draft = link.draft()
        val proposal = link.capture(link.alice) {
            link.alice.service().queueProposal(draft, link.alice.user, link.conversation, link.alice.contact)
        }
        link.now += 5
        val dealId = link.dealId(draft)
        val revision = link.capture(link.alice) { link.alice.service().queueRevision(dealId, "Later terms.") }
        val waiting = link.bob.service().receive(revision)
        assertEquals(DealTransportState.WAITING, waiting.status)
        assertNull(link.bob.database.dealDao().findDeal(dealId))
        link.bob.service().receive(proposal)
        val deal = link.bob.database.dealDao().findDeal(dealId)!!
        assertEquals(2, link.bob.database.dealDao().versionCount(dealId))
        val parent = link.bob.database.dealDao().versions(dealId).first { it.versionId != deal.currentVersionId }
        assertTrue(parent.superseded)
        assertNotEquals(parent.versionId, deal.currentVersionId)
        val opened = link.openBob(dealId, deal.currentVersionId)
        assertEquals("Later terms.", opened.terms)
        link.close()
    }

    @Test
    fun interruptedSendIsRecoveredAndThePeerDeduplicates() {
        val link = Link()
        val draft = link.draft()
        val queued = link.alice.service().queueProposal(draft, link.alice.user, link.conversation, link.alice.contact)
        assertEquals(DealTransportState.QUEUED, queued.status)
        val restarted = link.alice.service()
        val captured = ArrayList<ByteArray>()
        assertThrows(IllegalStateException::class.java) {
            restarted.flush { frame ->
                captured += frame.copyOf()
                throw IllegalStateException("process stopped")
            }
        }
        assertEquals(
            DealTransportState.QUEUED,
            link.alice.database.dealDao().findTransport(queued.envelopeId)!!.status,
        )
        link.bob.service().receive(captured.single())
        link.alice.service().flush { frame -> link.bob.service().receive(frame) }
        val dealId = link.dealId(draft)
        assertEquals(1, link.bob.database.dealDao().versionCount(dealId))
        assertEquals(DealTransportState.SENT, link.alice.database.dealDao().findTransport(queued.envelopeId)!!.status)
        link.close()
    }

    @Test
    fun changesAndRejectionKeepEarlierVersionsAndAuditEvidence() {
        val link = Link()
        val draft = link.draft()
        val frame = link.capture(link.alice) {
            link.alice.service().queueProposal(draft, link.alice.user, link.conversation, link.alice.contact)
        }
        link.bob.service().receive(frame)
        link.flush(link.bob, link.alice)
        val dealId = link.dealId(draft)
        val version = link.bob.database.dealDao().findDeal(dealId)!!.currentVersionId
        val before = link.bob.database.dealDao().findVersion(dealId, version)!!.sealedDocument.copyOf()
        link.bob.service().queueChanges(dealId, SECRET_NOTE)
        val changeFrame = link.capture(link.bob) { }
        assertFalse(contains(changeFrame, SECRET_NOTE))
        link.alice.service().receive(changeFrame)
        assertEquals(DealStatus.NEGOTIATING.name, link.alice.database.dealDao().findDeal(dealId)!!.status)
        assertEquals(DealStatus.NEGOTIATING.name, link.bob.database.dealDao().findDeal(dealId)!!.status)
        link.bob.service().queueRejection(dealId, "Declined.")
        link.flush(link.bob, link.alice)
        assertEquals(DealStatus.REJECTED.name, link.alice.database.dealDao().findDeal(dealId)!!.status)
        assertEquals(version, link.alice.database.dealDao().findDeal(dealId)!!.currentVersionId)
        assertTrue(before.contentEquals(link.bob.database.dealDao().findVersion(dealId, version)!!.sealedDocument))
        assertEquals(0, link.alice.database.dealDao().signatureCount(dealId))
        val actions = link.alice.database.dealDao().audits(dealId).map { it.action }
        assertTrue(actions.contains("changes_requested"))
        assertTrue(actions.contains("rejected"))
        assertTrue(link.alice.database.dealDao().audits(dealId).none { it.outcome.contains(SECRET_NOTE) || it.action.contains(SECRET_NOTE) })
        link.close()
    }

    @Test
    fun canonicalV1V2V3StayCompatibleAndFinalityStillNeedsTwoSignatures() {
        val v1 = CanonicalDeal.create(
            dealId = "d".repeat(22),
            versionId = "v".repeat(22),
            parentVersionId = null,
            createdAtEpochMs = 1_700_000_000_000,
            currency = null,
            amountMinor = null,
            parties = listOf(
                com.codeyantra.armorchat.deal.domain.DealParty(
                    "a".repeat(22), PartyRole.PROPOSER, "A", ByteArray(32) { 0x11 }, ByteArray(32) { 0x33 },
                ),
                com.codeyantra.armorchat.deal.domain.DealParty(
                    "b".repeat(22), PartyRole.COUNTERPARTY, "B", ByteArray(32) { 0x22 }, ByteArray(32) { 0x44 },
                ),
            ),
            ancestry = emptyList(),
            terms = "Rent",
        )
        assertEquals(DealFeature.CANONICAL_VERSION, v1.canonicalVersion)
        assertEquals(1, v1.canonicalBytes()[4].toInt())
        val parsed = CanonicalEncoding.parse(v1.canonicalBytes())
        assertEquals(v1.canonicalVersion, parsed.canonicalVersion)
        assertNull(parsed.annex)
        assertTrue(v1.canonicalBytes().contentEquals(parsed.canonicalBytes()))
        assertFalse(DealLifecycle.claimsFinality(DealStatus.PROPOSED))
        assertFalse(DealLifecycle.claimsFinality(DealStatus.NEGOTIATING))
        assertFalse(DealLifecycle.claimsFinality(DealStatus.REJECTED))
        assertTrue(DealLifecycle.claimsFinality(DealStatus.FULLY_SIGNED))
        val verdict = DealFinality.assess(v1, emptyList(), v1.parties.associate { it.partyId to it.signingPublicKey })
        assertFalse(verdict.proven)
    }

    private fun storedBlobs(endpoint: Endpoint): List<ByteArray> {
        val dao = endpoint.database.dealDao()
        val out = ArrayList<ByteArray>()
        listOf(DealTransportState.QUEUED, DealTransportState.SENT, DealTransportState.DELIVERED, DealTransportState.ACKNOWLEDGED)
            .forEach { status ->
                dao.transportByStatus(DealTransportState.OUT, status).forEach { out += it.sealedEnvelope }
            }
        dao.transportByStatus(DealTransportState.IN, DealTransportState.APPLIED).forEach { out += it.sealedEnvelope }
        dao.listDeals().forEach { deal ->
            dao.versions(deal.dealId).forEach { out += it.sealedDocument }
        }
        return out
    }

    @Test
    fun schemaUpgradeAddsTransportWithoutRewritingDraftsOrSignedRows() {
        val name = "transport-migrate-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val dealId = "d".repeat(22)
        val versionId = "v".repeat(22)
        val partyId = "a".repeat(22)
        val sealed = byteArrayOf(9, 8, 7, 6, 5)
        val signature = byteArrayOf(3, 3, 3, 3)
        val draftBlob = byteArrayOf(4, 4, 4, 4)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            SCHEMA_V1.forEach(old::execSQL)
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `deal_drafts` (`draftId` TEXT NOT NULL, `dealId` TEXT NOT NULL, `versionId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `contactId` TEXT NOT NULL, `status` TEXT NOT NULL, `sealedPayload` BLOB NOT NULL, `sealedPartyASigning` BLOB NOT NULL, `sealedPartyAEncryption` BLOB NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`draftId`))",
            )
            old.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_deal_drafts_dealId` ON `deal_drafts` (`dealId`)")
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_deal_drafts_conversationId` ON `deal_drafts` (`conversationId`)")
            old.execSQL(
                "INSERT INTO deals (dealId, status, currentVersionId, createdAtEpochMs, updatedAtEpochMs) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(dealId, "FULLY_SIGNED", versionId, 10L, 11L),
            )
            old.execSQL(
                "INSERT INTO deal_versions (dealId, versionId, parentVersionId, documentSha256, sealedDocument, superseded, immutable, createdAtEpochMs) VALUES (?, ?, NULL, ?, ?, 0, 1, 10)",
                arrayOf(dealId, versionId, "hash-1", sealed),
            )
            old.execSQL(
                "INSERT INTO deal_signatures (dealId, versionId, partyId, action, signature, signerPublicKey) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(dealId, versionId, partyId, "SIGN", signature, "signer-key"),
            )
            old.execSQL(
                "INSERT INTO deal_drafts (draftId, dealId, versionId, conversationId, contactId, status, sealedPayload, sealedPartyASigning, sealedPartyAEncryption, createdAtEpochMs, updatedAtEpochMs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("draft-1", "e".repeat(22), "f".repeat(22), "conversation-1", "contact-1", "LOCAL_DRAFT", draftBlob, draftBlob, draftBlob, 12L, 13L),
            )
            old.version = 2
        }
        val upgraded = DealDatabase.builder(context, name).allowMainThreadQueries().build()
        try {
            assertEquals("FULLY_SIGNED", upgraded.dealDao().findDeal(dealId)!!.status)
            assertTrue(sealed.contentEquals(upgraded.dealDao().sealedBytes(dealId, versionId)!!))
            assertTrue(signature.contentEquals(upgraded.dealDao().signatures(dealId, versionId).single().signature))
            assertEquals(1, upgraded.dealDao().draftCount())
            assertTrue(draftBlob.contentEquals(upgraded.dealDao().findDraft("draft-1")!!.sealedPayload))
            assertEquals(0, upgraded.dealDao().transportCount())
            val cursor = upgraded.openHelper.readableDatabase.query(
                "SELECT sql FROM sqlite_master WHERE name = ?",
                arrayOf("deal_transport"),
            )
            cursor.use { assertTrue(it.moveToNext()) }
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    private class Link {
        var now: Long = 1_700_000_000_000L
        val aliceHandle: KeysetHandle = messaging()
        val bobHandle: KeysetHandle = messaging()
        val mallory: KeysetHandle = messaging()
        val alice = endpoint(
            aliceHandle,
            bobHandle,
            PartyRole.PROPOSER,
            ByteArray(32) { 4 },
            ByteArray(32) { 7 },
            ByteArray(32) { 8 },
        )
        val bob = endpoint(
            bobHandle,
            aliceHandle,
            PartyRole.COUNTERPARTY,
            ByteArray(32) { 5 },
            ByteArray(32) { 8 },
            ByteArray(32) { 7 },
        )
        val conversation = Conversation("conversation-1", "contact-1", null, now)

        fun draft(): String {
            val opened = alice.store().createFromChat(
                local = alice.user,
                conversation = conversation,
                contact = alice.contact,
                editor = editor(),
                localDealKeys = alice.dealKeys,
                localSigner = alice.signer,
            )
            return opened.draftId
        }

        fun dealId(draftId: String): String = alice.store().open(draftId).ids.dealId

        fun capture(sender: Endpoint, block: () -> Unit): ByteArray {
            block()
            val frames = ArrayList<ByteArray>()
            sender.service().flush { frames += it.copyOf() }
            return frames.single()
        }

        fun flush(sender: Endpoint, receiver: Endpoint) {
            sender.service().flush { frame -> receiver.service().receive(frame) }
        }

        fun openBob(dealId: String, versionId: String): CanonicalDeal {
            val row = bob.database.dealDao().findVersion(dealId, versionId)!!
            val partyId = bob.database.dealDao().parties(dealId).first { it.role == PartyRole.COUNTERPARTY.name }.partyId
            val keys = keys(bob, partyId)
            val plain = DealCipher.decrypt(SealedDocument.parse(row.sealedDocument), partyId, keys.encryptionHandle())
            return CanonicalEncoding.parse(plain)
        }

        fun bobPrivate(partyId: String): ByteArray = keys(bob, partyId).serializedSigning()

        fun siblingRevision(dealId: String, parentVersion: String, terms: String): ByteArray {
            val stored = alice.protector.open(
                alice.database.dealDao().transportForDeal(dealId, DealTransportState.OUT, DealWireEvent.PROPOSAL.wire).single().sealedEnvelope,
                DealTransportService.PURPOSE,
            )
            val offer = DealEnvelopes.parseProposal(DealEnvelopes.parse(stored).payload).offer
            return signedOfferFrame(offer, parentVersion, terms, DealWireEvent.REVISED_PROPOSAL)
        }

        fun secondGenesis(dealId: String): ByteArray {
            val stored = alice.protector.open(
                alice.database.dealDao().transportForDeal(dealId, DealTransportState.OUT, DealWireEvent.PROPOSAL.wire).single().sealedEnvelope,
                DealTransportService.PURPOSE,
            )
            val offer = DealEnvelopes.parseProposal(DealEnvelopes.parse(stored).payload).offer
            val replacement = DealOffer(
                dealId = offer.dealId,
                versionId = DealIds.generate(),
                parentVersionId = null,
                createdAtEpochMs = offer.createdAtEpochMs,
                currency = offer.currency,
                amountMinor = offer.amountMinor,
                terms = "A second genesis.",
                details = offer.details,
                annex = offer.annex,
                ancestry = emptyList(),
                proposerPartyId = offer.proposerPartyId,
                counterpartyPartyId = offer.counterpartyPartyId,
                proposerLabel = offer.proposerLabel,
                counterpartyLabel = offer.counterpartyLabel,
                partyA = offer.partyA,
                partyB = offer.partyB,
                partyASigningPublic = offer.partyASigningPublic,
                partyAEncryptionPublic = offer.partyAEncryptionPublic,
                partyABinding = offer.partyABinding,
                partyAPublicEncryptionKeyset = offer.partyAPublicEncryptionKeyset,
                conversationId = offer.conversationId,
            )
            val bytes = DealOffers.encode(replacement)
            val signature = DealOffers.sign(alice.dealKeys.signingHandle(), bytes)
            val envelope = DealEnvelope(
                event = DealWireEvent.PROPOSAL,
                envelopeId = DealIds.generate(),
                dealId = dealId,
                versionId = replacement.versionId,
                senderId = alice.user.armorChatId,
                recipientId = bob.user.armorChatId,
                senderKey = alice.publicKey,
                recipientKey = bob.publicKey,
                conversationId = conversation.id,
                createdAtEpochMs = now,
                payload = DealEnvelopes.proposalPayload(bytes, signature),
            )
            return alice.cipher.seal(alice.session, DealEnvelopes.encode(envelope))
        }

        fun close() {
            alice.database.close()
            bob.database.close()
        }

        private fun signedOfferFrame(offer: DealOffer, parent: String, terms: String, event: DealWireEvent): ByteArray {
            val replacement = DealOffer(
                dealId = offer.dealId,
                versionId = DealIds.generate(),
                parentVersionId = parent,
                createdAtEpochMs = now,
                currency = offer.currency,
                amountMinor = offer.amountMinor,
                terms = terms,
                details = offer.details,
                annex = offer.annex,
                ancestry = listOf(parent),
                proposerPartyId = offer.proposerPartyId,
                counterpartyPartyId = offer.counterpartyPartyId,
                proposerLabel = offer.proposerLabel,
                counterpartyLabel = offer.counterpartyLabel,
                partyA = offer.partyA,
                partyB = offer.partyB,
                partyASigningPublic = offer.partyASigningPublic,
                partyAEncryptionPublic = offer.partyAEncryptionPublic,
                partyABinding = offer.partyABinding,
                partyAPublicEncryptionKeyset = offer.partyAPublicEncryptionKeyset,
                conversationId = offer.conversationId,
            )
            val bytes = DealOffers.encode(replacement)
            val signature = DealOffers.sign(alice.dealKeys.signingHandle(), bytes)
            val envelope = DealEnvelope(
                event = event,
                envelopeId = DealIds.generate(),
                dealId = offer.dealId,
                versionId = replacement.versionId,
                senderId = alice.user.armorChatId,
                recipientId = bob.user.armorChatId,
                senderKey = alice.publicKey,
                recipientKey = bob.publicKey,
                conversationId = conversation.id,
                createdAtEpochMs = now,
                payload = DealEnvelopes.proposalPayload(bytes, signature),
            )
            return alice.cipher.seal(alice.session, DealEnvelopes.encode(envelope))
        }

        private fun keys(endpoint: Endpoint, partyId: String): DealKeyMaterial {
            val row = endpoint.database.dealDao().findKeys(partyId)!!
            val signing = endpoint.protector.open(row.sealedSigningKeyset, DealRepository.SIGNING_PURPOSE)
            val encryption = endpoint.protector.open(row.sealedEncryptionKeyset, DealRepository.ENCRYPTION_PURPOSE)
            return DealKeyMaterial.fromSerialized(signing, encryption)
        }

        private fun endpoint(
            local: KeysetHandle,
            peer: KeysetHandle,
            role: PartyRole,
            storageSeed: ByteArray,
            sendKey: ByteArray,
            receiveKey: ByteArray,
        ): Endpoint {
            val database = Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                DealDatabase::class.java,
            ).allowMainThreadQueries().build()
            val protector = SoftwareDealKeyProtector(storageSeed)
            val localKey = DealIdentityCrypto.publicKey(local)
            val peerKey = DealIdentityCrypto.publicKey(peer)
            val localParticipant = VerifiedDealParticipant.verify(ArmorChatId.derive(localKey), localKey)
            val peerParticipant = VerifiedDealParticipant.verify(ArmorChatId.derive(peerKey), peerKey)
            val session = DealMessagingSession.establish("conversation-1", localParticipant, peerParticipant)
            val user = UserIdentity(
                armorChatId = localParticipant.armorChatId,
                publicIdentityKey = Base64Url.encode(localKey),
                createdAtEpochMs = now,
                displayName = if (role == PartyRole.PROPOSER) "Neeraj" else "Recipient",
            )
            val contact = Contact(
                id = "contact-1",
                armorChatId = peerParticipant.armorChatId,
                publicIdentityKey = Base64Url.encode(peerKey),
                displayName = "Peer",
                createdAtEpochMs = now,
            )
            return Endpoint(
                database = database,
                protector = protector,
                user = user,
                contact = contact,
                publicKey = localKey,
                dealKeys = DealKeyMaterial.generate(),
                signer = HandleSigner(local),
                session = session,
                cipher = ExportedSessionCipher(sendKey, receiveKey),
                role = role,
                clock = { now },
            )
        }
    }

    private class Endpoint(
        val database: DealDatabase,
        val protector: SoftwareDealKeyProtector,
        val user: UserIdentity,
        val contact: Contact,
        val publicKey: ByteArray,
        val dealKeys: DealKeyMaterial,
        val signer: DealMessagingSigner,
        val session: DealMessagingSession,
        val cipher: ExportedSessionCipher,
        val role: PartyRole,
        val clock: () -> Long,
    ) {
        fun store(): DealDraftStore = DealDraftStore(database, protector, clock = clock, debugBuild = true)

        fun service(): DealTransportService = DealTransportService(
            database = database,
            protector = protector,
            drafts = store(),
            session = session,
            cipher = cipher,
            localRole = role,
            localMessagingSigner = signer,
            debugBuild = true,
            clock = clock,
        )
    }

    private class HandleSigner(private val handle: KeysetHandle) : DealMessagingSigner {
        override val publicKey: ByteArray = DealIdentityCrypto.publicKey(handle)
        override fun sign(payload: ByteArray): ByteArray = DealIdentityCrypto.sign(handle, payload)
        override fun toString(): String = "HandleSigner"
    }

    companion object {
        private const val SECRET_TERMS = "UNIQUE_TERMS_MARKER_9421_NOT_PLAINTEXT"
        private const val SECRET_PURPOSE = "UNIQUE_PURPOSE_MARKER_keep_this_encrypted"
        private const val SECRET_NOTE = "UNIQUE_NOTE_MARKER_do_not_preview"

        private val SCHEMA_V1 = listOf(
            "CREATE TABLE IF NOT EXISTS `deals` (`dealId` TEXT NOT NULL, `status` TEXT NOT NULL, `currentVersionId` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`dealId`))",
            "CREATE TABLE IF NOT EXISTS `deal_parties` (`dealId` TEXT NOT NULL, `partyId` TEXT NOT NULL, `role` TEXT NOT NULL, `label` TEXT NOT NULL, `signingPublicKey` TEXT NOT NULL, `encryptionPublicKey` TEXT NOT NULL, PRIMARY KEY(`dealId`, `partyId`), FOREIGN KEY(`dealId`) REFERENCES `deals`(`dealId`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_deal_parties_dealId` ON `deal_parties` (`dealId`)",
            "CREATE TABLE IF NOT EXISTS `deal_versions` (`dealId` TEXT NOT NULL, `versionId` TEXT NOT NULL, `parentVersionId` TEXT, `documentSha256` TEXT NOT NULL, `sealedDocument` BLOB NOT NULL, `superseded` INTEGER NOT NULL, `immutable` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`dealId`, `versionId`), FOREIGN KEY(`dealId`) REFERENCES `deals`(`dealId`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_deal_versions_dealId` ON `deal_versions` (`dealId`)",
            "CREATE TABLE IF NOT EXISTS `deal_signatures` (`dealId` TEXT NOT NULL, `versionId` TEXT NOT NULL, `partyId` TEXT NOT NULL, `action` TEXT NOT NULL, `signature` BLOB NOT NULL, `signerPublicKey` TEXT NOT NULL, PRIMARY KEY(`dealId`, `versionId`, `partyId`, `action`))",
            "CREATE INDEX IF NOT EXISTS `index_deal_signatures_dealId` ON `deal_signatures` (`dealId`)",
            "CREATE TABLE IF NOT EXISTS `deal_backup_receipts` (`dealId` TEXT NOT NULL, `versionId` TEXT NOT NULL, `partyId` TEXT NOT NULL, `objectKey` TEXT NOT NULL, `ciphertextSha256` TEXT NOT NULL, `confirmedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`dealId`, `versionId`, `partyId`))",
            "CREATE INDEX IF NOT EXISTS `index_deal_backup_receipts_dealId` ON `deal_backup_receipts` (`dealId`)",
            "CREATE TABLE IF NOT EXISTS `deal_audit_events` (`eventId` TEXT NOT NULL, `dealId` TEXT NOT NULL, `action` TEXT NOT NULL, `outcome` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`eventId`))",
            "CREATE TABLE IF NOT EXISTS `deal_recovery_meta` (`partyId` TEXT NOT NULL, `dealId` TEXT NOT NULL, `formatVersion` INTEGER NOT NULL, `publicKeyFingerprint` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`partyId`, `dealId`))",
            "CREATE TABLE IF NOT EXISTS `deal_local_keys` (`partyId` TEXT NOT NULL, `sealedSigningKeyset` BLOB NOT NULL, `sealedEncryptionKeyset` BLOB NOT NULL, `signingPublicKey` TEXT NOT NULL, `encryptionPublicKey` TEXT NOT NULL, PRIMARY KEY(`partyId`))",
        )

        private fun editor(): DealDraftEditor = DealDraftEditor(
            title = "Service agreement",
            purpose = SECRET_PURPOSE,
            proposerRole = "Client",
            counterpartyRole = "Provider",
            terms = SECRET_TERMS,
            paymentConditions = "Pay on acceptance of the work.",
            currency = "INR",
            amountMinor = 150050L,
            deadlineDate = "2026-12-31",
            effectiveDate = "2026-11-01",
            partyALegalName = "",
        )

        private fun messaging(): KeysetHandle {
            DealTink.register()
            return KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        }
    }
}

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

private fun contains(haystack: ByteArray, needle: String): Boolean = contains(haystack, needle.toByteArray(Charsets.UTF_8))
