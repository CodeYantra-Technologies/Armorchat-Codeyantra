package com.codeyantra.armorchat.core.mesh

import android.bluetooth.BluetoothAdapter
import com.codeyantra.armorchat.domain.model.EmergencyMeshStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshAdapterRecoveryTest {
    @Test
    fun armedBluetoothOffIsUnavailableAndStaysArmed() {
        val armed = true
        val (phase, blocked) = MeshPresence.apply(
            armed,
            MeshRadioPhase.LISTENING,
            radioBlocked = false,
            MeshRadioEvent.RADIO_BLOCKED,
        )
        assertEquals(EmergencyMeshStatus.UNAVAILABLE, MeshPresence.status(armed, phase, blocked))
        assertNotEquals(EmergencyMeshStatus.LISTENING, MeshPresence.status(armed, phase, blocked))
        assertNotEquals(EmergencyMeshStatus.CONNECTED, MeshPresence.status(armed, phase, blocked))
        assertEquals(
            MeshAdapterRecovery.Action.RELEASE,
            MeshAdapterRecovery.action(armed, permissionsGranted = true, BluetoothAdapter.STATE_OFF, radioActive = true),
        )
        assertTrue(armed)
    }

    @Test
    fun armedBluetoothOnGoesSearchingThenListening() {
        var phase = MeshRadioPhase.LISTENING
        var blocked = true
        val recovering = MeshPresence.apply(true, phase, blocked, MeshRadioEvent.RECOVERING)
        phase = recovering.first
        blocked = recovering.second
        assertEquals(EmergencyMeshStatus.SEARCHING, MeshPresence.status(true, phase, blocked))
        val up = MeshPresence.apply(true, phase, blocked, MeshRadioEvent.RADIO_UP)
        phase = up.first
        blocked = up.second
        assertEquals(EmergencyMeshStatus.LISTENING, MeshPresence.status(true, phase, blocked))
        assertNotEquals(EmergencyMeshStatus.CONNECTED, MeshPresence.status(true, phase, blocked))
        assertEquals(
            MeshAdapterRecovery.Action.RESTART,
            MeshAdapterRecovery.action(true, true, BluetoothAdapter.STATE_ON, radioActive = false),
        )
    }

    @Test
    fun repeatedOffOnDoesNotStackRegistrations() {
        var slot = MeshAdapterRecovery.Registration()
        slot = cycle(slot)
        assertEquals(1, slot.starts)
        assertTrue(slot.active)
        slot = MeshAdapterRecovery.step(slot, true, true, BluetoothAdapter.STATE_ON)
        assertEquals(1, slot.starts)
        slot = cycle(slot)
        slot = cycle(slot)
        assertEquals(3, slot.starts)
        assertEquals(3, slot.releases)
        assertTrue(slot.active)
        assertFalse(slot.starts > slot.releases + 1)
    }

    @Test
    fun disarmedBluetoothOnStaysStandby() {
        assertEquals(
            MeshAdapterRecovery.Action.IGNORE,
            MeshAdapterRecovery.action(false, true, BluetoothAdapter.STATE_ON, radioActive = false),
        )
        val (phase, blocked) = MeshPresence.apply(
            armed = false,
            MeshRadioPhase.IDLE,
            radioBlocked = false,
            MeshRadioEvent.RADIO_UP,
        )
        assertEquals(MeshRadioPhase.IDLE, phase)
        assertEquals(EmergencyMeshStatus.STANDBY, MeshPresence.status(false, phase, blocked))
        assertEquals(
            EmergencyMeshStatus.STANDBY,
            MeshPresence.status(false, MeshRadioPhase.CONNECTED, radioBlocked = false),
        )
    }

    @Test
    fun bluetoothOffDuringConnectionAttemptIsUnavailable() {
        val (phase, blocked) = MeshPresence.apply(
            true,
            MeshRadioPhase.CONNECTING,
            radioBlocked = false,
            MeshRadioEvent.RADIO_BLOCKED,
        )
        assertEquals(EmergencyMeshStatus.UNAVAILABLE, MeshPresence.status(true, phase, blocked))
        val stale = MeshPresence.apply(true, phase, blocked, MeshRadioEvent.LINK_UP)
        assertEquals(EmergencyMeshStatus.UNAVAILABLE, MeshPresence.status(true, stale.first, stale.second))
        assertNotEquals(EmergencyMeshStatus.CONNECTING, MeshPresence.status(true, stale.first, stale.second))
    }

    @Test
    fun bluetoothOffDuringOpenLinkIsUnavailable() {
        val (phase, blocked) = MeshPresence.apply(
            true,
            MeshRadioPhase.CONNECTED,
            radioBlocked = false,
            MeshRadioEvent.RADIO_BLOCKED,
        )
        assertEquals(EmergencyMeshStatus.UNAVAILABLE, MeshPresence.status(true, phase, blocked))
        assertEquals("Unavailable", MeshPresence.status(true, phase, blocked).displayTitle)
        val stale = MeshPresence.apply(true, phase, blocked, MeshRadioEvent.LINK_UP)
        assertNotEquals(EmergencyMeshStatus.CONNECTED, MeshPresence.status(true, stale.first, stale.second))
    }

    @Test
    fun processRestartWhileArmedAndBluetoothUnavailable() {
        val restarted = MeshPresence.phaseAfterProcessRestart(armed = true)
        assertEquals(MeshRadioPhase.SEARCHING, restarted)
        val (phase, blocked) = MeshPresence.apply(
            true,
            restarted,
            radioBlocked = false,
            MeshRadioEvent.RADIO_BLOCKED,
        )
        assertEquals(EmergencyMeshStatus.UNAVAILABLE, MeshPresence.status(true, phase, blocked))
        assertEquals(
            MeshAdapterRecovery.Action.RELEASE,
            MeshAdapterRecovery.action(true, true, BluetoothAdapter.STATE_OFF, radioActive = false),
        )
        assertNotEquals(
            MeshAdapterRecovery.Action.RESTART,
            MeshAdapterRecovery.action(true, true, BluetoothAdapter.STATE_OFF, radioActive = false),
        )
    }

    @Test
    fun permissionDenialDuringRecoveryDoesNotRestart() {
        assertEquals(
            MeshAdapterRecovery.Action.RELEASE,
            MeshAdapterRecovery.action(true, permissionsGranted = false, BluetoothAdapter.STATE_ON, radioActive = false),
        )
        var slot = MeshAdapterRecovery.Registration(active = true, starts = 1, releases = 0)
        slot = MeshAdapterRecovery.step(slot, true, permissionsGranted = false, BluetoothAdapter.STATE_ON)
        assertEquals(1, slot.starts)
        assertFalse(slot.active)
        assertEquals(1, slot.releases)
        val (phase, blocked) = MeshPresence.apply(
            true,
            MeshRadioPhase.LISTENING,
            radioBlocked = false,
            MeshRadioEvent.RADIO_BLOCKED,
        )
        assertEquals(EmergencyMeshStatus.UNAVAILABLE, MeshPresence.status(true, phase, blocked))
    }

    private fun cycle(slot: MeshAdapterRecovery.Registration): MeshAdapterRecovery.Registration {
        val down = MeshAdapterRecovery.step(slot, true, true, BluetoothAdapter.STATE_OFF)
        return MeshAdapterRecovery.step(down, true, true, BluetoothAdapter.STATE_ON)
    }
}
