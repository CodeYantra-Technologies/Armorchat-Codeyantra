package com.codeyantra.armorchat.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Host JNI harness. Message type is whatever vodozemac returned; Kotlin never infers it. */
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class VodozemacFeasibilityTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun requireNative() {
            try {
                VodozemacFeasibility.transact(ByteArray(0), """{"op":"Create"}""")
            } catch (_: UnsatisfiedLinkError) {
                Assume.assumeTrue("libarmorchat_vodozemac not on java.library.path", false)
            } catch (_: ExceptionInInitializerError) {
                Assume.assumeTrue("libarmorchat_vodozemac not on java.library.path", false)
            } catch (_: NoClassDefFoundError) {
                Assume.assumeTrue("libarmorchat_vodozemac not on java.library.path", false)
            }
        }
    }

    private fun b64(bytes: ByteArray) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private class Peer(seed: Int) {
        var state = byteArrayOf()
            private set
        val credential: String
        val pin: String

        init {
            SignatureConfig.register()
            val identity = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
            val pub = (identity.publicKeysetHandle.primary.key as Ed25519PublicKey)
                .publicKeyBytes.toByteArray()
            pin = Base64.getUrlEncoder().withoutPadding().encodeToString(pub)
            call("""{"op":"Create"}""")
            val body = call(
                JSONObject()
                    .put("op", "DeviceBody")
                    .put("identity", pin)
                    .put("device_id", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(ByteArray(16) { seed.toByte() }))
                    .put("generation", 1)
                    .put("now", 1000)
                    .toString(),
            )
            val bytes = Base64.getUrlDecoder().decode(body.getString("signing_bytes"))
            val signature = RawIdentitySignature.sign(identity, bytes)
            Ed25519Verify(pub).verify(signature, bytes)
            credential = JSONObject()
                .put("body", body.getJSONObject("body"))
                .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
                .toString()
            call(
                JSONObject()
                    .put("op", "Bind")
                    .put("credential", credential)
                    .put("pin", pin)
                    .put("now", 1000)
                    .toString(),
            )
        }

        fun call(request: String): JSONObject {
            val result = JSONObject(
                String(VodozemacFeasibility.transact(state, request), StandardCharsets.UTF_8),
            )
            state = result.getString("state").toByteArray(StandardCharsets.UTF_8)
            return result.getJSONObject("output")
        }

        fun reject(request: String) {
            val before = state.copyOf()
            assertThrows(SecurityException::class.java) { call(request) }
            assertArrayEquals(before, state)
        }
    }

    private fun outbound(a: Peer, b: Peer, pin: String = b.pin, credential: String = b.credential, prekey: String? = null): String {
        val pre = prekey ?: b.call("""{"op":"Prekey","now":1000}""").getString("prekey")
        return JSONObject()
            .put("op", "Outbound")
            .put("credential", credential)
            .put("prekey", pre)
            .put("pin", pin)
            .put("now", 1000)
            .toString()
    }

    private fun inbound(a: Peer, packet: JSONObject, pin: String = a.pin) = JSONObject()
        .put("op", "Inbound")
        .put("credential", a.credential)
        .put("pin", pin)
        .put("now", 1000)
        .put("packet", packet)
        .toString()

    @Test
    fun encryptDecryptHelloArmorChatRoundTrip() {
        val a = Peer(1)
        val b = Peer(2)
        a.call(outbound(a, b))
        val packet = a.call("""{"op":"Encrypt","plaintext":"Hello ArmorChat"}""").getJSONObject("packet")
        assertEquals("library prekey type must come from vodozemac", 0, packet.getInt("message_type"))
        assertEquals(
            "Hello ArmorChat",
            b.call(inbound(a, packet)).getString("plaintext"),
        )
        val reply = b.call("""{"op":"Encrypt","plaintext":"Hello Alice"}""").getJSONObject("packet")
        assertEquals("library normal type must come from vodozemac", 1, reply.getInt("message_type"))
        assertEquals(
            "Hello Alice",
            a.call(JSONObject().put("op", "Decrypt").put("packet", reply).toString()).getString("plaintext"),
        )
    }

    @Test
    fun wrongPinnedArmorChatIdentityIsRejected() {
        val a = Peer(1)
        val b = Peer(2)
        val pre = b.call("""{"op":"Prekey","now":1000}""").getString("prekey")
        a.reject(outbound(a, b, pin = a.pin, prekey = pre))
        a.call(outbound(a, b, prekey = pre))
        val packet = a.call("""{"op":"Encrypt","plaintext":"Hello ArmorChat"}""").getJSONObject("packet")
        b.reject(inbound(a, packet, pin = b.pin))
    }

    @Test
    fun modifiedDeviceCredentialIsRejected() {
        val a = Peer(1)
        val b = Peer(2)
        val pre = b.call("""{"op":"Prekey","now":1000}""").getString("prekey")
        for (field in listOf("device_agreement_key", "device_signing_key", "device_id", "generation")) {
            val credential = JSONObject(b.credential)
            val body = credential.getJSONObject("body")
            when (field) {
                "generation" -> body.put("generation", 2)
                "device_id" -> body.put("device_id", b64(ByteArray(16) { 3 }))
                else -> body.put(field, a.pin)
            }
            a.reject(outbound(a, b, credential = credential.toString(), prekey = pre))
        }
    }

    @Test
    fun modifiedPrekeyIsRejected() {
        val a = Peer(1)
        val b = Peer(2)
        val pre = JSONObject(b.call("""{"op":"Prekey","now":1000}""").getString("prekey"))
        pre.getJSONObject("body").put("prekey_public", a.pin)
        a.reject(outbound(a, b, prekey = pre.toString()))
    }

    @Test
    fun tamperedCiphertextAndReplayAreRejected() {
        val a = Peer(1)
        val b = Peer(2)
        a.call(outbound(a, b))
        val packet = a.call("""{"op":"Encrypt","plaintext":"Hello ArmorChat"}""").getJSONObject("packet")
        val tampered = JSONObject(packet.toString())
        val bytes = Base64.getUrlDecoder().decode(tampered.getString("ciphertext"))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        tampered.put("ciphertext", b64(bytes))
        b.reject(inbound(a, tampered))
        val wrongType = JSONObject(packet.toString()).put("message_type", 1)
        b.reject(inbound(a, wrongType))
        assertEquals("Hello ArmorChat", b.call(inbound(a, packet)).getString("plaintext"))
        b.reject(inbound(a, packet))
        b.reject(JSONObject().put("op", "Decrypt").put("packet", packet).toString())
        val reply = b.call("""{"op":"Encrypt","plaintext":"Hello Alice"}""").getJSONObject("packet")
        val replyBytes = Base64.getUrlDecoder().decode(reply.getString("ciphertext"))
        replyBytes[replyBytes.lastIndex] = (replyBytes.last().toInt() xor 1).toByte()
        val badReply = JSONObject(reply.toString()).put("ciphertext", b64(replyBytes))
        a.reject(JSONObject().put("op", "Decrypt").put("packet", badReply).toString())
        assertEquals(
            "Hello Alice",
            a.call(JSONObject().put("op", "Decrypt").put("packet", reply).toString()).getString("plaintext"),
        )
        a.reject(JSONObject().put("op", "Decrypt").put("packet", reply).toString())
    }

    @Test
    fun rawTinkRfc8032Vector() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val expected = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertArrayEquals(expected, Ed25519Sign(seed).sign(byteArrayOf()))
    }
}
