package com.codeyantra.armorchat.core.mesh

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.dao.MeshCustodyDao
import com.codeyantra.armorchat.core.database.entity.MeshCustodyEntity
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Mesh-1D-B (expired pruning before admission capacity) and Mesh-1K-AS (refuse, do not evict).
 * REAL: EmergencyMeshEngine, CustodyLedger, BundleCodec, MeshCourier.ingest, Room
 * `mesh_custody` (in-memory). SIMULATED: process restart = a new engine on the same database;
 * DB write failure = [FlakyDao] throwing around the real DAO. No radio, no messaging.
 *
 * Foreign admission is capped at [MeshPolicy.FOREIGN_MAX] and at [MeshPolicy.FOREIGN_RATE_MAX]
 * per [MeshPolicy.FOREIGN_RATE_WINDOW_MS]. These tests advance that window explicitly. They do
 * not loop until a row count that ingest will never reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshCustodyHygieneTest {

    private class FlakyDao(private val real: MeshCustodyDao) : MeshCustodyDao {
        var failUpsert = false
        var failDelete = false
        override suspend fun all() = real.all()
        override suspend fun upsert(row: MeshCustodyEntity) {
            if (failUpsert) throw IOException("simulated upsert failure")
            real.upsert(row)
        }
        override suspend fun delete(id: String) {
            if (failDelete) throw IOException("simulated delete failure")
            real.delete(id)
        }
        override suspend fun deleteExpired(nowMs: Long) = real.deleteExpired(nowMs)
    }

    private var nowMs = 1_800_000_000_000L
    private val db: ArmorChatDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(),
        ArmorChatDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val dao = FlakyDao(db.meshCustodyDao())
    private var seq = 0

    private fun engine() = EmergencyMeshEngine(RuntimeEnvironment.getApplication(), dao, AppClock { nowMs })

    private fun bundle(createdAtMs: Long, emergency: Boolean = true, hop: Int = 0): CustodyBundle {
        val payload = "sealed-${seq++}".toByteArray()
        return CustodyBundle(
            id = MeshIds.idFor(payload),
            createdAtMs = createdAtMs,
            expireAtMs = MeshPolicy.expireAt(createdAtMs),
            hopCount = hop,
            emergency = emergency,
            payload = payload,
        )
    }

    private fun EmergencyMeshEngine.take(b: CustodyBundle) = runBlocking { ingest(BundleCodec.encode(b), RadioPath.BLE) }
    private fun dbIds() = runBlocking { dao.all().map { it.id }.toSet() }
    private fun EmergencyMeshEngine.memoryIds() = runBlocking { phaseFor(emptySet(), false).ids.map { MeshIds.hex(it) }.toSet() }

    /** Simulated restart: fresh engine, same Room DB, loaded through the production restore(). */
    private fun restarted(): EmergencyMeshEngine = engine().also { runBlocking { it.arm() } }

    /**
     * Admits exactly [n] foreign rows that share [createdAtMs]. Opens the next rate window
     * after each [MeshPolicy.FOREIGN_RATE_MAX] admits, then once more when the last window
     * is full so the next refusal is FULL rather than RATE. [n] is at most the foreign cap.
     */
    private fun admitForeign(
        e: EmergencyMeshEngine,
        n: Int,
        createdAtMs: Long,
        emergency: Boolean = true,
    ): List<CustodyBundle> {
        check(n in 0..MeshPolicy.FOREIGN_MAX)
        val out = mutableListOf<CustodyBundle>()
        var inWindow = 0
        repeat(n) {
            if (inWindow == MeshPolicy.FOREIGN_RATE_MAX) {
                nowMs += MeshPolicy.FOREIGN_RATE_WINDOW_MS + 1_000L
                inWindow = 0
            }
            val b = bundle(createdAtMs, emergency)
            assertEquals(MeshAdmission.Admitted(b.idHex), e.take(b))
            out += b
            inWindow++
        }
        if (inWindow == MeshPolicy.FOREIGN_RATE_MAX) {
            nowMs += MeshPolicy.FOREIGN_RATE_WINDOW_MS + 1_000L
        }
        return out
    }

    @After
    fun close() = db.close()

    // ---------------------------------------------------------------- 1D-B expired pruning
    @Test
    fun fullLedgerOfExpiredEntriesAdmitsValidBundleAfterPruning() {
        val e = engine()
        val t0 = nowMs
        val old = admitForeign(e, MeshPolicy.FOREIGN_MAX, t0)
        assertEquals(MeshPolicy.FOREIGN_MAX, dbIds().size)
        val refused = bundle(nowMs)
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), e.take(refused))
        assertEquals("a full live pool is not evicted by a refusal", old.map { it.idHex }.toSet(), dbIds())
        nowMs = t0 + MeshPolicy.ABSOLUTE_TTL_MS // every old row: expireAt == now -> expired
        val fresh = bundle(nowMs)
        assertEquals(MeshAdmission.Admitted(fresh.idHex), e.take(fresh))
        assertEquals(setOf(fresh.idHex), dbIds())
        assertEquals(setOf(fresh.idHex), e.memoryIds())
        assertTrue(old.none { it.idHex in dbIds() })
    }

    @Test
    fun liveEntriesAreUnchangedByPruning() {
        val e = engine()
        val t0 = nowMs
        admitForeign(e, MeshPolicy.FOREIGN_MAX - 1, t0)
        val live = bundle(t0 + 1_000, hop = 2)
        assertTrue(e.take(live) is MeshAdmission.Admitted)
        val liveRowBefore = runBlocking { dao.all() }.single { it.id == live.idHex }
        nowMs = t0 + MeshPolicy.ABSOLUTE_TTL_MS
        val fresh = bundle(nowMs)
        assertEquals(MeshAdmission.Admitted(fresh.idHex), e.take(fresh))
        val liveRowAfter = runBlocking { dao.all() }.single { it.id == live.idHex }
        assertEquals(liveRowBefore.createdAtMs, liveRowAfter.createdAtMs)
        assertEquals(liveRowBefore.expireAtMs, liveRowAfter.expireAtMs)
        assertEquals(3, liveRowAfter.hopCount)
        assertEquals(liveRowBefore.hopCount, liveRowAfter.hopCount)
        assertEquals(liveRowBefore.forwardedCount, liveRowAfter.forwardedCount)
        assertEquals(liveRowBefore.emergency, liveRowAfter.emergency)
        assertArrayEquals(liveRowBefore.payload, liveRowAfter.payload)
        assertEquals(setOf(live.idHex, fresh.idHex), dbIds())
        assertEquals(setOf(live.idHex, fresh.idHex), e.memoryIds())
    }

    @Test
    fun exactExpiryBoundaryMatchesSweepAndAcceptSemantics() {
        val e = engine()
        val t0 = nowMs
        val held = admitForeign(e, MeshPolicy.FOREIGN_MAX, t0)
        // One millisecond before the deadline every row is live: the foreign pool is still full.
        nowMs = t0 + MeshPolicy.ABSOLUTE_TTL_MS - 1
        val early = bundle(nowMs)
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), e.take(early))
        assertEquals(held.map { it.idHex }.toSet(), dbIds())
        assertEquals(MeshPolicy.FOREIGN_MAX, dbIds().size)
        // At expireAt == now the rows are dead (sweep uses <=), so the same bundle is admitted.
        nowMs = t0 + MeshPolicy.ABSOLUTE_TTL_MS
        assertEquals(MeshAdmission.Admitted(early.idHex), e.take(early))
        // An incoming bundle whose own deadline is exactly now is still EXPIRED (accept uses <=).
        val deadOnArrival = bundle(nowMs - MeshPolicy.ABSOLUTE_TTL_MS)
        assertEquals(MeshAdmission.Rejected(MeshRejection.EXPIRED), e.take(deadOnArrival))
        assertEquals(setOf(early.idHex), dbIds())
    }

    @Test
    fun invalidBundlesStayRejectedBeforeDeliveryWithFullExpiredLedger() = runBlocking {
        val e = engine()
        val t0 = nowMs
        admitForeign(e, MeshPolicy.FOREIGN_MAX, t0)
        nowMs = t0 + MeshPolicy.ABSOLUTE_TTL_MS
        val good = bundle(nowMs)
        val badTtl = good.copy(expireAtMs = good.expireAtMs + 1)
        val badId = bundle(nowMs).copy(id = MeshIds.idFor("other".toByteArray()))
        val badHops = bundle(nowMs, hop = MeshPolicy.MAX_HOPS)
        assertEquals(MeshAdmission.Rejected(MeshRejection.MALFORMED), e.ingest(byteArrayOf(1, 2, 3), RadioPath.BLE))
        // BundleCodec.decode already refuses a refreshed deadline and a forged id (MALFORMED).
        assertEquals(MeshAdmission.Rejected(MeshRejection.MALFORMED), e.take(badTtl))
        assertEquals(MeshAdmission.Rejected(MeshRejection.MALFORMED), e.take(badId))
        assertEquals(MeshAdmission.Rejected(MeshRejection.HOPS), e.take(badHops))
        var delivered = 0
        MeshCourier.ingest(
            bytes = MeshFrames.encodeBundles(listOf(BundleCodec.encode(badTtl), BundleCodec.encode(badId), byteArrayOf(9))),
            path = RadioPath.BLE,
            store = { enc, via -> e.ingest(enc, via) },
            deliver = { delivered++; true },
            dropDelivered = { fail("nothing may be dropped") },
        )
        assertEquals(0, delivered)
        assertFalse(dbIds().contains(badTtl.idHex))
        assertFalse(dbIds().contains(badId.idHex))
    }

    @Test
    fun pruningKeepsRetentionAndWireEncodingUnchanged() = runBlocking {
        assertEquals(4L * 60L * 60L * 1000L, MeshPolicy.ABSOLUTE_TTL_MS)
        val e = engine()
        val t0 = nowMs
        admitForeign(e, MeshPolicy.FOREIGN_MAX, t0)
        nowMs = t0 + MeshPolicy.ABSOLUTE_TTL_MS
        val fresh = bundle(nowMs - 60_000, hop = 1)
        assertTrue(e.take(fresh) is MeshAdmission.Admitted)
        val row = dao.all().single()
        assertEquals(fresh.createdAtMs, row.createdAtMs)
        assertEquals(fresh.expireAtMs, row.expireAtMs) // deadline copied, not restarted
        val offered = e.offers(emptyList()).single()
        assertArrayEquals(BundleCodec.encode(fresh.copy(hopCount = 2)), offered)
    }

    // ---------------------------------------------------------------- 1D-C persistent eviction
    @Test
    fun fullForeignPoolRefusesNewBundlesAndKeepsEveryRowAcrossRestart() {
        val e = engine()
        val t0 = nowMs
        val carried = admitForeign(e, MeshPolicy.FOREIGN_MAX, t0, emergency = false)
        val ids = carried.map { it.idHex }.toSet()
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), e.take(bundle(nowMs, emergency = true)))
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), e.take(bundle(nowMs, emergency = false)))
        assertEquals("every foreign row stays in memory", ids, e.memoryIds())
        assertEquals("every foreign row stays in Room", ids, dbIds())
        val after = restarted()
        assertEquals("restart keeps the refused-against rows", ids, after.memoryIds())
        assertEquals(dbIds(), after.memoryIds())
        assertEquals(MeshPolicy.FOREIGN_MAX, after.memoryIds().size)
    }

    @Test
    fun fullOwnPoolRefusesTheNextHoldAndKeepsEveryRow() {
        val e = engine()
        val locals = List(MeshPolicy.OWN_MAX) { i -> "local-$i".toByteArray() }
        locals.forEach { p ->
            nowMs += 1
            assertTrue(runBlocking { e.holdSealed(p, emergency = false) } is MeshHoldResult.Held)
        }
        val first = MeshIds.hex(MeshIds.idFor(locals.first()))
        nowMs += 1
        val refused = runBlocking { e.holdSealed("emergency".toByteArray(), emergency = true) }
        assertTrue(refused is MeshHoldResult.Rejected)
        assertTrue("the oldest own row is kept", first in dbIds())
        assertEquals(MeshPolicy.OWN_MAX, dbIds().size)
        assertEquals(e.memoryIds(), dbIds())
        assertEquals(e.memoryIds(), restarted().memoryIds())
    }

    @Test
    fun emergencyPriorityIsUnchanged() {
        val e = engine()
        val t0 = nowMs
        val held = admitForeign(e, MeshPolicy.FOREIGN_MAX, t0, emergency = true)
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), e.take(bundle(nowMs, emergency = true)))
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), e.take(bundle(nowMs, emergency = false)))
        assertEquals(held.map { it.idHex }.toSet(), dbIds())
        assertEquals(dbIds(), e.memoryIds())
    }

    @Test
    fun fullPoolRefusalDoesNotDeleteAndLeavesMemoryEqualToRoom() {
        val e = engine()
        val t0 = nowMs
        val carried = admitForeign(e, MeshPolicy.FOREIGN_MAX, t0, emergency = false)
        val before = carried.map { it.idHex }.toSet()
        val incoming = bundle(nowMs, emergency = true)
        dao.failDelete = true
        val result = runCatching { e.take(incoming) }
        dao.failDelete = false
        assertEquals("refusal does not delete, so a delete fault is not exercised", null, result.exceptionOrNull())
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), result.getOrNull())
        assertEquals(before, dbIds())
        assertEquals("memory diverged from Room", before, e.memoryIds())
        assertEquals(before, restarted().memoryIds())
    }

    @Test
    fun upsertFailureOnAdmitKeepsMemoryEqualToRoomAndIsNotSilent() {
        val e = engine()
        val incoming = bundle(nowMs, emergency = false)
        dao.failUpsert = true
        val thrown = runCatching { e.take(incoming) }.exceptionOrNull()
        dao.failUpsert = false
        assertTrue("DB failure must surface", thrown is IOException)
        assertFalse(incoming.idHex in dbIds())
        assertEquals("memory diverged from Room", dbIds(), e.memoryIds())
        assertTrue(e.memoryIds().isEmpty())
        assertEquals(dbIds(), restarted().memoryIds())
        assertEquals(MeshAdmission.Admitted(incoming.idHex), e.take(incoming))
        assertEquals(dbIds(), e.memoryIds())
    }
}
