package com.codeyantra.armorchat.core.mesh

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustodyLedgerTest {
    private val origin = 1_700_000_000_000L

    @Test
    fun messageSurvivesFourHoursWithNoPeerNearby() {
        val ledger = CustodyLedger()
        val held = ledger.admit(payload("need water"), origin, emergency = true) as AdmitResult.Held
        val deadline = origin + MeshPolicy.ABSOLUTE_TTL_MS

        assertEquals(deadline, held.bundle.expireAtMs)
        assertEquals(1, ledger.offer(emptySet(), origin).size)
        assertEquals(1, ledger.offer(emptySet(), deadline - 1).size)
        assertTrue(ledger.sweep(deadline - 1).isEmpty())

        assertTrue(ledger.offer(emptySet(), deadline).isEmpty())
        assertEquals(1, ledger.sweep(deadline).size)
        assertTrue(ledger.snapshot().isEmpty())
    }

    @Test
    fun hopDoesNotRestartTheFourHours() {
        val originLedger = CustodyLedger()
        val held = originLedger.admit(payload("meet at the school"), origin, emergency = true) as AdmitResult.Held
        val courierArrives = origin + (3 * 60 * 60 * 1000) + (59 * 60 * 1000)
        val courier = CustodyLedger()
        val stored = courier.accept(held.bundle, courierArrives) as AcceptResult.Stored

        assertEquals(held.bundle.expireAtMs, stored.bundle.expireAtMs)
        assertEquals(1, stored.bundle.hopCount)
        assertEquals(1, courier.offer(emptySet(), courierArrives).size)
        assertTrue(courier.offer(emptySet(), held.bundle.expireAtMs).isEmpty())
    }

    @Test
    fun extendedDeadlineIsRejected() {
        val held = (CustodyLedger().admit(payload("x"), origin, true) as AdmitResult.Held).bundle
        val tampered = held.copy(expireAtMs = held.expireAtMs + 60_000)
        val result = CustodyLedger().accept(tampered, origin + 1_000)
        assertTrue(result is AcceptResult.Rejected)
    }

    @Test
    fun soonestEmergencyLeavesFirst() {
        val ledger = CustodyLedger()
        ledger.admit(payload("later"), origin, emergency = false)
        val early = ledger.admit(payload("early"), origin - 60_000, emergency = true) as AdmitResult.Held
        val offered = ledger.offer(emptySet(), origin)
        assertEquals(early.bundle.idHex, offered.first().idHex)
    }

    @Test
    fun codecRejectsARefreshedClock() {
        val held = (CustodyLedger().admit(payload("sealed"), origin, true) as AdmitResult.Held).bundle
        val encoded = BundleCodec.encode(held)
        val decoded = BundleCodec.decode(encoded)
        assertTrue(decoded is BundleDecode.Ok)
        val mutated = encoded.copyOf()
        mutated[32] = (mutated[32].toInt() xor 0x01).toByte()
        assertTrue(BundleCodec.decode(mutated) is BundleDecode.Rejected)
    }

    @Test
    fun beaconHidesIdentityAndChangesWithCustody() {
        val id = MeshIds.idFor(payload("one"))
        val beacon = MeshBeacon(
            holding = 1,
            emergency = true,
            wifiDirect = true,
            minutesLeft = 240,
            nonce = byteArrayOf(1, 2, 3, 4),
            digest = BeaconCodec.digest(listOf(id)),
        )
        val decoded = BeaconCodec.decode(BeaconCodec.encode(beacon))
        assertEquals(beacon, decoded)
        assertTrue(!beacon.digest.contentEquals(BeaconCodec.digest(listOf(MeshIds.idFor(payload("two"))))))
    }

    @Test
    fun largeBatchPrefersWifiDirectThenBluetooth() {
        assertEquals(listOf(RadioPath.BLE), RadioPlanner.plan(100, peerWifiDirect = true))
        assertEquals(
            listOf(RadioPath.WIFI_DIRECT, RadioPath.BLE),
            RadioPlanner.plan(MeshPolicy.BLE_DIRECT_MAX_BYTES + 1, peerWifiDirect = true),
        )
        assertEquals(RadioPath.BLE, RadioPlanner.agree(100, 100, bothWifiDirect = true))
        assertEquals(
            RadioPath.WIFI_DIRECT,
            RadioPlanner.agree(MeshPolicy.BLE_DIRECT_MAX_BYTES + 1, 20, bothWifiDirect = true),
        )
    }

    @Test
    fun higherNonceInitiates() {
        assertEquals(true, EncounterRole.localInitiates(byteArrayOf(2), byteArrayOf(1)))
        assertEquals(false, EncounterRole.localInitiates(byteArrayOf(1), byteArrayOf(2)))
        assertNull(EncounterRole.localInitiates(byteArrayOf(1), byteArrayOf(1)))
    }

    @Test
    fun conversationExchangesWithoutDeadlock() = runBlocking {
        val aToB = Channel<ByteArray>(4)
        val bToA = Channel<ByteArray>(4)
        val initiator = async {
            MeshConversation.run(
                initiator = true,
                localPhase = "A-phase".toByteArray(),
                send = { aToB.send(it) },
                receive = { bToA.receive() },
                bundlesFor = { remote -> "for-${remote.decodeToString()}".toByteArray() },
            )
        }
        val responder = async {
            MeshConversation.run(
                initiator = false,
                localPhase = "B-phase".toByteArray(),
                send = { bToA.send(it) },
                receive = { aToB.receive() },
                bundlesFor = { remote -> "back-${remote.decodeToString()}".toByteArray() },
            )
        }
        val (remotePhaseA, remoteBundlesA) = initiator.await()
        val (remotePhaseB, remoteBundlesB) = responder.await()
        assertEquals("B-phase", remotePhaseA.toString(Charsets.UTF_8))
        assertEquals("A-phase", remotePhaseB.toString(Charsets.UTF_8))
        assertEquals("back-A-phase", remoteBundlesA.decodeToString())
        assertEquals("for-B-phase", remoteBundlesB.decodeToString())
    }

    @Test
    fun phaseFrameRoundTrips() {
        val phase = MeshPhase(
            beacon = MeshBeacon(1, true, true, 240, byteArrayOf(9, 8, 7, 6), ByteArray(8) { 3 }),
            ids = listOf(ByteArray(16) { it.toByte() }),
        )
        val decoded = MeshFrames.decodePhase(MeshFrames.encodePhase(phase))
        assertEquals(phase.beacon, decoded?.beacon)
        assertTrue(phase.ids[0].contentEquals(decoded!!.ids[0]))
    }

    private fun payload(text: String) = text.toByteArray(Charsets.UTF_8)
}
