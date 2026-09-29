package com.codeyantra.armorchat.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MeshBootReceiver : BroadcastReceiver() {
    @Inject lateinit var mesh: EmergencyMeshEngine

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                mesh.resumeIfArmed()
            } finally {
                pending.finish()
            }
        }
    }
}
