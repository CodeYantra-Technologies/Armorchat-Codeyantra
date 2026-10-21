package com.codeyantra.armorchat.deal

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.crypto.DealTink
import com.codeyantra.armorchat.deal.crypto.PartyWrapTarget
import com.codeyantra.armorchat.deal.crypto.SealedDocument
import com.codeyantra.armorchat.deal.domain.AgreementDetails
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.ClosureAdmit
import com.codeyantra.armorchat.deal.domain.ClosureConfirmation
import com.codeyantra.armorchat.deal.domain.ClosureDefect
import com.codeyantra.armorchat.deal.domain.ClosureKind
import com.codeyantra.armorchat.deal.domain.ClosureState
import com.codeyantra.armorchat.deal.domain.ContractAnnex
import com.codeyantra.armorchat.deal.domain.DealClosure
import com.codeyantra.armorchat.deal.domain.DealEvent
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.DealMessagingSigner
import com.codeyantra.armorchat.deal.domain.DealPartyDraft
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.DealVersions
import com.codeyantra.armorchat.deal.domain.ImmutableVersionException
import com.codeyantra.armorchat.deal.domain.LegalNameStatus
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.domain.VerifiedDealParticipant
import com.codeyantra.armorchat.deal.draft.DealDraftStore
import com.codeyantra.armorchat.deal.storage.DealClosureRow
import com.codeyantra.armorchat.deal.storage.DealClosureStore
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealRepository
import com.codeyantra.armorchat.deal.testing.SoftwareDealKeyProtector
import com.codeyantra.armorchat.deal.testing.signedFixture
import com.codeyantra.armorchat.deal.transport.DealEnvelope
import com.codeyantra.armorchat.deal.transport.DealEnvelopes
import com.codeyantra.armorchat.deal.transport.DealMessagingSession
import com.codeyantra.armorchat.deal.transport.DealSigningLookup
import com.codeyantra.armorchat.deal.transport.DealTransportService
import com.codeyantra.armorchat.deal.transport.DealTransportState
import com.codeyantra.armorchat.deal.transport.DealWireEvent
import com.codeyantra.armorchat.deal.transport.ExportedSessionCipher
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DealClosureTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun oneConfirmationLeavesTheDealOpen() {
        val clock = Clock()
        val bound = bound()
        val db = memory()
        val store = persist(db, bound, DealStatus.FULLY_SIGNED, clock)
        val before = db.dealDao().findDeal(bound.deal.dealId)!!
        clock.now = 250L
        val goods = DealClosure.sign(bound.deal, bound.buyerId, bound.buyerKeys.signingHandle())
        assertEquals(ClosureKind.GOODS_RECEIVED, goods.kind)
        val admitted = store.admit(bound.deal, goods)
        val after = db.dealDao().findDeal(bound.deal.dealId)!!
        assertEquals(ClosureAdmit.ACCEPTED, admitted.admit)
        assertEquals(ClosureState.AWAITING_PAYMENT, admitted.state)
        assertEquals(DealStatus.FULLY_SIGNED.name, after.status)
        assertEquals(before.updatedAtEpochMs, after.updatedAtEpochMs)
        assertEquals(before.currentVersionId, after.currentVersionId)
        assertEquals(1, db.dealDao().closures(bound.deal.dealId, bound.deal.versionId).size)
        assertEquals(2, db.dealDao().signatures(bound.deal.dealId, bound.deal.versionId).size)
        assertEquals(0, count(db, bound, DealClosureStore.ACTION_CLOSED))
        assertEquals(1, count(db, bound, DealClosureStore.ACTION_ACCEPT))
        assertClean(db)
        db.close()
    }

    @Test
    fun paymentAloneLeavesTheDealWaitingForGoodsFromEveryFinalStatus() {
        listOf(DealStatus.FULLY_SIGNED, DealStatus.BACKUP_PENDING, DealStatus.EXECUTED).forEach { status ->
            val clock = Clock()
            val bound = bound()
            val db = memory()
            val store = persist(db, bound, status, clock)
            clock.now = 250L
            val payment = DealClosure.sign(bound.deal, bound.supplierId, bound.supplierKeys.signingHandle())
            assertEquals(ClosureKind.PAYMENT_RECEIVED, payment.kind)
            val admitted = store.admit(bound.deal, payment)
            val row = db.dealDao().findDeal(bound.deal.dealId)!!
            assertEquals(ClosureState.AWAITING_GOODS, admitted.state)
            assertEquals(status.name, row.status)
            assertEquals(100L, row.updatedAtEpochMs)
            assertEquals(0, count(db, bound, DealClosureStore.ACTION_CLOSED))
            assertClean(db)
            db.close()
        }
    }

    @Test
    fun eitherOrderClosesOnceAndARetryDoesNotTransitionAgain() {
        listOf(true, false).forEach { goodsFirst ->
            val clock = Clock()
            val bound = bound()
            val db = memory()
            val store = persist(db, bound, DealStatus.FULLY_SIGNED, clock)
            val goods = DealClosure.sign(bound.deal, bound.buyerId, bound.buyerKeys.signingHandle())
            val payment = DealClosure.sign(bound.deal, bound.supplierId, bound.supplierKeys.signingHandle())
            val first = if (goodsFirst) goods else payment
            val second = if (goodsFirst) payment else goods
            clock.now = 180L
            assertEquals(ClosureAdmit.ACCEPTED, store.admit(bound.deal, first).admit)
            assertEquals(DealStatus.FULLY_SIGNED.name, db.dealDao().findDeal(bound.deal.dealId)!!.status)
            assertEquals(100L, db.dealDao().findDeal(bound.deal.dealId)!!.updatedAtEpochMs)
            clock.now = 240L
            val closed = store.admit(bound.deal, second)
            assertEquals(ClosureAdmit.CLOSED, closed.admit)
            assertEquals(ClosureState.CLOSED, closed.state)
            assertEquals(DealStatus.CLOSED.name, db.dealDao().findDeal(bound.deal.dealId)!!.status)
            assertEquals(240L, db.dealDao().findDeal(bound.deal.dealId)!!.updatedAtEpochMs)
            assertEquals(1, count(db, bound, DealClosureStore.ACTION_CLOSED))
            clock.now = 300L
            val retry = store.admit(bound.deal, second)
            assertEquals(ClosureAdmit.DUPLICATE, retry.admit)
            assertEquals(ClosureState.CLOSED, retry.state)
            assertEquals(240L, db.dealDao().findDeal(bound.deal.dealId)!!.updatedAtEpochMs)
            assertEquals(1, count(db, bound, DealClosureStore.ACTION_CLOSED))
            assertEquals(2, db.dealDao().closures(bound.deal.dealId, bound.deal.versionId).size)
            assertEquals(2, db.dealDao().signatures(bound.deal.dealId, bound.deal.versionId).size)
            assertEquals(1, db.dealDao().versionCount(bound.deal.dealId))
            assertClean(db)
            db.close()
        }
    }

    @Test
    fun duplicateOfTheFirstConfirmationDoesNotChangeStatusOrTime() {
        val clock = Clock()
        val bound = bound()
        val db = memory()
        val store = persist(db, bound, DealStatus.FULLY_SIGNED, clock)
        val goods = DealClosure.sign(bound.deal, bound.buyerId, bound.buyerKeys.signingHandle())
        store.admit(bound.deal, goods)
        clock.now = 900L
        val again = store.admit(bound.deal, goods)
        val row = db.dealDao().findDeal(bound.deal.dealId)!!
        assertEquals(ClosureAdmit.DUPLICATE, again.admit)
        assertEquals(DealStatus.FULLY_SIGNED.name, row.status)
        assertEquals(100L, row.updatedAtEpochMs)
        assertEquals(1, db.dealDao().closures(bound.deal.dealId, bound.deal.versionId).size)
        assertEquals(1, count(db, bound, DealClosureStore.ACTION_DUPLICATE))
        assertEquals(0, count(db, bound, DealClosureStore.ACTION_CLOSED))
        db.close()
    }

    @Test
    fun forgedWrongRoleWrongDealAndStaleVersionDoNotClose() {
        val clock = Clock()
        val bound = bound()
        val db = memory()
        val store = persist(db, bound, DealStatus.FULLY_SIGNED, clock)
        val goods = DealClosure.sign(bound.deal, bound.buyerId, bound.buyerKeys.signingHandle())
        val flipped = goods.signature.copyOf()
        flipped[0] = (flipped[0].toInt() xor 0x01).toByte()
        val forged = confirmation(bound.deal.dealId, bound.deal.versionId, bound.buyerId, ClosureKind.GOODS_RECEIVED, flipped, bound.buyerKeys.signingPublic)
        assertEquals(ClosureDefect.FORGED, store.admit(bound.deal, forged).defect)

        val rolePayload = DealClosure.payloadBytes(bound.deal, bound.buyerId, ClosureKind.PAYMENT_RECEIVED)
        val roleSignature = DealIdentityCrypto.sign(bound.buyerKeys.signingHandle(), rolePayload)
        val wrongRole = confirmation(
            bound.deal.dealId,
            bound.deal.versionId,
            bound.buyerId,
            ClosureKind.PAYMENT_RECEIVED,
            roleSignature,
            bound.buyerKeys.signingPublic,
        )
        assertEquals(ClosureDefect.WRONG_ROLE, store.admit(bound.deal, wrongRole).defect)

        val wrongDeal = confirmation(
            "c".repeat(22),
            bound.deal.versionId,
            bound.buyerId,
            goods.kind,
            goods.signature,
            goods.signerPublicKey,
        )
        assertEquals(ClosureDefect.WRONG_DEAL, store.admit(bound.deal, wrongDeal).defect)

        val stale = confirmation(
            bound.deal.dealId,
            "s".repeat(22),
            bound.buyerId,
            goods.kind,
            goods.signature,
            goods.signerPublicKey,
        )
        assertEquals(ClosureDefect.STALE, store.admit(bound.deal, stale).defect)

        val otherKeyPayload = DealClosure.payloadBytes(bound.deal, bound.buyerId, ClosureKind.GOODS_RECEIVED)
        val otherKeySignature = DealIdentityCrypto.sign(bound.supplierKeys.signingHandle(), otherKeyPayload)
        val otherKey = confirmation(
            bound.deal.dealId,
            bound.deal.versionId,
            bound.buyerId,
            ClosureKind.GOODS_RECEIVED,
            otherKeySignature,
            bound.supplierKeys.signingPublic,
        )
        assertEquals(ClosureDefect.WRONG_SIGNER, store.admit(bound.deal, otherKey).defect)

        val row = db.dealDao().findDeal(bound.deal.dealId)!!
        assertEquals(DealStatus.FULLY_SIGNED.name, row.status)
        assertEquals(100L, row.updatedAtEpochMs)
        assertEquals(0, db.dealDao().closures(bound.deal.dealId, bound.deal.versionId).size)
        assertEquals(0, count(db, bound, DealClosureStore.ACTION_CLOSED))
        assertTrue(count(db, bound, DealClosureStore.ACTION_REJECT) >= 5)
        assertClean(db)
        db.close()
    }

    @Test
    fun aPartyCannotCloseAloneOrForTheOtherParty() {
        val clock = Clock()
        val bound = bound()
        val db = memory()
        val store = persist(db, bound, DealStatus.FULLY_SIGNED, clock)
        val payment = DealClosure.sign(bound.deal, bound.supplierId, bound.supplierKeys.signingHandle())
        assertEquals(ClosureAdmit.ACCEPTED, store.admit(bound.deal, payment).admit)
        val posedAsBuyer = confirmation(
            bound.deal.dealId,
            bound.deal.versionId,
            bound.buyerId,
            ClosureKind.GOODS_RECEIVED,
            payment.signature,
            bound.supplierKeys.signingPublic,
        )
        assertEquals(ClosureDefect.WRONG_SIGNER, store.admit(bound.deal, posedAsBuyer).defect)
        assertEquals(DealStatus.FULLY_SIGNED.name, db.dealDao().findDeal(bound.deal.dealId)!!.status)
        assertEquals(1, db.dealDao().closures(bound.deal.dealId, bound.deal.versionId).size)
        assertEquals(ClosureState.AWAITING_GOODS, store.project(bound.deal).state)
        db.close()
    }

    @Test
    fun unboundRolesAndAVersionWithoutIdentityCannotClose() {
        val clock = Clock()
        val unbound = bound(proposerRole = "Buyer", counterpartyRole = "Buyer")
        val db = memory()
        persist(db, unbound, DealStatus.FULLY_SIGNED, clock)
        assertThrows(DealStateException::class.java) {
            DealClosure.sign(unbound.deal, unbound.buyerId, unbound.buyerKeys.signingHandle())
        }
        val crafted = confirmation(
            unbound.deal.dealId,
            unbound.deal.versionId,
            unbound.buyerId,
            ClosureKind.GOODS_RECEIVED,
            ByteArray(64) { 3 },
            unbound.buyerKeys.signingPublic,
        )
        val rejected = DealClosureStore(db, clock::now).admit(unbound.deal, crafted)
        assertEquals(ClosureDefect.UNBOUND, rejected.defect)
        assertEquals(0, db.dealDao().closures(unbound.deal.dealId, unbound.deal.versionId).size)
        db.close()

        val legacyDb = memory()
        val fixture = signedFixture(terms = "Rent")
        val protector = SoftwareDealKeyProtector(ByteArray(32) { 9 })
        DealRepository(legacyDb).saveVerified(
            fixture.deal,
            fixture.sealed,
            fixture.signatures,
            fixture.partyA,
            fixture.keysA,
            protector,
            DealStatus.FULLY_SIGNED.name,
        )
        val legacy = confirmation(
            fixture.deal.dealId,
            fixture.deal.versionId,
            fixture.partyA,
            ClosureKind.GOODS_RECEIVED,
            ByteArray(64) { 4 },
            fixture.keysA.signingPublic,
        )
        val legacyResult = DealClosureStore(legacyDb).admit(fixture.deal, legacy)
        assertEquals(ClosureDefect.UNBOUND, legacyResult.defect)
        assertEquals(DealStatus.FULLY_SIGNED.name, legacyDb.dealDao().findDeal(fixture.deal.dealId)!!.status)
        assertEquals(0, legacyDb.dealDao().closures(fixture.deal.dealId, fixture.deal.versionId).size)
        legacyDb.close()
    }

    @Test
    fun oneSignatureCannotBeClosed() {
        val clock = Clock()
        val bound = bound(bothSignatures = false)
        val db = memory()
        val store = persist(db, bound, DealStatus.PARTIALLY_SIGNED, clock)
        val goods = DealClosure.sign(bound.deal, bound.buyerId, bound.buyerKeys.signingHandle())
        val admitted = store.admit(bound.deal, goods)
        assertEquals(ClosureDefect.NOT_FINAL, admitted.defect)
        assertEquals(DealStatus.PARTIALLY_SIGNED.name, db.dealDao().findDeal(bound.deal.dealId)!!.status)
        assertEquals(0, db.dealDao().closures(bound.deal.dealId, bound.deal.versionId).size)
        assertEquals(1, db.dealDao().signatures(bound.deal.dealId, bound.deal.versionId).size)
        db.close()
    }

    @Test
    fun conflictingBytesAreNotReplaced() {
        val clock = Clock()
        val bound = bound()
        val db = memory()
        val store = persist(db, bound, DealStatus.FULLY_SIGNED, clock)
        val planted = ByteArray(64) { 7 }
        db.dealDao().insertClosure(
            DealClosureRow(
                dealId = bound.deal.dealId,
                versionId = bound.deal.versionId,
                partyId = bound.buyerId,
                kind = ClosureKind.GOODS_RECEIVED.wire,
                signature = planted,
                signerPublicKey = Base64Url.encode(bound.buyerKeys.signingPublic),
                documentSha256 = Base64Url.encode(bound.deal.documentHash()),
                createdAtEpochMs = 1L,
            ),
        )
        val goods = DealClosure.sign(bound.deal, bound.buyerId, bound.buyerKeys.signingHandle())
        val admitted = store.admit(bound.deal, goods)
        val stored = db.dealDao().findClosure(
            bound.deal.dealId,
            bound.deal.versionId,
            bound.buyerId,
            ClosureKind.GOODS_RECEIVED.wire,
        )!!
        assertEquals(ClosureDefect.CONFLICT, admitted.defect)
        assertTrue(planted.contentEquals(stored.signature))
        assertEquals(DealStatus.FULLY_SIGNED.name, db.dealDao().findDeal(bound.deal.dealId)!!.status)
        db.close()
    }

    @Test
    fun closedStatusCannotMoveBackwardOrBeRevised() {
        val bound = bound()
        assertTrue(DealLifecycle.claimsFinality(DealStatus.CLOSED))
        assertFalse(DealLifecycle.allowsRevision(DealStatus.CLOSED))
        assertFalse(DealLifecycle.allowsFinalizedStatus(DealStatus.CLOSED, DealStatus.FULLY_SIGNED))
        assertFalse(DealLifecycle.allowsFinalizedStatus(DealStatus.CLOSED, DealStatus.CANCELLED))
        assertTrue(DealLifecycle.allowsFinalizedStatus(DealStatus.FULLY_SIGNED, DealStatus.CLOSED))
        assertTrue(DealLifecycle.allowsFinalizedStatus(DealStatus.BACKUP_PENDING, DealStatus.CLOSED))
        assertTrue(DealLifecycle.allowsFinalizedStatus(DealStatus.EXECUTED, DealStatus.CLOSED))
        assertThrows(DealStateException::class.java) {
            DealLifecycle.transition(DealStatus.CLOSED, DealEvent.CANCEL)
        }
        assertThrows(ImmutableVersionException::class.java) {
            DealVersions.revise(
                current = bound.deal,
                currentBytes = bound.deal.canonicalBytes(),
                signatureCount = 2,
                dealStatus = DealStatus.CLOSED,
                newVersionId = "w".repeat(22),
                newTerms = "changed after closure",
                revisedAtEpochMs = bound.deal.createdAtEpochMs + 10,
            )
        }
    }

    @Test
    fun restartKeepsBothConfirmationsAndTheClosedStatus() {
        val name = "closure-restart-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val clock = Clock()
        val bound = bound()
        val protector = SoftwareDealKeyProtector(ByteArray(32) { 9 })
        val first = DealDatabase.builder(context, name).allowMainThreadQueries().build()
        try {
            val store = persist(first, bound, DealStatus.FULLY_SIGNED, clock, protector)
            clock.now = 180L
            store.admit(bound.deal, DealClosure.sign(bound.deal, bound.supplierId, bound.supplierKeys.signingHandle()))
            clock.now = 260L
            assertEquals(
                ClosureAdmit.CLOSED,
                store.admit(bound.deal, DealClosure.sign(bound.deal, bound.buyerId, bound.buyerKeys.signingHandle())).admit,
            )
            assertEquals(DealStatus.CLOSED.name, first.dealDao().findDeal(bound.deal.dealId)!!.status)
        } finally {
            first.close()
        }
        val reopened = DealDatabase.builder(context, name).allowMainThreadQueries().build()
        try {
            val row = reopened.dealDao().findDeal(bound.deal.dealId)!!
            assertEquals(DealStatus.CLOSED.name, row.status)
            assertEquals(260L, row.updatedAtEpochMs)
            assertEquals(2, reopened.dealDao().closures(bound.deal.dealId, bound.deal.versionId).size)
            val projection = DealClosureStore(reopened).project(bound.deal)
            assertEquals(ClosureState.CLOSED, projection.state)
            assertTrue(projection.buyerConfirmed)
            assertTrue(projection.supplierConfirmed)
            assertEquals(1, reopened.dealDao().audits(bound.deal.dealId).count { it.action == DealClosureStore.ACTION_CLOSED })
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun schemaUpgradeAddsClosuresWithoutRewritingSignedRows() {
        val name = "closure-migrate-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val dealId = "d".repeat(22)
        val versionId = "v".repeat(22)
        val partyId = "a".repeat(22)
        val sealed = byteArrayOf(9, 8, 7)
        val signature = byteArrayOf(3, 3, 3)
        val envelope = byteArrayOf(6, 6, 6)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            SCHEMA_V3.forEach(old::execSQL)
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
                "INSERT INTO deal_transport (envelopeId, dealId, versionId, conversationId, direction, eventType, status, sealedEnvelope, createdAtEpochMs, updatedAtEpochMs) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("env-1", dealId, versionId, "conversation-1", "IN", "SIGNATURE", "APPLIED", envelope, 12L, 13L),
            )
            old.version = 3
        }
        val upgraded = DealDatabase.builder(context, name).allowMainThreadQueries().build()
        try {
            val deal = upgraded.dealDao().findDeal(dealId)!!
            assertEquals("FULLY_SIGNED", deal.status)
            assertEquals(11L, deal.updatedAtEpochMs)
            assertTrue(sealed.contentEquals(upgraded.dealDao().sealedBytes(dealId, versionId)!!))
            assertTrue(signature.contentEquals(upgraded.dealDao().signatures(dealId, versionId).single().signature))
            assertTrue(envelope.contentEquals(upgraded.dealDao().findTransport("env-1")!!.sealedEnvelope))
            assertEquals(0, upgraded.dealDao().closures(dealId, versionId).size)
            upgraded.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4, cursor.getInt(0))
            }
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun snapshotShowsPendingThenClosedWithoutTerms() {
        val clock = Clock()
        val bound = bound()
        val db = memory()
        val protector = SoftwareDealKeyProtector(ByteArray(32) { 9 })
        val store = persist(db, bound, DealStatus.FULLY_SIGNED, clock, protector)
        store.admit(bound.deal, DealClosure.sign(bound.deal, bound.buyerId, bound.buyerKeys.signingHandle()))
        val buyer = DealSigningLookup.find(db, protector, bound.buyerMessagingPublic, bound.supplierMessagingPublic)
        assertNotNull(buyer)
        assertTrue(buyer!!.proven)
        assertTrue(buyer.buyerConfirmed)
        assertFalse(buyer.supplierConfirmed)
        assertFalse(buyer.canConfirmClosure)
        assertEquals(ClosureKind.GOODS_RECEIVED, buyer.localClosureKind)
        assertEquals(
            "Open. Buyer confirmed goods received. Waiting for the supplier to confirm payment.",
            buyer.closureHeadline,
        )
        assertFalse(buyer.closureHeadline.contains(TERMS))
        assertFalse(buyer.toString().contains(TERMS))
        val supplier = DealSigningLookup.find(db, protector, bound.supplierMessagingPublic, bound.buyerMessagingPublic)
        assertNotNull(supplier)
        assertTrue(supplier!!.canConfirmClosure)
        assertEquals(ClosureKind.PAYMENT_RECEIVED, supplier.localClosureKind)
        assertEquals("Confirm payment received", DealClosure.confirmLabel(supplier.localClosureKind!!))
        assertEquals("Buyer confirmation: recorded", if (supplier.buyerConfirmed) "Buyer confirmation: recorded" else "Buyer confirmation: waiting")
        assertEquals("Supplier confirmation: waiting", if (supplier.supplierConfirmed) "Supplier confirmation: recorded" else "Supplier confirmation: waiting")

        clock.now = 240L
        store.admit(bound.deal, DealClosure.sign(bound.deal, bound.supplierId, bound.supplierKeys.signingHandle()))
        val closed = DealSigningLookup.find(db, protector, bound.buyerMessagingPublic, bound.supplierMessagingPublic)!!
        assertEquals("Closed. Both confirmations are recorded.", closed.closureHeadline)
        assertTrue(closed.buyerConfirmed)
        assertTrue(closed.supplierConfirmed)
        assertTrue(closed.closureTrusted)
        assertFalse(closed.canConfirmClosure)
        assertClean(db)
        db.close()
    }

    @Test
    fun storedClosedStatusWithoutBothConfirmationsDoesNotDisplayAsClosed() {
        val clock = Clock()
        val bound = bound()
        val db = memory()
        val protector = SoftwareDealKeyProtector(ByteArray(32) { 9 })
        val store = persist(db, bound, DealStatus.FULLY_SIGNED, clock, protector)
        store.admit(bound.deal, DealClosure.sign(bound.deal, bound.buyerId, bound.buyerKeys.signingHandle()))
        db.dealDao().updateDeal(bound.deal.dealId, DealStatus.CLOSED.name, bound.deal.versionId, 100L)
        val view = DealSigningLookup.find(db, protector, bound.buyerMessagingPublic, bound.supplierMessagingPublic)!!
        assertFalse(view.closureTrusted)
        assertEquals("Closure status does not verify. The agreement is not treated as closed.", view.closureHeadline)
        assertFalse(view.canConfirmClosure)
        db.close()
    }

    @Test
    fun unboundSignedRolesStayOpenInTheSnapshot() {
        val clock = Clock()
        val bound = bound(proposerRole = "Client", counterpartyRole = "Provider")
        val db = memory()
        val protector = SoftwareDealKeyProtector(ByteArray(32) { 9 })
        persist(db, bound, DealStatus.FULLY_SIGNED, clock, protector)
        val view = DealSigningLookup.find(db, protector, bound.buyerMessagingPublic, bound.supplierMessagingPublic)!!
        assertTrue(view.proven)
        assertFalse(view.canConfirmClosure)
        assertEquals(
            "Closure needs the signed roles Buyer and Supplier. This agreement stays open.",
            view.closureHeadline,
        )
        db.close()
    }

    @Test
    fun transportDeliversOutOfOrderAndRejectsTheOtherPartysConfirmation() {
        val clock = Clock()
        val bound = bound()
        val protector = SoftwareDealKeyProtector(ByteArray(32) { 8 })
        val supplierDb = memory()
        val buyerDb = memory()
        persist(supplierDb, bound, DealStatus.FULLY_SIGNED, clock, protector)
        persist(buyerDb, bound, DealStatus.FULLY_SIGNED, clock, protector, party = PartyRole.COUNTERPARTY)
        val supplierService = service(supplierDb, protector, PartyRole.PROPOSER, bound, clock)
        val buyerService = service(buyerDb, protector, PartyRole.COUNTERPARTY, bound, clock)
        val posed = DealEnvelopes.encode(
            DealEnvelope(
                event = DealWireEvent.CLOSURE,
                envelopeId = DealIds.generate(),
                dealId = bound.deal.dealId,
                versionId = bound.deal.versionId,
                senderId = bound.buyerParticipant.armorChatId,
                recipientId = bound.supplierParticipant.armorChatId,
                senderKey = bound.buyerMessagingPublic,
                recipientKey = bound.supplierMessagingPublic,
                conversationId = CONVERSATION,
                createdAtEpochMs = 100L,
                payload = DealEnvelopes.closurePayload(
                    ClosureKind.PAYMENT_RECEIVED,
                    bound.supplierId,
                    DealClosure.sign(bound.deal, bound.supplierId, bound.supplierKeys.signingHandle()).signature,
                    bound.supplierKeys.signingPublic,
                ),
            ),
        )
        assertThrows(DealFormatException::class.java) { supplierService.acceptPlain(posed) }
        assertEquals(0, supplierDb.dealDao().closures(bound.deal.dealId, bound.deal.versionId).size)
        assertEquals(
            DealTransportState.REJECTED,
            supplierDb.dealDao().transportForDeal(bound.deal.dealId, DealTransportState.IN, DealWireEvent.CLOSURE.wire).single().status,
        )

        clock.now = 180L
        buyerService.queueOwnClosure(bound.deal.dealId)
        val goodsFrame = buyerService.copyQueued().last().second
        assertEquals(DealTransportState.APPLIED, supplierService.acceptPlain(goodsFrame).status)
        assertEquals(DealStatus.FULLY_SIGNED.name, supplierDb.dealDao().findDeal(bound.deal.dealId)!!.status)
        assertEquals(ClosureState.AWAITING_PAYMENT, DealClosureStore(supplierDb).project(bound.deal).state)
        assertEquals(DealStatus.FULLY_SIGNED.name, buyerDb.dealDao().findDeal(bound.deal.dealId)!!.status)

        clock.now = 260L
        supplierService.queueOwnClosure(bound.deal.dealId)
        val paymentFrame = supplierService.copyQueued().last().second
        assertEquals(DealTransportState.APPLIED, buyerService.acceptPlain(paymentFrame).status)
        assertEquals(DealStatus.CLOSED.name, buyerDb.dealDao().findDeal(bound.deal.dealId)!!.status)
        assertEquals(DealStatus.CLOSED.name, supplierDb.dealDao().findDeal(bound.deal.dealId)!!.status)
        assertEquals(1, count(buyerDb, bound, DealClosureStore.ACTION_CLOSED))
        assertEquals(1, count(supplierDb, bound, DealClosureStore.ACTION_CLOSED))

        clock.now = 400L
        supplierService.queueOwnClosure(bound.deal.dealId)
        val retry = supplierService.copyQueued().last().second
        assertEquals(DealTransportState.DUPLICATE, buyerService.acceptPlain(retry).status)
        assertEquals(260L, buyerDb.dealDao().findDeal(bound.deal.dealId)!!.updatedAtEpochMs)
        assertEquals(1, count(buyerDb, bound, DealClosureStore.ACTION_CLOSED))
        assertEquals(2, buyerDb.dealDao().closures(bound.deal.dealId, bound.deal.versionId).size)
        assertClean(supplierDb)
        assertClean(buyerDb)
        supplierDb.close()
        buyerDb.close()
    }

    private fun memory(): DealDatabase =
        Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()

    private fun persist(
        db: DealDatabase,
        bound: Bound,
        status: DealStatus,
        clock: Clock,
        protector: SoftwareDealKeyProtector = SoftwareDealKeyProtector(ByteArray(32) { 9 }),
        party: PartyRole = PartyRole.PROPOSER,
    ): DealClosureStore {
        val repository = DealRepository(db, { clock.now })
        val signatures = if (bound.bothSignatures) {
            bound.signatures
        } else {
            bound.signatures.filter { it.partyId == bound.supplierId }
        }
        val saved = if (bound.bothSignatures && status != DealStatus.PARTIALLY_SIGNED) {
            DealStatus.FULLY_SIGNED.name
        } else {
            DealStatus.PARTIALLY_SIGNED.name
        }
        val localId = if (party == PartyRole.PROPOSER) bound.supplierId else bound.buyerId
        val localKeys = if (party == PartyRole.PROPOSER) bound.supplierKeys else bound.buyerKeys
        repository.saveVerified(bound.deal, bound.sealed, signatures, localId, localKeys, protector, saved)
        if (status.name != saved) {
            db.dealDao().updateDeal(bound.deal.dealId, status.name, bound.deal.versionId, clock.now)
        }
        return DealClosureStore(db, clock::now)
    }

    private fun service(
        db: DealDatabase,
        protector: SoftwareDealKeyProtector,
        role: PartyRole,
        bound: Bound,
        clock: Clock,
    ): DealTransportService {
        val local = if (role == PartyRole.PROPOSER) bound.supplierParticipant else bound.buyerParticipant
        val peer = if (role == PartyRole.PROPOSER) bound.buyerParticipant else bound.supplierParticipant
        val signer = if (role == PartyRole.PROPOSER) bound.supplierSigner else bound.buyerSigner
        val session = DealMessagingSession.establish(CONVERSATION, local, peer)
        return DealTransportService(
            database = db,
            protector = protector,
            drafts = DealDraftStore(db, protector, clock::now, true),
            session = session,
            cipher = ExportedSessionCipher(ByteArray(32) { 4 }, ByteArray(32) { 5 }),
            localRole = role,
            localMessagingSigner = signer,
            debugBuild = true,
            productionUiEnabled = false,
            clock = clock::now,
        )
    }

    private fun confirmation(
        dealId: String,
        versionId: String,
        partyId: String,
        kind: ClosureKind,
        signature: ByteArray,
        signer: ByteArray,
    ): ClosureConfirmation = ClosureConfirmation(dealId, versionId, partyId, kind, signature, signer)

    private fun count(db: DealDatabase, bound: Bound, action: String): Int =
        db.dealDao().audits(bound.deal.dealId).count { it.action == action }

    private fun assertClean(db: DealDatabase) {
        db.dealDao().listDeals().forEach { deal ->
            db.dealDao().audits(deal.dealId).forEach { audit ->
                FORBIDDEN.forEach { secret ->
                    assertFalse(audit.action.contains(secret))
                    assertFalse(audit.outcome.contains(secret))
                }
            }
        }
    }

    private class Clock {
        var now: Long = 100L
    }

    private class Bound(
        val deal: CanonicalDeal,
        val supplierId: String,
        val buyerId: String,
        val supplierKeys: DealKeyMaterial,
        val buyerKeys: DealKeyMaterial,
        val supplierParticipant: VerifiedDealParticipant,
        val buyerParticipant: VerifiedDealParticipant,
        val supplierSigner: DealMessagingSigner,
        val buyerSigner: DealMessagingSigner,
        val supplierMessagingPublic: ByteArray,
        val buyerMessagingPublic: ByteArray,
        val signatures: List<DealSignature>,
        val sealed: SealedDocument,
        val bothSignatures: Boolean,
    )

    private class HandleSigner(private val handle: KeysetHandle) : DealMessagingSigner {
        override val publicKey: ByteArray = DealIdentityCrypto.publicKey(handle)
        override fun sign(payload: ByteArray): ByteArray = DealIdentityCrypto.sign(handle, payload)
        override fun toString(): String = "HandleSigner"
    }

    companion object {
        private const val TERMS = "TERMS_MARKER_closure_9421"
        private const val PURPOSE = "PURPOSE_MARKER_closure"
        private const val LEGAL_SUPPLIER = "NameAlpha"
        private const val LEGAL_BUYER = "NameBeta"
        private const val CONVERSATION = "closure-conv"
        private val FORBIDDEN = listOf(TERMS, PURPOSE, LEGAL_SUPPLIER, LEGAL_BUYER)

        private fun messaging(): KeysetHandle {
            DealTink.register()
            return KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        }

        private fun bound(
            proposerRole: String = "Supplier",
            counterpartyRole: String = "Buyer",
            bothSignatures: Boolean = true,
        ): Bound {
            val supplierKeys = DealKeyMaterial.generate()
            val buyerKeys = DealKeyMaterial.generate()
            val supplierHandle = messaging()
            val buyerHandle = messaging()
            val supplierPublic = DealIdentityCrypto.publicKey(supplierHandle)
            val buyerPublic = DealIdentityCrypto.publicKey(buyerHandle)
            val supplierParticipant = VerifiedDealParticipant.verify(ArmorChatId.derive(supplierPublic), supplierPublic)
            val buyerParticipant = VerifiedDealParticipant.verify(ArmorChatId.derive(buyerPublic), buyerPublic)
            val supplierSigner = HandleSigner(supplierHandle)
            val buyerSigner = HandleSigner(buyerHandle)
            val supplierId = "a".repeat(22)
            val buyerId = "b".repeat(22)
            var deal = DealIdentity.assemble(
                dealId = "d".repeat(22),
                versionId = "v".repeat(22),
                parentVersionId = null,
                createdAtEpochMs = 1_700_000_000_000L,
                currency = "INR",
                amountMinor = 100L,
                terms = TERMS,
                details = AgreementDetails(
                    title = "Mutual closure",
                    deadlineDate = "2026-12-31",
                    paymentConditions = "",
                    proposerRole = proposerRole,
                    counterpartyRole = counterpartyRole,
                ),
                ancestry = emptyList(),
                drafts = listOf(
                    DealPartyDraft(
                        partyId = supplierId,
                        role = PartyRole.PROPOSER,
                        label = "Side A",
                        participant = supplierParticipant,
                        signingPublicKey = supplierKeys.signingPublic,
                        encryptionPublicKey = supplierKeys.encryptionPublic,
                    ),
                    DealPartyDraft(
                        partyId = buyerId,
                        role = PartyRole.COUNTERPARTY,
                        label = "Side B",
                        participant = buyerParticipant,
                        signingPublicKey = buyerKeys.signingPublic,
                        encryptionPublicKey = buyerKeys.encryptionPublic,
                    ),
                ),
                annex = ContractAnnex(purpose = PURPOSE, effectiveDate = null),
            )
            deal = DealIdentity.authorizeOwnBinding(deal, supplierId, supplierSigner, 0, DealStatus.DRAFT)
            deal = DealIdentity.authorizeOwnBinding(deal, buyerId, buyerSigner, 0, DealStatus.DRAFT)
            deal = DealIdentity.confirmOwnLegalName(deal, supplierId, supplierSigner, LEGAL_SUPPLIER, 0, DealStatus.DRAFT)
            deal = DealIdentity.confirmOwnLegalName(deal, buyerId, buyerSigner, LEGAL_BUYER, 0, DealStatus.DRAFT)
            val signatures = listOf(
                DealSignatures.sign(supplierKeys.signingHandle(), deal, supplierId, SignAction.SIGN),
                DealSignatures.sign(buyerKeys.signingHandle(), deal, buyerId, SignAction.SIGN),
            )
            val sealed = DealCipher.encrypt(
                dealId = deal.dealId,
                versionId = deal.versionId,
                canonical = deal.canonicalBytes(),
                recipients = listOf(
                    PartyWrapTarget(supplierId, supplierKeys.encryptionPublicHandle()),
                    PartyWrapTarget(buyerId, buyerKeys.encryptionPublicHandle()),
                ),
            )
            return Bound(
                deal = deal,
                supplierId = supplierId,
                buyerId = buyerId,
                supplierKeys = supplierKeys,
                buyerKeys = buyerKeys,
                supplierParticipant = supplierParticipant,
                buyerParticipant = buyerParticipant,
                supplierSigner = supplierSigner,
                buyerSigner = buyerSigner,
                supplierMessagingPublic = supplierPublic,
                buyerMessagingPublic = buyerPublic,
                signatures = signatures,
                sealed = sealed,
                bothSignatures = bothSignatures,
            )
        }

        private val SCHEMA_V3 = listOf(
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
            "CREATE TABLE IF NOT EXISTS `deal_drafts` (`draftId` TEXT NOT NULL, `dealId` TEXT NOT NULL, `versionId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `contactId` TEXT NOT NULL, `status` TEXT NOT NULL, `sealedPayload` BLOB NOT NULL, `sealedPartyASigning` BLOB NOT NULL, `sealedPartyAEncryption` BLOB NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`draftId`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_deal_drafts_dealId` ON `deal_drafts` (`dealId`)",
            "CREATE INDEX IF NOT EXISTS `index_deal_drafts_conversationId` ON `deal_drafts` (`conversationId`)",
            "CREATE TABLE IF NOT EXISTS `deal_transport` (`envelopeId` TEXT NOT NULL, `dealId` TEXT NOT NULL, `versionId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `direction` TEXT NOT NULL, `eventType` TEXT NOT NULL, `status` TEXT NOT NULL, `sealedEnvelope` BLOB NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`envelopeId`))",
            "CREATE INDEX IF NOT EXISTS `index_deal_transport_dealId` ON `deal_transport` (`dealId`)",
            "CREATE INDEX IF NOT EXISTS `index_deal_transport_conversationId` ON `deal_transport` (`conversationId`)",
        )
    }
}
