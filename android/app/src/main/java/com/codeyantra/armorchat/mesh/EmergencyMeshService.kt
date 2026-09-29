package com.codeyantra.armorchat.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.codeyantra.armorchat.MainActivity
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.core.mesh.BundleCodec
import com.codeyantra.armorchat.core.mesh.BundleDecode
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshConversation
import com.codeyantra.armorchat.core.mesh.MeshFrames
import com.codeyantra.armorchat.core.mesh.RadioPath
import com.codeyantra.armorchat.core.mesh.RadioPlanner
import com.codeyantra.armorchat.messaging.ChatMessagingService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EmergencyMeshService : Service() {
    @Inject lateinit var engine: EmergencyMeshEngine
    @Inject lateinit var messaging: ChatMessagingService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val radio by lazy { NearbyMeshRadio(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("Emergency mesh is listening")
        if (MeshPermissions.missing(this).isNotEmpty()) {
            engine.setRadioDetail("Allow nearby-device access, then turn Emergency Mesh on again.")
            stopSelf()
            return START_NOT_STICKY
        }
        val detail = try {
            radio.start { pipe -> encounter(pipe) }
        } catch (_: SecurityException) {
            "Nearby permission is missing. Sealed messages stay saved until their deadline."
        }
        engine.setRadioDetail(detail)
        scope.launch {
            while (isActive) {
                engine.sweep()
                val holding = engine.state.value.holding
                val soonest = engine.state.value.soonestExpireAtMs
                val text = if (holding == 0 || soonest == null) {
                    "Listening. Nothing is waiting on this phone."
                } else {
                    "Holding $holding sealed message(s). Oldest deadline ${formatRemaining(soonest)}."
                }
                startInForeground(text)
                delay(30_000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        radio.stop()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun encounter(pipe: MeshPipe) {
        val localPhase = MeshFrames.encodePhase(engine.phaseFor(emptySet(), radio.wifiDirectReady))
        var sentIds: List<ByteArray> = emptyList()
        val (_, remoteBundles) = MeshConversation.run(
            initiator = pipe.initiator,
            localPhase = localPhase,
            send = { pipe.send(it) },
            receive = { pipe.receive() },
            bundlesFor = { remotePhase ->
                val parsed = MeshFrames.decodePhase(remotePhase)
                if (parsed == null) {
                    ByteArray(0)
                } else {
                val offers = engine.offers(parsed.ids)
                sentIds = offers.mapNotNull { encoded ->
                    (BundleCodec.decode(encoded) as? BundleDecode.Ok)?.bundle?.id
                }
                val blob = MeshFrames.encodeBundles(offers)
                val agreed = RadioPlanner.agree(
                    localBytes = blob.size,
                    remoteBytes = blob.size,
                    bothWifiDirect = parsed.beacon.wifiDirect && radio.wifiDirectReady,
                )
                if (agreed == RadioPath.WIFI_DIRECT) {
                    scope.launch {
                        val carried = radio.wifiExchange(blob)
                        if (carried != null) ingest(carried, RadioPath.WIFI_DIRECT)
                    }
                }
                blob
                }
            },
        )
        if (sentIds.isNotEmpty()) engine.markForwarded(sentIds, RadioPath.BLE)
        ingest(remoteBundles, RadioPath.BLE)
    }

    private suspend fun ingest(bytes: ByteArray, path: RadioPath) {
        for (encoded in MeshFrames.decodeBundles(bytes)) {
            val decoded = BundleCodec.decode(encoded) as? BundleDecode.Ok ?: continue
            engine.ingest(encoded, path)
            if (messaging.deliverMesh(decoded.bundle.payload)) {
                engine.dropDelivered(decoded.bundle.idHex)
            }
        }
    }

    private fun startInForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Emergency mesh", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while sealed messages can be carried over Bluetooth or Wi-Fi Direct"
                },
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Emergency mesh")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun formatRemaining(expireAtMs: Long): String {
        val left = expireAtMs - System.currentTimeMillis()
        if (left <= 0) return "reached"
        val minutes = left / 60_000
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    companion object {
        private const val CHANNEL_ID = "emergency_mesh"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, EmergencyMeshService::class.java),
            )
        }
    }
}
