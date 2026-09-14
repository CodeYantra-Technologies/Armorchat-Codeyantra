package com.codeyantra.armorchat.domain.identity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrockfordBase32Test {
    @Test
    fun encodeDecode_roundTrip() {
        val data = byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0x20, 0x7A)
        val encoded = CrockfordBase32.encode(data)
        assertThat(encoded).isEqualTo(encoded.uppercase())
        assertThat(CrockfordBase32.decode(encoded)).isEqualTo(data)
    }

    @Test
    fun decode_acceptsAliases() {
        // O → 0, I/L → 1 per Crockford
        val viaZero = CrockfordBase32.decode("0")
        val viaO = CrockfordBase32.decode("O")
        assertThat(viaO).isEqualTo(viaZero)
    }
}
