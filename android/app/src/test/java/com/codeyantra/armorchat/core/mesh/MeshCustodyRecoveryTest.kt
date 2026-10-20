package com.codeyantra.armorchat.core.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mesh-1H-2: scheduling, single-flight and bounds of [MeshCustodyRecovery] in virtual time.
 * REAL: MeshCustodyRecovery. SIMULATED: custody snapshot, messaging delivery and drop are
 * in-memory lambdas (the Room / messaging path is covered by MeshCustodyRecoveryIntegrationTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshCustodyRecoveryTest {

    private class World(scope: TestScope, rowCount: Int, emergency: Boolean = true) {
        val rows = linkedMapOf<String, CustodyBundle>()
        val deliverLog = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val passStartedAt = mutableListOf<Long>()
        var succeed: (String) -> Boolean = { false }
        var stored: (String) -> Boolean = { false }
        var onDeliver: suspend (String) -> Unit = {}
        var candidateCalls = 0

        init {
            repeat(rowCount) { add(it, emergency) }
        }

        fun add(i: Int, emergency: Boolean = true): CustodyBundle {
            val payload = "row-$i".toByteArray()
            val b = CustodyBundle(MeshIds.idFor(payload), 0, MeshPolicy.expireAt(0) + i, 2, emergency, payload)
            rows[b.idHex] = b
            return b
        }

        private fun idOf(payload: ByteArray) = MeshIds.hex(MeshIds.idFor(payload))

        val recovery = MeshCustodyRecovery(
            scope = scope,
            candidates = {
                candidateCalls++
                passStartedAt += scope.testScheduler.currentTime
                rows.values.toList()
            },
            alreadyStored = { p -> stored(idOf(p)) },
            deliver = { p ->
                val id = idOf(p)
                deliverLog += id
                onDeliver(id)
                succeed(id)
            },
            dropDelivered = { id -> dropped += id; rows.remove(id) },
            clockMs = { scope.testScheduler.currentTime },
        )
    }

    @Test
    fun requestIsDebouncedAndRunsOnePass() = runTest {
        val w = World(this, 1)
        w.recovery.request()
        w.recovery.request()
        advanceTimeBy(MeshCustodyRecovery.TRIGGER_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(0, w.candidateCalls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, w.candidateCalls)
        assertEquals(1, w.deliverLog.size)
    }

    @Test
    fun failingRowsAreBoundedByAttemptLimitAndWorkerGoesIdleWithoutTightLoop() = runTest {
        val w = World(this, 2)
        w.recovery.request()
        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()
        assertEquals(2 * MeshCustodyRecovery.MAX_ATTEMPTS_PER_ROW, w.deliverLog.size)
        assertEquals(MeshCustodyRecovery.MAX_ATTEMPTS_PER_ROW, w.passStartedAt.size)
        w.passStartedAt.zipWithNext().forEach { (a, b) ->
            assertTrue("passes closer than MIN_PASS_GAP_MS: $a -> $b", b - a >= MeshCustodyRecovery.MIN_PASS_GAP_MS)
        }
        assertFalse("worker must go idle", w.recovery.hasScheduledPass())
        assertTrue(w.dropped.isEmpty())
        assertEquals(2, w.rows.size)
        // A later trigger looks at the snapshot but attempts nothing more for exhausted rows.
        w.recovery.request()
        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()
        assertEquals(2 * MeshCustodyRecovery.MAX_ATTEMPTS_PER_ROW, w.deliverLog.size)
        assertFalse(w.recovery.hasScheduledPass())
    }

    @Test
    fun perPassCandidateLimitThenFollowUpPassAfterGap() = runTest {
        val w = World(this, 10)
        w.succeed = { true }
        assertEquals(MeshCustodyRecovery.PassResult.RAN, w.recovery.passNow())
        assertEquals(MeshCustodyRecovery.MAX_CANDIDATES_PER_PASS, w.deliverLog.size)
        assertEquals(MeshCustodyRecovery.MAX_CANDIDATES_PER_PASS, w.dropped.size)
        assertTrue(w.recovery.hasScheduledPass())
        advanceTimeBy(MeshCustodyRecovery.MIN_PASS_GAP_MS - 1)
        runCurrent()
        assertEquals(MeshCustodyRecovery.MAX_CANDIDATES_PER_PASS, w.deliverLog.size)
        advanceTimeBy(10 * MeshCustodyRecovery.MIN_PASS_GAP_MS)
        runCurrent()
        assertEquals(10, w.deliverLog.size)
        assertEquals(10, w.dropped.size)
        assertEquals(w.deliverLog, w.dropped)
        assertTrue(w.rows.isEmpty())
        assertFalse(w.recovery.hasScheduledPass())
    }

    @Test
    fun singleFlightOverlappingRequestsCoalesceIntoOneFollowUp() = runTest {
        val w = World(this, 1)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        w.onDeliver = { entered.complete(Unit); gate.await() }
        val first = launch { w.recovery.passNow() }
        runCurrent()
        assertTrue(entered.isCompleted)
        assertEquals(MeshCustodyRecovery.PassResult.BUSY, w.recovery.passNow())
        repeat(5) { w.recovery.request() }
        assertFalse("no second pass may be scheduled while one runs", w.recovery.hasScheduledPass())
        assertEquals(1, w.candidateCalls)
        w.onDeliver = {}
        gate.complete(Unit)
        runCurrent()
        first.join()
        assertTrue("one follow-up for the coalesced requests", w.recovery.hasScheduledPass())
        advanceTimeBy(MeshCustodyRecovery.MIN_PASS_GAP_MS + 1)
        runCurrent()
        assertEquals(2, w.candidateCalls)
    }

    @Test
    fun noPassWhileEncounterActiveThenRunsAfterItEnds() = runTest {
        val w = World(this, 1)
        w.succeed = { true }
        w.recovery.encounterStarted()
        w.recovery.request()
        advanceTimeBy(10 * 60 * 1000L)
        runCurrent()
        assertEquals(0, w.candidateCalls)
        assertEquals(MeshCustodyRecovery.PassResult.DEFERRED_ENCOUNTER, w.recovery.passNow())
        assertEquals(0, w.candidateCalls)
        w.recovery.encounterStarted() // a second overlapping encounter
        w.recovery.encounterEnded()
        advanceTimeBy(10 * 60 * 1000L)
        runCurrent()
        assertEquals("still one encounter active", 0, w.candidateCalls)
        w.recovery.encounterEnded()
        advanceTimeBy(MeshCustodyRecovery.TRIGGER_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(1, w.candidateCalls)
        assertEquals(1, w.dropped.size)
    }

    @Test
    fun encounterStartingMidPassStopsFurtherRowsUntilItEnds() = runTest {
        val w = World(this, 3)
        w.succeed = { true }
        w.onDeliver = { w.recovery.encounterStarted(); w.onDeliver = {} }
        w.recovery.passNow()
        assertEquals("in-flight row completes, no further row while encounter active", 1, w.deliverLog.size)
        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()
        assertEquals(1, w.deliverLog.size)
        w.recovery.encounterEnded()
        advanceTimeBy(MeshCustodyRecovery.MIN_PASS_GAP_MS + 1)
        runCurrent()
        assertEquals(3, w.deliverLog.size)
    }

    @Test
    fun alreadyStoredRowIsNeverDeliveredOrDroppedAndIsExhausted() = runTest {
        val w = World(this, 1)
        w.stored = { true }
        w.succeed = { true }
        w.recovery.passNow()
        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()
        w.recovery.request()
        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()
        assertTrue(w.deliverLog.isEmpty())
        assertTrue(w.dropped.isEmpty())
        assertEquals(1, w.rows.size)
        assertEquals(1, w.recovery.stats().skippedStored)
        assertFalse(w.recovery.hasScheduledPass())
    }

    @Test
    fun successDropsOnlyThatRowAndClearsItsAttempts() = runTest {
        val w = World(this, 3)
        val target = w.rows.keys.elementAt(1)
        w.succeed = { it == target }
        w.recovery.passNow()
        assertEquals(listOf(target), w.dropped)
        assertEquals(0, w.recovery.attemptsFor(target))
        assertEquals(2, w.rows.size)
        w.rows.keys.forEach { assertEquals(1, w.recovery.attemptsFor(it)) }
    }

    @Test
    fun attemptMapIsPrunedToHeldRowsAndNeverExceedsCustodyCapacity() = runTest {
        val w = World(this, 0)
        repeat(MeshCustodyRecovery.MAX_TRACKED_ROWS + 12) { w.add(it) }
        repeat(30) {
            w.recovery.passNow()
            assertTrue(w.recovery.stats().tracked <= MeshCustodyRecovery.MAX_TRACKED_ROWS)
            advanceTimeBy(MeshCustodyRecovery.MIN_PASS_GAP_MS + 1)
            runCurrent()
        }
        val perRow = w.deliverLog.groupingBy { it }.eachCount()
        assertTrue(perRow.values.all { it <= MeshCustodyRecovery.MAX_ATTEMPTS_PER_ROW })
        val gone = w.rows.keys.first()
        w.rows.remove(gone)
        w.recovery.passNow()
        assertEquals("removed row forgotten", 0, w.recovery.attemptsFor(gone))
    }

    @Test
    fun cancelledAttemptCountsAndKeepsRow() = runTest {
        val w = World(this, 1)
        val id = w.rows.keys.single()
        w.onDeliver = { throw CancellationException("encounter cancelled") }
        val job = launch { w.recovery.passNow() }
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, w.recovery.attemptsFor(id))
        assertTrue(w.dropped.isEmpty())
        assertEquals(1, w.rows.size)
    }

    @Test
    fun lookupFailureNeverDecryptsAndCountsAttempt() = runTest {
        val w = World(this, 1)
        val id = w.rows.keys.single()
        w.stored = { error("db") }
        w.recovery.passNow()
        assertTrue(w.deliverLog.isEmpty())
        assertEquals(1, w.recovery.attemptsFor(id))
    }
}
