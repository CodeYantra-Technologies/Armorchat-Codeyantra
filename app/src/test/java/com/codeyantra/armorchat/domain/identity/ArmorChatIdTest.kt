package com.codeyantra.armorchat.domain.identity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArmorChatIdTest {

    private val samplePublicKey = ByteArray(32) { it.toByte() }

    @Test
    fun derive_isDeterministic() {
        val a = ArmorChatId.derive(samplePublicKey)
        val b = ArmorChatId.derive(samplePublicKey)
        assertThat(a).isEqualTo(b)
        assertThat(a).startsWith("ac1:")
        assertThat(a.removePrefix("ac1:")).hasLength(32)
    }

    @Test
    fun derive_changesWithPublicKey() {
        val other = samplePublicKey.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThat(ArmorChatId.derive(samplePublicKey))
            .isNotEqualTo(ArmorChatId.derive(other))
    }

    @Test
    fun parse_roundTrip() {
        val id = ArmorChatId.derive(samplePublicKey)
        val parsed = ArmorChatId.parse(id).getOrThrow()
        assertThat(parsed.id).isEqualTo(id)
        assertThat(parsed.fingerprint).hasLength(16)
        assertThat(parsed.fingerprint)
            .isEqualTo(ArmorChatId.fingerprintOf(samplePublicKey))
    }

    @Test
    fun parse_rejectsInvalidPrefix() {
        val result = ArmorChatId.parse("xx1:0123456789ABCDEFGHJKMNPQRSTVWXYZ")
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun parse_rejectsWrongLength() {
        val result = ArmorChatId.parse("ac1:SHORT")
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun parse_rejectsTamperedChecksum() {
        val id = ArmorChatId.derive(samplePublicKey)
        val body = id.removePrefix("ac1:").toCharArray()
        // Flip last Crockford character to break CRC while keeping length.
        body[body.lastIndex] = if (body[body.lastIndex] == '0') '1' else '0'
        val tampered = "ac1:" + String(body)
        val result = ArmorChatId.parse(tampered)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun derive_rejectsWrongKeyLength() {
        try {
            ArmorChatId.derive(ByteArray(31))
            throw AssertionError("expected require failure")
        } catch (e: IllegalArgumentException) {
            assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
