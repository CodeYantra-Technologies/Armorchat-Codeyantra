package com.codeyantra.armorchat.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRadioContractTest {
    @Test
    fun legacyAdvertStillFitsWithEncounterNonce() {
        assertTrue(MeshLinkPlanner.advertFitsLegacy())
        assertTrue(MeshLinkPlanner.LEGACY_ADVERT_OCTETS <= MeshLinkPlanner.LEGACY_ADVERT_BUDGET)
    }

    @Test
    fun onlyHigherNonceOpensTheClientLink() {
        val low = byteArrayOf(0, 0, 0, 1)
        val high = byteArrayOf(0, 0, 0, 2)
        assertTrue(MeshLinkPlanner.shouldConnect(high, low))
        assertFalse(MeshLinkPlanner.shouldConnect(low, high))
        assertFalse(MeshLinkPlanner.shouldConnect(low, low.copyOf()))
        assertFalse(MeshLinkPlanner.shouldConnect(low, null))
        assertFalse(MeshLinkPlanner.shouldConnect(low, byteArrayOf(1, 2, 3)))
        assertFalse(MeshLinkPlanner.shouldConnect(byteArrayOf(), low))
    }

    @Test
    fun chunksRoundTripAtDefaultAndRequestedMtu() {
        val sizes = intArrayOf(0, 1, 19, 20, 181, 182, 3_000)
        val mtus = intArrayOf(GattChunks.DEFAULT_MTU, 185)
        for (mtu in mtus) {
            val ceiling = GattChunks.usableValue(mtu)
            for (size in sizes) {
                val payload = ByteArray(size) { (it * 31 + size).toByte() }
                val chunks = GattChunks.split(payload, mtu)
                assertTrue(chunks.isNotEmpty())
                chunks.forEachIndexed { index, chunk ->
                    assertTrue(chunk.isNotEmpty())
                    assertTrue(chunk.size <= ceiling)
                    val last = chunk[0].toInt() and 0x01 != 0
                    assertEquals(index == chunks.lastIndex, last)
                }
                val reassembler = GattReassembler()
                var restored: ByteArray? = null
                chunks.forEach { restored = reassembler.feed(it) }
                assertTrue(payload.contentEquals(restored))
            }
        }
    }

    @Test
    fun reassemblerKeepsTwoMessagesApart() {
        val first = GattChunks.split("one".toByteArray(), GattChunks.DEFAULT_MTU)
        val second = GattChunks.split("two-two".toByteArray(), GattChunks.DEFAULT_MTU)
        val reassembler = GattReassembler()
        var got = reassembler.feed(first.first())
        if (first.size > 1) {
            assertEquals(null, got)
            got = reassembler.feed(first.last())
        }
        assertTrue("one".toByteArray().contentEquals(got))
        var next: ByteArray? = null
        second.forEach { next = reassembler.feed(it) }
        assertTrue("two-two".toByteArray().contentEquals(next))
    }

    @Test
    fun phaseFrameSurvivesDefaultMtuFraming() {
        val phase = MeshPhase(
            beacon = MeshBeacon(
                holding = 2,
                emergency = true,
                wifiDirect = false,
                minutesLeft = 240,
                nonce = byteArrayOf(4, 3, 2, 1),
                digest = ByteArray(8) { 7 },
            ),
            ids = listOf(ByteArray(16) { it.toByte() }, ByteArray(16) { (255 - it).toByte() }),
        )
        val encoded = MeshFrames.encodePhase(phase)
        val reassembler = GattReassembler()
        var restored: ByteArray? = null
        GattChunks.split(encoded, GattChunks.DEFAULT_MTU).forEach { restored = reassembler.feed(it) }
        val decoded = MeshFrames.decodePhase(restored!!)
        assertEquals(phase.beacon, decoded?.beacon)
        assertEquals(phase.ids.size, decoded?.ids?.size)
        assertTrue(phase.ids[0].contentEquals(decoded!!.ids[0]))
        assertTrue(phase.ids[1].contentEquals(decoded.ids[1]))
    }
}
