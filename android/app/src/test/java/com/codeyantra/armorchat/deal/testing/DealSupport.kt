package com.codeyantra.armorchat.deal.testing

import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealKeyProtector
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.crypto.PartyWrapTarget
import com.codeyantra.armorchat.deal.crypto.SealedDocument
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealKeyException
import com.codeyantra.armorchat.deal.domain.DealParty
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun hexToBytes(hex: String): ByteArray =
    hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal class SignedFixture(
    val deal: CanonicalDeal,
    val partyA: String,
    val partyB: String,
    val keysA: DealKeyMaterial,
    val keysB: DealKeyMaterial,
    val signatures: List<DealSignature>,
    val sealed: SealedDocument,
)

internal fun signedFixture(
    terms: String = "Rent",
    currency: String? = null,
    amountMinor: Long? = null,
    createdAtEpochMs: Long = 1_700_000_000_000,
): SignedFixture {
    val keysA = DealKeyMaterial.generate()
    val keysB = DealKeyMaterial.generate()
    val partyA = "a".repeat(22)
    val partyB = "b".repeat(22)
    val deal = CanonicalDeal.create(
        dealId = "d".repeat(22),
        versionId = "v".repeat(22),
        parentVersionId = null,
        createdAtEpochMs = createdAtEpochMs,
        currency = currency,
        amountMinor = amountMinor,
        parties = listOf(
            DealParty(partyB, PartyRole.COUNTERPARTY, "B", keysB.signingPublic, keysB.encryptionPublic),
            DealParty(partyA, PartyRole.PROPOSER, "A", keysA.signingPublic, keysA.encryptionPublic),
        ),
        ancestry = emptyList(),
        terms = terms,
    )
    val signatures = listOf(
        DealSignatures.sign(keysA.signingHandle(), deal, partyA, SignAction.SIGN),
        DealSignatures.sign(keysB.signingHandle(), deal, partyB, SignAction.SIGN),
    )
    val sealed = DealCipher.encrypt(
        dealId = deal.dealId,
        versionId = deal.versionId,
        canonical = deal.canonicalBytes(),
        recipients = listOf(
            PartyWrapTarget(partyA, keysA.encryptionPublicHandle()),
            PartyWrapTarget(partyB, keysB.encryptionPublicHandle()),
        ),
    )
    return SignedFixture(deal, partyA, partyB, keysA, keysB, signatures, sealed)
}

/** Test-only AES-256-GCM protector. Not used by the app process. */
internal class SoftwareDealKeyProtector(seed: ByteArray) : DealKeyProtector {
    private val key = seed.copyOf()
    private val random = SecureRandom()

    override fun seal(plaintext: ByteArray, purpose: String): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return byteArrayOf(1) + nonce + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, purpose: String): ByteArray {
        if (sealed.size < 1 + 12 + 16 || sealed[0] != 1.toByte()) throw DealCryptoException.AuthenticationFailed()
        return try {
            val nonce = sealed.copyOfRange(1, 13)
            val ciphertext = sealed.copyOfRange(13, sealed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext)
        } catch (e: DealCryptoException) {
            throw e
        } catch (e: Exception) {
            throw DealCryptoException.AuthenticationFailed()
        }
    }
}

internal class RevokedDealKeyProtector : DealKeyProtector {
    override fun seal(plaintext: ByteArray, purpose: String): ByteArray = throw DealKeyException.RevokedDeviceAccess()
    override fun open(sealed: ByteArray, purpose: String): ByteArray = throw DealKeyException.RevokedDeviceAccess()
}
