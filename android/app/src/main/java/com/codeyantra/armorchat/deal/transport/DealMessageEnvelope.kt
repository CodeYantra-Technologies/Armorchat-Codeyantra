package com.codeyantra.armorchat.deal.transport

import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealTink
import com.codeyantra.armorchat.deal.domain.AgreementDetails
import com.codeyantra.armorchat.deal.domain.ByteArrayOutput
import com.codeyantra.armorchat.deal.domain.ClosureKind
import com.codeyantra.armorchat.deal.domain.ContractAnnex
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.VerifiedDealParticipant
import com.codeyantra.armorchat.deal.domain.requireCanonicalText
import com.codeyantra.armorchat.deal.domain.requireRawKey
import com.codeyantra.armorchat.deal.domain.sha256
import com.codeyantra.armorchat.deal.domain.utf8Strict
import com.codeyantra.armorchat.deal.domain.validateAncestry
import com.codeyantra.armorchat.deal.domain.validateMoney
import com.google.crypto.tink.KeysetHandle
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Fixed chat-list label. It never includes agreement terms. */
object DealTransportPreview {
    const val LABEL = "Armor Deal"

    fun label(): String = LABEL
}

enum class DealWireEvent(val code: Int, val wire: String) {
    PROPOSAL(1, "PROPOSAL"),
    ACK(2, "ACK"),
    REQUEST_CHANGES(3, "REQUEST_CHANGES"),
    REJECTION(4, "REJECTION"),
    REVISED_PROPOSAL(5, "REVISED_PROPOSAL"),
    DELIVERY(6, "DELIVERY"),
    REVIEW(7, "REVIEW"),
    NAME_CONFIRMATION(8, "NAME_CONFIRMATION"),
    SIGNATURE(9, "SIGNATURE"),
    SIGNING_REJECTION(10, "SIGNING_REJECTION"),
    SIGNING_REVISION(11, "SIGNING_REVISION"),
    CLOSURE(12, "CLOSURE"),
    ;

    companion object {
        fun fromCode(code: Int): DealWireEvent =
            entries.firstOrNull { it.code == code } ?: throw DealFormatException("Deal envelope was rejected.")
    }
}

object DealTransportState {
    const val QUEUED = "QUEUED"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val ACKNOWLEDGED = "ACKNOWLEDGED"
    const val APPLIED = "APPLIED"
    const val DUPLICATE = "DUPLICATE"
    const val STALE = "STALE"
    const val CONFLICT = "CONFLICT"
    const val REJECTED = "REJECTED"
    const val WAITING = "WAITING"
    const val CHUNK = "CHUNK"
    const val OUT = "OUT"
    const val IN = "IN"

    fun rank(status: String): Int = when (status) {
        QUEUED -> 1
        SENT -> 2
        DELIVERED -> 3
        ACKNOWLEDGED -> 4
        else -> 0
    }
}

/**
 * The authenticated one-to-one session this envelope is bound to.
 * The local device holds only its own messaging public key here.
 */
class DealMessagingSession private constructor(
    val conversationId: String,
    val localArmorChatId: String,
    val localMessagingPublic: ByteArray,
    val peerArmorChatId: String,
    val peerMessagingPublic: ByteArray,
) {
    override fun toString(): String = "DealMessagingSession"

    companion object {
        fun establish(
            conversationId: String,
            local: VerifiedDealParticipant,
            peer: VerifiedDealParticipant,
        ): DealMessagingSession {
            requireCanonicalText(conversationId, allowNewlines = false, maxBytes = 80, emptyAllowed = false)
            if (local.armorChatId == peer.armorChatId ||
                local.messagingPublicKey.contentEquals(peer.messagingPublicKey)
            ) {
                throw DealFormatException("A participant cannot deal with themself.")
            }
            return DealMessagingSession(
                conversationId = conversationId,
                localArmorChatId = local.armorChatId,
                localMessagingPublic = local.messagingPublicKey.copyOf(),
                peerArmorChatId = peer.armorChatId,
                peerMessagingPublic = peer.messagingPublicKey.copyOf(),
            )
        }
    }
}

/**
 * Seals a deal envelope for one established session.
 *
 * [sendKey] and [receiveKey] are directional exporters from that session.
 * They are not messaging private keys. The frame is not a chat text body,
 * not an attachment, and not a Mesh packet.
 */
class ExportedSessionCipher(
    sendKey: ByteArray,
    receiveKey: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    private val sendKey = sendKey.copyOf()
    private val receiveKey = receiveKey.copyOf()

    init {
        if (this.sendKey.size != 32 || this.receiveKey.size != 32 || this.sendKey.contentEquals(this.receiveKey)) {
            throw DealFormatException("Session key is not valid.")
        }
    }

    fun seal(session: DealMessagingSession, plaintext: ByteArray): ByteArray =
        transform(Cipher.ENCRYPT_MODE, sendKey, session, plaintext, session.localMessagingPublic, session.peerMessagingPublic)

    fun open(session: DealMessagingSession, frame: ByteArray): ByteArray =
        transform(Cipher.DECRYPT_MODE, receiveKey, session, frame, session.peerMessagingPublic, session.localMessagingPublic)

    private fun transform(
        mode: Int,
        key: ByteArray,
        session: DealMessagingSession,
        input: ByteArray,
        sender: ByteArray,
        recipient: ByteArray,
    ): ByteArray {
        try {
            if (mode == Cipher.ENCRYPT_MODE) {
                val nonce = ByteArray(NONCE_BYTES)
                random.nextBytes(nonce)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(aad(session.conversationId, sender, recipient))
                val body = cipher.doFinal(input)
                val out = ByteArrayOutput()
                out.raw(MAGIC)
                out.u8(DealFeature.MESSAGE_PROTOCOL_VERSION)
                out.raw(nonce)
                out.raw(body)
                return out.toByteArray()
            }
            if (input.size < MAGIC.size + 1 + NONCE_BYTES + TAG_BYTES) {
                throw DealCryptoException.AuthenticationFailed()
            }
            if (!input.copyOfRange(0, 4).contentEquals(MAGIC) || input[4].toInt() != DealFeature.MESSAGE_PROTOCOL_VERSION) {
                throw DealCryptoException.AuthenticationFailed()
            }
            val nonce = input.copyOfRange(5, 5 + NONCE_BYTES)
            val body = input.copyOfRange(5 + NONCE_BYTES, input.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(session.conversationId, sender, recipient))
            return cipher.doFinal(body)
        } catch (e: DealCryptoException) {
            throw e
        } catch (e: AEADBadTagException) {
            throw DealCryptoException.AuthenticationFailed()
        } catch (e: Exception) {
            throw DealCryptoException.AuthenticationFailed()
        }
    }

    private fun aad(conversationId: String, sender: ByteArray, recipient: ByteArray): ByteArray {
        val out = ByteArrayOutput()
        out.raw(AAD_DOMAIN.toByteArray(StandardCharsets.UTF_8))
        out.u8(0)
        out.lp(conversationId)
        out.raw(sender)
        out.raw(recipient)
        return out.toByteArray()
    }

    companion object {
        private val MAGIC = byteArrayOf(0x41, 0x44, 0x53, 0x54)
        private const val AAD_DOMAIN = "ArmorDeal/messaging-session/v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
    }
}

/** Contractual proposal before Party B's deal key exists. Party B's private key is absent. */
class DealOffer(
    val dealId: String,
    val versionId: String,
    val parentVersionId: String?,
    val createdAtEpochMs: Long,
    val currency: String?,
    val amountMinor: Long?,
    val terms: String,
    val details: AgreementDetails,
    val annex: ContractAnnex,
    val ancestry: List<String>,
    val proposerPartyId: String,
    val counterpartyPartyId: String,
    val proposerLabel: String,
    val counterpartyLabel: String,
    val partyA: VerifiedDealParticipant,
    val partyB: VerifiedDealParticipant,
    val partyASigningPublic: ByteArray,
    val partyAEncryptionPublic: ByteArray,
    val partyABinding: ByteArray,
    val partyAPublicEncryptionKeyset: ByteArray,
    val conversationId: String,
) {
    init {
        DealIds.require(dealId)
        DealIds.require(versionId)
        if (parentVersionId != null) DealIds.require(parentVersionId)
        validateAncestry(versionId, parentVersionId, ancestry)
        validateMoney(currency, amountMinor)
        requireCanonicalText(terms, allowNewlines = true, maxBytes = 48_000, emptyAllowed = false)
        requireCanonicalText(proposerLabel, allowNewlines = false, maxBytes = 80, emptyAllowed = false)
        requireCanonicalText(counterpartyLabel, allowNewlines = false, maxBytes = 80, emptyAllowed = false)
        requireRawKey(partyASigningPublic, "signing")
        requireRawKey(partyAEncryptionPublic, "encryption")
        if (partyABinding.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        if (partyAPublicEncryptionKeyset.isEmpty() || partyAPublicEncryptionKeyset.size > 4_096) {
            throw DealFormatException("Keyset length is invalid.")
        }
        if (partyA.armorChatId == partyB.armorChatId ||
            partyA.messagingPublicKey.contentEquals(partyB.messagingPublicKey) ||
            partyASigningPublic.contentEquals(partyAEncryptionPublic) ||
            partyASigningPublic.contentEquals(partyA.messagingPublicKey) ||
            partyAEncryptionPublic.contentEquals(partyA.messagingPublicKey)
        ) {
            throw DealFormatException("A participant cannot deal with themself.")
        }
        if (proposerPartyId == counterpartyPartyId) throw DealFormatException("Party identifiers must be unique.")
        DealIds.require(proposerPartyId)
        DealIds.require(counterpartyPartyId)
    }

    override fun toString(): String = "DealOffer"
}

class DealEnvelope(
    val event: DealWireEvent,
    val envelopeId: String,
    val dealId: String,
    val versionId: String,
    val senderId: String,
    val recipientId: String,
    val senderKey: ByteArray,
    val recipientKey: ByteArray,
    val conversationId: String,
    val createdAtEpochMs: Long,
    payload: ByteArray,
) {
    val payload: ByteArray = payload.copyOf()

    init {
        DealIds.require(envelopeId)
        DealIds.require(dealId)
        DealIds.require(versionId)
        requireRawKey(senderKey, "messaging")
        requireRawKey(recipientKey, "messaging")
        if (senderId == recipientId || senderKey.contentEquals(recipientKey)) {
            throw DealFormatException("A participant cannot deal with themself.")
        }
        if (createdAtEpochMs <= 0L) throw DealFormatException("Timestamp is outside the accepted range.")
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES) throw DealFormatException("Deal envelope was rejected.")
    }

    override fun toString(): String = "DealEnvelope(event=${event.wire})"

    companion object {
        const val MAX_PAYLOAD_BYTES = 80_000
    }
}

class ParsedProposal(
    val offer: DealOffer,
    val offerBytes: ByteArray,
    val signature: ByteArray,
) {
    override fun toString(): String = "ParsedProposal"
}

class ParsedAck(
    val offerHash: ByteArray,
    val signingPublic: ByteArray,
    val encryptionPublic: ByteArray,
    val binding: ByteArray,
    val publicEncryptionKeyset: ByteArray,
) {
    override fun toString(): String = "ParsedAck"
}

class ParsedNote(
    val offerHash: ByteArray,
    val note: String,
) {
    override fun toString(): String = "ParsedNote"
}

class ParsedDelivery(
    val referencedEnvelopeId: String,
    val stateCode: Int,
) {
    override fun toString(): String = "ParsedDelivery"
}

class ParsedReview(
    val documentHash: ByteArray,
    val signature: ByteArray,
) {
    override fun toString(): String = "ParsedReview"
}

class ParsedNameConfirmation(
    val partyId: String,
    val signature: ByteArray,
    val successor: ByteArray?,
    val reviewSignature: ByteArray?,
) {
    override fun toString(): String = "ParsedNameConfirmation"
}

class ParsedDealSignature(
    val partyId: String,
    val signature: ByteArray,
    val signerPublicKey: ByteArray,
) {
    override fun toString(): String = "ParsedDealSignature"
}

class ParsedClosure(
    val kind: ClosureKind,
    val partyId: String,
    val signature: ByteArray,
    val signerPublicKey: ByteArray,
) {
    override fun toString(): String = "ParsedClosure"
}

class ParsedRevision(
    val canonicalBytes: ByteArray,
    val signature: ByteArray,
) {
    override fun toString(): String = "ParsedRevision"
}

object DealOffers {
    const val DOMAIN = "ArmorDeal/proposal-offer/v1"
    private val MAGIC = byteArrayOf(0x41, 0x44, 0x50, 0x4F)
    private const val MAX_KEYSET_BYTES = 4_096

    fun encode(offer: DealOffer): ByteArray {
        val out = ByteArrayOutput()
        out.raw(MAGIC)
        out.u8(1)
        out.lp(offer.dealId)
        out.lp(offer.versionId)
        out.lp(offer.parentVersionId ?: "")
        out.u64(offer.createdAtEpochMs)
        if (offer.currency == null) {
            out.u8(0)
        } else {
            out.u8(3)
            out.raw(offer.currency.toByteArray(StandardCharsets.US_ASCII))
        }
        if (offer.amountMinor == null) {
            out.u8(0)
        } else {
            out.u8(1)
            out.u64(offer.amountMinor)
        }
        val terms = offer.terms.toByteArray(StandardCharsets.UTF_8)
        out.u32(terms.size)
        out.raw(terms)
        out.lp(offer.details.title)
        out.lp(offer.details.deadlineDate)
        out.lp(offer.details.paymentConditions)
        out.lp(offer.details.proposerRole)
        out.lp(offer.details.counterpartyRole)
        out.lp(offer.annex.purpose)
        if (offer.annex.effectiveDate == null) {
            out.u8(0)
        } else {
            out.u8(1)
            out.lp(offer.annex.effectiveDate)
        }
        out.u16(offer.ancestry.size)
        offer.ancestry.forEach { out.lp(it) }
        out.lp(offer.proposerPartyId)
        out.lp(offer.counterpartyPartyId)
        out.lp(offer.proposerLabel)
        out.lp(offer.counterpartyLabel)
        out.lp(offer.partyA.armorChatId)
        writeKey(out, offer.partyA.messagingPublicKey)
        out.lp(offer.partyB.armorChatId)
        writeKey(out, offer.partyB.messagingPublicKey)
        writeKey(out, offer.partyASigningPublic)
        writeKey(out, offer.partyAEncryptionPublic)
        writeKey(out, offer.partyABinding)
        out.u32(offer.partyAPublicEncryptionKeyset.size)
        out.raw(offer.partyAPublicEncryptionKeyset)
        out.lp(offer.conversationId)
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray): DealOffer {
        val cursor = DealCursor(bytes)
        val magic = cursor.bytes(4)
        if (!magic.contentEquals(MAGIC) || cursor.u8() != 1) throw DealFormatException("Deal envelope was rejected.")
        val dealId = cursor.lp()
        val versionId = cursor.lp()
        val parent = cursor.lp().ifEmpty { null }
        val createdAt = cursor.u64()
        val currency = when (val length = cursor.u8()) {
            0 -> null
            3 -> {
                val text = String(cursor.bytes(3), StandardCharsets.US_ASCII)
                if (!text.matches(Regex("^[A-Z]{3}$"))) throw DealFormatException("Currency is not canonical.")
                text
            }
            else -> throw DealFormatException("Currency length is not canonical.")
        }
        val amount = when (val flag = cursor.u8()) {
            0 -> null
            1 -> cursor.u64()
            else -> throw DealFormatException("Amount flag is not canonical.")
        }
        val termsLength = cursor.u32()
        if (termsLength > 48_000) throw DealFormatException("Terms are too long.")
        val terms = utf8Strict(cursor.bytes(termsLength))
        val details = AgreementDetails(cursor.lp(), cursor.lp(), cursor.lp(), cursor.lp(), cursor.lp())
        val purpose = cursor.lp()
        val effective = when (val flag = cursor.u8()) {
            0 -> null
            1 -> cursor.lp()
            else -> throw DealFormatException("Effective date flag is not canonical.")
        }
        val ancestryCount = cursor.u16()
        if (ancestryCount > 64) throw DealFormatException("Ancestry is too long.")
        val ancestry = ArrayList<String>(ancestryCount)
        repeat(ancestryCount) { ancestry += cursor.lp() }
        val offer = DealOffer(
            dealId = dealId,
            versionId = versionId,
            parentVersionId = parent,
            createdAtEpochMs = createdAt,
            currency = currency,
            amountMinor = amount,
            terms = terms,
            details = details,
            annex = ContractAnnex(purpose, effective),
            ancestry = ancestry,
            proposerPartyId = cursor.lp(),
            counterpartyPartyId = cursor.lp(),
            proposerLabel = cursor.lp(),
            counterpartyLabel = cursor.lp(),
            partyA = VerifiedDealParticipant.verify(cursor.lp(), cursor.key()),
            partyB = VerifiedDealParticipant.verify(cursor.lp(), cursor.key()),
            partyASigningPublic = cursor.key(),
            partyAEncryptionPublic = cursor.key(),
            partyABinding = cursor.signature(),
            partyAPublicEncryptionKeyset = cursor.blob(MAX_KEYSET_BYTES),
            conversationId = cursor.lp(),
        )
        if (cursor.remaining() != 0) throw DealFormatException("Deal envelope was rejected.")
        val public = DealKeyMaterial.parsePublicEncryption(offer.partyAPublicEncryptionKeyset)
        if (!DealKeyMaterial.publicEncryptionRaw(public).contentEquals(offer.partyAEncryptionPublic)) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        return offer
    }

    fun sign(signingKey: KeysetHandle, offerBytes: ByteArray): ByteArray {
        DealTink.register()
        return DealIdentityCrypto.sign(signingKey, signedPayload(offerBytes))
    }

    fun verify(publicKey: ByteArray, signature: ByteArray, offerBytes: ByteArray) {
        DealIdentityCrypto.verify(publicKey, signature, signedPayload(offerBytes))
    }

    fun hash(offerBytes: ByteArray): ByteArray = sha256(offerBytes)

    private fun signedPayload(offerBytes: ByteArray): ByteArray {
        val out = ByteArrayOutput()
        out.raw(DOMAIN.toByteArray(StandardCharsets.UTF_8))
        out.u8(0)
        out.raw(offerBytes)
        return out.toByteArray()
    }

    private fun writeKey(out: ByteArrayOutput, key: ByteArray) {
        out.u16(key.size)
        out.raw(key)
    }
}

object DealEnvelopes {
    private val MAGIC = byteArrayOf(0x41, 0x44, 0x4D, 0x45)

    fun encode(envelope: DealEnvelope): ByteArray {
        val out = ByteArrayOutput()
        out.raw(MAGIC)
        out.u8(DealFeature.MESSAGE_PROTOCOL_VERSION)
        out.u8(envelope.event.code)
        out.lp(envelope.envelopeId)
        out.lp(envelope.dealId)
        out.lp(envelope.versionId)
        out.lp(envelope.senderId)
        out.lp(envelope.recipientId)
        out.u16(envelope.senderKey.size)
        out.raw(envelope.senderKey)
        out.u16(envelope.recipientKey.size)
        out.raw(envelope.recipientKey)
        out.lp(envelope.conversationId)
        out.u64(envelope.createdAtEpochMs)
        out.u32(envelope.payload.size)
        out.raw(envelope.payload)
        val bytes = out.toByteArray()
        if (bytes.size > 96_000) throw DealFormatException("Deal envelope was rejected.")
        return bytes
    }

    fun parse(bytes: ByteArray): DealEnvelope {
        if (bytes.size > 96_000) throw DealFormatException("Deal envelope was rejected.")
        val cursor = DealCursor(bytes)
        val magic = cursor.bytes(4)
        if (!magic.contentEquals(MAGIC) || cursor.u8() != DealFeature.MESSAGE_PROTOCOL_VERSION) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val envelope = DealEnvelope(
            event = DealWireEvent.fromCode(cursor.u8()),
            envelopeId = cursor.lp(),
            dealId = cursor.lp(),
            versionId = cursor.lp(),
            senderId = cursor.lp(),
            recipientId = cursor.lp(),
            senderKey = cursor.key(),
            recipientKey = cursor.key(),
            conversationId = cursor.lp(),
            createdAtEpochMs = cursor.u64(),
            payload = cursor.blob(DealEnvelope.MAX_PAYLOAD_BYTES),
        )
        if (cursor.remaining() != 0) throw DealFormatException("Deal envelope was rejected.")
        VerifiedDealParticipant.verify(envelope.senderId, envelope.senderKey)
        VerifiedDealParticipant.verify(envelope.recipientId, envelope.recipientKey)
        return envelope
    }

    fun proposalPayload(offerBytes: ByteArray, signature: ByteArray): ByteArray {
        if (signature.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        val out = ByteArrayOutput()
        out.u32(offerBytes.size)
        out.raw(offerBytes)
        out.u16(signature.size)
        out.raw(signature)
        return out.toByteArray()
    }

    fun parseProposal(payload: ByteArray): ParsedProposal {
        val cursor = DealCursor(payload)
        val offerBytes = cursor.blob(DealEnvelope.MAX_PAYLOAD_BYTES)
        val signature = cursor.signature()
        if (cursor.remaining() != 0) throw DealFormatException("Deal envelope was rejected.")
        return ParsedProposal(DealOffers.parse(offerBytes), offerBytes, signature)
    }

    fun ackPayload(
        offerHash: ByteArray,
        signingPublic: ByteArray,
        encryptionPublic: ByteArray,
        binding: ByteArray,
        publicEncryptionKeyset: ByteArray,
    ): ByteArray {
        if (offerHash.size != 32 || binding.size != 64) throw DealFormatException("Deal envelope was rejected.")
        requireRawKey(signingPublic, "signing")
        requireRawKey(encryptionPublic, "encryption")
        val out = ByteArrayOutput()
        out.raw(offerHash)
        out.u16(32)
        out.raw(signingPublic)
        out.u16(32)
        out.raw(encryptionPublic)
        out.u16(64)
        out.raw(binding)
        out.u32(publicEncryptionKeyset.size)
        out.raw(publicEncryptionKeyset)
        return out.toByteArray()
    }

    fun parseAck(payload: ByteArray): ParsedAck {
        val cursor = DealCursor(payload)
        val hash = cursor.bytes(32)
        val signing = cursor.key()
        val encryption = cursor.key()
        val binding = cursor.signature()
        val keyset = cursor.blob(4_096)
        if (cursor.remaining() != 0) throw DealFormatException("Deal envelope was rejected.")
        return ParsedAck(hash, signing, encryption, binding, keyset)
    }

    fun notePayload(offerHash: ByteArray, note: String): ByteArray {
        if (offerHash.size != 32) throw DealFormatException("Deal envelope was rejected.")
        requireCanonicalText(note, allowNewlines = true, maxBytes = 1_000, emptyAllowed = false)
        val out = ByteArrayOutput()
        out.raw(offerHash)
        out.lp(note)
        return out.toByteArray()
    }

    fun parseNote(payload: ByteArray): ParsedNote {
        val cursor = DealCursor(payload)
        val hash = cursor.bytes(32)
        val note = cursor.lp()
        if (cursor.remaining() != 0) throw DealFormatException("Deal envelope was rejected.")
        requireCanonicalText(note, allowNewlines = true, maxBytes = 1_000, emptyAllowed = false)
        return ParsedNote(hash, note)
    }

    fun deliveryPayload(referencedEnvelopeId: String, stateCode: Int): ByteArray {
        DealIds.require(referencedEnvelopeId)
        if (stateCode !in 1..4) throw DealFormatException("Deal envelope was rejected.")
        val out = ByteArrayOutput()
        out.lp(referencedEnvelopeId)
        out.u8(stateCode)
        return out.toByteArray()
    }

    fun parseDelivery(payload: ByteArray): ParsedDelivery {
        val cursor = DealCursor(payload)
        val id = cursor.lp()
        val code = cursor.u8()
        if (cursor.remaining() != 0 || code !in 1..4) throw DealFormatException("Deal envelope was rejected.")
        DealIds.require(id)
        return ParsedDelivery(id, code)
    }

    fun reviewPayload(documentHash: ByteArray, signature: ByteArray): ByteArray {
        if (documentHash.size != 32 || signature.size != 64) throw DealFormatException("Deal envelope was rejected.")
        val out = ByteArrayOutput()
        out.raw(documentHash)
        out.raw(signature)
        return out.toByteArray()
    }

    fun parseReview(payload: ByteArray): ParsedReview {
        val cursor = DealCursor(payload)
        val hash = cursor.bytes(32)
        val signature = cursor.bytes(64)
        if (cursor.remaining() != 0) throw DealFormatException("Deal envelope was rejected.")
        return ParsedReview(hash, signature)
    }

    fun namePayload(
        partyId: String,
        signature: ByteArray,
        successor: ByteArray?,
        reviewSignature: ByteArray?,
    ): ByteArray {
        DealIds.require(partyId)
        if (signature.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        if (reviewSignature != null && reviewSignature.size != 64) {
            throw DealFormatException("Ed25519 signature must be 64 bytes.")
        }
        val out = ByteArrayOutput()
        out.u8(if (successor == null) 1 else 2)
        out.lp(partyId)
        out.raw(signature)
        if (reviewSignature == null) {
            out.u8(0)
        } else {
            out.u8(1)
            out.raw(reviewSignature)
        }
        if (successor != null) {
            if (successor.isEmpty() || successor.size > MAX_DOCUMENT_BYTES) {
                throw DealFormatException("Deal envelope was rejected.")
            }
            out.u32(successor.size)
            out.raw(successor)
        }
        return out.toByteArray()
    }

    fun parseName(payload: ByteArray): ParsedNameConfirmation {
        val cursor = DealCursor(payload)
        val kind = cursor.u8()
        if (kind != 1 && kind != 2) throw DealFormatException("Deal envelope was rejected.")
        val partyId = cursor.lp()
        DealIds.require(partyId)
        val signature = cursor.bytes(64)
        val review = when (cursor.u8()) {
            0 -> null
            1 -> cursor.bytes(64)
            else -> throw DealFormatException("Deal envelope was rejected.")
        }
        val successor = if (kind == 2) cursor.blob(MAX_DOCUMENT_BYTES) else null
        if (cursor.remaining() != 0) throw DealFormatException("Deal envelope was rejected.")
        return ParsedNameConfirmation(partyId, signature, successor, review)
    }

    fun signaturePayload(partyId: String, signature: ByteArray, signerPublic: ByteArray): ByteArray {
        DealIds.require(partyId)
        if (signature.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        requireRawKey(signerPublic, "signing")
        val out = ByteArrayOutput()
        out.lp(partyId)
        out.raw(signature)
        out.u16(signerPublic.size)
        out.raw(signerPublic)
        return out.toByteArray()
    }

    fun parseSignature(payload: ByteArray): ParsedDealSignature {
        val cursor = DealCursor(payload)
        val partyId = cursor.lp()
        DealIds.require(partyId)
        val signature = cursor.bytes(64)
        val signer = cursor.key()
        if (cursor.remaining() != 0) throw DealFormatException("Deal envelope was rejected.")
        return ParsedDealSignature(partyId, signature, signer)
    }

    fun closurePayload(kind: ClosureKind, partyId: String, signature: ByteArray, signerPublic: ByteArray): ByteArray {
        DealIds.require(partyId)
        if (signature.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        requireRawKey(signerPublic, "signing")
        val out = ByteArrayOutput()
        out.u8(kind.code)
        out.lp(partyId)
        out.raw(signature)
        out.u16(signerPublic.size)
        out.raw(signerPublic)
        return out.toByteArray()
    }

    fun parseClosure(payload: ByteArray): ParsedClosure {
        val cursor = DealCursor(payload)
        val kind = ClosureKind.fromCode(cursor.u8())
        val partyId = cursor.lp()
        DealIds.require(partyId)
        val signature = cursor.bytes(64)
        val signer = cursor.key()
        if (cursor.remaining() != 0) throw DealFormatException("Deal envelope was rejected.")
        return ParsedClosure(kind, partyId, signature, signer)
    }

    fun revisionPayload(canonicalBytes: ByteArray, signature: ByteArray): ByteArray {
        if (signature.size != 64 || canonicalBytes.isEmpty() || canonicalBytes.size > MAX_DOCUMENT_BYTES) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val out = ByteArrayOutput()
        out.u32(canonicalBytes.size)
        out.raw(canonicalBytes)
        out.raw(signature)
        return out.toByteArray()
    }

    fun parseRevision(payload: ByteArray): ParsedRevision {
        val cursor = DealCursor(payload)
        val canonical = cursor.blob(MAX_DOCUMENT_BYTES)
        val signature = cursor.bytes(64)
        if (cursor.remaining() != 0) throw DealFormatException("Deal envelope was rejected.")
        return ParsedRevision(canonical, signature)
    }

    private const val MAX_DOCUMENT_BYTES = 70_000
}

class DealTransportResult(
    val envelopeId: String,
    val dealId: String,
    val versionId: String,
    val event: String,
    val status: String,
    val acceptedAgreement: Boolean = false,
    val finalized: Boolean = false,
) {
    override fun toString(): String =
        "DealTransportResult(event=$event, status=$status, accepted=$acceptedAgreement, finalized=$finalized)"
}

private class DealCursor(private val data: ByteArray) {
    private var index = 0

    fun remaining(): Int = data.size - index

    fun u8(): Int {
        if (index >= data.size) throw DealFormatException("Deal envelope was rejected.")
        return data[index++].toInt() and 0xff
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Int {
        var value = 0
        repeat(4) {
            val next = u8()
            if (value > (Int.MAX_VALUE shr 8)) throw DealFormatException("Deal envelope was rejected.")
            value = (value shl 8) or next
        }
        return value
    }

    fun u64(): Long {
        var value = 0L
        repeat(8) {
            val next = u8().toLong()
            if (value > (Long.MAX_VALUE shr 8)) throw DealFormatException("Deal envelope was rejected.")
            value = (value shl 8) or next
        }
        return value
    }

    fun bytes(count: Int): ByteArray {
        if (count < 0 || remaining() < count) throw DealFormatException("Deal envelope was rejected.")
        val slice = data.copyOfRange(index, index + count)
        index += count
        return slice
    }

    fun lp(): String = utf8Strict(bytes(u16()))

    fun key(): ByteArray {
        if (u16() != 32) throw DealFormatException("Deal envelope was rejected.")
        return bytes(32)
    }

    fun signature(): ByteArray {
        if (u16() != 64) throw DealFormatException("Deal envelope was rejected.")
        return bytes(64)
    }

    fun blob(max: Int): ByteArray {
        val length = u32()
        if (length <= 0 || length > max) throw DealFormatException("Deal envelope was rejected.")
        return bytes(length)
    }
}
