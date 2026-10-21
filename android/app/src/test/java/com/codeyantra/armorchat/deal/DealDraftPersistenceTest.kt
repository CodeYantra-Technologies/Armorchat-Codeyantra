package com.codeyantra.armorchat.deal

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.codeyantra.armorchat.core.security.TinkEd25519IdentityKeyStore
import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealKeyProtector
import com.codeyantra.armorchat.deal.crypto.DealTink
import com.codeyantra.armorchat.deal.domain.AgreementDetails
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.domain.DealFinality
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.DealKeyException
import com.codeyantra.armorchat.deal.domain.DealMessagingSigner
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.FinalityDefect
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.sha256
import com.codeyantra.armorchat.deal.draft.DealDraftEditor
import com.codeyantra.armorchat.deal.draft.DealDraftEnvelope
import com.codeyantra.armorchat.deal.draft.DealDraftStore
import com.codeyantra.armorchat.deal.draft.DealDrafts
import com.codeyantra.armorchat.deal.draft.OpenedLocalDraft
import com.codeyantra.armorchat.deal.origin.ChatDealFactory
import com.codeyantra.armorchat.deal.origin.ChatDealIds
import com.codeyantra.armorchat.deal.storage.DealClosureRow
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealRepository
import com.codeyantra.armorchat.deal.storage.DealRow
import com.codeyantra.armorchat.deal.storage.DealTransportRow
import com.codeyantra.armorchat.deal.testing.SoftwareDealKeyProtector
import com.codeyantra.armorchat.deal.testing.signedFixture
import com.codeyantra.armorchat.deal.testing.toHex
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import java.util.UUID
import org.junit.Assert.assertArrayEquals
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
class DealDraftPersistenceTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun chatOriginAssignsPartyAFromTheLocalIdentityAndPartyBFromTheContact() {
        val world = World()
        val database = memoryDb()
        try {
            val opened = world.store(database).createFromChat(
                local = world.local,
                conversation = world.conversation,
                contact = world.contact,
                editor = world.editor(legalName = ""),
                localDealKeys = world.keysA,
                localSigner = world.signer,
            )
            assertEquals(world.local.armorChatId, opened.origin.partyA.armorChatId)
            assertEquals(world.contact.armorChatId, opened.origin.partyB.armorChatId)
            assertArrayEquals(world.localKey, opened.origin.partyA.messagingPublicKey)
            assertArrayEquals(world.contactKey, opened.origin.partyB.messagingPublicKey)
            assertFalse(opened.origin.partyA.armorChatId == opened.origin.partyB.armorChatId)
            assertFalse(opened.partyASigningPublic.contentEquals(opened.origin.partyA.messagingPublicKey))
            assertFalse(opened.partyBBindingPresent)
            assertFalse(opened.partyBDealKeysPresent)
            assertFalse(opened.acceptedAgreement)
            assertFalse(opened.sent)
            assertEquals(DealDrafts.PUBLIC_LOCAL, opened.publicStatus)
            assertEquals("", opened.editor.partyALegalName)
            assertEquals(1, world.signer.signatures)
            assertEquals("Client", opened.editor.proposerRole)
            assertEquals("Provider", opened.editor.counterpartyRole)
            assertNull(database.dealDao().findDeal(opened.ids.dealId))
            assertEquals(0, database.dealDao().signatureCount(opened.ids.dealId))
            assertEquals(0, database.dealDao().versionCount(opened.ids.dealId))
        } finally {
            database.close()
        }
    }

    @Test
    fun wrongChatTypedPartyAndIdentitySubstitutionAreRejected() {
        val world = World()
        val database = memoryDb()
        try {
            val store = world.store(database)
            val wrongChat = world.conversation.copy(contactId = "someone-else")
            assertTrue(
                assertThrows(DealFormatException::class.java) {
                    store.createFromChat(
                        world.local, wrongChat, world.contact, world.editor(), world.keysA, world.signer,
                    )
                }.message!!.contains("contact in this conversation"),
            )
            assertTrue(
                assertThrows(DealFormatException::class.java) {
                    store.createFromChat(
                        world.local,
                        world.conversation,
                        world.contact,
                        world.editor(),
                        world.keysA,
                        world.signer,
                        typedPartyBId = world.contact.armorChatId,
                    )
                }.message!!.contains("typed Party B"),
            )
            val self = contactFor(world.localHandle, "self-contact", "Me")
            assertTrue(
                assertThrows(DealFormatException::class.java) {
                    store.createFromChat(
                        world.local,
                        world.conversation.copy(contactId = self.id),
                        self,
                        world.editor(),
                        world.keysA,
                        world.signer,
                    )
                }.message!!.contains("themself"),
            )
            assertThrows(DealFormatException::class.java) {
                store.createFromChat(
                    world.local, world.conversation, null, world.editor(), world.keysA, world.signer,
                )
            }
            val broken = world.contact.copy(publicIdentityKey = "!!!!")
            assertThrows(DealFormatException::class.java) {
                store.createFromChat(
                    world.local, world.conversation, broken, world.editor(), world.keysA, world.signer,
                )
            }
            assertEquals(0, database.dealDao().draftCount())
            assertEquals(0, world.signer.signatures)
            assertEquals(0, database.dealDao().auditCount())
        } finally {
            database.close()
        }
    }

    @Test
    fun termsAndLegalNamesStayOutOfRoomColumnsAndSurviveRestart() {
        val world = World()
        val name = "draft-restart-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val first = fileDb(name)
        val draftId = try {
            val opened = world.store(first).createFromChat(
                world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
            )
            val row = first.dealDao().findDraft(opened.draftId)!!
            val schema = tableSql(first, "deal_drafts")
            assertFalse(schema.contains("terms"))
            assertFalse(schema.contains("purpose"))
            assertFalse(schema.contains("legal"))
            assertFalse(schema.contains("amount"))
            assertFalse(schema.contains("title"))
            val columns = tableColumns(first, "deal_drafts")
            val haystack = buildString {
                append(columns.joinToString(" "))
                append(row.draftId)
                append(row.dealId)
                append(row.versionId)
                append(row.conversationId)
                append(row.contactId)
                append(row.status)
                append(String(row.sealedPayload, Charsets.ISO_8859_1))
                append(String(row.sealedPartyASigning, Charsets.ISO_8859_1))
                append(String(row.sealedPartyAEncryption, Charsets.ISO_8859_1))
            }
            assertFalse(haystack.contains(SECRET_TERMS))
            assertFalse(haystack.contains(SECRET_NAME))
            assertFalse(haystack.contains(SECRET_PURPOSE))
            assertFalse(row.sealedPayload.copyOfRange(0, 4).contentEquals(byteArrayOf(0x41, 0x44, 0x43, 0x42)))
            assertEquals(DealDrafts.STATUS_LOCAL, row.status)
            assertNotEquals("FULLY_SIGNED", row.status)
            opened.draftId
        } finally {
            first.close()
        }
        val second = fileDb(name)
        try {
            val restored = world.store(second).open(draftId)
            assertEquals(SECRET_TERMS, restored.editor.terms)
            assertEquals(SECRET_NAME, restored.editor.partyALegalName)
            assertEquals(SECRET_PURPOSE, restored.editor.purpose)
            assertEquals("2026-11-01", restored.editor.effectiveDate)
            assertEquals("INR", restored.editor.currency)
            assertEquals(150050L, restored.editor.amountMinor)
            assertEquals(world.local.armorChatId, restored.origin.partyA.armorChatId)
            assertEquals(world.contact.armorChatId, restored.origin.partyB.armorChatId)
            assertEquals(world.conversation.id, restored.origin.conversationId)
            assertEquals(world.contact.id, restored.origin.contactId)
            assertFalse(restored.acceptedAgreement)
            assertFalse(restored.partyBBindingPresent)
            val plain = world.protector.open(
                second.dealDao().findDraft(draftId)!!.sealedPayload,
                DealDrafts.PAYLOAD_PURPOSE,
            )
            try {
                assertThrows(DealFormatException::class.java) { CanonicalEncoding.parse(plain) }
                val envelope = DealDraftEnvelope.parse(plain)
                assertEquals(SECRET_PURPOSE, envelope.purpose)
                assertEquals("Service agreement", envelope.pending.details.title)
                assertEquals("Pay on acceptance of the work.", envelope.pending.details.paymentConditions)
            } finally {
                plain.fill(0)
            }
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun anEditKeepsTheIdentityBindingAndWritesNoSignatureHistory() {
        val world = World()
        val database = memoryDb()
        try {
            val store = world.store(database)
            val created = store.createFromChat(
                world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
            )
            val before = database.dealDao().findDraft(created.draftId)!!
            val edited = created.editor.let {
                DealDraftEditor(
                    title = "Revised title",
                    purpose = "A revised purpose for the same people.",
                    proposerRole = "Buyer",
                    counterpartyRole = "Seller",
                    terms = "Revised obligations that stay unsigned.",
                    paymentConditions = "Revised payment note.",
                    currency = "USD",
                    amountMinor = 2500L,
                    deadlineDate = "2027-01-15",
                    effectiveDate = "2026-12-01",
                    partyALegalName = "Neeraj Sharma",
                )
            }
            world.now += 5_000
            val updated = store.update(created.draftId, edited, world.local, world.conversation, world.contact)
            val after = database.dealDao().findDraft(created.draftId)!!
            assertEquals(created.draftId, updated.draftId)
            assertEquals(created.ids.dealId, updated.ids.dealId)
            assertEquals(created.ids.versionId, updated.ids.versionId)
            assertEquals(before.conversationId, after.conversationId)
            assertEquals(before.contactId, after.contactId)
            assertEquals(before.createdAtEpochMs, after.createdAtEpochMs)
            assertTrue(after.updatedAtEpochMs > before.updatedAtEpochMs)
            assertEquals(created.origin.partyA.armorChatId, updated.origin.partyA.armorChatId)
            assertEquals(created.origin.partyB.armorChatId, updated.origin.partyB.armorChatId)
            assertArrayEquals(created.partyABinding, updated.partyABinding)
            assertArrayEquals(created.partyASigningPublic, updated.partyASigningPublic)
            assertArrayEquals(created.partyAEncryptionPublic, updated.partyAEncryptionPublic)
            assertEquals("Revised obligations that stay unsigned.", updated.editor.terms)
            assertEquals("Neeraj Sharma", updated.editor.partyALegalName)
            assertEquals(1, world.signer.signatures)
            assertEquals(0, database.dealDao().signatureCount(created.ids.dealId))
            assertEquals(0, database.dealDao().versionCount(created.ids.dealId))
            assertNull(database.dealDao().findDeal(created.ids.dealId))
            assertEquals(0, database.dealDao().auditCount())
            assertEquals(1, database.dealDao().draftCount())
            DealIdentityCrypto.verify(
                updated.origin.partyA.messagingPublicKey,
                updated.partyABinding,
                DealIdentity.bindingPayload(
                    updated.ids.dealId,
                    PartyRole.PROPOSER,
                    updated.origin.partyA,
                    updated.partyASigningPublic,
                    updated.partyAEncryptionPublic,
                ),
            )
            val replacement = messagingIdentity("Replacement")
            val changed = world.contact.copy(
                armorChatId = replacement.armorChatId,
                publicIdentityKey = replacement.publicIdentityKey,
            )
            val payloadBefore = after.sealedPayload.copyOf()
            assertThrows(DealFormatException::class.java) {
                store.update(created.draftId, edited, world.local, world.conversation, changed)
            }
            assertArrayEquals(payloadBefore, database.dealDao().findDraft(created.draftId)!!.sealedPayload)
        } finally {
            database.close()
        }
    }

    @Test
    fun aFailedSaveLeavesThePreviousDraftIntact() {
        val world = World()
        val gate = GateProtector(world.protector)
        val database = memoryDb()
        try {
            val store = world.store(database, gate)
            val created = store.persistBeforeLeave(
                world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
            )
            val sealedBefore = database.dealDao().findDraft(created.draftId)!!.sealedPayload.copyOf()
            val signingBefore = database.dealDao().findDraft(created.draftId)!!.sealedPartyASigning.copyOf()
            gate.failSeal = true
            assertThrows(DealKeyException.RevokedDeviceAccess::class.java) {
                store.persistBeforeLeave(
                    world.local,
                    world.conversation,
                    world.contact,
                    world.secretEditor().copyTerms("This interrupted edit must not land."),
                    world.keysA,
                    world.signer,
                )
            }
            val row = database.dealDao().findDraft(created.draftId)!!
            assertArrayEquals(sealedBefore, row.sealedPayload)
            assertArrayEquals(signingBefore, row.sealedPartyASigning)
            assertEquals(SECRET_TERMS, store.open(created.draftId).editor.terms)
            assertEquals(DealDrafts.STATUS_LOCAL, row.status)
            gate.failSeal = false
            val tampered = row.sealedPayload.copyOf()
            tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()
            database.openHelper.writableDatabase.execSQL(
                "UPDATE deal_drafts SET sealedPayload = ? WHERE draftId = ?",
                arrayOf(tampered, created.draftId),
            )
            assertThrows(Exception::class.java) { store.open(created.draftId) }
            assertArrayEquals(tampered, database.dealDao().findDraft(created.draftId)!!.sealedPayload)
        } finally {
            database.close()
        }
    }

    @Test
    fun cancellingADraftDoesNotWriteSignatureHistory() {
        val world = World()
        val database = memoryDb()
        try {
            val store = world.store(database)
            val created = store.createFromChat(
                world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
            )
            val sealedBefore = database.dealDao().findDraft(created.draftId)!!.sealedPayload.copyOf()
            val cancelled = store.cancel(created.draftId)
            val row = database.dealDao().findDraft(created.draftId)!!
            assertEquals(DealDrafts.STATUS_CANCELLED, cancelled.status)
            assertEquals(DealDrafts.PUBLIC_CANCELLED, cancelled.publicStatus)
            assertFalse(cancelled.acceptedAgreement)
            assertArrayEquals(sealedBefore, row.sealedPayload)
            assertEquals(0, database.dealDao().signatureCount(created.ids.dealId))
            assertEquals(0, database.dealDao().versionCount(created.ids.dealId))
            assertEquals(0, database.dealDao().auditCount())
            assertThrows(DealStateException::class.java) {
                store.update(created.draftId, world.secretEditor(), world.local, world.conversation, world.contact)
            }
            assertArrayEquals(sealedBefore, database.dealDao().findDraft(created.draftId)!!.sealedPayload)
            val again = store.createFromChat(
                world.local, world.conversation, world.contact, world.editor(legalName = ""), world.keysA, world.signer,
            )
            assertNotEquals(created.ids.dealId, again.ids.dealId)
            assertEquals(DealDrafts.STATUS_LOCAL, again.status)
            assertEquals(2, database.dealDao().draftCount())
        } finally {
            database.close()
        }
    }

    @Test
    fun revalidateBeforeSendDoesNotTransmitOrAccept() {
        val world = World()
        val database = memoryDb()
        try {
            val store = world.store(database)
            val created = store.createFromChat(
                world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
            )
            val check = store.revalidateBeforeSend(created.draftId, world.local, world.conversation, world.contact)
            assertFalse(check.transmitted)
            assertFalse(check.acceptedAgreement)
            assertTrue(check.identitiesMatch)
            assertEquals(0, database.dealDao().signatureCount(created.ids.dealId))
            assertNull(database.dealDao().findDeal(created.ids.dealId))
            val moved = world.local.copy(
                armorChatId = messagingIdentity("Moved").armorChatId,
                publicIdentityKey = messagingIdentity("Moved two").publicIdentityKey,
            )
            assertThrows(DealFormatException::class.java) {
                store.revalidateBeforeSend(created.draftId, moved, world.conversation, world.contact)
            }
            assertEquals(DealDrafts.STATUS_LOCAL, database.dealDao().findDraft(created.draftId)!!.status)
            assertEquals(1, world.signer.signatures)
        } finally {
            database.close()
        }
    }

    @Test
    fun aDraftCannotReplaceASignedOrFinalizedAgreement() {
        val world = World()
        val database = memoryDb()
        try {
            val fixture = signedFixture(terms = "Signed terms stay sealed")
            val repository = DealRepository(database, clock = { 1_700_000_000_000L })
            repository.saveVerified(
                fixture.deal,
                fixture.sealed,
                fixture.signatures,
                fixture.partyA,
                fixture.keysA,
                world.protector,
                "FULLY_SIGNED",
            )
            val sealedBefore = database.dealDao().sealedBytes(fixture.deal.dealId, fixture.deal.versionId)!!.copyOf()
            val hashBefore = database.dealDao().findVersion(fixture.deal.dealId, fixture.deal.versionId)!!.documentSha256
            val signaturesBefore = signatureBytes(database, fixture.deal.dealId, fixture.deal.versionId)
            val store = world.store(database)
            val collision = ChatDealIds(
                dealId = fixture.deal.dealId,
                versionId = "w".repeat(22),
                proposerPartyId = "p".repeat(22),
                counterpartyPartyId = "q".repeat(22),
            )
            val refused = assertThrows(DealStateException::class.java) {
                store.createFromChat(
                    world.local,
                    world.conversation,
                    world.contact,
                    world.secretEditor(),
                    world.keysA,
                    world.signer,
                    ids = collision,
                )
            }
            assertTrue(refused.message!!.contains("cannot replace"))
            assertEquals("FULLY_SIGNED", database.dealDao().findDeal(fixture.deal.dealId)!!.status)
            assertEquals(hashBefore, database.dealDao().findVersion(fixture.deal.dealId, fixture.deal.versionId)!!.documentSha256)
            assertArrayEquals(sealedBefore, database.dealDao().sealedBytes(fixture.deal.dealId, fixture.deal.versionId))
            assertTrue(sameSignatures(signaturesBefore, signatureBytes(database, fixture.deal.dealId, fixture.deal.versionId)))
            assertEquals(0, world.signer.signatures)
            assertNull(database.dealDao().findDraftByDealId(fixture.deal.dealId))
            val verdict = repository.assessStored(fixture.deal.dealId, fixture.partyA, world.protector)
            assertTrue(verdict.proven)
            assertTrue(
                DealFinality.assess(
                    fixture.deal,
                    fixture.signatures,
                    fixture.deal.parties.associate { it.partyId to it.signingPublicKey },
                ).proven,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun phaseOneFinalityGuardsStillFailClosedWhenADraftIsRefused() {
        val world = World()
        val database = memoryDb()
        try {
            val fixture = signedFixture(terms = "One signature is not final")
            val one = fixture.signatures.filter { it.partyId == fixture.partyA }
            val repository = DealRepository(database)
            repository.saveVerified(
                fixture.deal, fixture.sealed, one, fixture.partyA, fixture.keysA, world.protector, "PARTIALLY_SIGNED",
            )
            database.dealDao().updateDeal(fixture.deal.dealId, "FULLY_SIGNED", fixture.deal.versionId, 5)
            val sealedBefore = repository.sealedDocument(fixture.deal.dealId, fixture.deal.versionId)!!.copyOf()
            val verdict = repository.assessStored(fixture.deal.dealId, fixture.partyA, world.protector)
            assertFalse(verdict.proven)
            assertEquals(FinalityDefect.MISSING_SIGNATURE, verdict.defect)
            assertThrows(DealStateException::class.java) {
                world.store(database).createFromChat(
                    world.local,
                    world.conversation,
                    world.contact,
                    world.secretEditor(),
                    world.keysA,
                    world.signer,
                    ids = ChatDealIds(
                        fixture.deal.dealId,
                        "w".repeat(22),
                        "p".repeat(22),
                        "q".repeat(22),
                    ),
                )
            }
            assertEquals("FULLY_SIGNED", database.dealDao().findDeal(fixture.deal.dealId)!!.status)
            assertArrayEquals(sealedBefore, repository.sealedDocument(fixture.deal.dealId, fixture.deal.versionId))
            assertEquals(1, database.dealDao().signatureCount(fixture.deal.dealId))
            val again = repository.assessStored(fixture.deal.dealId, fixture.partyA, world.protector)
            assertFalse(again.proven)
            assertEquals(FinalityDefect.MISSING_SIGNATURE, again.defect)
        } finally {
            database.close()
        }
    }

    @Test
    fun canonicalV1V2AndV3StillRoundTripAndDraftsAreNotCanonical() {
        assertFalse(DealFeature.PRODUCTION_UI_ENABLED)
        assertFalse(ChatDealFactory.enabled(debugBuild = false))
        assertFalse(ChatDealFactory.enabled(debugBuild = true, productionUiEnabled = true))
        val bytes = v1Deal().canonicalBytes()
        assertEquals(V1_HASH, sha256(bytes).toHex())
        assertArrayEquals(bytes, CanonicalEncoding.parse(bytes).canonicalBytes())
        assertEquals(1, bytes[4].toInt())

        val versionTwo = CanonicalDeal.create(
            dealId = "d".repeat(22),
            versionId = "v".repeat(22),
            parentVersionId = null,
            createdAtEpochMs = CREATED,
            currency = "INR",
            amountMinor = 1050,
            parties = listOf(
                com.codeyantra.armorchat.deal.domain.DealParty(
                    "a".repeat(22), PartyRole.PROPOSER, "A", ByteArray(32) { 1 }, ByteArray(32) { 2 },
                ),
                com.codeyantra.armorchat.deal.domain.DealParty(
                    "b".repeat(22), PartyRole.COUNTERPARTY, "B", ByteArray(32) { 3 }, ByteArray(32) { 4 },
                ),
            ),
            ancestry = emptyList(),
            terms = "Version two",
            details = AgreementDetails("Title", "2026-12-31", "", "Client", "Studio"),
        )
        assertEquals(2, versionTwo.canonicalVersion)
        assertNull(versionTwo.identity)
        assertArrayEquals(versionTwo.canonicalBytes(), CanonicalEncoding.parse(versionTwo.canonicalBytes()).canonicalBytes())

        val world = World()
        val pending = ChatDealFactory.create(
            debugBuild = true,
            local = world.local,
            conversation = world.conversation,
            contact = world.contact,
            claimedPartyBId = null,
            ids = world.ids,
            createdAtEpochMs = CREATED,
            localDealKeys = world.keysA,
            localSigner = world.signer,
            terms = "Version three",
            details = world.editor(legalName = "").details,
        )
        val counterpartyKeys = DealKeyMaterial.generate()
        val binding = DealIdentityCrypto.sign(
            world.contactHandle,
            DealIdentity.bindingPayload(
                world.ids.dealId,
                PartyRole.COUNTERPARTY,
                pending.origin.partyB,
                counterpartyKeys.signingPublic,
                counterpartyKeys.encryptionPublic,
            ),
        )
        val ready = ChatDealFactory.acceptCounterparty(
            debugBuild = true,
            pending = pending,
            counterpartySigningPublic = counterpartyKeys.signingPublic,
            counterpartyEncryptionPublic = counterpartyKeys.encryptionPublic,
            counterpartyBinding = binding,
        )
        val modern = ready.deal.canonicalBytes()
        assertEquals(3, modern[4].toInt())
        assertArrayEquals(modern, CanonicalEncoding.parse(modern).canonicalBytes())
        assertEquals(world.local.armorChatId, ready.deal.identity!!.pins.single { it.role == PartyRole.PROPOSER }.participant.armorChatId)

        val envelope = DealDraftEnvelope(pending, SECRET_PURPOSE, "2026-11-01", SECRET_NAME)
        val plain = DealDraftEnvelope.encode(envelope)
        assertEquals(0x41, plain[0].toInt())
        assertEquals(0x44, plain[1].toInt())
        assertEquals(0x44, plain[2].toInt())
        assertEquals(0x46, plain[3].toInt())
        assertThrows(DealFormatException::class.java) { CanonicalEncoding.parse(plain) }
        val parsed = DealDraftEnvelope.parse(plain)
        assertEquals(SECRET_PURPOSE, parsed.purpose)
        assertEquals(SECRET_NAME, parsed.partyALegalName)
        assertEquals(1, world.signer.signatures)
        val trailed = plain.copyOf(plain.size + 1)
        assertThrows(DealFormatException::class.java) { DealDraftEnvelope.parse(trailed) }

        val legal = DealIdentity.legalNamePayload(
            world.ids.dealId,
            PartyRole.PROPOSER,
            pending.origin.partyA,
            "Neeraj Sharma",
        )
        assertEquals(DealIdentity.BINDING_DOMAIN + "\u0000", TinkEd25519IdentityKeyStore.DEAL_BINDING_PREFIX)
        TinkEd25519IdentityKeyStore.requireDealIdentityBinding(pending.proposerBinding.let {
            DealIdentity.bindingPayload(
                world.ids.dealId,
                PartyRole.PROPOSER,
                pending.origin.partyA,
                pending.proposerSigningPublic,
                pending.proposerEncryptionPublic,
            )
        })
        // Phase 5.3H: legal-name statements are an allowed messaging-identity domain.
        TinkEd25519IdentityKeyStore.requireDealIdentityBinding(legal)
        assertThrows(IllegalArgumentException::class.java) {
            TinkEd25519IdentityKeyStore.requireDealIdentityBinding(
                "ArmorChat/device/v1\u0000".toByteArray(Charsets.US_ASCII) + legal,
            )
        }
        val fixture = signedFixture(terms = "Finality still requires both signatures")
        assertTrue(
            DealFinality.assess(
                fixture.deal,
                fixture.signatures,
                fixture.deal.parties.associate { it.partyId to it.signingPublicKey },
            ).proven,
        )
    }

    @Test
    fun releaseAndProductionGatesWriteNothing() {
        val world = World()
        val database = memoryDb()
        try {
            val disabled = DealDraftStore(database, world.protector, clock = { world.now }, debugBuild = false)
            assertThrows(DealStateException::class.java) {
                disabled.createFromChat(
                    world.local, world.conversation, world.contact, world.editor(), world.keysA, world.signer,
                )
            }
            val production = DealDraftStore(
                database,
                world.protector,
                clock = { world.now },
                debugBuild = true,
                productionUiEnabled = true,
            )
            assertThrows(DealStateException::class.java) {
                production.createFromChat(
                    world.local, world.conversation, world.contact, world.editor(), world.keysA, world.signer,
                )
            }
            assertEquals(0, database.dealDao().draftCount())
            assertEquals(0, world.signer.signatures)
        } finally {
            database.close()
        }
    }

    @Test
    fun anUnsentDraftIsResumedInsteadOfReplaced() {
        val name = "draft-unsent-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val database = fileDb(name)
        val world = World()
        try {
            val store = world.store(database)
            val first = store.createFromChat(
                world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
            )
            val payload = database.dealDao().findDraft(first.draftId)!!.sealedPayload.copyOf()
            val blocked = assertThrows(DealStateException::class.java) {
                store.createFromChat(
                    world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
                )
            }
            assertTrue(blocked.message!!.contains("already has a local draft"))
            assertArrayEquals(payload, database.dealDao().findDraft(first.draftId)!!.sealedPayload)
            assertEquals(DealDrafts.STATUS_LOCAL, database.dealDao().findDraft(first.draftId)!!.status)
            assertEquals(first.draftId, store.unsent(world.conversation.id)!!.draftId)
            assertEquals(first.ids.dealId, store.active(world.conversation.id)!!.ids.dealId)

            val resumed = store.update(
                first.draftId,
                resumedEditor(),
                world.local,
                world.conversation,
                world.contact,
            )
            assertEquals(first.ids.dealId, resumed.ids.dealId)
            assertEquals(first.draftId, resumed.draftId)
            assertEquals("Resumed agreement", resumed.editor.title)
            assertEquals(SECRET_TERMS, resumed.editor.terms)

            database.dealDao().insertDeal(
                DealRow("z".repeat(22), "FULLY_SIGNED", "y".repeat(22), 10L, 11L),
            )
            database.dealDao().insertClosure(
                DealClosureRow(
                    dealId = "z".repeat(22),
                    versionId = "y".repeat(22),
                    partyId = "p".repeat(22),
                    kind = "GOODS_RECEIVED",
                    signature = byteArrayOf(1),
                    signerPublicKey = "signer",
                    documentSha256 = "hash",
                    createdAtEpochMs = 12L,
                ),
            )
            val stillBlocked = assertThrows(DealStateException::class.java) {
                store.createFromChat(
                    world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
                )
            }
            assertTrue(stillBlocked.message!!.contains("already has a local draft"))
            assertEquals(1, database.dealDao().draftCount())
            database.close()

            val reopened = fileDb(name)
            try {
                val again = world.store(reopened)
                val opened = again.unsent(world.conversation.id)
                assertEquals(first.draftId, opened!!.draftId)
                assertEquals(first.ids.dealId, opened.ids.dealId)
                assertEquals("Resumed agreement", opened.editor.title)
                assertEquals(SECRET_TERMS, opened.editor.terms)
                assertThrows(DealStateException::class.java) {
                    again.createFromChat(
                        world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
                    )
                }
                assertEquals(DealDrafts.STATUS_LOCAL, reopened.dealDao().findDraft(first.draftId)!!.status)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun aTransportRecordForThatDealAllowsASecondDraft() {
        val name = "draft-transport-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val database = fileDb(name)
        val world = World()
        try {
            val store = world.store(database)
            val first = store.createFromChat(
                world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
            )
            val payload = database.dealDao().findDraft(first.draftId)!!.sealedPayload.copyOf()
            database.dealDao().insertTransport(
                DealTransportRow(
                    envelopeId = "t".repeat(22),
                    dealId = first.ids.dealId,
                    versionId = first.ids.versionId,
                    conversationId = world.conversation.id,
                    direction = "OUT",
                    eventType = "PROPOSAL",
                    status = "QUEUED",
                    sealedEnvelope = byteArrayOf(9, 9, 9),
                    createdAtEpochMs = 20L,
                    updatedAtEpochMs = 21L,
                ),
            )
            assertEquals(listOf(first.ids.dealId), store.pendingSentDealIds(world.conversation.id))
            assertNull(store.unsent(world.conversation.id))
            val second = store.createFromChat(
                world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
            )
            assertNotEquals(first.ids.dealId, second.ids.dealId)
            assertNotEquals(first.draftId, second.draftId)
            assertArrayEquals(payload, database.dealDao().findDraft(first.draftId)!!.sealedPayload)
            assertEquals(DealDrafts.STATUS_LOCAL, database.dealDao().findDraft(first.draftId)!!.status)
            assertNull(database.dealDao().findDeal(first.ids.dealId))
            val replaced = assertThrows(DealStateException::class.java) {
                store.update(first.draftId, resumedEditor(), world.local, world.conversation, world.contact)
            }
            assertTrue(replaced.message!!.contains("cannot be replaced"))
            assertEquals(second.draftId, store.unsent(world.conversation.id)!!.draftId)
            database.close()

            val reopened = fileDb(name)
            try {
                val again = world.store(reopened)
                assertEquals(2, reopened.dealDao().draftCount())
                assertEquals(second.ids.dealId, again.unsent(world.conversation.id)!!.ids.dealId)
                assertArrayEquals(payload, reopened.dealDao().findDraft(first.draftId)!!.sealedPayload)
                assertEquals(listOf(first.ids.dealId), again.pendingSentDealIds(world.conversation.id))
                assertEquals(1, reopened.dealDao().transportCountForDeal(first.ids.dealId))
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun aStoredRowForThatDealAllowsASecondDraftWithoutChangingTheRow() {
        val world = World()
        val database = memoryDb()
        try {
            val store = world.store(database)
            val first = store.createFromChat(
                world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
            )
            val payload = database.dealDao().findDraft(first.draftId)!!.sealedPayload.copyOf()
            database.dealDao().insertDeal(
                DealRow(first.ids.dealId, "FULLY_SIGNED", first.ids.versionId, 30L, 31L),
            )
            assertTrue(store.pendingSentDealIds(world.conversation.id).isEmpty())
            assertNull(store.unsent(world.conversation.id))
            val second = store.createFromChat(
                world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
            )
            assertNotEquals(first.ids.dealId, second.ids.dealId)
            assertArrayEquals(payload, database.dealDao().findDraft(first.draftId)!!.sealedPayload)
            assertEquals(DealDrafts.STATUS_LOCAL, database.dealDao().findDraft(first.draftId)!!.status)
            assertEquals("FULLY_SIGNED", database.dealDao().findDeal(first.ids.dealId)!!.status)
            assertEquals(31L, database.dealDao().findDeal(first.ids.dealId)!!.updatedAtEpochMs)
            val replaced = assertThrows(DealStateException::class.java) {
                store.update(first.draftId, resumedEditor(), world.local, world.conversation, world.contact)
            }
            assertTrue(replaced.message!!.contains("cannot be replaced"))
            assertEquals(second.draftId, store.unsent(world.conversation.id)!!.draftId)
        } finally {
            database.close()
        }
    }

    @Test
    fun schemaUpgradeAddsDraftsWithoutRewritingSignedRows() {
        val name = "draft-migrate-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val dealId = "d".repeat(22)
        val versionId = "v".repeat(22)
        val partyId = "a".repeat(22)
        val sealed = byteArrayOf(9, 8, 7, 6, 5)
        val signature = byteArrayOf(3, 3, 3, 3)
        val keyBlob = byteArrayOf(2, 2, 2)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            SCHEMA_V1.forEach(old::execSQL)
            old.execSQL(
                "INSERT INTO deals (dealId, status, currentVersionId, createdAtEpochMs, updatedAtEpochMs) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(dealId, "FULLY_SIGNED", versionId, 10L, 11L),
            )
            old.execSQL(
                "INSERT INTO deal_parties (dealId, partyId, role, label, signingPublicKey, encryptionPublicKey) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(dealId, partyId, "PROPOSER", "A", "signing-key", "encryption-key"),
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
                "INSERT INTO deal_local_keys (partyId, sealedSigningKeyset, sealedEncryptionKeyset, signingPublicKey, encryptionPublicKey) VALUES (?, ?, ?, ?, ?)",
                arrayOf(partyId, keyBlob, keyBlob, "signing-key", "encryption-key"),
            )
            old.execSQL(
                "INSERT INTO deal_audit_events (eventId, dealId, action, outcome, createdAtEpochMs) VALUES ('event-1', ?, 'local_save', 'ok', 12)",
                arrayOf(dealId),
            )
            old.version = 1
        }

        val upgraded = fileDb(name)
        try {
            assertEquals("FULLY_SIGNED", upgraded.dealDao().findDeal(dealId)!!.status)
            assertEquals(versionId, upgraded.dealDao().findDeal(dealId)!!.currentVersionId)
            assertArrayEquals(sealed, upgraded.dealDao().sealedBytes(dealId, versionId))
            assertTrue(upgraded.dealDao().findVersion(dealId, versionId)!!.immutable)
            assertArrayEquals(signature, upgraded.dealDao().signatures(dealId, versionId).single().signature)
            assertEquals("A", upgraded.dealDao().findParty(dealId, partyId)!!.label)
            assertArrayEquals(keyBlob, upgraded.dealDao().findKeys(partyId)!!.sealedSigningKeyset)
            assertEquals(1, upgraded.dealDao().auditCount())
            assertEquals(0, upgraded.dealDao().draftCount())
            assertTrue(tableSql(upgraded, "deal_drafts").contains("sealedPayload"))
            assertFalse(tableSql(upgraded, "deals").contains("sealedPayload"))

            val world = World()
            val opened = world.store(upgraded).createFromChat(
                world.local, world.conversation, world.contact, world.secretEditor(), world.keysA, world.signer,
            )
            assertNotEquals(dealId, opened.ids.dealId)
            assertEquals("FULLY_SIGNED", upgraded.dealDao().findDeal(dealId)!!.status)
            assertArrayEquals(sealed, upgraded.dealDao().sealedBytes(dealId, versionId))
            assertArrayEquals(signature, upgraded.dealDao().signatures(dealId, versionId).single().signature)
            assertEquals(1, upgraded.dealDao().auditCount())
            assertEquals(1, upgraded.dealDao().draftCount())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    private fun resumedEditor() = DealDraftEditor(
        title = "Resumed agreement",
        purpose = SECRET_PURPOSE,
        proposerRole = "Client",
        counterpartyRole = "Provider",
        terms = SECRET_TERMS,
        paymentConditions = "Pay on acceptance of the work.",
        currency = "INR",
        amountMinor = 150050L,
        deadlineDate = "2026-12-31",
        effectiveDate = "2026-11-01",
        partyALegalName = SECRET_NAME,
    )

    private fun memoryDb(): DealDatabase =
        Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()

    private fun fileDb(name: String): DealDatabase =
        DealDatabase.builder(context, name).allowMainThreadQueries().build()

    private fun tableSql(database: DealDatabase, table: String): String {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT sql FROM sqlite_master WHERE name = ?",
            arrayOf(table),
        )
        cursor.use {
            assertTrue(it.moveToNext())
            return it.getString(0)
        }
    }

    private fun tableColumns(database: DealDatabase, table: String): List<String> {
        val cursor = database.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)")
        cursor.use {
            val names = ArrayList<String>()
            while (it.moveToNext()) names += it.getString(1)
            return names
        }
    }

    private fun signatureBytes(database: DealDatabase, dealId: String, versionId: String): List<ByteArray> =
        database.dealDao().signatures(dealId, versionId).sortedBy { it.partyId }.map { it.signature.copyOf() }

    private fun sameSignatures(left: List<ByteArray>, right: List<ByteArray>): Boolean {
        if (left.size != right.size) return false
        return left.indices.all { left[it].contentEquals(right[it]) }
    }

    private class World {
        var now: Long = CREATED
        val protector = SoftwareDealKeyProtector(ByteArray(32) { 4 })
        val localHandle: KeysetHandle = messaging()
        val contactHandle: KeysetHandle = messaging()
        val localKey: ByteArray = DealIdentityCrypto.publicKey(localHandle)
        val contactKey: ByteArray = DealIdentityCrypto.publicKey(contactHandle)
        val local: UserIdentity = UserIdentity(
            armorChatId = ArmorChatId.derive(localKey),
            publicIdentityKey = Base64Url.encode(localKey),
            createdAtEpochMs = CREATED,
            displayName = "Neeraj",
        )
        val contact: Contact = contactFor(contactHandle, "contact-1", "Recipient")
        val conversation: Conversation = Conversation("conversation-1", contact.id, null, CREATED)
        val keysA: DealKeyMaterial = DealKeyMaterial.generate()
        val signer: CountingSigner = CountingSigner(localHandle)
        val ids: ChatDealIds = ChatDealIds(
            dealId = "e".repeat(22),
            versionId = "f".repeat(22),
            proposerPartyId = "g".repeat(22),
            counterpartyPartyId = "h".repeat(22),
        )

        fun store(database: DealDatabase, protector: DealKeyProtector = this.protector): DealDraftStore =
            DealDraftStore(database, protector, clock = { now }, debugBuild = true)

        fun editor(legalName: String = ""): DealDraftEditor = DealDraftEditor(
            title = "Service agreement",
            purpose = "State the work before either party signs.",
            proposerRole = "Client",
            counterpartyRole = "Provider",
            terms = "Shared obligations.",
            paymentConditions = "",
            currency = null,
            amountMinor = null,
            deadlineDate = "2026-12-31",
            effectiveDate = "",
            partyALegalName = legalName,
        )

        fun secretEditor(): DealDraftEditor = DealDraftEditor(
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
            partyALegalName = SECRET_NAME,
        )
    }

    private class GateProtector(private val inner: DealKeyProtector) : DealKeyProtector {
        var failSeal: Boolean = false

        override fun seal(plaintext: ByteArray, purpose: String): ByteArray {
            if (failSeal) throw DealKeyException.RevokedDeviceAccess()
            return inner.seal(plaintext, purpose)
        }

        override fun open(sealed: ByteArray, purpose: String): ByteArray = inner.open(sealed, purpose)
    }

    companion object {
        private const val CREATED = 1_700_000_000_000L
        private const val V1_HASH = "114207667dcdef469408d06d695c98d5b252947732e81b49a1867030914fee95"
        private const val SECRET_TERMS = "UNIQUE_TERMS_MARKER_9421_NOT_PLAINTEXT"
        private const val SECRET_NAME = "UNIQUE_LEGAL_NAME_MARKER_Neeraj"
        private const val SECRET_PURPOSE = "UNIQUE_PURPOSE_MARKER_keep_this_encrypted"

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
    }
}

private fun DealDraftEditor.copyTerms(terms: String): DealDraftEditor = DealDraftEditor(
    title = title,
    purpose = purpose,
    proposerRole = proposerRole,
    counterpartyRole = counterpartyRole,
    terms = terms,
    paymentConditions = paymentConditions,
    currency = currency,
    amountMinor = amountMinor,
    deadlineDate = deadlineDate,
    effectiveDate = effectiveDate,
    partyALegalName = partyALegalName,
)

private fun v1Deal(): CanonicalDeal = CanonicalDeal.create(
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

private fun messaging(): KeysetHandle {
    DealTink.register()
    return KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
}

private fun messagingIdentity(name: String): UserIdentity {
    val handle = messaging()
    val key = DealIdentityCrypto.publicKey(handle)
    return UserIdentity(
        armorChatId = ArmorChatId.derive(key),
        publicIdentityKey = Base64Url.encode(key),
        createdAtEpochMs = 1_700_000_000_000,
        displayName = name,
    )
}

private fun contactFor(handle: KeysetHandle, id: String, name: String): Contact {
    val key = DealIdentityCrypto.publicKey(handle)
    return Contact(
        id = id,
        armorChatId = ArmorChatId.derive(key),
        publicIdentityKey = Base64Url.encode(key),
        displayName = name,
        createdAtEpochMs = 1_700_000_000_000,
    )
}
