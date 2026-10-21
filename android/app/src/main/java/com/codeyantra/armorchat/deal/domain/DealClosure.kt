package com.codeyantra.armorchat.deal.domain

import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.google.crypto.tink.KeysetHandle
import java.nio.charset.StandardCharsets

/**
 * Buyer and supplier are the signed contractual role strings on the canonical
 * document. They are not [PartyRole] values. A confirmation is not a SIGN row:
 * [DealFinality] rejects any signature action other than SIGN.
 *
 * The signature domain is [DOMAIN]. It is signed by the per-deal signing key.
 * The ArmorChat messaging key does not sign it, and the domain is not added to
 * [DealIdentity.MESSAGING_SIGNER_DOMAINS].
 */
enum class ClosureKind(val code: Int, val wire: String) {
    GOODS_RECEIVED(1, "GOODS_RECEIVED"),
    PAYMENT_RECEIVED(2, "PAYMENT_RECEIVED"),
    ;

    companion object {
        fun fromCode(code: Int): ClosureKind =
            entries.firstOrNull { it.code == code } ?: throw DealFormatException("Deal envelope was rejected.")

        fun fromWire(wire: String): ClosureKind =
            entries.firstOrNull { it.wire == wire } ?: throw DealFormatException("Deal envelope was rejected.")
    }
}

enum class ClosureState {
    UNAVAILABLE,
    OPEN,
    AWAITING_PAYMENT,
    AWAITING_GOODS,
    CLOSED,
}

enum class ClosureDefect {
    UNBOUND,
    WRONG_DEAL,
    STALE,
    WRONG_ROLE,
    WRONG_SIGNER,
    FORGED,
    NOT_FINAL,
    CONFLICT,
}

enum class ClosureAdmit {
    ACCEPTED,
    DUPLICATE,
    CLOSED,
    REJECTED,
}

class ClosureAdmitResult(
    val admit: ClosureAdmit,
    val defect: ClosureDefect?,
    val state: ClosureState,
) {
    override fun toString(): String = "ClosureAdmitResult(admit=$admit, defect=$defect, state=$state)"
}

class ClosureProjection(
    val state: ClosureState,
    val buyerConfirmed: Boolean,
    val supplierConfirmed: Boolean,
    val rolesBound: Boolean,
) {
    override fun toString(): String =
        "ClosureProjection(state=$state, buyer=$buyerConfirmed, supplier=$supplierConfirmed, bound=$rolesBound)"
}

/**
 * One authenticated closure statement. The bytes are the signature and the
 * pinned deal signing key. [toString] omits both.
 */
class ClosureConfirmation(
    val dealId: String,
    val versionId: String,
    val partyId: String,
    val kind: ClosureKind,
    signature: ByteArray,
    signerPublicKey: ByteArray,
) {
    val signature: ByteArray = signature.copyOf()
    val signerPublicKey: ByteArray = signerPublicKey.copyOf()

    init {
        DealIds.require(dealId)
        DealIds.require(versionId)
        DealIds.require(partyId)
        if (this.signature.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        requireRawKey(this.signerPublicKey, "signing")
    }

    override fun toString(): String = "ClosureConfirmation(kind=$kind)"
}

object DealClosure {
    const val DOMAIN = "ArmorDeal/closure/v1"
    const val BUYER = "Buyer"
    const val SUPPLIER = "Supplier"

    fun rolesBound(deal: CanonicalDeal): Boolean {
        val details = deal.details ?: return false
        return setOf(details.proposerRole, details.counterpartyRole) == setOf(BUYER, SUPPLIER)
    }

    fun contractualRole(deal: CanonicalDeal, partyId: String): String? {
        val party = deal.parties.firstOrNull { it.partyId == partyId } ?: return null
        val details = deal.details ?: return null
        return when (party.role) {
            PartyRole.PROPOSER -> details.proposerRole
            PartyRole.COUNTERPARTY -> details.counterpartyRole
        }
    }

    /** The only kind this party may produce. Null when the signed roles are not Buyer and Supplier. */
    fun kindFor(deal: CanonicalDeal, partyId: String): ClosureKind? {
        if (!rolesBound(deal)) return null
        return when (contractualRole(deal, partyId)) {
            BUYER -> ClosureKind.GOODS_RECEIVED
            SUPPLIER -> ClosureKind.PAYMENT_RECEIVED
            else -> null
        }
    }

    /**
     * Signs this party's own kind with this party's deal key.
     * There is no argument for the other party's kind.
     */
    fun sign(deal: CanonicalDeal, partyId: String, signingKey: KeysetHandle): ClosureConfirmation {
        val kind = kindFor(deal, partyId) ?: throw DealStateException("Closure is not available for this agreement.")
        if (deal.identity == null) throw DealStateException("Closure is not available for this agreement.")
        val party = deal.party(partyId)
        val presented = DealIdentityCrypto.publicKey(signingKey)
        if (!presented.contentEquals(party.signingPublicKey)) throw DealCryptoException.WrongSigner()
        val payload = payloadBytes(deal, partyId, kind)
        val signature = DealIdentityCrypto.sign(signingKey, payload)
        return ClosureConfirmation(
            dealId = deal.dealId,
            versionId = deal.versionId,
            partyId = partyId,
            kind = kind,
            signature = signature,
            signerPublicKey = party.signingPublicKey.copyOf(),
        )
    }

    /**
     * Checks deal, version, role, signer, and signature against [deal].
     * A null result means the confirmation matches this document. Finality is
     * a separate check because a well-formed confirmation on an open deal is
     * not forged.
     */
    fun check(deal: CanonicalDeal, confirmation: ClosureConfirmation): ClosureDefect? {
        if (confirmation.dealId != deal.dealId) return ClosureDefect.WRONG_DEAL
        if (confirmation.versionId != deal.versionId) return ClosureDefect.STALE
        if (deal.identity == null || !rolesBound(deal)) return ClosureDefect.UNBOUND
        val party = deal.parties.firstOrNull { it.partyId == confirmation.partyId }
            ?: return ClosureDefect.WRONG_SIGNER
        val expected = kindFor(deal, party.partyId) ?: return ClosureDefect.WRONG_ROLE
        if (confirmation.kind != expected) return ClosureDefect.WRONG_ROLE
        if (!confirmation.signerPublicKey.contentEquals(party.signingPublicKey)) return ClosureDefect.WRONG_SIGNER
        return try {
            val payload = payloadBytes(deal, party.partyId, confirmation.kind)
            DealIdentityCrypto.verify(party.signingPublicKey, confirmation.signature, payload)
            null
        } catch (e: DealCryptoException) {
            ClosureDefect.FORGED
        } catch (e: DealFormatException) {
            ClosureDefect.FORGED
        }
    }

    fun payloadBytes(deal: CanonicalDeal, partyId: String, kind: ClosureKind): ByteArray {
        val identity = deal.identity ?: throw DealFormatException("This document has no identity binding.")
        val pin = identity.pin(partyId)
        val party = deal.party(partyId)
        val roleName = contractualRole(deal, partyId)
            ?: throw DealFormatException("Closure is not available for this agreement.")
        if (!pin.dealSigningPublicKey.contentEquals(party.signingPublicKey)) {
            throw DealFormatException("Identity binding does not match the pinned deal keys.")
        }
        val hash = deal.documentHash()
        if (hash.size != 32 || party.signingPublicKey.size != 32) {
            throw DealFormatException("Signature payload key material is the wrong length.")
        }
        val out = ByteArrayOutput()
        out.raw(DOMAIN.toByteArray(StandardCharsets.UTF_8))
        out.u8(0)
        out.u16(DealFeature.PROTOCOL_VERSION)
        out.u8(kind.code)
        out.lp(deal.dealId)
        out.lp(deal.versionId)
        out.lp(partyId)
        out.u8(party.role.code)
        out.lp(pin.participant.armorChatId)
        out.lp(roleName)
        out.u16(party.signingPublicKey.size)
        out.raw(party.signingPublicKey)
        out.u16(hash.size)
        out.raw(hash)
        return out.toByteArray()
    }

    fun rejectOutcome(defect: ClosureDefect): String = when (defect) {
        ClosureDefect.UNBOUND -> "reject_unbound"
        ClosureDefect.WRONG_DEAL -> "reject_wrong_deal"
        ClosureDefect.STALE -> "reject_stale"
        ClosureDefect.WRONG_ROLE -> "reject_wrong_role"
        ClosureDefect.WRONG_SIGNER -> "reject_wrong_signer"
        ClosureDefect.FORGED -> "reject_forged"
        ClosureDefect.NOT_FINAL -> "reject_not_final"
        ClosureDefect.CONFLICT -> "reject_conflict"
    }

    fun kindOutcome(kind: ClosureKind): String = when (kind) {
        ClosureKind.GOODS_RECEIVED -> "goods"
        ClosureKind.PAYMENT_RECEIVED -> "payment"
    }

    fun confirmLabel(kind: ClosureKind): String = when (kind) {
        ClosureKind.GOODS_RECEIVED -> "Confirm goods or service received"
        ClosureKind.PAYMENT_RECEIVED -> "Confirm payment received"
    }

    fun headline(projection: ClosureProjection, proven: Boolean, integrityFailure: Boolean): String {
        if (integrityFailure) return "Closure status does not verify. The agreement is not treated as closed."
        if (!proven) return ""
        if (!projection.rolesBound || projection.state == ClosureState.UNAVAILABLE) {
            return "Closure needs the signed roles Buyer and Supplier. This agreement stays open."
        }
        return when (projection.state) {
            ClosureState.AWAITING_PAYMENT ->
                "Open. Buyer confirmed goods received. Waiting for the supplier to confirm payment."
            ClosureState.AWAITING_GOODS ->
                "Open. Supplier confirmed payment received. Waiting for the buyer to confirm goods."
            ClosureState.CLOSED -> "Closed. Both confirmations are recorded."
            ClosureState.OPEN -> "Open. Neither party has confirmed closure."
            ClosureState.UNAVAILABLE ->
                "Closure needs the signed roles Buyer and Supplier. This agreement stays open."
        }
    }
}
