package com.codeyantra.armorchat.deal

import android.os.Process
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codeyantra.armorchat.deal.crypto.AndroidKeystoreDealKeyProtector
import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.crypto.PartyWrapTarget
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.DealKeyException
import com.codeyantra.armorchat.deal.domain.DealParty
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.recovery.DealRecovery
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Debug instrumentation for the Armor Deal device key.
 *
 * Do not run until the owner approves an instrumentation install. This class
 * does not uninstall the app, clear app data, reboot the device, or open
 * armorchat.db. It never deletes the messaging Keystore aliases. It deletes
 * only [AndroidKeystoreDealKeyProtector.PROBE_ALIAS], and only when this test
 * created that entry. A device reboot is not part of this harness.
 *
 * Process restart evidence requires two instrumentations separated by
 * force-stop: prepareRestart, then resumeAfterRestart. One JUnit process is
 * not that evidence.
 */
@RunWith(AndroidJUnit4::class)
class DealKeystoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val checkpoint = File(context.noBackupFilesDir, "deal-keystore-probe.json")

    @Test
    fun prepareRestart() {
        assertFalse(DealFeature.PRODUCTION_UI_ENABLED)
        val before = messagingPresence()
        val provider = keystore()
        assertEquals("AndroidKeyStore", provider.provider.name)
        assertEquals("AES/GCM/NoPadding", Cipher.getInstance("AES/GCM/NoPadding").algorithm)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")

        val protector = AndroidKeystoreDealKeyProtector()
        val plaintext = "synthetic-deal-keystore-probe".toByteArray(Charsets.UTF_8)
        val sealed = protector.seal(plaintext, PURPOSE)
        assertTrue(sealed.copyOfRange(0, 4).contentEquals(byteArrayOf(0x41, 0x44, 0x4b, 0x53)))
        assertArrayEquals(plaintext, protector.open(sealed, PURPOSE))
        val again = protector.seal(plaintext, PURPOSE)
        assertFalse(sealed.contentEquals(again))
        val storedKey = keystore().getKey(AndroidKeystoreDealKeyProtector.ALIAS, null) as SecretKey
        assertNull(storedKey.encoded)
        assertThrows(DealKeyException.RevokedDeviceAccess::class.java) {
            protector.open(sealed, "other-purpose")
        }
        val intact = sealed.copyOf()
        val flipped = sealed.copyOf()
        flipped[flipped.lastIndex] = (flipped[flipped.lastIndex].toInt() xor 0x01).toByte()
        assertThrows(DealKeyException.RevokedDeviceAccess::class.java) {
            protector.open(flipped, PURPOSE)
        }
        assertTrue(intact.contentEquals(sealed))
        rejectWrongKeyAndDeleteOnlyTheProbe(protector)
        assertRecoveryPackageOmitsTheDeviceKey()
        assertMessagingUntouched(before)
        assertTrue(keystore().containsAlias(AndroidKeystoreDealKeyProtector.ALIAS))

        checkpoint.writeText(
            JSONObject()
                .put("pid", Process.myPid())
                .put("sealed", Base64.getUrlEncoder().withoutPadding().encodeToString(sealed))
                .put("identity", before.getValue(IDENTITY_ALIAS))
                .put("relay", before.getValue(RELAY_ALIAS))
                .put("session", before.getValue(SESSION_ALIAS))
                .toString(),
        )
    }

    @Test
    fun resumeAfterRestart() {
        val saved = JSONObject(checkpoint.readText())
        assertNotEquals(saved.getInt("pid"), Process.myPid())
        val sealed = Base64.getUrlDecoder().decode(saved.getString("sealed"))
        val opened = AndroidKeystoreDealKeyProtector().open(sealed, PURPOSE)
        assertEquals("synthetic-deal-keystore-probe", opened.toString(Charsets.UTF_8))
        assertEquals(saved.getBoolean("identity"), keystore().containsAlias(IDENTITY_ALIAS))
        assertEquals(saved.getBoolean("relay"), keystore().containsAlias(RELAY_ALIAS))
        assertEquals(saved.getBoolean("session"), keystore().containsAlias(SESSION_ALIAS))
        assertTrue(keystore().containsAlias(AndroidKeystoreDealKeyProtector.ALIAS))
        assertFalse(keystore().containsAlias(AndroidKeystoreDealKeyProtector.PROBE_ALIAS))
    }

    private fun rejectWrongKeyAndDeleteOnlyTheProbe(production: AndroidKeystoreDealKeyProtector) {
        if (keystore().containsAlias(AndroidKeystoreDealKeyProtector.PROBE_ALIAS)) {
            throw AssertionError("Refusing to delete a pre-existing deal probe alias.")
        }
        var created = false
        try {
            val probe = AndroidKeystoreDealKeyProtector(AndroidKeystoreDealKeyProtector.PROBE_ALIAS)
            val sealed = probe.seal("probe-only".toByteArray(Charsets.UTF_8), PURPOSE)
            created = true
            assertThrows(DealKeyException.RevokedDeviceAccess::class.java) {
                production.open(sealed, PURPOSE)
            }
            keystore().deleteEntry(AndroidKeystoreDealKeyProtector.PROBE_ALIAS)
            created = false
            assertFalse(keystore().containsAlias(AndroidKeystoreDealKeyProtector.PROBE_ALIAS))
            assertThrows(DealKeyException.RevokedDeviceAccess::class.java) {
                probe.open(sealed, PURPOSE)
            }
        } finally {
            if (created && keystore().containsAlias(AndroidKeystoreDealKeyProtector.PROBE_ALIAS)) {
                keystore().deleteEntry(AndroidKeystoreDealKeyProtector.PROBE_ALIAS)
            }
        }
    }

    private fun assertRecoveryPackageOmitsTheDeviceKey() {
        val keysA = DealKeyMaterial.generate()
        val keysB = DealKeyMaterial.generate()
        val partyA = "a".repeat(22)
        val partyB = "b".repeat(22)
        val deal = CanonicalDeal.create(
            dealId = "d".repeat(22),
            versionId = "v".repeat(22),
            parentVersionId = null,
            createdAtEpochMs = 1_700_000_000_000,
            currency = null,
            amountMinor = null,
            parties = listOf(
                DealParty(partyA, PartyRole.PROPOSER, "A", keysA.signingPublic, keysA.encryptionPublic),
                DealParty(partyB, PartyRole.COUNTERPARTY, "B", keysB.signingPublic, keysB.encryptionPublic),
            ),
            ancestry = emptyList(),
            terms = "synthetic phase 2a",
        )
        val signatures = listOf(
            DealSignatures.sign(keysA.signingHandle(), deal, partyA, SignAction.SIGN),
            DealSignatures.sign(keysB.signingHandle(), deal, partyB, SignAction.SIGN),
        )
        val document = DealCipher.encrypt(
            deal.dealId,
            deal.versionId,
            deal.canonicalBytes(),
            listOf(
                PartyWrapTarget(partyA, keysA.encryptionPublicHandle()),
                PartyWrapTarget(partyB, keysB.encryptionPublicHandle()),
            ),
        )
        val secret = ByteArray(32) { 9 }
        val packageBytes = DealRecovery().seal(partyA, secret, keysA, document, deal, signatures)
        assertTrue(packageBytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x41, 0x44, 0x52, 0x50)))
        assertFalse(packageBytes.toString(Charsets.ISO_8859_1).contains(AndroidKeystoreDealKeyProtector.ALIAS))
        val restored = DealRecovery().open(packageBytes, secret)
        assertEquals("synthetic phase 2a", restored.deal.terms)
        assertArrayEquals(keysA.signingPublic, restored.keys.signingPublic)
    }

    private fun messagingPresence(): Map<String, Boolean> = mapOf(
        IDENTITY_ALIAS to keystore().containsAlias(IDENTITY_ALIAS),
        RELAY_ALIAS to keystore().containsAlias(RELAY_ALIAS),
        SESSION_ALIAS to keystore().containsAlias(SESSION_ALIAS),
    )

    private fun assertMessagingUntouched(before: Map<String, Boolean>) {
        assertEquals(before, messagingPresence())
    }

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val PURPOSE = "deal-signing-keyset"
        private const val IDENTITY_ALIAS = "armorchat_identity_kek_v1"
        private const val RELAY_ALIAS = "armorchat_relay_credentials_kek_v1"
        private const val SESSION_ALIAS = "armorchat_session_storage_wrap_v1"
    }
}
