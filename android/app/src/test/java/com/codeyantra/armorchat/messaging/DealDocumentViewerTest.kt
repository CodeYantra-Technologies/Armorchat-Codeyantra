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
import com.codeyantra.armorchat.deal.domain.AgreementDetails
import com.codeyantra.armorchat.deal.domain.DealReviewRequiredException
import com.codeyantra.armorchat.deal.transport.DealClauses
import com.codeyantra.armorchat.deal.transport.DealDocumentResult
import com.codeyantra.armorchat.deal.transport.DealDocumentView
import com.codeyantra.armorchat.deal.transport.DealHiddenCharacter
import com.codeyantra.armorchat.deal.transport.DealMoney
import com.codeyantra.armorchat.deal.transport.DealMoneyText
import com.codeyantra.armorchat.deal.transport.DealReviewConsent
import com.codeyantra.armorchat.deal.transport.DealClause
import com.codeyantra.armorchat.deal.transport.DealConsentBlock
import com.codeyantra.armorchat.deal.transport.DealConsentChecklist
import com.codeyantra.armorchat.deal.transport.DealConsentGate
import com.codeyantra.armorchat.deal.transport.DealDocumentPresenter
import com.codeyantra.armorchat.deal.transport.DealSignConfirmation
import com.codeyantra.armorchat.deal.transport.DealSigningControls
import com.codeyantra.armorchat.deal.transport.DealViewerState
import com.codeyantra.armorchat.deal.transport.DealSigningSnapshot

/**
 * Phase 5.4C: Deal Document Viewer presentation, consent gate and signing controls.
 * Isolated fixtures only (in-memory Room, FakeMls, in-memory relay); never phone DBs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DealDocumentViewerTest {

    private fun proposed(link: Link, terms: String = SECRET_TERMS): String {
        val draft = link.draft(terms)
        blocking {
            link.alice.bridge.sendDraft(draft, link.alice.user, link.alice.conversation, link.alice.contact)
            link.drain()
        }
        return link.dealId(draft)
    }

    private fun readyToSign(link: Link): String {
        val dealId = proposed(link)
        blocking {
            link.bob.bridge.reviewWithConsent()
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Neeraj Sharma")
            link.drain()
            link.bob.bridge.confirmNameWithConsent("Asha Menon")
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Neeraj Sharma")
            link.drain()
        }
        return dealId
    }

    private fun rendered(state: DealViewerState): DealViewerState.Rendered {
        assertTrue("expected Rendered, got $state", state is DealViewerState.Rendered)
        return state as DealViewerState.Rendered
    }

    private fun failedCode(state: DealViewerState): String {
        assertTrue("expected Failed, got $state", state is DealViewerState.Failed)
        return (state as DealViewerState.Failed).code
    }

    private fun DealDocumentView.with(
        title: String? = this.title,
        clauses: List<DealClause> = this.clauses,
        money: DealMoneyText = this.money,
        amountMinor: Long? = this.amountMinor,
    ) = DealDocumentView(
        dealId = dealId, versionId = versionId, parentVersionId = parentVersionId,
        canonicalVersion = canonicalVersion, documentHash = documentHash, title = title, purpose = purpose,
        proposerRole = proposerRole, counterpartyRole = counterpartyRole, paymentConditions = paymentConditions,
        currency = currency, amountMinor = amountMinor, money = money, deadlineDate = deadlineDate,
        effectiveDate = effectiveDate, terms = terms, clauses = clauses, hiddenCharacters = hiddenCharacters,
        parties = parties,
    )

    private fun audits(phone: Phone, dealId: String) = phone.database.dealDao().audits(dealId).map { it.eventId }

    @Test
    fun everyFieldRenderedWithoutTruncation() {
        val link = Link()
        val terms = "1. Provider delivers the service.\n\n2. Client pays \t on acceptance.\n3. " + "Long clause ".repeat(400)
        val dealId = proposed(link, terms)
        blocking {
            link.bob.bridge.reviewWithConsent()
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Neeraj Sharma")
            link.drain()
        }
        val state = rendered(DealDocumentPresenter.present(link.alice.bridge.signingSnapshot()))
        val view = state.view
        val values = state.lines.map { it.value }
        val expected = listOf(
            "Service agreement", "Keep the purpose sealed", "Client", "Provider", "Pay on acceptance of the work.",
            "1,500.50 INR (150050 minor units)", "INR", "150050", "2026-12-31", "2026-11-01",
            view.versionId, view.fingerprint, view.documentHashBase64,
            link.alice.user.armorChatId, link.bob.user.armorChatId,
        )
        for (value in expected) assertTrue("missing $value", values.contains(value))
        assertTrue(values.any { it.startsWith("Neeraj Sharma (") })
        view.parties.forEach { party -> assertTrue(values.contains(party.label)) }
        val clauseLines = state.lines.filter { it.label.startsWith("Clause ") }
        assertEquals(terms.split('\n').size, clauseLines.size)
        terms.split('\n').forEachIndexed { index, text ->
            assertEquals("Clause ${index + 1}", clauseLines[index].label)
            assertEquals(if (text.isEmpty()) "(blank line)" else text, clauseLines[index].value)
        }
        assertEquals(terms.split('\n').last(), clauseLines.last().value)
        assertTrue(state.lines.last().label == "End of document")
        assertNull(state.hiddenWarning)
        assertEquals(dealId, view.dealId)
        link.close()
    }

    @Test
    fun missingClausesOrFieldsFailClosed() {
        val link = Link()
        val dealId = readyToSign(link)
        val view = rendered(DealDocumentPresenter.present(link.alice.bridge.signingSnapshot())).view
        val withExtraClause = view.with(clauses = view.clauses + DealClause(view.clauses.size + 1, "hidden"))
        assertEquals(DealDocumentPresenter.CLAUSE_MISMATCH, failedCode(DealDocumentPresenter.present(withExtraClause)))
        val dropped = view.with(clauses = emptyList())
        assertEquals(DealDocumentPresenter.CLAUSE_MISMATCH, failedCode(DealDocumentPresenter.present(dropped)))
        val noTitle = view.with(title = null)
        assertEquals(DealDocumentPresenter.MISSING_FIELD, failedCode(DealDocumentPresenter.present(noTitle)))
        assertTrue(dealId.isNotEmpty())
        link.close()
    }

    @Test
    fun wrongAmountFailsClosed() {
        val link = Link()
        readyToSign(link)
        val view = rendered(DealDocumentPresenter.present(link.bob.bridge.signingSnapshot())).view
        val wrongRaw = view.with(money = DealMoneyText.Amount("1,500.00 INR", "INR", 150000L, 2))
        assertEquals(DealDocumentPresenter.MONEY_MISMATCH, failedCode(DealDocumentPresenter.present(wrongRaw)))
        val wrongText = view.with(money = DealMoneyText.Amount("15,005.0 INR", "INR", 150050L, 2))
        assertEquals(DealDocumentPresenter.MONEY_MISMATCH, failedCode(DealDocumentPresenter.present(wrongText)))
        val wrongCurrency = view.with(money = DealMoneyText.Amount("1,500.50 USD", "USD", 150050L, 2))
        assertEquals(DealDocumentPresenter.MONEY_MISMATCH, failedCode(DealDocumentPresenter.present(wrongCurrency)))
        val hiddenAmount = view.with(money = DealMoneyText.NoAmount)
        assertEquals(DealDocumentPresenter.MONEY_MISMATCH, failedCode(DealDocumentPresenter.present(hiddenAmount)))
        assertTrue(DealDocumentPresenter.present(view) is DealViewerState.Rendered)
        link.close()
    }

    @Test
    fun failedDecryptionShowsErrorAndBlocksConsent() {
        val link = Link()
        val dealId = proposed(link)
        assertEquals(DealDocumentPresenter.UNAVAILABLE, failedCode(DealDocumentPresenter.present(null as DealSigningSnapshot?)))
        assertTrue((DealDocumentPresenter.present(null as DealSigningSnapshot?) as DealViewerState.Failed).message.contains("could not decrypt"))
        val mismatch = DealDocumentPresenter.present(DealDocumentResult.Failed(DealDocumentResult.HASH_MISMATCH))
        assertTrue((mismatch as DealViewerState.Failed).message.contains("hash mismatch"))
        assertEquals(DealConsentBlock.NOT_RENDERED, DealConsentGate.block(mismatch, DealConsentChecklist("x", true), false, false))
        // Corrupt Bob's stored sealed copy in the isolated test DB: the phone can no longer decrypt.
        link.bob.database.openHelper.writableDatabase.execSQL("UPDATE deal_versions SET sealedDocument = X'00'")
        assertNull(link.bob.bridge.signingSnapshot())
        val state = DealDocumentPresenter.present(link.bob.bridge.signingSnapshot())
        assertEquals(DealDocumentPresenter.UNAVAILABLE, failedCode(state))
        val before = audits(link.bob, dealId)
        assertThrows(DealStateException::class.java) { link.bob.bridge.recordBoundReview() }
        assertEquals(before, audits(link.bob, dealId))
        link.close()
    }

    @Test
    fun hiddenUnicodeBlocksConsentWithoutRewriting() {
        val link = Link()
        val terms = "Client pays \u202E05\u202C lakh.\nNo\u200Bhidden"
        val dealId = proposed(link, terms)
        val state = rendered(DealDocumentPresenter.present(link.bob.bridge.signingSnapshot()))
        assertNotNull(state.hiddenWarning)
        assertTrue(state.hiddenWarning!!.contains("U+202E"))
        assertEquals(terms, DealClauses.join(state.view.clauses))
        assertEquals("Client pays \u202E05\u202C lakh.", state.lines.first { it.label == "Clause 1" }.value)
        val ticked = DealConsentChecklist().check(true, state)
        assertEquals(DealConsentBlock.HIDDEN_CHARACTERS, DealConsentGate.block(state, ticked, false, false))
        val before = audits(link.bob, dealId)
        assertThrows(DealStateException::class.java) { link.bob.bridge.recordBoundReview() }
        assertEquals(before, audits(link.bob, dealId))
        val controls = DealSigningControls.from(link.bob.bridge.signingSnapshot()!!, state)
        assertFalse(controls.review || controls.confirmName || controls.sign)
        val error = assertThrows(DealReviewRequiredException::class.java) { blocking { link.bob.bridge.reviewBoundDeal() } }
        assertEquals("review_required", DealFailureLog.code(error))
        link.close()
    }

    @Test
    fun consentBypassIsRefused() {
        val link = Link()
        val dealId = proposed(link)
        val snapshot = link.bob.bridge.signingSnapshot()!!
        val state = rendered(DealDocumentPresenter.present(snapshot))
        val before = audits(link.bob, dealId)
        // Opening/presenting/ticking never records consent.
        repeat(3) { DealDocumentPresenter.present(link.bob.bridge.signingSnapshot()) }
        val unticked = DealConsentChecklist().forState(state)
        assertEquals(DealConsentBlock.NOT_CHECKED, DealConsentGate.block(state, unticked, false, false))
        val ticked = unticked.check(true, state)
        assertEquals(DealConsentBlock.NONE, DealConsentGate.block(state, ticked, false, false))
        assertEquals(before, audits(link.bob, dealId))
        assertFalse(link.bob.bridge.signingSnapshot()!!.ownReviewConsent)
        // A checkbox ticked for another version does not count.
        assertEquals(DealConsentBlock.NOT_CHECKED, DealConsentGate.block(state, DealConsentChecklist("other:key", true), false, false))
        // Controls stay disabled and the service refuses without consent.
        assertFalse(DealSigningControls.from(snapshot, state).review)
        assertThrows(DealReviewRequiredException::class.java) { blocking { link.bob.bridge.reviewBoundDeal() } }
        // Explicit action records it; then controls enable.
        link.bob.bridge.recordBoundReview()
        val after = link.bob.bridge.signingSnapshot()!!
        assertTrue(after.ownReviewConsent)
        assertTrue(DealSigningControls.from(after, DealDocumentPresenter.present(after)).review)
        assertEquals(DealConsentBlock.ALREADY_RECORDED, DealConsentGate.block(DealDocumentPresenter.present(after), ticked, true, false))
        link.close()
    }

    @Test
    fun staleVersionResetsChecklistAndConsent() {
        val link = Link()
        proposed(link)
        val first = rendered(DealDocumentPresenter.present(link.bob.bridge.signingSnapshot()))
        val ticked = DealConsentChecklist().check(true, first)
        link.bob.bridge.recordBoundReview()
        assertTrue(link.bob.bridge.signingSnapshot()!!.ownReviewConsent)
        blocking {
            link.bob.bridge.reviewBoundDeal()
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Neeraj Sharma")
            link.drain()
        }
        val snapshot = link.bob.bridge.signingSnapshot()!!
        val second = rendered(DealDocumentPresenter.present(snapshot))
        assertNotEquals(first.consentKey, second.consentKey)
        assertFalse(snapshot.ownReviewConsent)
        val reset = ticked.forState(second)
        assertFalse(reset.checked)
        assertEquals(second.consentKey, reset.key)
        assertEquals(DealConsentBlock.NOT_CHECKED, DealConsentGate.block(second, reset, snapshot.ownReviewConsent, false))
        assertFalse(DealSigningControls.from(snapshot, second).confirmName)
        assertThrows(DealReviewRequiredException::class.java) { blocking { link.bob.bridge.confirmBoundName("Asha Menon") } }
        link.close()
    }

    @Test
    fun counterpartyRendersSameCompleteDocument() {
        val link = Link()
        readyToSign(link)
        val alice = rendered(DealDocumentPresenter.present(link.alice.bridge.signingSnapshot()))
        val bob = rendered(DealDocumentPresenter.present(link.bob.bridge.signingSnapshot()))
        assertEquals(alice.consentKey, bob.consentKey)
        assertEquals(alice.lines.map { it.section + "|" + it.label + "|" + it.value }, bob.lines.map { it.section + "|" + it.label + "|" + it.value })
        assertTrue(bob.lines.any { it.value == "Pay on acceptance of the work." })
        assertTrue(bob.lines.any { it.value == "1,500.50 INR (150050 minor units)" })
        assertTrue(bob.lines.any { it.value == SECRET_TERMS })
        link.close()
    }

    @Test
    fun signConfirmationDerivedFromVerifiedDocument() {
        val link = Link()
        readyToSign(link)
        val view = rendered(DealDocumentPresenter.present(link.bob.bridge.signingSnapshot())).view
        val confirmation = DealSignConfirmation.from(view, PartyRole.COUNTERPARTY)
        for (part in listOf(view.versionId, view.fingerprint, "Neeraj Sharma", "Asha Menon", "1,500.50 INR (150050 minor units)",
            "Pay on acceptance of the work.", "2026-12-31", "Service agreement", "Party B (counterparty) — you")) {
            assertTrue("dialog missing $part", confirmation.body.contains(part))
        }
        assertFalse(confirmation.toString().contains("Neeraj"))
        link.close()
    }

    @Test
    fun fullySignedDocumentStaysAccessibleWithControlsDisabled() {
        val link = Link()
        val dealId = readyToSign(link)
        blocking {
            link.alice.bridge.signWithConsent()
            link.drain()
            link.bob.bridge.signWithConsent()
            link.drain()
        }
        for (phone in listOf(link.alice, link.bob)) {
            val snapshot = phone.bridge.signingSnapshot()!!
            assertEquals(SigningStage.FULLY_SIGNED, snapshot.stage)
            val state = rendered(DealDocumentPresenter.present(snapshot))
            assertTrue(state.lines.any { it.value == SECRET_TERMS })
            val controls = DealSigningControls.from(snapshot, state)
            assertFalse(controls.review || controls.confirmName || controls.sign || controls.revise || controls.reject)
            assertEquals(DealConsentBlock.FINAL, DealConsentGate.block(state, DealConsentChecklist().check(true, state), snapshot.ownReviewConsent, snapshot.proven))
            assertEquals(2, phone.database.dealDao().signatureCount(dealId))
        }
        link.close()
    }

    // ---- fixture copied from DealMessagingIntegrationTest (signers exposed) ----



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
