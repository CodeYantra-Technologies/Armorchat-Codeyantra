package com.codeyantra.armorchat.core.mesh

/**
 * Mesh-1K-R (fix D1): typed result of EmergencyMeshEngine.holdSealed (it used to return Unit, so callers
 * could not tell a durable custody admission from a refusal). A Room write failure is still THROWN
 * (unchanged), never reported as a result.
 */
sealed interface MeshHoldResult {
    /** Admitted AND written to mesh_custody (durable). The only result that may be logged as held. */
    data class Held(val idHex: String) : MeshHoldResult

    /** The identical bytes are already in custody (durable since their own admission). */
    data class Duplicate(val idHex: String) : MeshHoldResult

    /**
     * Refused by CustodyLedger.admit ([reason] = ledger code, e.g. "full", "size"). [retryQueued]: the same
     * sealed bytes are in the bounded retry list and, when the engine queued them, in its private retry file.
     */
    data class Rejected(val reason: String, val retryQueued: Boolean = false) : MeshHoldResult
}

/**
 * Mesh-1K-R / Mesh-1K-AW: bounded retry list for this phone's own sealed DELIVERY receipt
 * envelopes that custody refused as "full". Holds the IDENTICAL sealed bytes (same custody id
 * = MeshIds.idFor(payload)); nothing here can re-seal, re-encrypt or touch MLS.
 *
 * Bounds: at most [maxEntries] entries (a new entry beyond that is refused, never evicting older ones);
 * each entry lives at most [retentionMs] after it was queued (deadline fixed at enqueue time) and is
 * dropped after [maxAttempts] failed admission attempts. A clock rollback of more than
 * MeshPolicy.CLOCK_SKEW_MS below the queue time also drops the entry (fail safe: only liveness is lost),
 * so a rolled-back clock cannot keep an entry alive beyond its deadline in real time by more than the skew. Attempts are made only by EmergencyMeshEngine at event triggers (capacity freed:
 * sweep/dropDelivered/releaseOwnCustody; bounded trigger: encounter start in phaseFor), each trigger
 * trying each entry at most once and only when custody would admit it - so there is no loop.
 *
 * This object is only the in-memory list. EmergencyMeshEngine keeps the same entries in a private
 * mesh_hold_retry.v1 file and reloads that file after process death. The relay outbox is still memory-only.
 * Not thread-safe: the engine uses it only under its custody mutex.
 */
class MeshHoldRetry(
    val maxEntries: Int = MAX_ENTRIES,
    val maxAttempts: Int = MAX_ATTEMPTS,
    val retentionMs: Long = MeshPolicy.ABSOLUTE_TTL_MS,
) {
    class Entry internal constructor(
        val idHex: String,
        val payload: ByteArray,
        val emergency: Boolean,
        val queuedAtMs: Long,
        val deadlineMs: Long,
        attempts: Int,
    ) {
        var attempts: Int = attempts
            internal set
    }

    private val entries = linkedMapOf<String, Entry>()

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    fun ids(): List<String> = entries.keys.toList()

    /** Snapshot in queue order (oldest first). */
    fun pending(): List<Entry> = entries.values.toList()

    /**
     * Replace the list with [saved], oldest first. Copies payload bytes. Used by the engine after a
     * durable snapshot is written, or when that snapshot is loaded. Does not touch the disk.
     */
    internal fun replaceAll(saved: List<Entry>) {
        entries.clear()
        val seen = HashSet<String>(saved.size)
        for (e in saved) {
            if (entries.size >= maxEntries) break
            if (!seen.add(e.idHex)) continue
            entries[e.idHex] = Entry(
                e.idHex,
                e.payload.copyOf(),
                e.emergency,
                e.queuedAtMs,
                e.deadlineMs,
                e.attempts,
            )
        }
    }

    /**
     * Queue [payload] (exact bytes). True when it is (now or already) queued; false when the list is full
     * or the payload is unusable. An already-queued identical payload keeps its original deadline/attempts.
     */
    fun enqueue(payload: ByteArray, emergency: Boolean, nowMs: Long): Boolean {
        if (payload.isEmpty() || payload.size > MeshPolicy.MAX_PAYLOAD_BYTES) return false
        val idHex = MeshIds.hex(MeshIds.idFor(payload))
        if (entries.containsKey(idHex)) return true
        if (entries.size >= maxEntries) return false
        entries[idHex] = Entry(idHex, payload.copyOf(), emergency, nowMs, nowMs + retentionMs, 0)
        return true
    }

    fun remove(idHex: String) {
        entries.remove(idHex)
    }

    /** One failed admission attempt; returns true when the entry was dropped (attempts exhausted). */
    fun failed(idHex: String): Boolean {
        val entry = entries[idHex] ?: return false
        entry.attempts += 1
        if (entry.attempts >= maxAttempts) {
            entries.remove(idHex)
            return true
        }
        return false
    }

    /** Drops entries past their fixed deadline or queued "in the future" (clock rollback); returns the count. */
    fun prune(nowMs: Long): Int {
        val dead = entries.values.filter { nowMs >= it.deadlineMs || nowMs + MeshPolicy.CLOCK_SKEW_MS < it.queuedAtMs }
        dead.forEach { entries.remove(it.idHex) }
        return dead.size
    }

    companion object {
        const val MAX_ENTRIES: Int = 16
        const val MAX_ATTEMPTS: Int = 8
    }
}
