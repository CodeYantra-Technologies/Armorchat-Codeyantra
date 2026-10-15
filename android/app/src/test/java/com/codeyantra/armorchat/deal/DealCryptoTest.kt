package com.codeyantra.armorchat.deal

import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.crypto.SealedDocument
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.testing.hexToBytes
import com.codeyantra.armorchat.deal.testing.signedFixture
import com.codeyantra.armorchat.deal.testing.toHex
import com.google.crypto.tink.subtle.Ed25519Sign
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DealCryptoTest {
    @Test
    fun signaturePayloadMatchesThePinnedCrossLanguageVector() {
        val payload = DealSignatures.payloadBytes(
            action = SignAction.SIGN,
            dealId = "d".repeat(22),
            versionId = "v".repeat(22),
            partyId = "a".repeat(22),
            publicKey = hexToBytes("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
            documentHash = hexToBytes("114207667dcdef469408d06d695c98d5b252947732e81b49a1867030914fee95"),
        )
        assertEquals(PAYLOAD, payload.toHex())
        val signature = Ed25519Sign(hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")).sign(payload)
        assertEquals(SIGNATURE, signature.toHex())
    }

    @Test
    fun validSignatureRejectsTamperWrongSignerWrongVersionAndWrongAction() {
        val fixture = signedFixture(terms = "UNIQUE_TERMS_MARKER_9421")
        val signature = fixture.signatures.first { it.partyId == fixture.partyA }
        DealSignatures.verify(
            fixture.deal, signature, SignAction.SIGN, fixture.deal.dealId, fixture.deal.versionId,
            fixture.keysA.signingPublic,
        )
        val tampered = com.codeyantra.armorchat.deal.domain.CanonicalDeal.create(
            dealId = fixture.deal.dealId,
            versionId = fixture.deal.versionId,
            parentVersionId = null,
            createdAtEpochMs = fixture.deal.createdAtEpochMs,
            currency = null,
            amountMinor = null,
            parties = fixture.deal.parties,
            ancestry = emptyList(),
            terms = "UNIQUE_TERMS_MARKER_9421!",
        )
        assertThrows(DealCryptoException.InvalidSignature::class.java) {
            DealSignatures.verify(
                tampered, signature, SignAction.SIGN, tampered.dealId, tampered.versionId, fixture.keysA.signingPublic,
            )
        }
        assertThrows(DealCryptoException.WrongSigner::class.java) {
            DealSignatures.verify(
                fixture.deal, signature, SignAction.SIGN, fixture.deal.dealId, fixture.deal.versionId,
                fixture.keysB.signingPublic,
            )
        }
        assertThrows(DealCryptoException.WrongVersion::class.java) {
            DealSignatures.verify(
                fixture.deal, signature, SignAction.SIGN, fixture.deal.dealId, "w".repeat(22), fixture.keysA.signingPublic,
            )
        }
        assertThrows(DealCryptoException.WrongAction::class.java) {
            DealSignatures.verify(
                fixture.deal, signature, SignAction.REJECT, fixture.deal.dealId, fixture.deal.versionId,
                fixture.keysA.signingPublic,
            )
        }
        assertThrows(DealCryptoException.WrongDeal::class.java) {
            DealSignatures.verify(
                fixture.deal, signature, SignAction.SIGN, "e".repeat(22), fixture.deal.versionId, fixture.keysA.signingPublic,
            )
        }
    }

    @Test
    fun aesGcmRoundTripUsesOneDocumentEncryptionAndRejectsTagTampering() {
        val fixture = signedFixture()
        val plainA = DealCipher.decrypt(fixture.sealed, fixture.partyA, fixture.keysA.encryptionHandle())
        val plainB = DealCipher.decrypt(fixture.sealed, fixture.partyB, fixture.keysB.encryptionHandle())
        assertArrayEquals(fixture.deal.canonicalBytes(), plainA)
        assertArrayEquals(plainA, plainB)
        assertEquals(2, fixture.sealed.envelopes.size)
        assertFalse(fixture.sealed.envelopes[0].wrappedDek.contentEquals(fixture.sealed.envelopes[1].wrappedDek))
        val again = DealCipher.encrypt(
            fixture.deal.dealId,
            fixture.deal.versionId,
            fixture.deal.canonicalBytes(),
            listOf(
                com.codeyantra.armorchat.deal.crypto.PartyWrapTarget(fixture.partyA, fixture.keysA.encryptionPublicHandle()),
                com.codeyantra.armorchat.deal.crypto.PartyWrapTarget(fixture.partyB, fixture.keysB.encryptionPublicHandle()),
            ),
        )
        assertFalse(fixture.sealed.nonce.contentEquals(again.nonce))
        val flipped = fixture.sealed.ciphertext.copyOf()
        flipped[flipped.lastIndex] = (flipped[flipped.lastIndex].toInt() xor 0x01).toByte()
        val tampered = SealedDocument(
            fixture.sealed.dealId,
            fixture.sealed.versionId,
            fixture.sealed.nonce,
            flipped,
            fixture.sealed.envelopes,
        )
        val error = assertThrows(DealCryptoException.AuthenticationFailed::class.java) {
            DealCipher.decrypt(tampered, fixture.partyA, fixture.keysA.encryptionHandle())
        }
        assertFalse(error.message.orEmpty().contains("Rent"))
        assertThrows(DealCryptoException.AuthenticationFailed::class.java) {
            DealCipher.decrypt(fixture.sealed, fixture.partyA, fixture.keysB.encryptionHandle())
        }
    }

    companion object {
        private const val PAYLOAD =
            "41726d6f724465616c2f7369676e2f76310000045349474e00010016646464646464646464646464646464646464646464640016767676767676767676767676767676767676767676760016616161616161616161616161616161616161616161610020d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a0020114207667dcdef469408d06d695c98d5b252947732e81b49a1867030914fee95"
        private const val SIGNATURE =
            "0645b3927bfdf047fd9c818c673150a1c973a522a5f93c7c474e27158464b7fe938abb163b69ed7ab34e8f9c587dcd9acf83e07a014ae5ac99131bf66aa7f305"
    }
}
