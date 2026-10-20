package com.codeyantra.armorchat.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.codeyantra.armorchat.MainActivity
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.core.mesh.BundleCodec
import com.codeyantra.armorchat.core.mesh.BundleDecode
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshAdapterRecovery
import com.codeyantra.armorchat.core.mesh.MeshConversation
import com.codeyantra.armorchat.core.mesh.MeshCourier
import com.codeyantra.armorchat.core.mesh.MeshCustodyRecovery
import com.codeyantra.armorchat.core.mesh.MeshFrames
import com.codeyantra.armorchat.core.mesh.MeshRadioEvent
import com.codeyantra.armorchat.core.mesh.RadioPath
import com.codeyantra.armorchat.core.mesh.RadioPlanner
import com.codeyantra.armorchat.domain.model.EmergencyMeshStatus
import com.codeyantra.armorchat.messaging.ChatMessagingService
import com.codeyantra.armorchat.messaging.SafeLog
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
    private var sweepStarted = false
    private var recoveryStarted = false

    /**
     * Mesh-1H-2: bounded single-flight re-offer of held custody rows (see MeshCustodyRecovery).
     * Same deliverMesh / dropDelivered as [ingest]; never runs while an encounter is active.
     */
    private val recovery by lazy {
        MeshCustodyRecovery(
            scope = scope,
            candidates = { engine.recoveryCandidates() },
            alreadyStored = { payload -> messaging.meshMessageAlreadyStored(payload) },
            deliver = { payload -> messaging.deliverMesh(payload) },
            dropDelivered = { idHex -> engine.dropDelivered(idHex) },
        )
    }
    private var receiverRegistered = false

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (MeshAdapterRecovery.action(engine.armed, permissionsGranted(), state, radio.isRunning())) {
                MeshAdapterRecovery.Action.IGNORE -> Unit
                MeshAdapterRecovery.Action.RELEASE -> releaseRadio()
                MeshAdapterRecovery.Action.RESTART -> {
                    engine.onRadioEvent(MeshRadioEvent.RECOVERING)
                    openRadio()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(adapterReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(adapterReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(foregroundText())
        if (!permissionsGranted()) {
            engine.setRadioDetail("Allow nearby-device access, then turn Emergency Mesh on again.")
            engine.onRadioEvent(MeshRadioEvent.RADIO_BLOCKED)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!radio.isRunning()) {
            engine.onRadioEvent(MeshRadioEvent.RECOVERING)
            openRadio()
        }
        if (!sweepStarted) {
            sweepStarted = true
            scope.launch {
                while (isActive) {
                    engine.sweep()
                    startInForeground(foregroundText())
                    delay(30_000)
                }
            }
        }
        if (!recoveryStarted) {
            recoveryStarted = true
            // StateFlow replays the current count, so a restore() that finished before this
            // service started is covered too.
            scope.launch { engine.restores.collect { recovery.request() } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            receiverRegistered = false
            runCatching { unregisterReceiver(adapterReceiver) }
        }
        radio.stop()
        if (engine.armed) {
            engine.onRadioEvent(MeshRadioEvent.RADIO_BLOCKED)
        } else {
            engine.onRadioEvent(MeshRadioEvent.LINK_DOWN)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun permissionsGranted(): Boolean = MeshPermissions.missing(this).isEmpty()

    private fun releaseRadio() {
        radio.stop()
        engine.setRadioDetail("Turn Bluetooth on. Saved messages stay on this phone until their deadline.")
        engine.onRadioEvent(MeshRadioEvent.RADIO_BLOCKED)
    }

    private fun openRadio() {
        if (!engine.armed) return
        if (!permissionsGranted()) {
            releaseRadio()
            return
        }
        val detail = try {
            radio.start(engine.localNonce(), engine::onRadioEvent) { pipe -> encounter(pipe) }
        } catch (_: SecurityException) {
            radio.stop()
            engine.onRadioEvent(MeshRadioEvent.RADIO_BLOCKED)
            "Nearby permission is missing. Sealed messages stay saved until their deadline."
        }
        engine.setRadioDetail(detail)
    }

    private fun foregroundText(): String {
        val runtime = engine.state.value
        return when (runtime.status) {
            EmergencyMeshStatus.UNAVAILABLE ->
                "Bluetooth is off or nearby-device access is missing."
            EmergencyMeshStatus.SEARCHING ->
                "Searching. Bluetooth is starting."
            EmergencyMeshStatus.STANDBY ->
                "Emergency mesh is off."
            else -> {
                val soonest = runtime.soonestExpireAtMs
                if (runtime.holding == 0 || soonest == null) {
                    "Listening. Nothing is waiting on this phone."
                } else {
                    "Holding ${runtime.holding} sealed message(s). Oldest deadline ${formatRemaining(soonest)}."
                }
            }
        }
    }

    private suspend fun encounter(pipe: MeshPipe) {
        recovery.encounterStarted()
        try {
            exchange(pipe)
        } finally {
            recovery.encounterEnded()
        }
    }

    private suspend fun exchange(pipe: MeshPipe) {
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
                    recovery.encounterStarted()
                    scope.launch {
                        try {
                            val carried = radio.wifiExchange(blob)
                            if (carried != null) ingest(carried, RadioPath.WIFI_DIRECT)
                        } finally {
                            recovery.encounterEnded()
                        }
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
        MeshCourier.ingest(
            bytes = bytes,
            path = path,
            store = { encoded, via -> engine.ingest(encoded, via) },
            deliver = { payload ->
                // Mesh-1H-2: a false here (LOCK_TIMEOUT, failed commit, not for us) leaves the row
                // held; ask for a bounded recovery pass, deferred until the encounter ends.
                messaging.deliverMesh(payload).also { if (!it) recovery.request() }
            },
            dropDelivered = { idHex -> engine.dropDelivered(idHex) },
            // Mesh-1K-R (fix A): custody FULL + outer mailbox is ours -> bounded slot-less delivery through
            // the same authenticated deliverMesh path. Nothing held, so no recovery request and no drop.
            addressedHere = { payload -> messaging.meshAddressedHere(payload) },
            deliverDirect = { payload -> messaging.deliverMeshDirect(payload) },
            reportDirect = { counts ->
                SafeLog.event("mesh_direct", counts.entries.joinToString(" ") { "${it.key.name.lowercase()}=${it.value}" })
            },
        )
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
