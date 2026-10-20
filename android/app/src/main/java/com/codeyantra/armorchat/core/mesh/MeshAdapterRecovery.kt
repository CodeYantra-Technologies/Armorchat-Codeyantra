package com.codeyantra.armorchat.core.mesh

import android.bluetooth.BluetoothAdapter

/**
 * What the mesh service should do when the Android Bluetooth adapter changes
 * state. One active registration at a time: a second STATE_ON while the radio
 * is already up is ignored, and STATE_OFF always drops that registration.
 * Disarmed phones ignore the adapter. The armed preference is not touched.
 */
object MeshAdapterRecovery {
    enum class Action {
        IGNORE,
        RELEASE,
        RESTART,
    }

    fun action(
        armed: Boolean,
        permissionsGranted: Boolean,
        adapterState: Int,
        radioActive: Boolean,
    ): Action {
        if (!armed) return Action.IGNORE
        if (adapterState == BluetoothAdapter.STATE_OFF ||
            adapterState == BluetoothAdapter.STATE_TURNING_OFF
        ) {
            return Action.RELEASE
        }
        if (adapterState != BluetoothAdapter.STATE_ON) return Action.IGNORE
        if (!permissionsGranted) return Action.RELEASE
        if (radioActive) return Action.IGNORE
        return Action.RESTART
    }

    data class Registration(
        val active: Boolean = false,
        val starts: Int = 0,
        val releases: Int = 0,
    )

    /** Pure model of [action] used to prove a toggle cannot stack two radios. */
    fun step(
        registration: Registration,
        armed: Boolean,
        permissionsGranted: Boolean,
        adapterState: Int,
    ): Registration = when (
        action(armed, permissionsGranted, adapterState, registration.active)
    ) {
        Action.IGNORE -> registration
        Action.RELEASE -> registration.copy(active = false, releases = registration.releases + 1)
        Action.RESTART -> registration.copy(active = true, starts = registration.starts + 1)
    }
}
