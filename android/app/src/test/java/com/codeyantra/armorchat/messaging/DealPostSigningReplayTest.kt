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

import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealSignature

/**
 * Phase 5.3N-A: pins CURRENT post-signing replay/duplicate behaviour.
 * Isolated fixtures only (in-memory Room, FakeMls, in-memory relay); never phone DBs.
 * The two-phone fixture is copied from DealMessagingIntegrationTest so that file stays untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DealPostSigningReplayTest {

    private fun fullySigned(link: Link): String {
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
            link.alice.bridge.signWithConsent()
            link.drain()
            link.bob.bridge.signWithConsent()
            link.drain()
        }
        val dealId = link.dealId(draft)
        assertEquals(SigningStage.FULLY_SIGNED, link.alice.bridge.signingSnapshot()!!.stage)
        assertEquals(SigningStage.FULLY_SIGNED, link.bob.bridge.signingSnapshot()!!.stage)
        assertEquals(2, link.alice.database.dealDao().signatureCount(dealId))
        assertEquals(2, link.bob.database.dealDao().signatureCount(dealId))
        return dealId
    }

    private class Snapshot(
        val currentVersionId: String,
        val status: String,
        val versionCount: Int,
        val signatures: List<String>,
        val immutable: Boolean,
        val transport: List<String>,
        val audits: Int,
    )

    private fun snapshot(phone: Phone, dealId: String): Snapshot {
        val dao = phone.database.dealDao()
        val deal = dao.findDeal(dealId)!!
        return Snapshot(
            currentVersionId = deal.currentVersionId,
            status = deal.status,
            versionCount = dao.versionCount(dealId),
            signatures = dao.signatures(dealId, deal.currentVersionId)
                .map { it.partyId + ":" + it.action + ":" + Base64Url.encode(it.signature) + ":" + it.signerPublicKey }
                .sorted(),
            immutable = dao.findVersion(dealId, deal.currentVersionId)!!.immutable,
            transport = DealWireEvent.entries.flatMap { event ->
                listOf(DealTransportState.IN, DealTransportState.OUT).flatMap { dir ->
                    dao.transportForDeal(dealId, dir, event.wire).map { it.envelopeId + ":" + dir + ":" + it.status }
                }
            }.sorted(),
            audits = dao.audits(dealId).size,
        )
    }

    private fun assertSignedStateUnchanged(before: Snapshot, after: Snapshot) {
        assertEquals(before.currentVersionId, after.currentVersionId)
        assertEquals(before.status, after.status)
        assertEquals(DealStatus.FULLY_SIGNED.name, after.status)
        assertEquals(before.versionCount, after.versionCount)
        assertEquals(before.signatures, after.signatures)
        assertEquals(2, after.signatures.size)
        assertTrue(after.immutable)
    }

    private fun appliedIn(phone: Phone, dealId: String, event: DealWireEvent) =
        phone.database.dealDao().transportForDeal(dealId, DealTransportState.IN, event.wire)
            .filter { it.status == DealTransportState.APPLIED }

    private fun plainOf(phone: Phone, row: com.codeyantra.armorchat.deal.storage.DealTransportRow): ByteArray =
        phone.protector.open(row.sealedEnvelope, DealTransportService.PURPOSE)

    private fun recoverCurrent(link: Link, dealId: String) = run {
        val current = link.alice.database.dealDao().findDeal(dealId)!!.currentVersionId
        val opened = link.open(link.alice, dealId, current, PartyRole.PROPOSER)
        SigningProgressStore(link.alice.database.dealDao()).recover(opened)
    }

    @Test
    fun identicalSignatureOnFullySignedIsNoOp() {
        val link = Link()
        val dealId = fullySigned(link)
        val progress = recoverCurrent(link, dealId)
        assertEquals(SigningStage.FULLY_SIGNED, progress.stage)
        for (existing in progress.signatures) {
            val replayed = DealSignature(
                dealId = existing.dealId,
                versionId = existing.versionId,
                partyId = existing.partyId,
                action = existing.action,
                signature = existing.signature.copyOf(),
                signerPublicKey = existing.signerPublicKey.copyOf(),
            )
            val next = SigningWorkflow.submitSignature(progress, replayed)
            assertTrue("identical replay must return the same progress", next === progress)
            assertEquals(SigningStage.FULLY_SIGNED, next.stage)
            assertEquals(2, next.signatureCount)
            assertTrue(next.verdict().proven)
        }
        link.close()
    }

    /**
     * Expected ImmutableVersionException, but signature verification runs first
     * (SigningWorkflow.submitSignature -> DealSignatures.verify), so a different
     * signature from an already-signed party is refused as DealCryptoException.
     * Ed25519 is deterministic, so a *valid* different signature cannot exist
     * for the same payload/key; the immutable branch is not reachable this way.
     * Current behaviour still refuses, which is safe.
     */
    @Test
    fun differentSignatureOnFullySignedThrowsImmutable() {
        val link = Link()
        val dealId = fullySigned(link)
        val progress = recoverCurrent(link, dealId)
        for (existing in progress.signatures) {
            val tampered = existing.signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val claimed = DealSignature(
                dealId = existing.dealId,
                versionId = existing.versionId,
                partyId = existing.partyId,
                action = existing.action,
                signature = tampered,
                signerPublicKey = existing.signerPublicKey.copyOf(),
            )
            assertThrows(DealCryptoException::class.java) { SigningWorkflow.submitSignature(progress, claimed) }
        }
        val after = recoverCurrent(link, dealId)
        assertEquals(SigningStage.FULLY_SIGNED, after.stage)
        assertEquals(2, after.signatureCount)
        assertEquals(2, link.alice.database.dealDao().signatureCount(dealId))
        link.close()
    }

    @Test
    fun fullySignedIgnoresReplayedSignatureWithNewEnvelopeId() {
        val link = Link()
        val dealId = fullySigned(link)
        val stored = appliedIn(link.bob, dealId, DealWireEvent.SIGNATURE).single()
        val original = DealEnvelopes.parse(plainOf(link.bob, stored))
        val fresh = DealIds.generate()
        val replay = DealEnvelopes.encode(original.copyEnvelope(envelopeId = fresh))
        val before = snapshot(link.bob, dealId)
        blocking { link.bob.bridge.acceptEnvelope(replay) }
        val after = snapshot(link.bob, dealId)
        assertSignedStateUnchanged(before, after)
        // Current behaviour: the identical signature is a no-op in the workflow and the new
        // envelope is recorded as APPLIED ("signing_sign"), not DUPLICATE.
        val row = link.bob.database.dealDao().findTransport(fresh)!!
        assertEquals(DealTransportState.APPLIED, row.status)
        assertEquals(before.transport.size + 1, after.transport.size)
        val progress = SigningProgressStore(link.bob.database.dealDao())
            .recover(link.open(link.bob, dealId, after.currentVersionId, PartyRole.COUNTERPARTY))
        assertEquals(SigningStage.FULLY_SIGNED, progress.stage)
        assertEquals(2, progress.signatureCount)
        assertTrue(progress.verdict().proven)
        link.close()
    }

    @Test
    fun identicalSignatureSameEnvelopeIdAfterFullySignedIsDuplicate() {
        val link = Link()
        val dealId = fullySigned(link)
        for (phone in listOf(link.alice, link.bob)) {
            val stored = appliedIn(phone, dealId, DealWireEvent.SIGNATURE).single()
            val plain = plainOf(phone, stored)
            val service = phone.database.dealDao()
            val before = snapshot(phone, dealId)
            blocking { phone.bridge.acceptEnvelope(plain) }
            val after = snapshot(phone, dealId)
            assertSignedStateUnchanged(before, after)
            assertEquals(before.transport, after.transport)
            assertEquals(before.audits, after.audits)
            assertEquals(DealTransportState.APPLIED, service.findTransport(stored.envelopeId)!!.status)
        }
        link.close()
    }

    @Test
    fun duplicateReviewAndNameEnvelopesAreDuplicate() {
        val link = Link()
        val dealId = fullySigned(link)
        val cases = listOf(
            link.alice to DealWireEvent.REVIEW,
            link.alice to DealWireEvent.NAME_CONFIRMATION,
            link.bob to DealWireEvent.NAME_CONFIRMATION,
        )
        var replayed = 0
        for ((phone, event) in cases) {
            for (row in appliedIn(phone, dealId, event)) {
                val before = snapshot(phone, dealId)
                blocking { phone.bridge.acceptEnvelope(plainOf(phone, row)) }
                val after = snapshot(phone, dealId)
                assertSignedStateUnchanged(before, after)
                assertEquals(before.transport, after.transport)
                assertEquals(before.audits, after.audits)
                replayed++
            }
        }
        assertTrue("expected review and name rows to replay, got $replayed", replayed >= 3)
        link.close()
    }

    @Test
    fun staleNameConfirmationAfterSuccessorIsStale() {
        val link = Link()
        val dealId = fullySigned(link)
        val current = link.bob.database.dealDao().findDeal(dealId)!!.currentVersionId
        val old = appliedIn(link.bob, dealId, DealWireEvent.NAME_CONFIRMATION)
            .map { DealEnvelopes.parse(plainOf(link.bob, it)) }
            .first { it.versionId != current }
        val fresh = DealIds.generate()
        val before = snapshot(link.bob, dealId)
        blocking { link.bob.bridge.acceptEnvelope(DealEnvelopes.encode(old.copyEnvelope(envelopeId = fresh))) }
        val after = snapshot(link.bob, dealId)
        assertSignedStateUnchanged(before, after)
        assertEquals(DealTransportState.STALE, link.bob.database.dealDao().findTransport(fresh)!!.status)
        link.close()
    }

    @Test
    fun rejectedEnvelopeReplayThrows() {
        val link = Link()
        val dealId = fullySigned(link)
        val current = link.bob.database.dealDao().findDeal(dealId)!!.currentVersionId
        val forgedId = DealIds.generate()
        val forged = DealEnvelopes.encode(
            DealEnvelope(
                event = DealWireEvent.SIGNATURE,
                envelopeId = forgedId,
                dealId = dealId,
                versionId = current,
                senderId = link.alice.user.armorChatId,
                recipientId = link.bob.user.armorChatId,
                senderKey = link.alice.publicKey,
                recipientKey = link.bob.publicKey,
                conversationId = "conversation-alice",
                createdAtEpochMs = link.now,
                payload = DealEnvelopes.signaturePayload(
                    link.partyId(link.bob, dealId, PartyRole.PROPOSER),
                    ByteArray(64) { 7 },
                    link.dealSigningPublic(link.bob, dealId, PartyRole.PROPOSER),
                ),
            ),
        )
        val before = snapshot(link.bob, dealId)
        assertThrows(DealFormatException::class.java) { blocking { link.bob.bridge.acceptEnvelope(forged) } }
        assertEquals(DealTransportState.REJECTED, link.bob.database.dealDao().findTransport(forgedId)!!.status)
        val afterFirst = snapshot(link.bob, dealId)
        assertSignedStateUnchanged(before, afterFirst)
        assertThrows(DealFormatException::class.java) { blocking { link.bob.bridge.acceptEnvelope(forged) } }
        val afterReplay = snapshot(link.bob, dealId)
        assertSignedStateUnchanged(before, afterReplay)
        assertEquals(afterFirst.transport, afterReplay.transport)
        assertEquals(DealTransportState.REJECTED, link.bob.database.dealDao().findTransport(forgedId)!!.status)
        link.close()
    }

    // ---- fixture copied verbatim from DealMessagingIntegrationTest ----

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
