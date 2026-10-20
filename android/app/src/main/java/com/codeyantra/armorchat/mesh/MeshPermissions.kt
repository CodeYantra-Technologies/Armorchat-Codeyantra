package com.codeyantra.armorchat.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object MeshPermissions {
    fun required(): Array<String> = required(Build.VERSION.SDK_INT)

    fun required(sdk: Int): Array<String> {
        val names = mutableListOf<String>()
        if (sdk >= 31) {
            names += Manifest.permission.BLUETOOTH_SCAN
            names += Manifest.permission.BLUETOOTH_ADVERTISE
            names += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            names += Manifest.permission.BLUETOOTH
            names += Manifest.permission.BLUETOOTH_ADMIN
            names += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (sdk >= 33) {
            names += Manifest.permission.NEARBY_WIFI_DEVICES
            names += Manifest.permission.POST_NOTIFICATIONS
        } else {
            if (Manifest.permission.ACCESS_FINE_LOCATION !in names) {
                names += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }
        return names.toTypedArray()
    }

    fun missing(context: Context): List<String> =
        required().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
}
