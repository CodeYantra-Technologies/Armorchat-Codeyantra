package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.core.mesh.MeshDirect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mesh-1A.1: parse/dispatch step of ChatMessagingService.deliverMesh, moved unchanged so JVM
 * tests run the same code. [offer] is SessionOrchestrator.offerMesh in production.
 */
internal object MeshDelivery {
    suspend fun deliver(
        payload: ByteArray,
        offer: suspend (canonical: String, persistInbound: suspend (String, InboundRecord) -> Unit) -> Boolean,
        persistInbound: suspend (peerPin: String, inbound: InboundRecord) -> Unit,
    ): Boolean {
        if (payload.isEmpty()) return false
        val raw = runCatching { payload.toString(Charsets.UTF_8) }.getOrNull() ?: return false
        if (!raw.startsWith("{")) return false
        return offer(raw, persistInbound)
    }

    /**
     * Mesh-1D-A: [deliver] under [lock], which in production is ChatMessagingService.mutex, the
     * lock relay sync already holds. Mesh decrypt, offerMesh snapshot/restore and persistInbound
     * then never interleave with relay MLS transitions. Kotlin's Mutex is not re-entrant: callers
     * must not already hold [lock] (EmergencyMeshService.ingest, the only caller, does not).
     */
    suspend fun deliverSerialized(
        lock: Mutex,
        payload: ByteArray,
        offer: suspend (canonical: String, persistInbound: suspend (String, InboundRecord) -> Unit) -> Boolean,
        persistInbound: suspend (peerPin: String, inbound: InboundRecord) -> Unit,
    ): Boolean = lock.withLock { deliver(payload, offer, persistInbound) }

    /**
     * Mesh-1K-M: DUPLICATE = the envelope is already in the durable inbox (same message id, same
     * ciphertext digest): nothing was decrypted, staged, committed or receipted. CONFLICT = a
     * durable row already owns this message id but the envelope does not match it (different
     * ciphertext digest, or this phone's own outgoing row): rejected, nothing touched or overwritten.
     */
    enum class Outcome {
        DELIVERED,
        DUPLICATE,
        CONFLICT,
        DECLINED,
        PERSIST_FAILED,
        CANCELLED,
        LOCK_TIMEOUT,
        ;

        /** True only when the message is durably in this inbox (custody copy may be dropped). */
        val inInbox: Boolean get() = this == DELIVERED || this == DUPLICATE
    }

    /** Mesh-1K-M: durable-inbox state of an envelope's outer message id (see [durableState]). */
    enum class Durable { NONE, SAME, CONFLICT }

    /**
     * Mesh-1K-M: durable pre-check run by [deliverDurable] under the lock, before any load, MLS
     * checkpoint, trial decrypt, staging, commit or receipt work.
     *  - NONE: no parsable outer id / ciphertext, or no `messages` row with that id: normal path.
     *  - SAME: an INBOUND row with this id whose receiptCiphertextSha256 equals this envelope's
     *    ciphertext digest (same function ReceiptMessagingPeer.processLeased stores). That
     *    ciphertext was already authenticated and committed here, so this copy is a duplicate.
     *  - CONFLICT: a row owns this id but is outgoing (this phone's own message) or was committed
     *    from a different ciphertext (or none recorded). Never decrypted, never overwritten.
     * The outer id and ciphertext are unauthenticated: SAME only lets the caller skip work and drop
     * its OWN just-admitted copy of an exact replay; it never accepts, delivers or deletes anything
     * else (no sender custody, no other row).
     */
    suspend fun durableState(payload: ByteArray, findById: suspend (String) -> MessageEntity?): Durable {
        val id = outerMessageId(payload) ?: return Durable.NONE
        val digest = outerCiphertextDigest(payload) ?: return Durable.NONE
        val row = findById(id) ?: return Durable.NONE
        if (row.isOutgoing) return Durable.CONFLICT
        return if (row.receiptCiphertextSha256 == digest) Durable.SAME else Durable.CONFLICT
    }

    /** Mesh-1K-M: libraryCiphertextSha256 of the outer `encrypted_payload`, or null if unusable. */
    internal fun outerCiphertextDigest(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val raw = runCatching { payload.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        if (!raw.startsWith("{")) return null
        val ct = runCatching { org.json.JSONObject(raw).getString("encrypted_payload") }.getOrNull() ?: return null
        return runCatching { libraryCiphertextSha256(ct) }.getOrNull()
    }

    /**
     * Mesh-1H-2: the cleartext outer `message_id` of a Mesh envelope, or null. UNAUTHENTICATED
     * (ReceiptMessagingPeer.bindInbound only binds it to the inner id after decrypt), so callers
     * may use it to SKIP work, never to accept or deliver anything.
     */
    fun outerMessageId(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val raw = runCatching { payload.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        if (!raw.startsWith("{")) return null
        return runCatching { org.json.JSONObject(raw).getString("message_id") }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Mesh-1K-AS: the cleartext outer `expires_at` (epoch seconds) of a Mesh envelope. null = not a JSON
     * envelope or no `expires_at` (no sanity check applies); a value <= 0 = present but malformed.
     * UNAUTHENTICATED at carriers (bindInbound binds it to the MLS inner record at the destination only),
     * so it may only be used to REFUSE obviously inconsistent custody, never to accept or deliver.
     */
    fun outerExpiresAtSeconds(payload: ByteArray): Long? {
        if (payload.isEmpty()) return null
        val raw = runCatching { payload.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        if (!raw.startsWith("{")) return null
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
        if (!obj.has("expires_at")) return null
        return when (val v = obj.opt("expires_at")) {
            is Int -> v.toLong()
            is Long -> v
            else -> 0L
        }
    }

    /**
     * Mesh-1K-R: the cleartext outer `recipient_mailbox` of a Mesh envelope, or null. UNAUTHENTICATED
     * (any carrier can read or forge it; bindInbound checks the INNER mailbox after decrypt): a hint
     * that may only select work for the direct local-delivery path, never accept or deliver anything.
     */
    fun outerRecipientMailbox(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val raw = runCatching { payload.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        if (!raw.startsWith("{")) return null
        return runCatching { org.json.JSONObject(raw).getString("recipient_mailbox") }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Mesh-1K-R (fix A) lock-free pre-filter. [known] = this phone's own mailbox ids as last seen under the
     * messaging lock, or null when not yet known (cold start: links not loaded). Known -> exact membership;
     * unknown -> true for any parsable mailbox (the in-lock [deliverDurable] `addressed` gate then decides
     * after load, so an unknown state costs at most one lock acquisition per budgeted attempt and no decrypt).
     */
    fun addressedHere(payload: ByteArray, known: Set<String>?): Boolean {
        val mailbox = outerRecipientMailbox(payload) ?: return false
        return known?.contains(mailbox) ?: true
    }

    /** Mesh-1K-R (fix A): deliverMesh's (ok, final outcome) -> truthful direct-delivery outcome. */
    fun direct(ok: Boolean, outcome: Outcome?): MeshDirect = when {
        ok && outcome == Outcome.DELIVERED -> MeshDirect.DELIVERED
        ok && outcome == Outcome.DUPLICATE -> MeshDirect.DUPLICATE
        ok -> MeshDirect.FAILED // never trust a true without an inInbox outcome
        outcome == Outcome.DECLINED || outcome == Outcome.CONFLICT -> MeshDirect.REJECTED
        else -> MeshDirect.FAILED // LOCK_TIMEOUT, PERSIST_FAILED, CANCELLED, or no outcome reported
    }

    /**
     * Mesh-1H-2: true when the envelope's outer message id already has a durable `messages` row
     * (an inbound row = already delivered here; an outbound row = this phone's own message).
     * Used by MeshCustodyRecovery before any trial decrypt, so a held row whose message is already
     * durable is never decrypted again (no second MLS advance, no second row or notification),
     * whatever the MLS engine does with a replayed ciphertext. [findById] = MessageDao.findById.
     */
    suspend fun alreadyStored(payload: ByteArray, findById: suspend (String) -> Any?): Boolean {
        val id = outerMessageId(payload) ?: return false
        return findById(id) != null
    }

    /**
     * Mesh-1G': upper bound on waiting to ACQUIRE [deliverDurable]'s lock, below NearbyMeshRadio's
     * 20 s encounter budget so a long relay hold yields a clean LOCK_TIMEOUT (false, nothing
     * touched, custody row kept) instead of an encounter-level cancellation. It bounds only the
     * wait; once acquired, delivery runs without any extra timeout.
     */
    internal const val MESH_LOCK_WAIT_MS = 12_000L

    /**
     * Mesh-1F: [deliverSerialized] plus MLS durability. Under [lock] (ChatMessagingService.mutex):
     *  1. [load] cold-start sessions (loadIfNeeded) so an empty link table cannot decline.
     *  2. [checkpoint] in-memory MLS + receipt store, then [deliver] (offerMesh -> processLeased ->
     *     persistInbound commits the inbox row).
     *  3. [commit] the matched session (persist(contact) -> contact_sessions) non-cancellably,
     *     and only then report true, so the custody copy is dropped only after MLS is durable.
     *  4. If [commit] fails: [rollback] memory to the checkpoint (= what is still durable) and
     *     return false. The inbox row stays; a retry re-decrypts from the same state (one net MLS
     *     advance) and persistInbound's findById drops the duplicate row.
     * NOT atomic: a crash between the inbox insert and [commit] leaves the row committed and the
     * MLS session durable at its pre-message state (same as the rollback case).
     * Cancellation propagates; before [commit] starts it rolls memory back first.
     *
     * Mesh-1G': only lock acquisition is bounded by [lockWaitMs]. If it elapses, report
     * LOCK_TIMEOUT and return false without load/checkpoint/decrypt/commit. Parent cancellation
     * while waiting is rethrown (withTimeoutOrNull only maps its own timeout to null).
     * kotlinx Mutex.lock releases a lock it acquired if it then resumes with cancellation (prompt
     * cancellation guarantee, 1.10.2); the holdsLock(owner) check is a defensive second guard.
     *
     * Mesh-1K-M: right after acquiring the lock (before [load]) [durable] classifies the envelope
     * against the durable inbox. SAME -> report DUPLICATE and return true (the caller's custody
     * cleanup is unchanged) with no load/checkpoint/decrypt/commit/receipt. CONFLICT -> report
     * CONFLICT and return false, nothing touched. A failing pre-check is PERSIST_FAILED / false
     * (fail closed, nothing touched). After a successful [commit], [committedAs] names what the
     * commit actually did (DELIVERED for a new row, DUPLICATE when every staged row already
     * existed with the same digest, CONFLICT when one existed with a different digest); only
     * DELIVERED/DUPLICATE return true. Defaults keep the pre-1K-M behaviour for other callers.
     *
     * Mesh-1K-O (P3): [afterCommit] runs under the lock, non-cancellably, only when the outcome is
     * DELIVERED (i.e. after the inbox row / receipt effects and the MLS session row are durable). Its
     * failure is swallowed: it can never turn a committed delivery into a failure. Default: nothing.
     *
     * Mesh-1K-R (fix A): [addressed], when given, runs under the lock right after [load] and before
     * [checkpoint]/decrypt; false (or a throw) -> DECLINED, nothing touched. Used only by the direct
     * (slot-less) path to confirm the outer mailbox against this phone's loaded links. It adds a filter;
     * it never replaces any authentication step. Default null: unchanged behaviour.
     */
    suspend fun <C> deliverDurable(
        lock: Mutex,
        payload: ByteArray,
        load: suspend () -> Unit,
        checkpoint: () -> C,
        rollback: (C) -> Unit,
        offer: suspend (canonical: String, persistInbound: suspend (String, InboundRecord) -> Unit) -> Boolean,
        persistInbound: suspend (peerPin: String, inbound: InboundRecord) -> Unit,
        commit: suspend (peerPin: String) -> Unit,
        clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
        report: (waitMs: Long, processMs: Long, outcome: Outcome) -> Unit = MeshTiming::mesh,
        lockWaitMs: Long = MESH_LOCK_WAIT_MS,
        durable: suspend () -> Durable = { Durable.NONE },
        committedAs: () -> Outcome = { Outcome.DELIVERED },
        afterCommit: suspend () -> Unit = {},
        addressed: (suspend () -> Boolean)? = null,
    ): Boolean {
        val requested = clockMs()
        val owner = Any()
        val acquired = withTimeoutOrNull(lockWaitMs) {
            lock.lock(owner)
            true
        }
        if (acquired == null) {
            if (lock.holdsLock(owner)) lock.unlock(owner)
            currentCoroutineContext().ensureActive() // parent cancelled at the same instant
            report(clockMs() - requested, 0L, Outcome.LOCK_TIMEOUT)
            return false
        }
        return try {
            val start = clockMs()
            var outcome = Outcome.DECLINED
            try {
                val state = try {
                    durable()
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    outcome = Outcome.PERSIST_FAILED
                    return false
                }
                when (state) {
                    Durable.SAME -> {
                        outcome = Outcome.DUPLICATE
                        return true
                    }
                    Durable.CONFLICT -> {
                        outcome = Outcome.CONFLICT
                        return false
                    }
                    Durable.NONE -> Unit
                }
                load()
                if (addressed != null) {
                    val here = try {
                        addressed()
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        false
                    }
                    if (!here) return false // outcome stays DECLINED
                }
                val saved = checkpoint()
                var matched: String? = null
                val ok = try {
                    deliver(payload, offer) { pin, inbound ->
                        matched = pin
                        persistInbound(pin, inbound)
                    }
                } catch (c: CancellationException) {
                    rollback(saved)
                    outcome = Outcome.CANCELLED
                    throw c
                }
                if (!ok) return false
                val pin = matched
                if (pin == null) {
                    rollback(saved)
                    outcome = Outcome.PERSIST_FAILED
                    return false
                }
                val committed = withContext(NonCancellable) {
                    try {
                        commit(pin)
                        true
                    } catch (e: Exception) {
                        rollback(saved)
                        false
                    }
                }
                if (!committed) {
                    outcome = Outcome.PERSIST_FAILED
                    return false
                }
                outcome = when (val c = runCatching(committedAs).getOrDefault(Outcome.DELIVERED)) {
                    Outcome.DUPLICATE, Outcome.CONFLICT -> c
                    else -> Outcome.DELIVERED
                }
                // Mesh-1K-O (P3): still under the lock, only after a durable DELIVERED commit. A failure
                // here never un-reports the committed delivery (and never cancels mid-way).
                if (outcome == Outcome.DELIVERED) {
                    withContext(NonCancellable) { runCatching { afterCommit() } }
                }
                outcome.inInbox
            } finally {
                report(start - requested, clockMs() - start, outcome)
            }
        } finally {
            lock.unlock(owner)
        }
    }
}

/** Mesh-1F: non-sensitive lock timing. Only durations and fixed outcome codes are logged. */
internal object MeshTiming {
    fun nowMs(): Long = System.nanoTime() / 1_000_000

    fun mesh(waitMs: Long, processMs: Long, outcome: MeshDelivery.Outcome) {
        runCatching { SafeLog.event("mesh_lock", "wait_ms=$waitMs process_ms=$processMs result=$outcome") }
    }

    /** [Mutex.withLock] that logs wait and hold time under [name] (a fixed code, never data). */
    suspend inline fun <T> timedLock(lock: Mutex, name: String, action: () -> T): T {
        val requested = nowMs()
        return lock.withLock {
            val start = nowMs()
            try {
                action()
            } finally {
                val end = nowMs()
                runCatching { SafeLog.event("lock_hold", "name=$name wait_ms=${start - requested} hold_ms=${end - start}") }
            }
        }
    }
}
