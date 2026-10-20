package com.codeyantra.armorchat.core.mesh

import com.codeyantra.armorchat.domain.model.EmergencyMeshStatus

/**
 * What the local radio is doing. [CONNECTED] is entered only from a GATT
 * link-up event. Process restart begins at [SEARCHING] when the phone was
 * armed, which is not evidence of a peer.
 */
enum class MeshRadioPhase {
    IDLE,
    SEARCHING,
    LISTENING,
    CONNECTING,
    CONNECTED,
}

enum class MeshRadioEvent {
    RADIO_UP,
    /** Adapter is back and the phone is still armed. Not a peer link. */
    RECOVERING,
    DIALING,
    LINK_UP,
    LINK_DOWN,
    RADIO_BLOCKED,
    RADIO_STOPPED,
}

object MeshPresence {
    /** In-memory phase after process death. Prefs may still say armed. The link does not survive. */
    fun phaseAfterProcessRestart(armed: Boolean): MeshRadioPhase =
        if (armed) MeshRadioPhase.SEARCHING else MeshRadioPhase.IDLE

    fun nextPhase(armed: Boolean, phase: MeshRadioPhase, event: MeshRadioEvent): MeshRadioPhase {
        if (!armed || event == MeshRadioEvent.RADIO_STOPPED) return MeshRadioPhase.IDLE
        return when (event) {
            MeshRadioEvent.RADIO_STOPPED -> MeshRadioPhase.IDLE
            MeshRadioEvent.RADIO_BLOCKED -> MeshRadioPhase.SEARCHING
            MeshRadioEvent.RECOVERING -> MeshRadioPhase.SEARCHING
            MeshRadioEvent.RADIO_UP -> when (phase) {
                MeshRadioPhase.CONNECTING, MeshRadioPhase.CONNECTED -> phase
                else -> MeshRadioPhase.LISTENING
            }
            MeshRadioEvent.DIALING ->
                if (phase == MeshRadioPhase.CONNECTED) phase else MeshRadioPhase.CONNECTING
            MeshRadioEvent.LINK_UP -> MeshRadioPhase.CONNECTED
            MeshRadioEvent.LINK_DOWN -> when (phase) {
                MeshRadioPhase.CONNECTING, MeshRadioPhase.CONNECTED -> MeshRadioPhase.LISTENING
                else -> phase
            }
        }
    }

    /**
     * One step of the chip. A blocked adapter ignores dial and link events, so a
     * GATT callback that was already in flight cannot report In contact after
     * Bluetooth has turned off.
     */
    fun apply(
        armed: Boolean,
        phase: MeshRadioPhase,
        radioBlocked: Boolean,
        event: MeshRadioEvent,
    ): Pair<MeshRadioPhase, Boolean> {
        if (!armed && event != MeshRadioEvent.RADIO_STOPPED) return phase to radioBlocked
        var blocked = radioBlocked
        if (event == MeshRadioEvent.RADIO_BLOCKED) blocked = true
        if (event == MeshRadioEvent.RECOVERING || event == MeshRadioEvent.RADIO_UP) blocked = false
        if (blocked && event != MeshRadioEvent.RADIO_BLOCKED && event != MeshRadioEvent.RADIO_STOPPED) {
            return phase to blocked
        }
        return nextPhase(armed, phase, event) to blocked
    }

    fun status(armed: Boolean, phase: MeshRadioPhase, radioBlocked: Boolean): EmergencyMeshStatus {
        if (!armed) return EmergencyMeshStatus.STANDBY
        if (radioBlocked) return EmergencyMeshStatus.UNAVAILABLE
        return when (phase) {
            MeshRadioPhase.IDLE, MeshRadioPhase.SEARCHING -> EmergencyMeshStatus.SEARCHING
            MeshRadioPhase.LISTENING -> EmergencyMeshStatus.LISTENING
            MeshRadioPhase.CONNECTING -> EmergencyMeshStatus.CONNECTING
            MeshRadioPhase.CONNECTED -> EmergencyMeshStatus.CONNECTED
        }
    }
}
