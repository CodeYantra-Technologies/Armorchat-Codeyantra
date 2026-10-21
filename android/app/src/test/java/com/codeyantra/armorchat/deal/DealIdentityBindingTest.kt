package com.codeyantra.armorchat.deal

import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.crypto.DealTink
import com.codeyantra.armorchat.deal.domain.AgreementDetails
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealFinality
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.DealMessagingSigner
import com.codeyantra.armorchat.deal.domain.DealParty
import com.codeyantra.armorchat.deal.domain.DealPartyDraft
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.DealVersions
import com.codeyantra.armorchat.deal.domain.ForeignPartyException
import com.codeyantra.armorchat.deal.domain.ImmutableVersionException
import com.codeyantra.armorchat.deal.domain.LegalNameStatus
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.domain.VerifiedDealParticipant
import com.codeyantra.armorchat.deal.domain.sha256
import com.codeyantra.armorchat.deal.origin.ChatDealFactory
import com.codeyantra.armorchat.deal.origin.ChatDealIds
import com.codeyantra.armorchat.deal.origin.ReadyChatDeal
import com.codeyantra.armorchat.deal.testing.signedFixture
import com.codeyantra.armorchat.deal.testing.toHex
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DealIdentityBindingTest {
    @Test
    fun partyAAndPartyBComeFromTheLocalIdentityAndTheChatContact() {
        val chat = ChatFixture()
        val ready = chat.ready()
        val deal = ready.deal
        assertEquals(DealFeature.CANONICAL_VERSION_V3, deal.canonicalVersion)
        assertEquals(1, chat.signer.signatures)
        val proposer = deal.identity!!.pins.single { it.role == PartyRole.PROPOSER }
        val counterparty = deal.identity!!.pins.single { it.role == PartyRole.COUNTERPARTY }
        assertEquals(ArmorChatId.derive(chat.localKey), proposer.participant.armorChatId)
        assertArrayEquals(chat.localKey, proposer.participant.messagingPublicKey)
        assertEquals(ArmorChatId.derive(chat.contactKey), counterparty.participant.armorChatId)
        assertArrayEquals(chat.contactKey, counterparty.participant.messagingPublicKey)
        assertArrayEquals(chat.keysA.signingPublic, proposer.dealSigningPublicKey)
        assertArrayEquals(chat.keysB.signingPublic, counterparty.dealSigningPublicKey)
        assertFalse(proposer.participant.messagingPublicKey.contentEquals(proposer.dealSigningPublicKey))
        assertFalse(counterparty.participant.messagingPublicKey.contentEquals(counterparty.dealSigningPublicKey))
        assertEquals("", proposer.legalName)
        assertEquals("", counterparty.legalName)
        assertEquals(LegalNameStatus.UNSET, proposer.nameStatus)
        val again = chat.pending(claimedPartyBId = chat.contact.armorChatId)
        assertEquals(chat.contact.armorChatId, again.origin.partyB.armorChatId)
        assertEquals(ArmorChatId.derive(chat.contactKey), again.origin.partyB.armorChatId)
    }

    @Test
    fun wrongChatRecipientAndTypedPartyBAreRejected() {
        val chat = ChatFixture()
        val other = messagingIdentity("Other")
        val wrongConversation = chat.conversation.copy(contactId = "someone-else")
        val error = assertThrows(DealFormatException::class.java) {
            chat.pending(conversation = wrongConversation)
        }
        assertTrue(error.message!!.contains("contact in this conversation"))
        val typed = assertThrows(DealFormatException::class.java) {
            chat.pending(claimedPartyBId = other.armorChatId)
        }
        assertTrue(typed.message!!.contains("typed Party B"))
        assertEquals(0, chat.signer.signatures)
    }

    @Test
    fun selfDealsMissingContactsAndInvalidKeysAreRejected() {
        val chat = ChatFixture()
        val selfContact = contactFor(chat.localHandle, "self-contact", "Me")
        val selfChat = chat.conversation.copy(contactId = selfContact.id)
        assertTrue(
            assertThrows(DealFormatException::class.java) {
                chat.pending(contact = selfContact, conversation = selfChat)
            }.message!!.contains("themself"),
        )
        assertTrue(
            assertThrows(DealFormatException::class.java) {
                chat.pending(contact = null)
            }.message!!.contains("missing"),
        )
        assertTrue(
            assertThrows(DealFormatException::class.java) {
                chat.pending(conversation = null)
            }.message!!.contains("missing"),
        )
        val broken = chat.contact.copy(publicIdentityKey = "!!!!")
        assertTrue(
            assertThrows(DealFormatException::class.java) {
                chat.pending(contact = broken)
            }.message!!.contains("not valid"),
        )
        val zeros = chat.contact.copy(
            publicIdentityKey = Base64Url.encode(ByteArray(32)),
            armorChatId = chat.contact.armorChatId,
        )
        assertTrue(
            assertThrows(DealFormatException::class.java) {
                chat.pending(contact = zeros)
            }.message!!.contains("not a valid Ed25519 point"),
        )
        val mismatched = chat.contact.copy(armorChatId = ArmorChatId.derive(ByteArray(32) { 3 }))
        assertThrows(DealFormatException::class.java) { chat.pending(contact = mismatched) }
        val overlap = assertThrows(DealFormatException::class.java) {
            DealIdentity.assemble(
                dealId = chat.ids.dealId,
                versionId = chat.ids.versionId,
                parentVersionId = null,
                createdAtEpochMs = CREATED,
                currency = null,
                amountMinor = null,
                terms = "Terms",
                details = chat.details,
                ancestry = emptyList(),
                drafts = listOf(
                    chat.draft(PartyRole.PROPOSER, chat.localParticipant, chat.localKey, ByteArray(32) { 9 }),
                    chat.draft(PartyRole.COUNTERPARTY, chat.contactParticipant, chat.keysB.signingPublic, chat.keysB.encryptionPublic),
                ),
            )
        }
        assertTrue(overlap.message!!.contains("separate"))
        assertEquals(0, chat.signer.signatures)
    }

    @Test
    fun changedIdentityIsRejectedAgainBeforeSending() {
        val chat = ChatFixture()
        val ready = chat.ready()
        ChatDealFactory.prepareToSend(
            debugBuild = true,
            ready = ready,
            local = chat.local,
            conversation = chat.conversation,
            contact = chat.contact,
        )
        val replacement = messagingIdentity("Replacement")
        val changed = chat.contact.copy(
            armorChatId = replacement.armorChatId,
            publicIdentityKey = replacement.publicIdentityKey,
        )
        val error = assertThrows(DealFormatException::class.java) {
            ChatDealFactory.prepareToSend(
                debugBuild = true,
                ready = ready,
                local = chat.local,
                conversation = chat.conversation,
                contact = changed,
            )
        }
        assertTrue(error.message!!.contains("changed"))
        val moved = chat.local.copy(
            armorChatId = replacement.armorChatId,
            publicIdentityKey = replacement.publicIdentityKey,
        )
        assertThrows(DealFormatException::class.java) {
            ChatDealFactory.revalidate(ready.origin, moved, chat.conversation, chat.contact)
        }
    }

    @Test
    fun forgedOrSubstitutedBindingsAreRejected() {
        val chat = ChatFixture()
        val ready = chat.ready()
        val bytes = ready.deal.canonicalBytes()
        val signature = ready.deal.identity!!.pins.first { it.role == PartyRole.PROPOSER }.bindingSignature!!
        val forged = bytes.copyOf()
        val at = indexOf(forged, signature)
        forged[at + signature.lastIndex] = (forged[at + signature.lastIndex].toInt() xor 0x01).toByte()
        assertThrows(DealCryptoException.InvalidSignature::class.java) { CanonicalEncoding.parse(forged) }

        val swapped = bytes.copyOf()
        val first = ready.deal.identity!!.pins[0].bindingSignature!!
        val second = ready.deal.identity!!.pins[1].bindingSignature!!
        first.copyInto(swapped, indexOf(swapped, second))
        second.copyInto(swapped, indexOf(bytes, first))
        assertThrows(DealCryptoException.InvalidSignature::class.java) { CanonicalEncoding.parse(swapped) }

        val other = messagingIdentity("Substitute")
        val substituted = String(bytes, Charsets.ISO_8859_1)
            .replace(chat.local.armorChatId, other.armorChatId)
            .toByteArray(Charsets.ISO_8859_1)
        assertThrows(DealFormatException::class.java) { CanonicalEncoding.parse(substituted) }

        val stolen = DealIdentityCrypto.sign(
            chat.localHandle,
            DealIdentity.bindingPayload(
                chat.ids.dealId,
                PartyRole.COUNTERPARTY,
                chat.contactParticipant,
                chat.keysB.signingPublic,
                chat.keysB.encryptionPublic,
            ),
        )
        assertThrows(DealCryptoException.InvalidSignature::class.java) {
            ChatDealFactory.acceptCounterparty(
                debugBuild = true,
                pending = chat.pending(),
                counterpartySigningPublic = chat.keysB.signingPublic,
                counterpartyEncryptionPublic = chat.keysB.encryptionPublic,
                counterpartyBinding = stolen,
            )
        }
        assertArrayEquals(bytes, ready.deal.canonicalBytes())
    }

    @Test
    fun partyACannotAuthorizePartyBOrUpgradeAnOlderDocument() {
        val chat = ChatFixture()
        val ready = chat.ready()
        val before = chat.signer.signatures
        val counterpartyId = ready.deal.parties.single { it.role == PartyRole.COUNTERPARTY }.partyId
        assertThrows(ForeignPartyException::class.java) {
            DealIdentity.authorizeOwnBinding(
                ready.deal,
                counterpartyId,
                chat.signer,
                signatureCount = 0,
                dealStatus = DealStatus.DRAFT,
            )
        }
        assertThrows(ForeignPartyException::class.java) {
            DealIdentity.confirmOwnLegalName(
                ready.deal,
                counterpartyId,
                chat.signer,
                "Grace Hopper",
                signatureCount = 0,
                dealStatus = DealStatus.DRAFT,
            )
        }
        assertEquals(before, chat.signer.signatures)
        assertEquals("", ready.deal.identity!!.pins.single { it.role == PartyRole.COUNTERPARTY }.legalName)
        val older = v1Deal()
        val olderBytes = older.canonicalBytes()
        assertThrows(DealFormatException::class.java) {
            DealIdentity.authorizeOwnBinding(
                older,
                older.parties.first().partyId,
                explosiveSigner(),
                signatureCount = 0,
                dealStatus = DealStatus.DRAFT,
            )
        }
        assertArrayEquals(olderBytes, older.canonicalBytes())
        assertNull(older.identity)
    }

    @Test
    fun participantsStayPinnedAcrossRevisionsAndStoredUpgradesAreRefused() {
        val chat = ChatFixture()
        val deal = chat.ready().deal
        val revised = DealVersions.revise(
            current = deal,
            currentBytes = deal.canonicalBytes(),
            signatureCount = 0,
            dealStatus = DealStatus.DRAFT,
            newVersionId = "w".repeat(22),
            newTerms = "Revised terms",
            revisedAtEpochMs = CREATED + 1_000,
        )
        assertEquals(DealFeature.CANONICAL_VERSION_V3, revised.canonicalVersion)
        DealIdentity.requireSameParticipants(deal, revised)
        val original = deal.identity!!.pins.single { it.role == PartyRole.PROPOSER }
        val kept = revised.identity!!.pins.single { it.role == PartyRole.PROPOSER }
        assertEquals(original.participant.armorChatId, kept.participant.armorChatId)
        assertArrayEquals(original.participant.messagingPublicKey, kept.participant.messagingPublicKey)
        assertArrayEquals(original.dealSigningPublicKey, kept.dealSigningPublicKey)
        assertArrayEquals(original.bindingSignature, kept.bindingSignature)
        val other = ChatFixture()
        assertThrows(DealFormatException::class.java) {
            DealIdentity.requireSameParticipants(deal, other.ready().deal)
        }
        val legacy = v1Deal()
        val legacyRevision = DealVersions.revise(
            current = legacy,
            currentBytes = legacy.canonicalBytes(),
            signatureCount = 0,
            dealStatus = DealStatus.DRAFT,
            newVersionId = "w".repeat(22),
            newTerms = "Still version one",
            revisedAtEpochMs = CREATED + 1_000,
        )
        assertEquals(DealFeature.CANONICAL_VERSION, legacyRevision.canonicalVersion)
        assertNull(legacyRevision.identity)
        assertThrows(DealFormatException::class.java) {
            DealIdentity.requireSameParticipants(legacy, unsignedVersionUpgrade(legacy))
        }
    }

    @Test
    fun eachPartyConfirmsOnlyTheirOwnLegalNameBeforeSigning() {
        val chat = ChatFixture()
        val draft = chat.ready().deal
        assertTrue(draft.identity!!.pins.all { it.legalName.isEmpty() })
        assertThrows(DealStateException::class.java) {
            DealSignatures.sign(chat.keysA.signingHandle(), draft, chat.ids.proposerPartyId, SignAction.SIGN)
        }
        val namedByA = DealIdentity.confirmOwnLegalName(
            draft,
            chat.ids.proposerPartyId,
            chat.signer,
            "Ada Lovelace",
            signatureCount = 0,
            dealStatus = DealStatus.DRAFT,
        )
        assertEquals("Ada Lovelace", namedByA.identity!!.pins.single { it.role == PartyRole.PROPOSER }.legalName)
        assertEquals("", namedByA.identity!!.pins.single { it.role == PartyRole.COUNTERPARTY }.legalName)
        assertEquals(
            LegalNameStatus.SELF_DECLARED_UNVERIFIED,
            namedByA.identity!!.pins.single { it.role == PartyRole.PROPOSER }.nameStatus,
        )
        val stolen = DealIdentityCrypto.sign(
            chat.localHandle,
            DealIdentity.legalNamePayload(chat.ids.dealId, PartyRole.COUNTERPARTY, chat.contactParticipant, "Stolen Name"),
        )
        assertThrows(DealCryptoException.InvalidSignature::class.java) {
            chat.assemble(namedByA, counterpartyName = "Stolen Name", counterpartyConfirmation = stolen)
        }
        val named = DealIdentity.confirmOwnLegalName(
            namedByA,
            chat.ids.counterpartyPartyId,
            signerFor(chat.contactHandle),
            "Grace Hopper",
            signatureCount = 0,
            dealStatus = DealStatus.DRAFT,
        )
        val text = String(named.canonicalBytes(), Charsets.UTF_8)
        assertTrue(text.contains("Ada Lovelace"))
        assertTrue(text.contains("Grace Hopper"))
        val payload = DealIdentity.legalNamePayload(chat.ids.dealId, PartyRole.PROPOSER, chat.localParticipant, "Ada Lovelace")
        val payloadText = String(payload, Charsets.UTF_8)
        assertTrue(payloadText.contains(DealIdentity.LEGAL_NAME_ASSURANCE))
        assertTrue(payloadText.contains(DealIdentity.LEGAL_NAME_DOMAIN))
        assertFalse(payloadText.contains("aadhaar"))
        assertFalse(payloadText.contains("phone"))
        assertFalse(payloadText.contains("address"))
        assertEquals(2, LegalNameStatus.entries.size)
        assertThrows(ImmutableVersionException::class.java) {
            DealIdentity.confirmOwnLegalName(
                named,
                chat.ids.proposerPartyId,
                chat.signer,
                "Ada King",
                signatureCount = 1,
                dealStatus = DealStatus.PARTIALLY_SIGNED,
            )
        }
        val signatures = listOf(
            DealSignatures.sign(chat.keysA.signingHandle(), named, chat.ids.proposerPartyId, SignAction.SIGN),
            DealSignatures.sign(chat.keysB.signingHandle(), named, chat.ids.counterpartyPartyId, SignAction.SIGN),
        )
        val verdict = DealFinality.assess(
            deal = named,
            signatures = signatures,
            pinnedSigningKeys = named.parties.associate { it.partyId to it.signingPublicKey },
        )
        assertTrue(verdict.proven)
        assertEquals(named.documentHash().toHex(), sha256(named.canonicalBytes()).toHex())
        assertThrows(ImmutableVersionException::class.java) {
            DealVersions.revise(
                current = named,
                currentBytes = named.canonicalBytes(),
                signatureCount = 2,
                dealStatus = DealStatus.FULLY_SIGNED,
                newVersionId = "x".repeat(22),
                newTerms = "After both signatures",
                revisedAtEpochMs = CREATED + 5_000,
            )
        }
    }

    @Test
    fun canonicalV1AndV2StayPinnedWhileV3RoundTrips() {
        val legacy = v1Deal()
        val bytes = legacy.canonicalBytes()
        assertEquals(DealFeature.CANONICAL_VERSION, legacy.canonicalVersion)
        assertNull(legacy.identity)
        assertEquals(V1_HASH, sha256(bytes).toHex())
        assertArrayEquals(bytes, CanonicalEncoding.parse(bytes).canonicalBytes())
        val unsupported = bytes.copyOf()
        unsupported[4] = 4
        assertThrows(DealFormatException::class.java) { CanonicalEncoding.parse(unsupported) }
        val trailed = bytes.copyOf(bytes.size + 1)
        assertThrows(DealFormatException::class.java) { CanonicalEncoding.parse(trailed) }

        val versionTwo = CanonicalDeal.create(
            dealId = "d".repeat(22),
            versionId = "v".repeat(22),
            parentVersionId = null,
            createdAtEpochMs = CREATED,
            currency = "INR",
            amountMinor = 1050,
            parties = listOf(
                DealParty("a".repeat(22), PartyRole.PROPOSER, "A", ByteArray(32) { 1 }, ByteArray(32) { 2 }),
                DealParty("b".repeat(22), PartyRole.COUNTERPARTY, "B", ByteArray(32) { 3 }, ByteArray(32) { 4 }),
            ),
            ancestry = emptyList(),
            terms = "Version two",
            details = AgreementDetails("Title", "2026-12-31", "", "Client", "Studio"),
        )
        assertEquals(DealFeature.CANONICAL_VERSION_V2, versionTwo.canonicalVersion)
        assertNull(versionTwo.identity)
        assertArrayEquals(versionTwo.canonicalBytes(), CanonicalEncoding.parse(versionTwo.canonicalBytes()).canonicalBytes())

        val ready = ChatFixture().ready().deal
        val modern = ready.canonicalBytes()
        assertEquals(3, modern[4].toInt())
        val parsed = CanonicalEncoding.parse(modern)
        assertArrayEquals(modern, parsed.canonicalBytes())
        assertEquals(ready.identity!!.pins[0].participant.armorChatId, parsed.identity!!.pins[0].participant.armorChatId)
        val extra = modern.copyOf(modern.size + 1)
        assertThrows(DealFormatException::class.java) { CanonicalEncoding.parse(extra) }
        val fixture = signedFixture(terms = "Finality still requires both signatures")
        assertTrue(
            DealFinality.assess(
                fixture.deal,
                fixture.signatures,
                fixture.deal.parties.associate { it.partyId to it.signingPublicKey },
            ).proven,
        )
        assertFalse(DealFeature.PRODUCTION_UI_ENABLED)
        assertFalse(ChatDealFactory.enabled(debugBuild = false))
        assertFalse(ChatDealFactory.enabled(debugBuild = true, productionUiEnabled = true))
        assertTrue(ChatDealFactory.enabled(debugBuild = true))
        assertThrows(DealStateException::class.java) {
            ChatFixture().pending(debugBuild = false, signer = explosiveSigner())
        }
    }

    private class ChatFixture {
        val localHandle: KeysetHandle = newMessaging()
        val contactHandle: KeysetHandle = newMessaging()
        val localKey: ByteArray = DealIdentityCrypto.publicKey(localHandle)
        val contactKey: ByteArray = DealIdentityCrypto.publicKey(contactHandle)
        val localParticipant: VerifiedDealParticipant = VerifiedDealParticipant.verify(
            ArmorChatId.derive(localKey),
            localKey,
        )
        val contactParticipant: VerifiedDealParticipant = VerifiedDealParticipant.verify(
            ArmorChatId.derive(contactKey),
            contactKey,
        )
        val local: UserIdentity = UserIdentity(
            armorChatId = localParticipant.armorChatId,
            publicIdentityKey = Base64Url.encode(localKey),
            createdAtEpochMs = CREATED,
            displayName = "Neeraj",
        )
        val contact: Contact = contactFor(contactHandle, "contact-1", "Recipient")
        val conversation: Conversation = Conversation("conversation-1", contact.id, null, CREATED)
        val keysA: DealKeyMaterial = DealKeyMaterial.generate()
        val keysB: DealKeyMaterial = DealKeyMaterial.generate()
        val ids: ChatDealIds = ChatDealIds(
            dealId = "d".repeat(22),
            versionId = "v".repeat(22),
            proposerPartyId = "a".repeat(22),
            counterpartyPartyId = "b".repeat(22),
        )
        val details: AgreementDetails = AgreementDetails(
            title = "Chat agreement",
            deadlineDate = "2026-12-31",
            paymentConditions = "",
            proposerRole = "Creator",
            counterpartyRole = "Recipient",
        )
        val signer: CountingSigner = CountingSigner(localHandle)

        fun pending(
            claimedPartyBId: String? = null,
            conversation: Conversation? = this.conversation,
            contact: Contact? = this.contact,
            debugBuild: Boolean = true,
            signer: DealMessagingSigner = this.signer,
        ) = ChatDealFactory.create(
            debugBuild = debugBuild,
            local = local,
            conversation = conversation,
            contact = contact,
            claimedPartyBId = claimedPartyBId,
            ids = ids,
            createdAtEpochMs = CREATED,
            localDealKeys = keysA,
            localSigner = signer,
            terms = "Shared terms",
            details = details,
        )

        fun ready(): ReadyChatDeal {
            val pending = pending()
            val binding = DealIdentityCrypto.sign(
                contactHandle,
                DealIdentity.bindingPayload(
                    ids.dealId,
                    PartyRole.COUNTERPARTY,
                    contactParticipant,
                    keysB.signingPublic,
                    keysB.encryptionPublic,
                ),
            )
            return ChatDealFactory.acceptCounterparty(
                debugBuild = true,
                pending = pending,
                counterpartySigningPublic = keysB.signingPublic,
                counterpartyEncryptionPublic = keysB.encryptionPublic,
                counterpartyBinding = binding,
            )
        }

        fun draft(
            role: PartyRole,
            participant: VerifiedDealParticipant,
            signing: ByteArray,
            encryption: ByteArray,
        ): DealPartyDraft = DealPartyDraft(
            partyId = if (role == PartyRole.PROPOSER) ids.proposerPartyId else ids.counterpartyPartyId,
            role = role,
            label = if (role == PartyRole.PROPOSER) "Neeraj" else "Recipient",
            participant = participant,
            signingPublicKey = signing,
            encryptionPublicKey = encryption,
        )

        fun assemble(
            current: CanonicalDeal,
            counterpartyName: String,
            counterpartyConfirmation: ByteArray,
        ): CanonicalDeal {
            val pins = current.identity!!.pins
            return DealIdentity.assemble(
                dealId = current.dealId,
                versionId = current.versionId,
                parentVersionId = current.parentVersionId,
                createdAtEpochMs = current.createdAtEpochMs,
                currency = current.currency,
                amountMinor = current.amountMinor,
                terms = current.terms,
                details = current.details ?: details,
                ancestry = current.ancestry,
                drafts = pins.map { pin ->
                    val named = pin.role == PartyRole.COUNTERPARTY
                    DealPartyDraft(
                        partyId = pin.partyId,
                        role = pin.role,
                        label = current.parties.single { it.partyId == pin.partyId }.label,
                        participant = pin.participant,
                        signingPublicKey = pin.dealSigningPublicKey,
                        encryptionPublicKey = pin.dealEncryptionPublicKey,
                        bindingSignature = pin.bindingSignature,
                        legalName = if (named) counterpartyName else pin.legalName,
                        nameStatus = if (named) LegalNameStatus.SELF_DECLARED_UNVERIFIED else pin.nameStatus,
                        nameConfirmation = if (named) counterpartyConfirmation else pin.nameConfirmation,
                    )
                },
            )
        }
    }

    companion object {
        private const val CREATED = 1_700_000_000_000L
        private const val V1_HASH = "114207667dcdef469408d06d695c98d5b252947732e81b49a1867030914fee95"
    }
}

internal class CountingSigner(private val handle: KeysetHandle) : DealMessagingSigner {
    var signatures: Int = 0
    override val publicKey: ByteArray = DealIdentityCrypto.publicKey(handle)
    override fun sign(payload: ByteArray): ByteArray {
        signatures += 1
        return DealIdentityCrypto.sign(handle, payload)
    }
}

internal fun unsignedVersionUpgrade(parent: CanonicalDeal): CanonicalDeal {
    val proposer = messagingIdentity("Upgrade A")
    val counterparty = messagingIdentity("Upgrade B")
    return DealIdentity.assemble(
        dealId = parent.dealId,
        versionId = "w".repeat(22),
        parentVersionId = parent.versionId,
        createdAtEpochMs = parent.createdAtEpochMs + 1_000,
        currency = parent.currency,
        amountMinor = parent.amountMinor,
        terms = "This must not replace the signed version",
        details = AgreementDetails("Upgrade", "2026-12-31", "", "Creator", "Recipient"),
        ancestry = listOf(parent.versionId),
        drafts = parent.parties.map { party ->
            val identity = if (party.role == PartyRole.PROPOSER) proposer else counterparty
            DealPartyDraft(
                partyId = party.partyId,
                role = party.role,
                label = party.label,
                participant = VerifiedDealParticipant.fromRecord(identity.armorChatId, identity.publicIdentityKey),
                signingPublicKey = party.signingPublicKey,
                encryptionPublicKey = party.encryptionPublicKey,
            )
        },
    )
}

private fun v1Deal(): CanonicalDeal = CanonicalDeal.create(
    dealId = "d".repeat(22),
    versionId = "v".repeat(22),
    parentVersionId = null,
    createdAtEpochMs = 1_700_000_000_000,
    currency = null,
    amountMinor = null,
    parties = listOf(
        DealParty("a".repeat(22), PartyRole.PROPOSER, "A", ByteArray(32) { 0x11 }, ByteArray(32) { 0x33 }),
        DealParty("b".repeat(22), PartyRole.COUNTERPARTY, "B", ByteArray(32) { 0x22 }, ByteArray(32) { 0x44 }),
    ),
    ancestry = emptyList(),
    terms = "Rent",
)

private fun newMessaging(): KeysetHandle {
    DealTink.register()
    return KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
}

private fun messagingIdentity(name: String): UserIdentity {
    val handle = newMessaging()
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

private fun signerFor(handle: KeysetHandle): DealMessagingSigner = object : DealMessagingSigner {
    override val publicKey: ByteArray = DealIdentityCrypto.publicKey(handle)
    override fun sign(payload: ByteArray): ByteArray = DealIdentityCrypto.sign(handle, payload)
}

private fun explosiveSigner(): DealMessagingSigner = object : DealMessagingSigner {
    override val publicKey: ByteArray
        get() = error("Party B signer was touched")
    override fun sign(payload: ByteArray): ByteArray = error("Party B signer was touched")
}

private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    for (start in 0..haystack.size - needle.size) {
        if (haystack.copyOfRange(start, start + needle.size).contentEquals(needle)) return start
    }
    throw AssertionError("needle missing")
}
