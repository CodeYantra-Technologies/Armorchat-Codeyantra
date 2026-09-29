package com.codeyantra.armorchat.core.mesh

enum class RadioPath {
    BLE,
    WIFI_DIRECT,
}

/**
 * Bluetooth is the control plane: discovery and small sealed bundles.
 * Wi-Fi Direct carries a large batch, and Bluetooth is the fallback when
 * group formation fails. Small bundles stay on Bluetooth on purpose.
 */
object RadioPlanner {
    fun plan(totalBytes: Int, peerWifiDirect: Boolean): List<RadioPath> {
        if (totalBytes <= 0) return emptyList()
        if (totalBytes <= MeshPolicy.BLE_DIRECT_MAX_BYTES || !peerWifiDirect) {
            return listOf(RadioPath.BLE)
        }
        return listOf(RadioPath.WIFI_DIRECT, RadioPath.BLE)
    }

    /** Both phones must pick the same leg or the encounter deadlocks. */
    fun agree(localBytes: Int, remoteBytes: Int, bothWifiDirect: Boolean): RadioPath {
        val largest = maxOf(localBytes, remoteBytes)
        return plan(largest, bothWifiDirect).firstOrNull() ?: RadioPath.BLE
    }
}

object EncounterRole {
    /** Higher nonce initiates. Equal nonces skip this round so both sides can rotate. */
    fun localInitiates(localNonce: ByteArray, remoteNonce: ByteArray): Boolean? {
        require(localNonce.size == remoteNonce.size && localNonce.isNotEmpty())
        for (i in localNonce.indices) {
            val a = localNonce[i].toInt() and 0xff
            val b = remoteNonce[i].toInt() and 0xff
            if (a > b) return true
            if (a < b) return false
        }
        return null
    }
}
