package com.codeyantra.armorchat.domain.identity

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ContactQrSecurityTest {
    private val key = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0).generatePublicKey().encoded
    private val payload = IdentityQrPayload.fromPublicKey(key)
    private val json = IdentityQrPayload.serialize(payload)
    private fun rejects(text: String) = assertTrue(IdentityQrPayload.parse(text).isFailure)

    @Test fun validPublicIdentityAccepted() { assertEquals(payload, IdentityQrPayload.parse(json).getOrThrow()) }
    @Test fun malformedJsonRejected() {
        listOf(json.dropLast(1), json.replace(",", " "), json + "trailing", "[$json]", "", "null",
            json.replace("\"v\":1", "\"v\":1.0"), json.replace("\"v\":1", "\"v\":1e0"),
            json.replace("\"v\":1", "\"v\":01"), json.replace("\"p\":", "\"p\" ")).forEach(::rejects)
    }
    @Test fun wrongProtocolRejected() { rejects(json.replace("armorchat", "other")) }
    @Test fun unsupportedVersionsRejected() {
        listOf("0", "2", "999999999999999999999", "-1", "\"1\"", "null").forEach {
            rejects(json.replace("\"v\":1", "\"v\":$it"))
        }
    }
    @Test fun invalidBase64UrlRejected() {
        listOf(payload.publicKeyBase64Url + "=", " " + payload.publicKeyBase64Url,
            "+" + payload.publicKeyBase64Url.drop(1), "/" + payload.publicKeyBase64Url.drop(1),
            "!".repeat(43)).forEach { rejects(json.replace(payload.publicKeyBase64Url, it)) }
    }
    @Test fun noncanonicalPaddingBitsRejected() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val pk = payload.publicKeyBase64Url
        val changed = pk.dropLast(1) + alphabet[alphabet.indexOf(pk.last()) + 1]
        assertArrayEquals(key, Base64Url.decode(changed))
        rejects(json.replace(pk, changed))
    }
    @Test fun wrongKeyLengthsRejected() {
        listOf(0, 1, 16, 31, 33, 64).forEach { rejects(json.replace(payload.publicKeyBase64Url, Base64Url.encode(ByteArray(it)))) }
    }
    @Test fun sameIdDifferentKeyRejected() {
        val other = Ed25519PrivateKeyParameters(ByteArray(32) { 99 }, 0).generatePublicKey().encoded
        rejects(json.replace(payload.publicKeyBase64Url, Base64Url.encode(other)))
    }
    @Test fun corruptedChecksumRejected() {
        val bad = payload.id.dropLast(1) + if (payload.id.last() == '0') "1" else "0"
        rejects(json.replace(payload.id, bad))
    }
    @Test fun invalidCurvePointsRejectedEvenWithMatchingId() {
        listOf(ByteArray(32), ByteArray(32).also { it[0] = 1 }, ByteArray(32) { -1 }).forEach {
            rejects(IdentityQrPayload.serialize(IdentityQrPayload.fromPublicKey(it)))
        }
    }
    @Test fun oversizedPayloadRejected() {
        rejects(" ".repeat(1025) + json)
        rejects(json.dropLast(1) + ",\"extra\":\"" + "x".repeat(2000) + "\"}")
        rejects("é".repeat(600))
    }
    @Test fun duplicateNestedAndUnknownFieldsRejected() {
        rejects(json.replace("\"v\":1", "\"v\":1,\"v\":1"))
        rejects("{\"nested\":$json}")
        rejects(json.dropLast(1) + ",\"privateKey\":\"secret\"}")
        rejects(json.replace("\"p\":\"armorchat\"", "\"p\":{\"p\":\"armorchat\"}"))
    }
    @Test fun reorderedFieldsAndJsonEscapesAccepted() {
        val alternate = """{ "pk":"${payload.publicKeyBase64Url}", "id":"${payload.id}", "v":1, "p":"armorchat" }"""
        assertEquals(payload, IdentityQrPayload.parse(alternate).getOrThrow())
        assertEquals(payload, IdentityQrPayload.parse(json.replace("armorchat", "armor" + "\\u0063" + "hat")).getOrThrow())
    }
    @Test fun parserRequiresNoPrivateKeyOrAndroidServices() {
        // Pure JVM execution: no Android runtime, Keystore, repository, network or private-key handle.
        val publicOnly = """{"p":"armorchat","v":1,"id":"${payload.id}","pk":"${payload.publicKeyBase64Url}"}"""
        assertTrue(IdentityQrPayload.parse(publicOnly).isSuccess)
    }
    @Test fun malformedFuzzDoesNotThrowOrAcceptGarbage() {
        val random = Random(1749)
        repeat(3000) {
            val garbage = buildString { repeat(random.nextInt(0, 1500)) { append(random.nextInt(0, 128).toChar()) } }
            rejects(garbage)
        }
        repeat(500) {
            val pos = random.nextInt(json.length)
            val mutated = json.substring(0, pos) + '\u0000' + json.substring(pos)
            rejects(mutated)
        }
    }
}
