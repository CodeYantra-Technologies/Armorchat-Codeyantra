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
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    /** One thread so adapter-off cannot lose the race to a stale link callback. */
    private val presenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val mutex = Mutex()
    private val random = SecureRandom()
    private var ledger = CustodyLedger()
    private var peersSeen = 0
    private var forwarded = 0
    private var deliveredHere = 0
    private var lastPath = "Bluetooth"
    private var radioDetail = ""
    private val nonce = ByteArray(4).also { random.nextBytes(it) }

    /**
     * Mesh-1K-N (P2): durable delivered-here markers (see MeshDeliveredStore). Loaded lazily on first
     * use. Internal var only so JVM tests can inject a fault-injecting store before first use.
     */
    internal var delivered: MeshDeliveredStore = MeshDeliveredStore(
        file = File(context.noBackupFilesDir, MeshDeliveredStore.FILE_NAME),
        log = { detail -> runCatching { SafeLog.event("mesh_tombstone", detail) } },
    )

    /**
     * Mesh-1K-AW: private snapshot of sealed receipt bytes waiting for an own slot. Internal var only so
     * JVM tests can point each phone at its own file and inject write faults before first use.
     * Lock order: [mutex], then the store lock. Never the reverse.
     */
    internal var holdRetryStore: MeshHoldRetryStore = MeshHoldRetryStore(
        file = File(context.noBackupFilesDir, MeshHoldRetryStore.FILE_NAME),
        log = { detail -> runCatching { SafeLog.event("mesh_hold_retry_store", detail) } },
    )

    /**
     * In-memory copy of [holdRetryStore]. Used only under [mutex]. [restore] reloads it from the file.
     * Updated only after the matching snapshot is durable.
     */
    internal val holdRetry = MeshHoldRetry()

    /**
     * Test hook. After the retry file is durably replaced, the next commit throws [MeshHoldRetryWriteCrash]
     * before [holdRetry] changes. One-shot. Further commits refuse to write until [restore] reloads.
     */
    internal var crashAfterRetryWrite: Boolean = false

    /** True after [crashAfterRetryWrite] until [restore] reloads. Commits do not clobber the file. */
    private var retryMemoryStale: Boolean = false

    /** Mesh-1K-AS: memory-only timestamps of recent foreign admissions (rate window). Used under [mutex]. */
    private val foreignAdmissions = ArrayDeque<Long>()

    @Volatile
    var armed: Boolean = prefs.getBoolean(KEY_ARMED, false)
        private set

    private var phase: MeshRadioPhase = MeshPresence.phaseAfterProcessRestart(armed)
    private var radioBlocked = false

    private val _state = MutableStateFlow(
        MeshRuntime(
            armed = armed,
            status = MeshPresence.status(armed, phase, radioBlocked),
        ),
    )
    val state: StateFlow<MeshRuntime> = _state.asStateFlow()

    private val _restores = MutableStateFlow(0L)

    /** Mesh-1H-2: bumped after every [restore] reload so MeshCustodyRecovery can re-offer held rows. */
    val restores: StateFlow<Long> = _restores.asStateFlow()

    fun resumeIfArmed() {
        scope.launch {
            mutex.withLock {
                if (armed) {
                    phase = MeshPresence.phaseAfterProcessRestart(true)
                    radioBlocked = false
                }
            }
            restore()
            if (armed) startService()
        }
    }

    suspend fun arm(): Boolean {
        armed = true
        prefs.edit().putBoolean(KEY_ARMED, true).apply()
        mutex.withLock {
            phase = MeshRadioPhase.SEARCHING
            radioBlocked = false
        }
        restore()
        return startService()
    }

    fun disarm() {
        armed = false
        prefs.edit().putBoolean(KEY_ARMED, false).apply()
        context.stopService(Intent(context, EmergencyMeshService::class.java))
        scope.launch {
            mutex.withLock {
                phase = MeshRadioPhase.IDLE
                radioBlocked = false
            }
            restore()
        }
    }

    /**
     * Radio lifecycle. [MeshRadioEvent.LINK_UP] is the only event that can
     * report a peer, and the radio emits it from a GATT connection callback.
     */
    fun onRadioEvent(event: MeshRadioEvent) {
        presenceScope.launch {
            mutex.withLock {
                val next = MeshPresence.apply(armed, phase, radioBlocked, event)
                phase = next.first
                radioBlocked = next.second
                publishLocked(clock.nowEpochMs())
            }
        }
    }

    suspend fun sweep() = mutex.withLock {
        val now = clock.nowEpochMs()
        val dead = ledger.sweep(now)
        dead.forEach { dao.delete(it.idHex) }
        retryPendingHoldsLocked(now) // Mesh-1K-R (D1): capacity may have been freed
        publishLocked(now)
    }

    /**
     * Mesh-1K-R (D1) / Mesh-1K-AW: returns the typed admission result. [MeshHoldResult.Held] only after the
     * row is in Room (persistAdmissionLocked returned); a Room failure is still thrown (unchanged). Policy
     * unchanged (CustodyLedger.admit with the caller's [emergency]). [retryIfFull] (own DELIVERY receipts
     * only): a "full" refusal keeps the IDENTICAL bytes in [holdRetry] only after [holdRetryStore] has
     * the same snapshot. Never re-sealed here. [MeshHoldResult.Duplicate] only when the live row is those
     * same bytes. A custody id that already holds different bytes is [MeshHoldResult.Rejected] "identity"
     * and the row is left in place.
     */
    suspend fun holdSealed(
        payload: ByteArray,
        emergency: Boolean = true,
        retryIfFull: Boolean = false,
    ): MeshHoldResult = mutex.withLock {
        val now = clock.nowEpochMs()
        ledger.sweep(now).forEach { dao.delete(it.idHex) }
        val before = ledger.snapshot()
        val out = when (val result = ledger.admit(payload, now, emergency)) {
            is AdmitResult.Held -> {
                persistAdmissionLocked(before, result.bundle, result.evicted)
                removeDurable(result.bundle.idHex)
                SafeLog.event("mesh_hold", "bytes=${payload.size} ttl_h=4")
                MeshHoldResult.Held(result.bundle.idHex)
            }
            is AdmitResult.Duplicate -> {
                if (sameSealed(result.bundle, payload)) {
                    removeDurable(result.bundle.idHex)
                    MeshHoldResult.Duplicate(result.bundle.idHex)
                } else {
                    SafeLog.event("mesh_hold_rejected", "reason=identity")
                    MeshHoldResult.Rejected("identity")
                }
            }
            is AdmitResult.Rejected -> {
                if (retryIfFull && result.reason == "full") {
                    val queued = enqueueDurable(payload, emergency, now)
                    SafeLog.event(
                        "mesh_hold_rejected",
                        "reason=${result.reason} retry=${if (queued) "queued" else "dropped"} pending=${holdRetry.size}",
                    )
                    MeshHoldResult.Rejected(result.reason, retryQueued = queued)
                } else {
                    SafeLog.event("mesh_hold_rejected", "reason=${result.reason}")
                    MeshHoldResult.Rejected(result.reason)
                }
            }
        }
        publishLocked(now)
        out
    }

    /** Mesh-1K-R (D1): read-only count of receipt hold retries (diagnostics/tests). */
    suspend fun pendingHoldRetries(): Int = mutex.withLock { holdRetry.size }

    /**
     * Mesh-1K-AW: one bounded pass over [holdRetry] (caller holds [mutex], after its own sweep).
     * A live custody row with the same id is resolved even when the own pool is full: matching sealed
     * bytes drop the retry entry only after that row is already durable; a different payload burns one
     * attempt and leaves the row. New admissions run only while an own slot is free, so a full pool
     * costs no attempt. Room admission is durable before the retry entry is removed. A failed removal
     * leaves the entry for a later match. Never re-seals, never touches MLS or message status.
     */
    private suspend fun retryPendingHoldsLocked(now: Long) {
        if (retryMemoryStale || holdRetry.isEmpty()) return
        val timed = holdRetry.pending().filter { stillQueued(it, now) }
        val expired = holdRetry.size - timed.size
        if (expired > 0 && !commit(timed)) {
            runCatching { SafeLog.event("mesh_hold_retry", "prune=write_failed pending=${holdRetry.size}") }
            return
        }
        var held = 0
        var duplicate = 0
        var failed = 0
        var exhausted = 0
        var dropped = 0
        for (entry in holdRetry.pending()) {
            if (!stillQueued(entry, now)) continue
            val row = ledger.snapshot().firstOrNull { it.idHex == entry.idHex && it.expireAtMs > now } ?: continue
            if (sameSealed(row, entry.payload)) {
                if (removeDurable(entry.idHex)) duplicate++
            } else if (recordFailure(entry.idHex)) {
                failed++
                if (holdRetry.pending().none { it.idHex == entry.idHex }) exhausted++
            }
        }
        for (entry in holdRetry.pending()) {
            if (holdRetry.pending().none { it.idHex == entry.idHex }) continue
            if (!stillQueued(entry, now)) continue
            if (ledger.snapshot().any { it.idHex == entry.idHex && it.expireAtMs > now }) continue
            if (ledger.snapshot().count { it.hopCount == 0 } >= MeshPolicy.OWN_MAX) break
            if (!canAdmitWithoutExtending(entry.payload, now)) {
                if (removeDurable(entry.idHex)) dropped++
                continue
            }
            val before = ledger.snapshot()
            when (val result = ledger.admit(entry.payload, now, entry.emergency)) {
                is AdmitResult.Held -> {
                    try {
                        persistAdmissionLocked(before, result.bundle, result.evicted)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (c: MeshHoldRetryWriteCrash) {
                        throw c
                    } catch (_: Exception) {
                        if (recordFailure(entry.idHex)) {
                            failed++
                            if (holdRetry.pending().none { it.idHex == entry.idHex }) exhausted++
                        }
                        continue
                    }
                    runCatching { SafeLog.event("mesh_hold", "bytes=${entry.payload.size} ttl_h=4 retry=1") }
                    if (removeDurable(entry.idHex)) held++
                }
                is AdmitResult.Duplicate -> {
                    if (sameSealed(result.bundle, entry.payload)) {
                        if (removeDurable(entry.idHex)) duplicate++
                    } else if (recordFailure(entry.idHex)) {
                        failed++
                        if (holdRetry.pending().none { it.idHex == entry.idHex }) exhausted++
                    }
                }
                is AdmitResult.Rejected -> {
                    if (result.reason == "full") break
                    if (recordFailure(entry.idHex)) {
                        failed++
                        if (holdRetry.pending().none { it.idHex == entry.idHex }) exhausted++
                    }
                }
            }
        }
        if (expired + dropped + held + duplicate + failed > 0) {
            runCatching {
                SafeLog.event(
                    "mesh_hold_retry",
                    "held=$held duplicate=$duplicate failed=$failed exhausted=$exhausted expired=$expired pending=${holdRetry.size}",
                )
            }
        }
    }

    private fun stillQueued(entry: MeshHoldRetry.Entry, now: Long): Boolean {
        if (now >= entry.deadlineMs) return false
        if (now + MeshPolicy.CLOCK_SKEW_MS < entry.queuedAtMs) return false
        return true
    }

    /** Authenticated outer expires_at in epoch ms, or null when missing, non-positive, or not representable. */
    private fun authenticatedExpiryMs(payload: ByteArray): Long? {
        val seconds = com.codeyantra.armorchat.messaging.MeshDelivery.outerExpiresAtSeconds(payload) ?: return null
        if (seconds <= 0L || seconds > Long.MAX_VALUE / 1000L) return null
        return seconds * 1000L
    }

    private fun custodyWouldOutlive(now: Long, authMs: Long): Boolean {
        if (now > Long.MAX_VALUE - MeshPolicy.ABSOLUTE_TTL_MS) return true
        return now + MeshPolicy.ABSOLUTE_TTL_MS > authMs
    }

    /** False when a new hop-0 row stamped now+4h would pass the sealed envelope's own expiry. */
    private fun canAdmitWithoutExtending(payload: ByteArray, now: Long): Boolean {
        val auth = authenticatedExpiryMs(payload) ?: return false
        if (now >= auth) return false
        return !custodyWouldOutlive(now, auth)
    }

    private fun sameSealed(bundle: CustodyBundle, payload: ByteArray): Boolean {
        return bundle.payload.contentEquals(payload) && bundle.id.contentEquals(MeshIds.idFor(payload))
    }

    private fun enqueueDurable(payload: ByteArray, emergency: Boolean, now: Long): Boolean {
        if (retryMemoryStale) return false
        if (payload.isEmpty() || payload.size > MeshPolicy.MAX_PAYLOAD_BYTES) return false
        val auth = authenticatedExpiryMs(payload) ?: return false
        if (now >= auth || custodyWouldOutlive(now, auth)) return false
        val idHex = MeshIds.hex(MeshIds.idFor(payload))
        if (holdRetry.pending().any { it.idHex == idHex }) return true
        if (holdRetry.size >= holdRetry.maxEntries) return false
        val deadline = minOf(now + MeshPolicy.ABSOLUTE_TTL_MS, auth)
        val entry = MeshHoldRetry.Entry(idHex, payload.copyOf(), emergency, now, deadline, 0)
        return commit(holdRetry.pending() + entry)
    }

    /** File replace first. Memory changes only after it returns. False leaves both as they were. */
    private fun commit(next: List<MeshHoldRetry.Entry>): Boolean {
        if (retryMemoryStale) return false
        if (!holdRetryStore.replace(next.map { it.toSaved() })) return false
        if (crashAfterRetryWrite) {
            crashAfterRetryWrite = false
            retryMemoryStale = true
            throw MeshHoldRetryWriteCrash()
        }
        holdRetry.replaceAll(next)
        return true
    }

    private fun removeDurable(idHex: String): Boolean {
        if (holdRetry.pending().none { it.idHex == idHex }) return true
        return commit(holdRetry.pending().filter { it.idHex != idHex })
    }

    /** Persist attempts+1, or drop the entry at [MeshHoldRetry.maxAttempts]. False consumes nothing. */
    private fun recordFailure(idHex: String): Boolean {
        val current = holdRetry.pending()
        val entry = current.firstOrNull { it.idHex == idHex } ?: return false
        val nextAttempts = entry.attempts + 1
        val next = if (nextAttempts >= holdRetry.maxAttempts) {
            current.filter { it.idHex != idHex }
        } else {
            current.map { e ->
                if (e.idHex != idHex) e
                else MeshHoldRetry.Entry(e.idHex, e.payload.copyOf(), e.emergency, e.queuedAtMs, e.deadlineMs, nextAttempts)
            }
        }
        return commit(next)
    }

    private fun loadHoldRetryLocked() {
        holdRetry.replaceAll(holdRetryStore.load().map { savedToEntry(it) })
        retryMemoryStale = false
    }

    private fun MeshHoldRetry.Entry.toSaved() = MeshHoldRetryStore.Saved(
        id = MeshIds.parseHex(idHex),
        emergency = emergency,
        queuedAtMs = queuedAtMs,
        deadlineMs = deadlineMs,
        attempts = attempts,
        payload = payload,
    )

    private fun savedToEntry(saved: MeshHoldRetryStore.Saved) = MeshHoldRetry.Entry(
        idHex = MeshIds.hex(saved.id),
        payload = saved.payload.copyOf(),
        emergency = saved.emergency,
        queuedAtMs = saved.queuedAtMs,
        deadlineMs = saved.deadlineMs,
        attempts = saved.attempts,
    )

    suspend fun phaseFor(remoteIds: Set<String>, wifiDirect: Boolean): MeshPhase {
        return mutex.withLock {
            val now = clock.nowEpochMs()
            ledger.sweep(now).forEach { dao.delete(it.idHex) }
            // Mesh-1K-R (D1): bounded trigger at encounter start (before the snapshot, so a re-held receipt
            // is offered in this same encounter). No attempt while custody is full of emergency rows.
            retryPendingHoldsLocked(now)
            val live = ledger.snapshot().filter { it.live(now) }
            publishLocked(now)
            // Mesh-1K-N (P2): append up to 64 delivered-here custody ids (not already held) to the
            // SAME ids frame. Peers only skip offering those to this phone; beacon count/digest stay
            // over held rows. Never an ACK: nothing anywhere deletes custody from remote ids.
            val markers = delivered.advertised(now, live.mapTo(HashSet()) { it.idHex })
            if (markers.isNotEmpty()) {
                runCatching { SafeLog.event("mesh_phase", "held=${live.size} delivered=${markers.size}") }
            }
            MeshPhase(
                beacon = MeshBeacon(
                    holding = live.size,
                    emergency = live.any { it.emergency },
                    wifiDirect = wifiDirect,
                    minutesLeft = BeaconCodec.minutesLeft(ledger.soonestExpireAt(now), now),
                    nonce = nonce.copyOf(),
                    digest = BeaconCodec.digest(live.map { it.id }),
                ),
                ids = live.map { it.id } + markers.map { MeshIds.parseHex(it) },
            )
        }
    }

    suspend fun offers(remoteIds: Collection<ByteArray>): List<ByteArray> = mutex.withLock {
        val now = clock.nowEpochMs()
        ledger.offer(remoteIds.map { MeshIds.hex(it) }.toSet(), now).map { BundleCodec.encode(it) }
    }

    suspend fun ingest(encoded: ByteArray, path: RadioPath): MeshAdmission = mutex.withLock {
        val now = clock.nowEpochMs()
        val decoded = BundleCodec.decode(encoded)
        if (decoded !is BundleDecode.Ok) return MeshAdmission.Rejected(MeshRejection.MALFORMED)
        // Mesh-1D-B: same sweep as holdSealed/phaseFor, before the capacity decision in accept.
        val pruned = ledger.sweep(now)
        pruned.forEach { dao.delete(it.idHex) }
        if (pruned.isNotEmpty()) publishLocked(now)
        // Mesh-1K-AS: relay-side SANITY bound only. The cleartext envelope expires_at is unauthenticated here
        // (only the destination binds it to the MLS inner record), so this catches obvious/malformed TTL
        // extension; it does NOT prevent a malicious refresh that stays inside the envelope lifetime.
        val envExp = com.codeyantra.armorchat.messaging.MeshDelivery.outerExpiresAtSeconds(decoded.bundle.payload)
        if (envExp != null && (envExp <= 0L || decoded.bundle.expireAtMs > envExp * 1000L + MeshPolicy.CLOCK_SKEW_MS)) {
            return MeshAdmission.Rejected(MeshRejection.DEADLINE)
        }
        // Mesh-1K-AS: foreign admission rate window (not keyed on any origin id: no Sybil resistance).
        while (foreignAdmissions.isNotEmpty() && foreignAdmissions.first() <= now - MeshPolicy.FOREIGN_RATE_WINDOW_MS) {
            foreignAdmissions.removeFirst()
        }
        if (foreignAdmissions.size >= MeshPolicy.FOREIGN_RATE_MAX && !ledger.contains(decoded.bundle.idHex)) {
            return MeshAdmission.Rejected(MeshRejection.RATE)
        }
        val before = ledger.snapshot()
        when (val result = ledger.accept(decoded.bundle, now)) {
            is AcceptResult.Stored -> {
                persistAdmissionLocked(before, result.bundle, result.evicted)
                foreignAdmissions.addLast(now)
                lastPath = if (path == RadioPath.WIFI_DIRECT) "Wi-Fi Direct" else "Bluetooth"
                peersSeen += 1
                publishLocked(now)
                MeshAdmission.Admitted(result.bundle.idHex)
            }
            is AcceptResult.Duplicate -> MeshAdmission.Duplicate(result.bundle.idHex)
            is AcceptResult.Rejected -> MeshAdmission.Rejected(MeshRejection.fromLedger(result.reason))
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

    /**
     * Mesh-1H-2: read-only snapshot for MeshCustodyRecovery: RECEIVED held rows (hopCount >= 1:
     * CustodyLedger.accept always stores hop + 1) whose deadline has not passed. Rows this phone
     * sealed itself (holdSealed, hopCount == 0) are outbound and never offered to messaging.
     * hopCount says nothing about the recipient: a candidate is NOT assumed to be for this phone,
     * only messaging's trial decrypt decides. Rows at MAX_HOPS (no longer forwarded) are still
     * candidates for local delivery. Nothing is mutated, admitted or re-counted.
     */
    suspend fun recoveryCandidates(): List<CustodyBundle> = mutex.withLock {
        val now = clock.nowEpochMs()
        ledger.snapshot().filter { it.hopCount >= 1 && it.expireAtMs > now }
    }

    /**
     * Mesh-1K-N (P2): record a delivered-here marker for [payload] (custody id = MeshIds.idFor).
     * Call only after the inbox row is durably committed (or already durable, DUPLICATE). The
     * retention deadline follows the held row's absolute expireAtMs when this phone still holds it.
     * Returns true only when the marker is on disk; false never claims suppression.
     */
    suspend fun recordDelivered(payload: ByteArray, messageId: String, binding: String): Boolean = mutex.withLock {
        val now = clock.nowEpochMs()
        val idHex = MeshIds.hex(MeshIds.idFor(payload))
        val expireAt = ledger.snapshot().firstOrNull { it.idHex == idHex }?.expireAtMs
        try {
            delivered.record(idHex, messageId, binding, expireAt, now)
        } catch (_: Exception) {
            false
        }
    }

    /** Mesh-1K-N (P2): true when [payload] (exact bytes) already has a live delivered-here marker. */
    fun hasDeliveredMarker(payload: ByteArray, messageId: String): Boolean =
        delivered.contains(MeshIds.hex(MeshIds.idFor(payload)), messageId, clock.nowEpochMs())

    /**
     * Mesh-1K-O (P3): live custody rows this phone sealed itself (hopCount 0: holdSealed). Read-only.
     * Used by messaging to bind a validated delivery receipt to this phone's own envelope copy.
     */
    suspend fun ownHeld(): List<CustodyBundle> = mutex.withLock {
        val now = clock.nowEpochMs()
        ledger.snapshot().filter { it.hopCount == 0 && it.expireAtMs > now }
    }

    /**
     * Mesh-1K-O (P3): run [durable] (one Room transaction that deletes this phone's OWN custody rows after
     * a VALIDATED delivery receipt, see MeshInboxCommit.Release) under this mutex, then drop from memory
     * exactly the own ids it reports deleted. Holding the mutex across the transaction means phaseFor /
     * offers / markForwarded (which re-upserts forwarded rows from memory) cannot run between the Room
     * delete and the memory drop, so a released row is never re-offered or resurrected. If [durable]
     * throws, memory is untouched. Never called from inventory ids.
     * Lock order: messaging mutex -> this mutex -> Room (same order as sendText -> holdSealed).
     */
    suspend fun <T> releaseOwnCustody(durable: suspend () -> Pair<T, Collection<String>>): T = mutex.withLock {
        val (result, deleted) = durable()
        val own = ledger.snapshot().filter { it.hopCount == 0 }.mapTo(HashSet()) { it.idHex }
        deleted.filter { it in own }.forEach { ledger.drop(it) }
        if (deleted.isNotEmpty()) {
            runCatching { SafeLog.event("mesh_ack", "released_custody=${deleted.size}") }
            retryPendingHoldsLocked(clock.nowEpochMs()) // Mesh-1K-R (D1): capacity freed
        }
        publishLocked(clock.nowEpochMs())
        result
    }

    suspend fun dropDelivered(idHex: String) = mutex.withLock {
        ledger.drop(idHex)
        dao.delete(idHex)
        deliveredHere += 1
        retryPendingHoldsLocked(clock.nowEpochMs()) // Mesh-1K-R (D1): capacity freed
        publishLocked(clock.nowEpochMs())
    }

    /**
     * Mesh-1D-C: write an admission to Room so memory and Room hold the same rows. Victim delete
     * first: if it fails nothing changed in Room, so memory is reset to [before]. If the upsert
     * then fails, Room lacks only the new row, so it is dropped from memory too. Either failure
     * is rethrown (never silent). Caller holds [mutex].
     */
    private suspend fun persistAdmissionLocked(
        before: List<CustodyBundle>,
        stored: CustodyBundle,
        evicted: CustodyBundle?,
    ) {
        if (evicted != null) {
            try {
                dao.delete(evicted.idHex)
            } catch (err: Exception) {
                ledger = CustodyLedger(before)
                throw err
            }
        }
        try {
            dao.upsert(stored.toEntity())
        } catch (err: Exception) {
            ledger.drop(stored.idHex)
            throw err
        }
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
            loadHoldRetryLocked()
            retryPendingHoldsLocked(now)
            publishLocked(now)
        }
        _restores.value = _restores.value + 1
    }

    private fun publishLocked(now: Long) {
        val live = ledger.snapshot().filter { it.live(now) }
        val soonest = live.minOfOrNull { it.expireAtMs }
        val status = MeshPresence.status(armed, phase, radioBlocked)
        val note = when {
            !armed && live.isEmpty() ->
                "Off. Turn it on before you need it. A sealed message then stays on this phone for 4 hours."
            !armed ->
                "Listening is off. ${live.size} sealed message(s) are still saved until their deadline, and will be offered again when you turn mesh on."
            status == EmergencyMeshStatus.UNAVAILABLE ->
                "Bluetooth is off or nearby-device access is missing. Saved messages stay on this phone until their deadline."
            status == EmergencyMeshStatus.CONNECTING ->
                "Opening a Bluetooth link. Saved messages stay on this phone until that link is up."
            status == EmergencyMeshStatus.CONNECTED && live.isEmpty() ->
                "A Bluetooth link is open. Nothing is waiting on this phone."
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

/** Mesh-1K-AW test hook. The retry file was replaced and the in-memory list was not. */
internal class MeshHoldRetryWriteCrash : Exception("mesh_hold_retry write crash")
