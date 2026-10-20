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

/**
 * One GATT connection per encounter. The higher nonce opens the client link.
 * The other phone only accepts it. Equal nonces, or an advert with no nonce,
 * skip the round so both sides can rotate later.
 */
object MeshLinkPlanner {
    /** Flags (3) + 128-bit service UUID (18) + manufacturer nonce (8). */
    const val LEGACY_ADVERT_BUDGET: Int = 31
    const val LEGACY_ADVERT_OCTETS: Int = 3 + 18 + 8

    fun advertFitsLegacy(): Boolean = LEGACY_ADVERT_OCTETS <= LEGACY_ADVERT_BUDGET

    fun shouldConnect(localNonce: ByteArray, remoteNonce: ByteArray?): Boolean {
        if (remoteNonce == null || remoteNonce.size != localNonce.size || localNonce.isEmpty()) return false
        return EncounterRole.localInitiates(localNonce, remoteNonce) == true
    }
}
