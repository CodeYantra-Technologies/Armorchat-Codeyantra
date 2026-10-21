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
import com.codeyantra.armorchat.deal.domain.DealAlreadySignedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Phase 5.3N-B: service-level finality guard for NEW local SIGN.
 * Isolated fixtures only (in-memory Room, FakeMls, in-memory relay); never phone DBs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DealFinalityGuardTest {

    private fun readyToSign(link: Link): String {
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
        assertEquals(SigningStage.READY_TO_SIGN, link.alice.bridge.signingSnapshot()!!.stage)
        assertEquals(SigningStage.READY_TO_SIGN, link.bob.bridge.signingSnapshot()!!.stage)
        return link.dealId(draft)
    }

    private fun fullySigned(link: Link): String {
        val dealId = readyToSign(link)
        blocking {
            link.alice.bridge.signWithConsent()
            link.drain()
            link.bob.bridge.signWithConsent()
            link.drain()
        }
        assertFullySigned(link, dealId)
        return dealId
    }

    private fun assertFullySigned(link: Link, dealId: String) {
        for (phone in listOf(link.alice, link.bob)) {
            assertEquals(SigningStage.FULLY_SIGNED, phone.bridge.signingSnapshot()!!.stage)
            assertEquals(DealStatus.FULLY_SIGNED.name, phone.database.dealDao().findDeal(dealId)!!.status)
            assertEquals(2, phone.database.dealDao().signatureCount(dealId))
            val current = phone.database.dealDao().findDeal(dealId)!!.currentVersionId
            assertTrue(phone.database.dealDao().findVersion(dealId, current)!!.immutable)
        }
    }

    /** Everything a blocked local SIGN must leave untouched. */
    private fun state(phone: Phone, dealId: String): List<String> {
        val dao = phone.database.dealDao()
        val deal = dao.findDeal(dealId)!!
        val out = mutableListOf("deal:${deal.status}:${deal.currentVersionId}:${deal.updatedAtEpochMs}")
        dao.versions(dealId).sortedBy { it.versionId }.forEach {
            out += "ver:${it.versionId}:${it.documentSha256}:${it.superseded}:${it.immutable}:${it.createdAtEpochMs}"
            dao.signatures(dealId, it.versionId).forEach { s ->
                out += "sig:${s.versionId}:${s.partyId}:${s.action}:${Base64Url.encode(s.signature)}:${s.signerPublicKey}"
            }
        }
        DealWireEvent.entries.forEach { event ->
            listOf(DealTransportState.IN, DealTransportState.OUT).forEach { dir ->
                dao.transportForDeal(dealId, dir, event.wire).forEach {
                    out += "tx:$dir:${it.envelopeId}:${it.status}:${it.updatedAtEpochMs}"
                }
            }
        }
        out += "audits:${dao.audits(dealId).size}"
        return out.sorted()
    }

    private fun assertBlocked(link: Link, phone: Phone, dealId: String) {
        val beforeSelf = state(phone, dealId)
        val other = if (phone === link.alice) link.bob else link.alice
        val beforeOther = state(other, dealId)
        link.now += 60_000
        val error = assertThrows(DealAlreadySignedException::class.java) {
            blocking { phone.bridge.signBoundDeal() }
        }
        assertEquals("already_signed", DealFailureLog.code(error))
        assertEquals(
            "action=sign result=FAIL code=already_signed type=DealAlreadySignedException",
            DealFailureLog.line("sign", error),
        )
        assertEquals(beforeSelf, state(phone, dealId))
        blocking { link.drain() }
        assertEquals(beforeOther, state(other, dealId))
    }

    @Test
    fun localSignBlockedWhenFullySigned() {
        val link = Link()
        val dealId = fullySigned(link)
        assertBlocked(link, link.alice, dealId)
        assertBlocked(link, link.bob, dealId)
        assertFullySigned(link, dealId)
        link.close()
    }

    @Test
    fun localSignBlockedWhenLocalPartyAlreadySigned() {
        val link = Link()
        val dealId = readyToSign(link)
        blocking {
            link.alice.bridge.signWithConsent()
            link.drain()
        }
        assertEquals(SigningStage.PARTIALLY_SIGNED, link.alice.bridge.signingSnapshot()!!.stage)
        assertEquals(SigningStage.PARTIALLY_SIGNED, link.bob.bridge.signingSnapshot()!!.stage)
        assertBlocked(link, link.alice, dealId)
        assertEquals(1, link.alice.database.dealDao().signatureCount(dealId))
        assertEquals(1, link.bob.database.dealDao().signatureCount(dealId))
        // Party B is still free to sign.
        blocking {
            link.bob.bridge.signWithConsent()
            link.drain()
        }
        assertFullySigned(link, dealId)
        link.close()
    }

    @Test
    fun concurrentLocalSignAttemptsProduceExactlyOneSignatureAndOneEnvelope() {
        val link = Link()
        val dealId = readyToSign(link)
        val alice = link.alice
        val partyId = link.partyId(alice, dealId, PartyRole.PROPOSER)
        val local = VerifiedDealParticipant.verify(alice.user.armorChatId, Base64Url.decode(alice.user.publicIdentityKey))
        val peerUser = link.bob.user
        val peer = VerifiedDealParticipant.verify(peerUser.armorChatId, Base64Url.decode(peerUser.publicIdentityKey))
        val attempts = 4
        val services = List(attempts) {
            DealTransportService(
                database = alice.database,
                protector = alice.protector,
                drafts = DealDraftStore(alice.database, alice.protector, clock = link.clock, debugBuild = true),
                session = DealMessagingSession.establish("conversation-alice", local, peer),
                cipher = ExportedSessionCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
                localRole = PartyRole.PROPOSER,
                localMessagingSigner = link.aliceSigner,
                debugBuild = true,
                clock = link.clock,
            )
        }
        alice.bridge.recordBoundReview()
        val outBefore = alice.database.dealDao().transportForDeal(dealId, DealTransportState.OUT, DealWireEvent.SIGNATURE.wire).size
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(attempts)
        val futures = services.map { service ->
            pool.submit<Result<com.codeyantra.armorchat.deal.transport.DealTransportResult>> {
                start.await()
                runCatching { service.queueOwnSignature(dealId) }
            }
        }
        start.countDown()
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, results.count { it.isSuccess })
        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertEquals(attempts - 1, failures.size)
        assertTrue(failures.joinToString { it::class.java.simpleName }, failures.all { it is DealAlreadySignedException })
        val dao = alice.database.dealDao()
        val current = dao.findDeal(dealId)!!.currentVersionId
        assertEquals(1, dao.signatures(dealId, current).count { it.partyId == partyId && it.action == SignAction.SIGN.name })
        assertEquals(1, dao.signatureCount(dealId))
        assertEquals(outBefore + 1, dao.transportForDeal(dealId, DealTransportState.OUT, DealWireEvent.SIGNATURE.wire).size)
        link.close()
    }

    @Test
    fun legitimateSecondPartySignStillSucceeds() {
        val link = Link()
        val dealId = readyToSign(link)
        blocking {
            link.alice.bridge.signWithConsent()
            link.drain()
            assertEquals(SigningStage.PARTIALLY_SIGNED, link.bob.bridge.signingSnapshot()!!.stage)
            link.bob.bridge.signWithConsent()
            link.drain()
        }
        assertFullySigned(link, dealId)
        link.close()
    }

    @Test
    fun remoteSignatureStillAppliedAfterLocalSign() {
        val link = Link()
        val dealId = readyToSign(link)
        blocking {
            // Both sign before either signature is delivered: the envelopes cross in flight.
            link.alice.bridge.signWithConsent()
            link.bob.bridge.signWithConsent()
            link.drain()
        }
        assertFullySigned(link, dealId)
        for (phone in listOf(link.alice, link.bob)) {
            val applied = phone.database.dealDao()
                .transportForDeal(dealId, DealTransportState.IN, DealWireEvent.SIGNATURE.wire)
                .count { it.status == DealTransportState.APPLIED }
            assertEquals(1, applied)
        }
        link.close()
    }

    @Test
    fun duplicateIncomingStillIdempotent() {
        val link = Link()
        val dealId = fullySigned(link)
        for (phone in listOf(link.alice, link.bob)) {
            val stored = phone.database.dealDao()
                .transportForDeal(dealId, DealTransportState.IN, DealWireEvent.SIGNATURE.wire)
                .single { it.status == DealTransportState.APPLIED }
            val plain = phone.protector.open(stored.sealedEnvelope, DealTransportService.PURPOSE)
            val before = state(phone, dealId)
            blocking { phone.bridge.acceptEnvelope(plain) }
            assertEquals(before, state(phone, dealId))
        }
        assertFullySigned(link, dealId)
        link.close()
    }

    // ---- fixture copied from DealMessagingIntegrationTest (signers exposed for the concurrency test) ----


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
        val aliceSigner: DealMessagingSigner = signerFor(aliceHandle, "alice")
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
