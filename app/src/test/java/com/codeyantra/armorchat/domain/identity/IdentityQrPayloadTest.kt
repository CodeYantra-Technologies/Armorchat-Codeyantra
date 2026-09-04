package com.codeyantra.armorchat.domain.identity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IdentityQrPayloadTest {

    private val publicKey = ByteArray(32) { (31 - it).toByte() }

    @Test
    fun serializeParse_roundTrip_andBinding() {
        val payload = IdentityQrPayload.fromPublicKey(publicKey)
        val json = IdentityQrPayload.serialize(payload)
        assertThat(json).contains("\"p\":\"armorchat\"")
        assertThat(json).contains("\"v\":1")
        assertThat(json).doesNotContain("sk")
        assertThat(json).doesNotContain("private")

        val parsed = IdentityQrPayload.parse(json).getOrThrow()
        assertThat(parsed.id).isEqualTo(payload.id)
        assertThat(parsed.publicKeyBase64Url).isEqualTo(payload.publicKeyBase64Url)
    }

    @Test
    fun parse_rejectsBindingMismatch() {
        val payload = IdentityQrPayload.fromPublicKey(publicKey)
        val otherKey = publicKey.copyOf().also { it[5] = (it[5] + 1).toByte() }
        val wrongId = ArmorChatId.derive(otherKey)
        val badJson =
            """{"p":"armorchat","v":1,"id":"$wrongId","pk":"${payload.publicKeyBase64Url}"}"""
        val result = IdentityQrPayload.parse(badJson)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("binding")
    }

    @Test
    fun parse_rejectsWrongProduct() {
        val payload = IdentityQrPayload.fromPublicKey(publicKey)
        val json = IdentityQrPayload.serialize(payload).replace("armorchat", "other")
        assertThat(IdentityQrPayload.parse(json).isFailure).isTrue()
    }

    @Test
    fun parse_rejectsInvalidPublicKeyLength() {
        val id = ArmorChatId.derive(publicKey)
        val shortPk = Base64Url.encode(ByteArray(16))
        val json = """{"p":"armorchat","v":1,"id":"$id","pk":"$shortPk"}"""
        assertThat(IdentityQrPayload.parse(json).isFailure).isTrue()
    }
}
