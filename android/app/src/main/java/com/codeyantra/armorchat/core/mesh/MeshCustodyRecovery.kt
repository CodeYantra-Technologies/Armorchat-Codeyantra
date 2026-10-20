package com.codeyantra.armorchat.core.mesh

import com.codeyantra.armorchat.messaging.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mesh-1H-2: bounded, single-flight re-offer of custody rows this phone ALREADY holds.
 *
 * Why: MeshCourier offers a bundle to messaging only once, right after admission. If that one
 * delivery returns false (LOCK_TIMEOUT, failed MLS/inbox commit, cancellation, cold start, BLE
 * encounter timeout) the row stays in custody, a re-received copy is a Duplicate that is never
 * offered, and nothing re-offers the held row before it expires.
 *
 * What a pass does, and nothing more:
 *  - takes a read-only snapshot of RECEIVED held rows ([candidates] = EmergencyMeshEngine
 *    .recoveryCandidates: hopCount >= 1, not expired). No engine.ingest, no hop/emergency/
 *    forwarding/payload change. A candidate is NOT assumed to be for this phone: only messaging's
 *    trial decrypt decides, so transit rows are expected to fail.
 *  - [alreadyStored] first (durable messages-table lookup of the envelope's outer message id).
 *    A hit is never delivered, decrypted or dropped: the row is marked exhausted for this process
 *    and keeps being forwarded until its deadline. The outer id is unauthenticated, so it is
 *    only ever used to SKIP work, never to accept anything.
 *  - otherwise [deliver] (ChatMessagingService.deliverMesh: messaging mutex with the 12 s
 *    acquisition bound, checkpoint/rollback, one Room transaction for inbox + contact_sessions).
 *  - on true only: [dropDelivered] with that row's id. On false: the row is left untouched.
 *
 * Bounds (all in memory, reset on process restart):
 *  - at most [MAX_CANDIDATES_PER_PASS] rows looked at per pass;
 *  - at most [MAX_ATTEMPTS_PER_ROW] attempts per row id per process (counted BEFORE the attempt,
 *    so a cancelled attempt also counts); exhausted rows are skipped without any work;
 *  - the attempt map is pruned to the current custody ids every pass and never grows beyond
 *    [MAX_TRACKED_ROWS] (= custody capacity); a row that would exceed it is skipped (fail closed);
 *  - passes start at least [TRIGGER_DEBOUNCE_MS] after a trigger and [MIN_PASS_GAP_MS] after the
 *    previous pass ended; one pass at a time (single flight); a trigger during a pass becomes at
 *    most one follow-up pass.
 *  - no pass starts, and no further row is attempted, while a BLE/Wi-Fi encounter is active
 *    ([encounterStarted]/[encounterEnded]); the deferred pass is scheduled when the last
 *    encounter ends. A row attempt already in flight when an encounter starts is not interrupted
 *    (its commit is non-cancellable); the encounter's own delivery then waits on the messaging
 *    mutex like any relay holder.
 */
class MeshCustodyRecovery(
    private val scope: CoroutineScope,
    private val candidates: suspend () -> List<CustodyBundle>,
    private val alreadyStored: suspend (payload: ByteArray) -> Boolean,
    private val deliver: suspend (payload: ByteArray) -> Boolean,
    private val dropDelivered: suspend (idHex: String) -> Unit,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class PassResult { RAN, BUSY, DEFERRED_ENCOUNTER, FAILED }

    /** Counters for logs and tests. Only ids' counts, never payload or ids. */
    data class Stats(
        val passes: Int,
        val deliverCalls: Int,
        val delivered: Int,
        val skippedStored: Int,
        val tracked: Int,
    )

    private val guard = Any()
    private var scheduled: Job? = null
    private var running = false
    private var rerun = false
    private var encounters = 0
    private var waitingForEncounter = false
    private var lastPassEndMs: Long? = null
    private val attempts = HashMap<String, Int>()
    private var passes = 0
    private var deliverCalls = 0
    private var delivered = 0
    private var skippedStored = 0

    fun stats(): Stats = synchronized(guard) {
        Stats(passes, deliverCalls, delivered, skippedStored, attempts.size)
    }

    fun attemptsFor(idHex: String): Int = synchronized(guard) { attempts[idHex] ?: 0 }

    /** True while a pass is scheduled but not yet started (tests: proves the worker went idle). */
    fun hasScheduledPass(): Boolean = synchronized(guard) { scheduled?.isActive == true }

    /** Ask for a pass (after restore, or after a local delivery returned false). Never blocks. */
    fun request() {
        synchronized(guard) {
            if (running) {
                rerun = true
                return
            }
            if (encounters > 0) {
                waitingForEncounter = true
                return
            }
            scheduleLocked()
        }
    }

    fun encounterStarted() {
        synchronized(guard) { encounters++ }
    }

    fun encounterEnded() {
        synchronized(guard) {
            if (encounters > 0) encounters--
            if (encounters == 0 && waitingForEncounter && !running) {
                waitingForEncounter = false
                scheduleLocked()
            }
        }
    }

    private fun scheduleLocked() {
        if (scheduled?.isActive == true) return
        val last = lastPassEndMs
        val gap = if (last == null) 0L else last + MIN_PASS_GAP_MS - clockMs()
        val wait = maxOf(TRIGGER_DEBOUNCE_MS, gap)
        scheduled = scope.launch {
            delay(wait)
            passNow()
        }
    }

    /** One bounded pass. Scheduled passes call this; tests may call it directly. */
    suspend fun passNow(): PassResult {
        val self = currentCoroutineContext()[Job]
        synchronized(guard) {
            if (scheduled === self) scheduled = null
            if (running) {
                rerun = true
                return PassResult.BUSY
            }
            if (encounters > 0) {
                waitingForEncounter = true
                return PassResult.DEFERRED_ENCOUNTER
            }
            running = true
            passes++
        }
        var more = false
        var retryable = false
        var deferred = false
        var result = PassResult.RAN
        try {
            val rows = candidates()
                .sortedWith(compareBy({ !it.emergency }, { it.expireAtMs }, { it.idHex }))
            val eligible = synchronized(guard) {
                attempts.keys.retainAll(rows.map { it.idHex }.toSet())
                rows.filter { row ->
                    val n = attempts[row.idHex]
                    if (n == null) attempts.size < MAX_TRACKED_ROWS else n < MAX_ATTEMPTS_PER_ROW
                }
            }
            var taken = 0
            for (row in eligible) {
                if (taken >= MAX_CANDIDATES_PER_PASS) {
                    more = true
                    break
                }
                if (synchronized(guard) { encounters > 0 }) {
                    deferred = true
                    break
                }
                taken++
                val n = synchronized(guard) {
                    if (!attempts.containsKey(row.idHex) && attempts.size >= MAX_TRACKED_ROWS) {
                        null
                    } else {
                        ((attempts[row.idHex] ?: 0) + 1).also { attempts[row.idHex] = it }
                    }
                } ?: continue
                val stored = try {
                    alreadyStored(row.payload)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    null // lookup failed: no decrypt this time; the attempt still counts
                }
                if (stored == null) {
                    if (n < MAX_ATTEMPTS_PER_ROW) retryable = true
                    continue
                }
                if (stored) {
                    synchronized(guard) {
                        attempts[row.idHex] = MAX_ATTEMPTS_PER_ROW
                        skippedStored++
                    }
                    continue
                }
                synchronized(guard) { deliverCalls++ }
                val ok = try {
                    deliver(row.payload)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    false
                }
                if (!ok) {
                    if (n < MAX_ATTEMPTS_PER_ROW) retryable = true
                    continue
                }
                try {
                    dropDelivered(row.idHex)
                    synchronized(guard) {
                        attempts.remove(row.idHex)
                        delivered++
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    // Committed but the custody delete failed: the row stays; its next attempt
                    // hits alreadyStored (no decrypt) and it expires at its deadline.
                    runCatching { SafeLog.event("mesh_recovery", "drop=failed") }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            result = PassResult.FAILED
            runCatching { SafeLog.event("mesh_recovery", "pass=failed") }
        } finally {
            synchronized(guard) {
                running = false
                lastPassEndMs = clockMs()
                val again = rerun || more || retryable || deferred
                rerun = false
                if (again) {
                    if (encounters > 0) waitingForEncounter = true else scheduleLocked()
                }
            }
        }
        return result
    }

    companion object {
        const val MAX_CANDIDATES_PER_PASS = 4
        const val MAX_ATTEMPTS_PER_ROW = 3
        const val MAX_TRACKED_ROWS = MeshPolicy.MAX_BUNDLES
        const val TRIGGER_DEBOUNCE_MS = 2_000L
        const val MIN_PASS_GAP_MS = 30_000L
    }
}
