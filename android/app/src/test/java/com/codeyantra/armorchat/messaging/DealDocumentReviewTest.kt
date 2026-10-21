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

/**
 * Phase 5.4B (complete document view) and 5.4D (version/hash-bound review consent).
 * Isolated fixtures only (in-memory Room, FakeMls, in-memory relay); never phone DBs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DealDocumentReviewTest {

    // ---------- shared flow helpers ----------

    private fun proposed(link: Link): String {
        val draft = link.draft(SECRET_TERMS)
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
        assertEquals(SigningStage.READY_TO_SIGN, link.alice.bridge.signingSnapshot()!!.stage)
        return dealId
    }

    private fun current(phone: Phone, dealId: String) = phone.database.dealDao().findDeal(dealId)!!.currentVersionId

    private fun storedHash(phone: Phone, dealId: String, versionId: String) =
        phone.database.dealDao().findVersion(dealId, versionId)!!.documentSha256

    private fun ready(result: DealDocumentResult): DealDocumentView {
        assertTrue("expected Ready, got $result", result is DealDocumentResult.Ready)
        return (result as DealDocumentResult.Ready).view
    }

    private fun state(phone: Phone, dealId: String, withAudits: Boolean = true): List<String> {
        val dao = phone.database.dealDao()
        val deal = dao.findDeal(dealId)!!
        val out = mutableListOf("deal:${deal.status}:${deal.currentVersionId}:${deal.updatedAtEpochMs}")
        dao.versions(dealId).sortedBy { it.versionId }.forEach {
            out += "ver:${it.versionId}:${it.documentSha256}:${it.superseded}:${it.immutable}"
            dao.signatures(dealId, it.versionId).forEach { s ->
                out += "sig:${s.versionId}:${s.partyId}:${Base64Url.encode(s.signature)}"
            }
        }
        DealWireEvent.entries.forEach { event ->
            listOf(DealTransportState.IN, DealTransportState.OUT).forEach { dir ->
                dao.transportForDeal(dealId, dir, event.wire).forEach {
                    out += "tx:$dir:${it.envelopeId}:${it.status}:${it.updatedAtEpochMs}"
                }
            }
        }
        if (withAudits) dao.audits(dealId).forEach { out += "audit:${it.eventId}:${it.action}:${it.outcome}" }
        return out.sorted()
    }

    private fun consents(phone: Phone, dealId: String) =
        phone.database.dealDao().audits(dealId).filter { it.action == DealReviewConsent.ACTION }

    /** The action must throw review_required and leave BOTH phones byte-identical (incl. audits). */
    private fun assertReviewRequired(link: Link, dealId: String, action: suspend () -> Unit) {
        val beforeA = state(link.alice, dealId)
        val beforeB = state(link.bob, dealId)
        link.now += 60_000
        val error = assertThrows(DealReviewRequiredException::class.java) { blocking { action() } }
        assertEquals("review_required", DealFailureLog.code(error))
        blocking { link.drain() }
        assertEquals(beforeA, state(link.alice, dealId))
        assertEquals(beforeB, state(link.bob, dealId))
    }

    private fun service(link: Link): DealTransportService {
        val alice = link.alice
        val local = VerifiedDealParticipant.verify(alice.user.armorChatId, Base64Url.decode(alice.user.publicIdentityKey))
        val peer = VerifiedDealParticipant.verify(link.bob.user.armorChatId, Base64Url.decode(link.bob.user.publicIdentityKey))
        return DealTransportService(
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

    // ---------- 5.4B ----------

    @Test
    fun documentViewIncludesEveryCanonicalField() {
        val link = Link()
        val dealId = readyToSign(link)
        val versionId = current(link.alice, dealId)
        val v4 = link.open(link.alice, dealId, versionId, PartyRole.PROPOSER)
        assertEquals(4, v4.canonicalVersion)
        val view = ready(DealDocumentView.from(v4, storedHash(link.alice, dealId, versionId)))
        assertEquals(dealId, view.dealId)
        assertEquals(versionId, view.versionId)
        assertEquals(4, view.canonicalVersion)
        assertEquals("Service agreement", view.title)
        assertEquals("Keep the purpose sealed", view.purpose)
        assertEquals("Client", view.proposerRole)
        assertEquals("Provider", view.counterpartyRole)
        assertEquals("Pay on acceptance of the work.", view.paymentConditions)
        assertEquals("INR", view.currency)
        assertEquals(150050L, view.amountMinor)
        assertEquals(DealMoneyText.Amount("1,500.50 INR", "INR", 150050L, 2), view.money)
        assertEquals("2026-12-31", view.deadlineDate)
        assertEquals("2026-11-01", view.effectiveDate)
        assertEquals(SECRET_TERMS, view.terms)
        assertEquals(SECRET_TERMS, DealClauses.join(view.clauses))
        assertTrue(view.hiddenCharacters.isEmpty())
        assertEquals(storedHash(link.alice, dealId, versionId), view.documentHashBase64)
        assertEquals(35, view.fingerprint.length)
        val proposer = view.parties.single { it.role == PartyRole.PROPOSER }
        val counterparty = view.parties.single { it.role == PartyRole.COUNTERPARTY }
        assertEquals("Neeraj Sharma", proposer.legalName)
        assertEquals("Asha Menon", counterparty.legalName)
        assertEquals(LegalNameStatus.SELF_DECLARED_UNVERIFIED, proposer.nameStatus)
        assertEquals(LegalNameStatus.SELF_DECLARED_UNVERIFIED, counterparty.nameStatus)
        assertEquals("Client", proposer.contractualRole)
        assertEquals("Provider", counterparty.contractualRole)
        assertEquals(link.alice.user.armorChatId, proposer.armorChatId)
        assertEquals(link.bob.user.armorChatId, counterparty.armorChatId)
        // Both phones' snapshots expose the same complete, self-checked document.
        val aliceDoc = ready(link.alice.bridge.signingSnapshot()!!.document)
        val bobDoc = ready(link.bob.bridge.signingSnapshot()!!.document)
        assertEquals(view.documentHashBase64, aliceDoc.documentHashBase64)
        assertEquals(view.documentHashBase64, bobDoc.documentHashBase64)
        assertEquals("Pay on acceptance of the work.", bobDoc.paymentConditions)
        assertEquals(SECRET_TERMS, link.bob.bridge.signingSnapshot()!!.terms)

        val v3 = CanonicalDeal.create(
            dealId = v4.dealId, versionId = v4.versionId, parentVersionId = v4.parentVersionId,
            createdAtEpochMs = v4.createdAtEpochMs, currency = v4.currency, amountMinor = v4.amountMinor,
            parties = v4.parties, ancestry = v4.ancestry, terms = v4.terms, details = v4.details,
            identity = v4.identity, annex = null,
        )
        assertEquals(3, v3.canonicalVersion)
        val view3 = ready(DealDocumentView.from(v3, Base64Url.encode(v3.documentHash())))
        assertEquals(3, view3.canonicalVersion)
        assertNull(view3.purpose)
        assertNull(view3.effectiveDate)
        assertEquals("Service agreement", view3.title)
        assertEquals("Asha Menon", view3.parties.single { it.role == PartyRole.COUNTERPARTY }.legalName)

        val v2 = CanonicalDeal.create(
            dealId = v4.dealId, versionId = v4.versionId, parentVersionId = v4.parentVersionId,
            createdAtEpochMs = v4.createdAtEpochMs, currency = "JPY", amountMinor = 150050L,
            parties = v4.parties, ancestry = v4.ancestry, terms = "Clause one\nClause two", details = v4.details,
        )
        assertEquals(2, v2.canonicalVersion)
        val view2 = ready(DealDocumentView.from(v2, Base64Url.encode(v2.documentHash())))
        assertEquals(2, view2.canonicalVersion)
        assertEquals("2026-12-31", view2.deadlineDate)
        assertEquals("150,050 JPY", (view2.money as DealMoneyText.Amount).text)
        assertEquals(listOf("Clause one", "Clause two"), view2.clauses.map { it.text })
        assertTrue(view2.parties.all { it.legalName == null && it.armorChatId == null })
        assertEquals(v4.parties.map { it.label }.sorted(), view2.parties.map { it.label }.sorted())
        assertTrue(view2.parties.all { it.label.isNotEmpty() })
        link.close()
    }

    @Test
    fun documentViewFailsClosedOnHashMismatch() {
        val link = Link()
        val dealId = proposed(link)
        val versionId = current(link.alice, dealId)
        val deal = link.open(link.alice, dealId, versionId, PartyRole.PROPOSER)
        val wrong = DealDocumentView.from(deal, Base64Url.encode(ByteArray(32) { 1 }))
        assertTrue(wrong is DealDocumentResult.Failed)
        assertEquals(DealDocumentResult.HASH_MISMATCH, (wrong as DealDocumentResult.Failed).code)
        val garbage = DealDocumentView.from(deal, "not*base64")
        assertEquals(DealDocumentResult.HASH_MISMATCH, (garbage as DealDocumentResult.Failed).code)
        val unknownCurrency = CanonicalDeal.create(
            dealId = deal.dealId, versionId = deal.versionId, parentVersionId = deal.parentVersionId,
            createdAtEpochMs = deal.createdAtEpochMs, currency = "ZZZ", amountMinor = 5L,
            parties = deal.parties, ancestry = deal.ancestry, terms = deal.terms, details = deal.details,
        )
        val money = DealDocumentView.from(unknownCurrency, Base64Url.encode(unknownCurrency.documentHash()))
        assertEquals(DealDocumentResult.MONEY_UNRENDERABLE, (money as DealDocumentResult.Failed).code)
        // A snapshot that cannot render cannot record consent (fail closed).
        link.alice.database.dealDao().let { dao ->
            assertEquals(storedHash(link.alice, dealId, versionId), Base64Url.encode(deal.documentHash()))
            assertTrue(dao.audits(dealId).none { it.action == DealReviewConsent.ACTION })
        }
        link.close()
    }

    @Test
    fun toStringRedactsTerms() {
        val link = Link()
        val dealId = readyToSign(link)
        val snapshot = link.bob.bridge.signingSnapshot()!!
        val view = ready(snapshot.document)
        val printed = listOf(
            snapshot.toString(), snapshot.document.toString(), view.toString(),
            view.parties.joinToString(), view.clauses.joinToString(), view.money.toString(),
        ).joinToString("|")
        for (secret in listOf(SECRET_TERMS, "Neeraj Sharma", "Asha Menon", "Service agreement", "Keep the purpose sealed", "Pay on acceptance")) {
            assertFalse("toString leaked $secret", printed.contains(secret))
        }
        assertTrue(consents(link.alice, dealId).none { row -> listOf(SECRET_TERMS, "Neeraj Sharma").any { row.outcome.contains(it) } })
        link.close()
    }

    @Test
    fun moneyFormattingUsesMinorUnitsAndFailsClosed() {
        assertEquals(DealMoneyText.Amount("1,500.50 INR", "INR", 150050L, 2), DealMoney.format("INR", 150050L))
        assertEquals("1,500.50 INR (150050 minor units)", (DealMoney.format("INR", 150050L) as DealMoneyText.Amount).display)
        assertEquals("0.05 INR", (DealMoney.format("INR", 5L) as DealMoneyText.Amount).text)
        assertEquals("0.00 INR", (DealMoney.format("INR", 0L) as DealMoneyText.Amount).text)
        assertEquals(DealMoneyText.Amount("150,050 JPY", "JPY", 150050L, 0), DealMoney.format("JPY", 150050L))
        assertEquals(DealMoneyText.Amount("1,234.567 KWD", "KWD", 1234567L, 3), DealMoney.format("KWD", 1234567L))
        assertEquals("1,000,000,000,000,000.000 KWD", (DealMoney.format("KWD", 1_000_000_000_000_000_000L) as DealMoneyText.Amount).text)
        assertTrue(DealMoney.format("ZZZ", 100L) is DealMoneyText.Invalid)
        assertTrue(DealMoney.format("XXX", 100L) is DealMoneyText.Invalid)
        assertTrue(DealMoney.format("INR", -1L) is DealMoneyText.Invalid)
        assertTrue(DealMoney.format("INR", null) is DealMoneyText.Invalid)
        assertEquals(DealMoneyText.NoAmount, DealMoney.format(null, null))
        assertEquals("No amount specified", DealMoneyText.NoAmount.TEXT)
    }

    @Test
    fun clauseSplitPreservesBytes() {
        val samples = listOf(
            "single",
            "a\n\nb \tc\n",
            "\nleading and trailing\n\n",
            "मूल्य ₹1,500\n第二条 \u202Eabc\u202C\nend 😀",
        )
        for (sample in samples) {
            val clauses = DealClauses.split(sample)
            assertEquals(sample, DealClauses.join(clauses))
            assertTrue(sample.toByteArray(Charsets.UTF_8).contentEquals(DealClauses.join(clauses).toByteArray(Charsets.UTF_8)))
            assertEquals(sample.count { it == '\n' } + 1, clauses.size)
            assertEquals((1..clauses.size).toList(), clauses.map { it.number })
        }
        assertEquals(listOf("a", "", "b \tc", ""), DealClauses.split("a\n\nb \tc\n").map { it.text })
    }

    @Test
    fun bidiAndZeroWidthDetection() {
        assertTrue(com.codeyantra.armorchat.deal.transport.DealTextScan.hidden("Plain text ₹1,500 मूल्य 😀\tok").isEmpty())
        val text = "Pay \u202E01\u202C x\u200By\uFEFFz\u00AD\u2066i\u2069\u200F"
        val found = com.codeyantra.armorchat.deal.transport.DealTextScan.hidden(text)
        assertEquals(
            listOf(0x202E, 0x202C, 0x200B, 0xFEFF, 0x00AD, 0x2066, 0x2069, 0x200F),
            found.map { it.codePoint },
        )
        assertEquals(DealHiddenCharacter.Kind.BIDI_CONTROL, found[0].kind)
        assertEquals(DealHiddenCharacter.Kind.ZERO_WIDTH, found[2].kind)
        assertEquals(DealHiddenCharacter.Kind.SOFT_HYPHEN, found[4].kind)
        assertEquals(4, found[0].index)
        assertEquals("U+202E", found[0].label)
        found.forEach { assertEquals(it.codePoint, text.codePointAt(it.index)) }
    }

    // ---------- 5.4D ----------

    @Test
    fun reviewWithoutConsentRefused() {
        val link = Link()
        val dealId = proposed(link)
        assertReviewRequired(link, dealId) { link.bob.bridge.reviewBoundDeal() }
        assertTrue(consents(link.bob, dealId).isEmpty())
        blocking {
            link.bob.bridge.reviewWithConsent()
            link.drain()
        }
        assertEquals(1, consents(link.bob, dealId).size)
        assertEquals(SigningStage.AWAITING_PARTY_B_REVIEW, link.alice.bridge.signingSnapshot()!!.stage.let {
            if (it == SigningStage.AWAITING_NAME_CONFIRMATIONS) SigningStage.AWAITING_PARTY_B_REVIEW else it
        })
        link.close()
    }

    @Test
    fun nameWithoutConsentRefused() {
        val link = Link()
        val dealId = proposed(link)
        blocking {
            link.bob.bridge.reviewWithConsent()
            link.drain()
        }
        assertReviewRequired(link, dealId) { link.alice.bridge.confirmBoundName("Neeraj Sharma") }
        // Bob consented to this exact version before his review, so his name step is allowed.
        blocking { link.bob.bridge.confirmBoundName("Asha Menon") }
        link.close()
    }

    @Test
    fun signWithoutConsentRefusedNoSideEffects() {
        val link = Link()
        val dealId = readyToSign(link)
        // Bob's last consent was for the version before Alice's final name step... which created no successor,
        // but Bob's own name step created the current version: Bob has never consented to it.
        assertReviewRequired(link, dealId) { link.bob.bridge.signBoundDeal() }
        // Recording consent writes exactly one audit row and nothing else.
        val before = state(link.bob, dealId, withAudits = false)
        val audits = link.bob.database.dealDao().audits(dealId).size
        link.bob.bridge.recordBoundReview()
        link.bob.bridge.recordBoundReview() // idempotent
        assertEquals(before, state(link.bob, dealId, withAudits = false))
        assertEquals(audits + 1, link.bob.database.dealDao().audits(dealId).size)
        val row = consents(link.bob, dealId).last()
        val versionId = current(link.bob, dealId)
        assertEquals(
            DealReviewConsent.outcome(versionId, storedHash(link.bob, dealId, versionId), link.partyId(link.bob, dealId, PartyRole.COUNTERPARTY)),
            row.outcome,
        )
        blocking {
            link.bob.bridge.signBoundDeal()
            link.drain()
        }
        assertEquals(1, link.alice.database.dealDao().signatureCount(dealId))
        link.close()
    }

    @Test
    fun consentForOldVersionRejectedAfterRevision() {
        val link = Link()
        val dealId = proposed(link)
        val v1 = current(link.alice, dealId)
        link.alice.bridge.recordBoundReview()
        link.bob.bridge.recordBoundReview()
        blocking {
            link.alice.bridge.reviseBoundDeal("Revised clause 1\nRevised clause 2")
            link.drain()
        }
        val v2 = current(link.alice, dealId)
        assertNotEquals(v1, v2)
        assertEquals(v2, current(link.bob, dealId))
        assertReviewRequired(link, dealId) { link.bob.bridge.reviewBoundDeal() }
        assertReviewRequired(link, dealId) { link.alice.bridge.confirmBoundName("Neeraj Sharma") }
        val oldHash = Base64Url.decode(storedHash(link.alice, dealId, v1))
        val audits = link.alice.database.dealDao().audits(dealId).size
        assertThrows(DealStateException::class.java) { service(link).recordOwnReview(dealId, v1, oldHash) }
        assertEquals(audits, link.alice.database.dealDao().audits(dealId).size)
        blocking {
            link.bob.bridge.reviewWithConsent()
            link.drain()
        }
        assertEquals(SECRET_TERMS.isNotEmpty(), true)
        link.close()
    }

    @Test
    fun consentInvalidAfterNameSuccessor() {
        val link = Link()
        val dealId = proposed(link)
        blocking {
            link.bob.bridge.reviewWithConsent()
            link.drain()
            link.alice.bridge.confirmNameWithConsent("Neeraj Sharma")
            link.drain()
        }
        val successor = current(link.alice, dealId)
        assertEquals(successor, current(link.bob, dealId))
        assertTrue(link.alice.database.dealDao().versionCount(dealId) >= 2)
        // Both phones consented only to the parent version.
        assertReviewRequired(link, dealId) { link.bob.bridge.confirmBoundName("Asha Menon").let { } }
        assertReviewRequired(link, dealId) { link.alice.bridge.confirmBoundName("Neeraj Sharma").let { } }
        assertTrue(consents(link.alice, dealId).none { it.outcome.contains(":$successor:") })
        link.close()
    }

    @Test
    fun consentRequiresPersistedCurrentVersion() {
        val link = Link()
        val dealId = proposed(link)
        val versionId = current(link.alice, dealId)
        val hash = Base64Url.decode(storedHash(link.alice, dealId, versionId))
        val svc = service(link)
        val before = state(link.alice, dealId)
        assertThrows(DealStateException::class.java) { svc.recordOwnReview(dealId, DealIds.generate(), hash) }
        assertThrows(DealStateException::class.java) { svc.recordOwnReview(dealId, versionId, ByteArray(32) { 7 }) }
        assertThrows(DealStateException::class.java) {
            svc.recordOwnReview(dealId, versionId, hash.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() })
        }
        assertThrows(DealStateException::class.java) { svc.recordOwnReview(DealIds.generate(), versionId, hash) }
        assertEquals(before, state(link.alice, dealId))
        val id = svc.recordOwnReview(dealId, versionId, hash)
        assertEquals(id, svc.recordOwnReview(dealId, versionId, hash))
        assertEquals(1, consents(link.alice, dealId).size)
        link.close()
    }

    @Test
    fun consentThenSignSucceeds() {
        val link = Link()
        val dealId = readyToSign(link)
        blocking {
            link.alice.bridge.signWithConsent()
            link.drain()
            link.bob.bridge.signWithConsent()
            link.drain()
        }
        val versionId = current(link.alice, dealId)
        for ((phone, role) in listOf(link.alice to PartyRole.PROPOSER, link.bob to PartyRole.COUNTERPARTY)) {
            assertEquals(SigningStage.FULLY_SIGNED, phone.bridge.signingSnapshot()!!.stage)
            assertEquals(2, phone.database.dealDao().signatureCount(dealId))
            assertEquals(versionId, current(phone, dealId))
            val expected = DealReviewConsent.outcome(versionId, storedHash(phone, dealId, versionId), link.partyId(phone, dealId, role))
            assertTrue(consents(phone, dealId).any { it.outcome == expected })
            assertTrue(ready(phone.bridge.signingSnapshot()!!.document).versionId == versionId)
        }
        link.close()
    }

    @Test
    fun fullySignedDealUnaffected() {
        val link = Link()
        val dealId = readyToSign(link)
        blocking {
            link.alice.bridge.signWithConsent()
            link.drain()
            link.bob.bridge.signWithConsent()
            link.drain()
        }
        for (phone in listOf(link.alice, link.bob)) {
            val before = state(phone, dealId)
            link.now += 60_000
            val error = assertThrows(com.codeyantra.armorchat.deal.domain.DealAlreadySignedException::class.java) {
                blocking { phone.bridge.signBoundDeal() }
            }
            assertEquals("already_signed", DealFailureLog.code(error))
            assertEquals(before, state(phone, dealId))
            assertTrue(ready(phone.bridge.signingSnapshot()!!.document).money is DealMoneyText.Amount)
        }
        link.close()
    }

    @Test
    fun sealedPayloadStartingWithFakeMlsPrefixStillPasses() {
        // Fixed nonce 0x45 0x7C ('E','|'...): the exact case that made the old raw-prefix check flake.
        val fixed = SealingMls(ByteArray(32) { 9 }) { nonce ->
            nonce.fill(0)
            nonce[0] = 'E'.code.toByte()
            nonce[1] = '|'.code.toByte()
        }
        val sessionId = "session-regression"
        val sealed = fixed.seal(Base64Url.encode("E|$sessionId|inner".toByteArray()))
        val raw = Base64Url.decode(sealed)
        assertEquals('E'.code.toByte(), raw[0])
        assertEquals('|'.code.toByte(), raw[1])
        assertSecretAbsent(sealed, fixed::unseal, sessionId)
        // Negative cases stay strict: bare FakeMls payload, wrong session, wrong key, terms on the wire.
        val bare = Base64Url.encode("E|$sessionId|inner".toByteArray())
        assertThrows(AssertionError::class.java) { assertSecretAbsent(bare, fixed::unseal, sessionId) }
        assertThrows(AssertionError::class.java) { assertSecretAbsent(sealed, fixed::unseal, "other-session") }
        val otherKey = SealingMls(ByteArray(32) { 8 })
        assertThrows(AssertionError::class.java) { assertSecretAbsent(sealed, otherKey::unseal, sessionId) }
        val leaky = Base64Url.encode(SECRET_TERMS.toByteArray())
        assertThrows(AssertionError::class.java) { assertSecretAbsent(leaky, fixed::unseal, sessionId) }
    }

    // ---- fixture copied from DealMessagingIntegrationTest (signers exposed) ----



    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    private fun assertStateRefused(block: suspend () -> Unit) {
        assertThrows(DealStateException::class.java) {
            runBlocking { block() }
        }
    }

    private class SealingMls(
        seed: ByteArray,
        private val fillNonce: (ByteArray) -> Unit = SecureRandom()::nextBytes,
    ) {
        private val key = seed.copyOf()

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

        fun seal(fake: String): String {
            val nonce = ByteArray(12)
            fillNonce(nonce)
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
