package com.codeyantra.armorchat.core.mesh

import com.codeyantra.armorchat.domain.model.EmergencyMeshStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MeshPresenceTest {
    @Test
    fun restartOfAnArmedPhoneIsSearchingNotInContact() {
        val phase = MeshPresence.phaseAfterProcessRestart(armed = true)
        val status = MeshPresence.status(armed = true, phase, radioBlocked = false)
        assertEquals(MeshRadioPhase.SEARCHING, phase)
        assertEquals(EmergencyMeshStatus.SEARCHING, status)
        assertNotEquals(EmergencyMeshStatus.CONNECTED, status)
        assertEquals("Searching", status.displayTitle)
    }

    @Test
    fun restartOfADisarmedPhoneStaysStandby() {
        val phase = MeshPresence.phaseAfterProcessRestart(armed = false)
        assertEquals(MeshRadioPhase.IDLE, phase)
        assertEquals(
            EmergencyMeshStatus.STANDBY,
            MeshPresence.status(armed = false, phase, radioBlocked = false),
        )
    }

    @Test
    fun disarmClearsALiveLink() {
        val phase = MeshPresence.nextPhase(armed = false, MeshRadioPhase.CONNECTED, MeshRadioEvent.RADIO_STOPPED)
        assertEquals(MeshRadioPhase.IDLE, phase)
        assertEquals(
            EmergencyMeshStatus.STANDBY,
            MeshPresence.status(armed = false, MeshRadioPhase.CONNECTED, radioBlocked = false),
        )
    }

    @Test
    fun radioUpIsListeningUntilAGattLinkExists() {
        var phase = MeshPresence.phaseAfterProcessRestart(armed = true)
        phase = MeshPresence.nextPhase(true, phase, MeshRadioEvent.RADIO_UP)
        assertEquals(EmergencyMeshStatus.LISTENING, MeshPresence.status(true, phase, false))
        phase = MeshPresence.nextPhase(true, phase, MeshRadioEvent.RADIO_UP)
        assertEquals(EmergencyMeshStatus.LISTENING, MeshPresence.status(true, phase, false))
        assertNotEquals(EmergencyMeshStatus.CONNECTED, MeshPresence.status(true, phase, false))
    }

    @Test
    fun blockedRadioIsUnavailableAndNotInContact() {
        val phase = MeshPresence.nextPhase(
            true,
            MeshRadioPhase.SEARCHING,
            MeshRadioEvent.RADIO_BLOCKED,
        )
        assertEquals(EmergencyMeshStatus.UNAVAILABLE, MeshPresence.status(true, phase, radioBlocked = true))
    }

    @Test
    fun simulatedPeersConnectOnlyAfterLinkUpAndReturnToListening() {
        var local = MeshPresence.phaseAfterProcessRestart(true)
        var remote = MeshPresence.phaseAfterProcessRestart(true)
        local = MeshPresence.nextPhase(true, local, MeshRadioEvent.RADIO_UP)
        remote = MeshPresence.nextPhase(true, remote, MeshRadioEvent.RADIO_UP)
        val low = byteArrayOf(1, 0, 0, 0)
        val high = byteArrayOf(2, 0, 0, 0)
        assertEquals(true, MeshLinkPlanner.shouldConnect(high, low))
        assertEquals(false, MeshLinkPlanner.shouldConnect(low, high))
        local = MeshPresence.nextPhase(true, local, MeshRadioEvent.DIALING)
        assertEquals(EmergencyMeshStatus.CONNECTING, MeshPresence.status(true, local, false))
        assertEquals(EmergencyMeshStatus.LISTENING, MeshPresence.status(true, remote, false))

        local = MeshPresence.nextPhase(true, local, MeshRadioEvent.LINK_UP)
        remote = MeshPresence.nextPhase(true, remote, MeshRadioEvent.LINK_UP)
        assertEquals(EmergencyMeshStatus.CONNECTED, MeshPresence.status(true, local, false))
        assertEquals(EmergencyMeshStatus.CONNECTED, MeshPresence.status(true, remote, false))

        local = MeshPresence.nextPhase(true, local, MeshRadioEvent.LINK_DOWN)
        remote = MeshPresence.nextPhase(true, remote, MeshRadioEvent.LINK_DOWN)
        assertEquals(EmergencyMeshStatus.LISTENING, MeshPresence.status(true, local, false))
        assertEquals(EmergencyMeshStatus.LISTENING, MeshPresence.status(true, remote, false))

        local = MeshPresence.nextPhase(true, local, MeshRadioEvent.DIALING)
        local = MeshPresence.nextPhase(true, local, MeshRadioEvent.LINK_UP)
        assertEquals(EmergencyMeshStatus.CONNECTED, MeshPresence.status(true, local, false))
    }

    @Test
    fun advertSuccessDoesNotDowngradeAnOpenLink() {
        var phase = MeshPresence.nextPhase(true, MeshRadioPhase.LISTENING, MeshRadioEvent.LINK_UP)
        phase = MeshPresence.nextPhase(true, phase, MeshRadioEvent.RADIO_UP)
        assertEquals(MeshRadioPhase.CONNECTED, phase)
    }

    @Test
    fun chunkAckStopsOnTheFirstFailure() {
        assertEquals(3, ChunkSendSequence.acknowledgedCount(listOf(0, 0, 0)))
        assertEquals(1, ChunkSendSequence.acknowledgedCount(listOf(0, 201, 0)))
        assertEquals(0, ChunkSendSequence.acknowledgedCount(listOf(201)))
        assertEquals(0, ChunkSendSequence.acknowledgedCount(emptyList()))
    }

    @Test
    fun custodySurvivesAProcessRestartCopy() {
        val origin = 1_700_000_000_000L
        val first = CustodyLedger()
        val held = first.admit("need water".toByteArray(), origin, emergency = true) as AdmitResult.Held
        val restored = CustodyLedger(first.snapshot())
        val row = restored.snapshot().single()
        assertEquals(held.bundle.expireAtMs, row.expireAtMs)
        assertEquals(held.bundle.idHex, row.idHex)
        assertEquals(1, restored.offer(emptySet(), origin).size)
        assertEquals(0, restored.offer(emptySet(), held.bundle.expireAtMs).size)
    }
}
