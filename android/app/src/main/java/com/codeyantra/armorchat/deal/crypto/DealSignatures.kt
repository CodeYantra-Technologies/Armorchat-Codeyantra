package com.codeyantra.armorchat.deal.crypto

import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.domain.ByteArrayOutput
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.SignAction
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException

/**
 * Ed25519 over a domain-separated payload. The payload binds the canonical
 * document hash, deal, version, party, protocol, and action. Verification uses
 * the public key pinned inside the canonical document.
 */
object DealSignatures {
    fun sign(signingKey: KeysetHandle, deal: CanonicalDeal, partyId: String, action: SignAction): DealSignature {
        DealTink.register()
        if (deal.identity != null) DealIdentity.requireSignable(deal, partyId)
        val party = deal.party(partyId)
        val payload = payloadBytes(
            action = action,
            dealId = deal.dealId,
            versionId = deal.versionId,
            partyId = partyId,
            publicKey = party.signingPublicKey,
            documentHash = deal.documentHash(),
        )
        val signature = RawIdentitySignature.sign(signingKey, payload)
        return DealSignature(
            dealId = deal.dealId,
            versionId = deal.versionId,
            partyId = partyId,
            action = action,
            signature = signature,
            signerPublicKey = party.signingPublicKey.copyOf(),
        )
    }

    fun verify(
        deal: CanonicalDeal,
        claimed: DealSignature,
        expectedAction: SignAction,
        expectedDealId: String,
        expectedVersionId: String,
        pinnedPublicKey: ByteArray,
    ) {
        if (expectedDealId != deal.dealId || claimed.dealId != deal.dealId) {
            throw DealCryptoException.WrongDeal()
        }
        if (expectedVersionId != deal.versionId || claimed.versionId != deal.versionId) {
            throw DealCryptoException.WrongVersion()
        }
        if (expectedAction != claimed.action) throw DealCryptoException.WrongAction()
        if (deal.identity != null) {
            try {
                DealIdentity.requireSignable(deal, claimed.partyId)
            } catch (e: DealStateException) {
                throw DealCryptoException.InvalidSignature()
            }
        }
        val party = try {
            deal.party(claimed.partyId)
        } catch (e: DealCryptoException) {
            throw DealCryptoException.WrongSigner()
        }
        if (!pinnedPublicKey.contentEquals(party.signingPublicKey) ||
            !claimed.signerPublicKey.contentEquals(party.signingPublicKey)
        ) {
            throw DealCryptoException.WrongSigner()
        }
        val payload = payloadBytes(
            action = claimed.action,
            dealId = deal.dealId,
            versionId = deal.versionId,
            partyId = claimed.partyId,
            publicKey = party.signingPublicKey,
            documentHash = deal.documentHash(),
        )
        try {
            Ed25519Verify(party.signingPublicKey).verify(claimed.signature, payload)
        } catch (e: GeneralSecurityException) {
            throw DealCryptoException.InvalidSignature()
        } catch (e: IllegalArgumentException) {
            throw DealCryptoException.InvalidSignature()
        }
    }

    fun payloadBytes(
        action: SignAction,
        dealId: String,
        versionId: String,
        partyId: String,
        publicKey: ByteArray,
        documentHash: ByteArray,
    ): ByteArray {
        if (publicKey.size != 32 || documentHash.size != 32) {
            throw DealFormatException("Signature payload key material is the wrong length.")
        }
        val out = ByteArrayOutput()
        out.raw("ArmorDeal/sign/v1".toByteArray(Charsets.UTF_8))
        out.u8(0)
        out.lp(action.wire)
        out.u16(DealFeature.PROTOCOL_VERSION)
        out.lp(dealId)
        out.lp(versionId)
        out.lp(partyId)
        out.u16(publicKey.size)
        out.raw(publicKey)
        out.u16(documentHash.size)
        out.raw(documentHash)
        return out.toByteArray()
    }
}
