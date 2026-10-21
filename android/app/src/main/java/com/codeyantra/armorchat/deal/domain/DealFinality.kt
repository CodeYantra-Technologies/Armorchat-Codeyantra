package com.codeyantra.armorchat.deal.domain

import com.codeyantra.armorchat.deal.crypto.DealSignatures

/**
 * Finality comes only from two distinct SIGN signatures over one canonical
 * document. A stored status is not an input.
 */
enum class FinalityDefect {
    MISSING_SIGNATURE,
    DUPLICATE_SIGNER,
    INVALID_SIGNATURE,
    MISMATCHED_KEY,
    DIFFERENT_HASH,
    WRONG_DEAL,
    WRONG_VERSION,
    INCONSISTENT_VERSION,
}

class FinalityVerdict private constructor(
    val proven: Boolean,
    val defect: FinalityDefect?,
) {
    companion object {
        fun proven(): FinalityVerdict = FinalityVerdict(true, null)

        fun failed(defect: FinalityDefect): FinalityVerdict = FinalityVerdict(false, defect)
    }
}

object DealFinality {
    fun assess(
        deal: CanonicalDeal,
        signatures: List<DealSignature>,
        pinnedSigningKeys: Map<String, ByteArray>,
        storedVersionId: String = deal.versionId,
        storedDocumentSha256: ByteArray = deal.documentHash(),
    ): FinalityVerdict {
        if (storedVersionId != deal.versionId) return FinalityVerdict.failed(FinalityDefect.INCONSISTENT_VERSION)
        if (storedDocumentSha256.size != 32 || !storedDocumentSha256.contentEquals(deal.documentHash())) {
            return FinalityVerdict.failed(FinalityDefect.DIFFERENT_HASH)
        }
        if (signatures.any { it.dealId != deal.dealId }) return FinalityVerdict.failed(FinalityDefect.WRONG_DEAL)
        if (signatures.any { it.versionId != deal.versionId }) return FinalityVerdict.failed(FinalityDefect.WRONG_VERSION)
        if (signatures.any { it.action != SignAction.SIGN }) return FinalityVerdict.failed(FinalityDefect.INVALID_SIGNATURE)
        if (signatures.size != 2) return FinalityVerdict.failed(FinalityDefect.MISSING_SIGNATURE)
        if (signatures.map { it.partyId }.distinct().size != 2) {
            return FinalityVerdict.failed(FinalityDefect.DUPLICATE_SIGNER)
        }
        val expectedParties = deal.parties.map { it.partyId }.toSet()
        if (signatures.map { it.partyId }.toSet() != expectedParties) {
            return FinalityVerdict.failed(FinalityDefect.MISSING_SIGNATURE)
        }
        for (signature in signatures) {
            val documented = deal.party(signature.partyId).signingPublicKey
            val pinned = pinnedSigningKeys[signature.partyId]
            if (pinned == null ||
                pinned.size != documented.size ||
                !pinned.contentEquals(documented) ||
                !signature.signerPublicKey.contentEquals(documented)
            ) {
                return FinalityVerdict.failed(FinalityDefect.MISMATCHED_KEY)
            }
            try {
                DealSignatures.verify(
                    deal = deal,
                    claimed = signature,
                    expectedAction = SignAction.SIGN,
                    expectedDealId = deal.dealId,
                    expectedVersionId = deal.versionId,
                    pinnedPublicKey = pinned,
                )
            } catch (e: DealCryptoException.WrongSigner) {
                return FinalityVerdict.failed(FinalityDefect.MISMATCHED_KEY)
            } catch (e: DealCryptoException.WrongVersion) {
                return FinalityVerdict.failed(FinalityDefect.WRONG_VERSION)
            } catch (e: DealCryptoException.WrongDeal) {
                return FinalityVerdict.failed(FinalityDefect.WRONG_DEAL)
            } catch (e: DealCryptoException) {
                return FinalityVerdict.failed(FinalityDefect.INVALID_SIGNATURE)
            }
        }
        return FinalityVerdict.proven()
    }
}
