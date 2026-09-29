package com.codeyantra.armorchat.core.mesh

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.dao.MeshCustodyDao
import com.codeyantra.armorchat.core.database.entity.MeshCustodyEntity
import com.codeyantra.armorchat.domain.model.EmergencyMeshStatus
import com.codeyantra.armorchat.mesh.EmergencyMeshService
import com.codeyantra.armorchat.messaging.SafeLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class EmergencyMeshEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: MeshCustodyDao,
    private val clock: AppClock,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val mutex = Mutex()
    private val random = SecureRandom()
    private var ledger = CustodyLedger()
    private var peersSeen = 0
    private var forwarded = 0
    private var deliveredHere = 0
    private var lastPath = "Bluetooth"
    private var radioDetail = ""
    private var searching = false
    private val nonce = ByteArray(4).also { random.nextBytes(it) }

    private val _state = MutableStateFlow(MeshRuntime())
    val state: StateFlow<MeshRuntime> = _state.asStateFlow()

    @Volatile
    var armed: Boolean = prefs.getBoolean(KEY_ARMED, false)
        private set

    fun resumeIfArmed() {
        scope.launch {
            restore()
            if (armed) startService()
        }
    }

    suspend fun arm(): Boolean {
        armed = true
        prefs.edit().putBoolean(KEY_ARMED, true).apply()
        restore()
        searching = true
        publish()
        return startService()
    }

    fun disarm() {
        armed = false
        searching = false
        prefs.edit().putBoolean(KEY_ARMED, false).apply()
        context.stopService(Intent(context, EmergencyMeshService::class.java))
        scope.launch {
            restore()
            publish()
        }
    }

    suspend fun sweep() = mutex.withLock {
        val now = clock.nowEpochMs()
        val dead = ledger.sweep(now)
        dead.forEach { dao.delete(it.idHex) }
        publishLocked(now)
    }

    suspend fun holdSealed(payload: ByteArray, emergency: Boolean = true) {
        mutex.withLock {
            val now = clock.nowEpochMs()
            ledger.sweep(now).forEach { dao.delete(it.idHex) }
            when (val result = ledger.admit(payload, now, emergency)) {
                is AdmitResult.Held -> {
                    dao.upsert(result.bundle.toEntity())
                    SafeLog.event("mesh_hold", "bytes=${payload.size} ttl_h=4")
                }
                is AdmitResult.Duplicate -> Unit
                is AdmitResult.Rejected -> SafeLog.event("mesh_hold_rejected", "reason=${result.reason}")
            }
            publishLocked(now)
        }
    }

    suspend fun phaseFor(remoteIds: Set<String>, wifiDirect: Boolean): MeshPhase {
        return mutex.withLock {
            val now = clock.nowEpochMs()
            ledger.sweep(now).forEach { dao.delete(it.idHex) }
            val live = ledger.snapshot().filter { it.live(now) }
            publishLocked(now)
            MeshPhase(
                beacon = MeshBeacon(
                    holding = live.size,
                    emergency = live.any { it.emergency },
                    wifiDirect = wifiDirect,
                    minutesLeft = BeaconCodec.minutesLeft(ledger.soonestExpireAt(now), now),
                    nonce = nonce.copyOf(),
                    digest = BeaconCodec.digest(live.map { it.id }),
                ),
                ids = live.map { it.id },
            )
        }
    }

    suspend fun offers(remoteIds: Collection<ByteArray>): List<ByteArray> = mutex.withLock {
        val now = clock.nowEpochMs()
        ledger.offer(remoteIds.map { MeshIds.hex(it) }.toSet(), now).map { BundleCodec.encode(it) }
    }

    suspend fun ingest(encoded: ByteArray, path: RadioPath): Boolean = mutex.withLock {
        val now = clock.nowEpochMs()
        val decoded = BundleCodec.decode(encoded)
        if (decoded !is BundleDecode.Ok) return false
        when (val result = ledger.accept(decoded.bundle, now)) {
            is AcceptResult.Stored -> {
                dao.upsert(result.bundle.toEntity())
                lastPath = if (path == RadioPath.WIFI_DIRECT) "Wi-Fi Direct" else "Bluetooth"
                peersSeen += 1
                publishLocked(now)
                true
            }
            is AcceptResult.Duplicate -> false
            is AcceptResult.Rejected -> false
        }
    }

    suspend fun markForwarded(ids: List<ByteArray>, path: RadioPath) = mutex.withLock {
        ids.forEach { ledger.markForwarded(MeshIds.hex(it)) }
        forwarded += ids.size
        lastPath = if (path == RadioPath.WIFI_DIRECT) "Wi-Fi Direct" else "Bluetooth"
        peersSeen += 1
        val now = clock.nowEpochMs()
        ledger.snapshot().filter { it.forwardedCount > 0 }.forEach { dao.upsert(it.toEntity()) }
        publishLocked(now)
    }

    suspend fun dropDelivered(idHex: String) = mutex.withLock {
        ledger.drop(idHex)
        dao.delete(idHex)
        deliveredHere += 1
        publishLocked(clock.nowEpochMs())
    }

    fun localNonce(): ByteArray = nonce.copyOf()

    fun setRadioDetail(text: String) {
        scope.launch {
            mutex.withLock {
                radioDetail = text
                publishLocked(clock.nowEpochMs())
            }
        }
    }

    private suspend fun restore() {
        mutex.withLock {
            val now = clock.nowEpochMs()
            dao.deleteExpired(now)
            ledger = CustodyLedger(dao.all().map { it.toBundle() })
            ledger.sweep(now)
            publishLocked(now)
        }
    }

    private fun publish() {
        scope.launch { mutex.withLock { publishLocked(clock.nowEpochMs()) } }
    }

    private fun publishLocked(now: Long) {
        val live = ledger.snapshot().filter { it.live(now) }
        val soonest = live.minOfOrNull { it.expireAtMs }
        val status = when {
            !armed && live.isNotEmpty() -> EmergencyMeshStatus.STANDBY
            !armed -> EmergencyMeshStatus.STANDBY
            searching && live.isNotEmpty() -> EmergencyMeshStatus.SEARCHING
            searching -> EmergencyMeshStatus.SEARCHING
            else -> EmergencyMeshStatus.CONNECTED
        }
        val note = when {
            !armed && live.isEmpty() ->
                "Off. Turn it on before you need it. A sealed message then stays on this phone for 4 hours."
            !armed ->
                "Listening is off. ${live.size} sealed message(s) are still saved until their deadline, and will be offered again when you turn mesh on."
            live.isEmpty() ->
                "Listening over Bluetooth. Nothing is waiting. A message you send while the relay is down is saved here for 4 hours."
            else ->
                "${live.size} sealed message(s) are on this phone. A nearby ArmorChat device can carry them until the deadline. The 4 hours do not restart when someone carries a copy."
        }
        _state.value = MeshRuntime(
            armed = armed,
            status = status,
            holding = live.size,
            emergencyHolding = live.count { it.emergency },
            soonestExpireAtMs = soonest,
            peersSeen = peersSeen,
            forwarded = forwarded,
            deliveredHere = deliveredHere,
            lastPath = lastPath,
            radioDetail = radioDetail,
            note = note,
        )
    }

    private fun startService(): Boolean {
        return try {
            ContextCompat.startForegroundService(context, Intent(context, EmergencyMeshService::class.java))
            true
        } catch (err: Exception) {
            SafeLog.event("mesh_service", "start_failed")
            false
        }
    }

    private fun CustodyBundle.toEntity() = MeshCustodyEntity(
        id = idHex,
        createdAtMs = createdAtMs,
        expireAtMs = expireAtMs,
        hopCount = hopCount,
        emergency = emergency,
        payload = payload,
        forwardedCount = forwardedCount,
    )

    private fun MeshCustodyEntity.toBundle() = CustodyBundle(
        id = MeshIds.parseHex(id),
        createdAtMs = createdAtMs,
        expireAtMs = expireAtMs,
        hopCount = hopCount,
        emergency = emergency,
        payload = payload,
        forwardedCount = forwardedCount,
    )

    private companion object {
        const val PREFS = "mesh_runtime"
        const val KEY_ARMED = "armed"
    }
}
