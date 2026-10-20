package com.codeyantra.armorchat.mesh

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPermissionContractTest {
    @Test
    fun api30UsesLegacyBluetoothAndLocation() {
        val names = MeshPermissions.required(30).toSet()
        assertTrue(Manifest.permission.BLUETOOTH in names)
        assertTrue(Manifest.permission.BLUETOOTH_ADMIN in names)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in names)
        assertFalse(Manifest.permission.BLUETOOTH_SCAN in names)
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in names)
    }

    @Test
    fun api31UsesNearbyBluetoothAndLocation() {
        val names = MeshPermissions.required(31).toSet()
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in names)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in names)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in names)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in names)
        assertFalse(Manifest.permission.BLUETOOTH in names)
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in names)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in names)
    }

    @Test
    fun api33AddsNearbyWifiAndNotificationsWithoutLocation() {
        val names = MeshPermissions.required(33).toSet()
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in names)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in names)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in names)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in names)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in names)
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in names)
    }
}
