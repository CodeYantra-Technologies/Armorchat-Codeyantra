package com.codeyantra.armorchat.core.mesh

import com.codeyantra.armorchat.messaging.InboundRecord
import com.codeyantra.armorchat.messaging.MeshDelivery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mesh-1A.1 unit tests for the extracted courier/delivery steps (pure JVM, fake collaborators). */
class MeshCourierTest {
    private val now = 1_800_000_000_000L

    private fun bundle(payload: ByteArray, hop: Int = 0, created: Long = now) = BundleCodec.encode(
        CustodyBundle(MeshIds.idFor(payload), created, MeshPolicy.expireAt(created), hop, true, payload),
    )

    private fun run(frames: ByteArray, storeResult: MeshAdmission, accepted: (ByteArray) -> Boolean): List<String> {
        val log = mutableListOf<String>()
        runBlocking {
            MeshCourier.ingest(
                frames, RadioPath.BLE,
                store = { e, _ -> log += "store:" + MeshIds.hex(e.copyOfRange(8, 24)); storeResult },
                deliver = { p -> log += "deliver:" + String(p); accepted(p) },
                dropDelivered = { id -> log += "drop:$id" },
            )
        }
        return log
    }

    @Test
    fun orderIsStoreThenDeliverThenDropOnlyWhenAccepted() {
        val a = "a".toByteArray()
        val b = "b".toByteArray()
        val ida = MeshIds.hex(MeshIds.idFor(a))
        val idb = MeshIds.hex(MeshIds.idFor(b))
        val log = run(MeshFrames.encodeBundles(listOf(bundle(a), bundle(b))), MeshAdmission.Admitted("x")) { String(it) == "a" }
        assertEquals(listOf("store:$ida", "deliver:a", "drop:$ida", "store:$idb", "deliver:b"), log)
    }

    @Test
    fun undecodableBundlesAreSkippedWithoutStoreOrDeliver() {
        val bad = bundle("x".toByteArray()).also { it[0] = 0 }
        val tampered = bundle("y".toByteArray()).also { it[it.size - 1] = 'z'.code.toByte() }
        val over = bundle("w".toByteArray(), hop = MeshPolicy.MAX_HOPS + 1)
        assertTrue(run(MeshFrames.encodeBundles(listOf(bad, tampered, over)), MeshAdmission.Admitted("x")) { true }.isEmpty())
        assertTrue(run(byteArrayOf(1, 0), MeshAdmission.Admitted("x")) { true }.isEmpty())
    }

    @Test
    fun onlyAdmittedBundlesReachMessaging() {
        // Mesh-1B: replaces the 1A.1 test that documented the admission bypass.
        val frames = MeshFrames.encodeBundles(listOf(bundle("q".toByteArray())))
        val refused = listOf(
            MeshAdmission.Duplicate("x"),
            MeshAdmission.Rejected(MeshRejection.EXPIRED),
            MeshAdmission.Rejected(MeshRejection.FUTURE),
            MeshAdmission.Rejected(MeshRejection.HOPS),
            MeshAdmission.Rejected(MeshRejection.FULL),
            MeshAdmission.Rejected(MeshRejection.MALFORMED),
        )
        for (admission in refused) {
            val log = run(frames, admission) { true }
            assertEquals(admission.toString(), 1, log.size)
            assertTrue(log.single().startsWith("store:"))
        }
        assertEquals(3, run(frames, MeshAdmission.Admitted("x")) { true }.size)
    }

    @Test
    fun ledgerReasonsMapToTypedRejections() {
        assertEquals(MeshRejection.DEADLINE, MeshRejection.fromLedger("ttl"))
        assertEquals(MeshRejection.FUTURE, MeshRejection.fromLedger("future"))
        assertEquals(MeshRejection.EXPIRED, MeshRejection.fromLedger("expired"))
        assertEquals(MeshRejection.HOPS, MeshRejection.fromLedger("hops"))
        assertEquals(MeshRejection.ID, MeshRejection.fromLedger("id"))
        assertEquals(MeshRejection.FULL, MeshRejection.fromLedger("full"))
        assertEquals(MeshRejection.UNKNOWN, MeshRejection.fromLedger("something-new"))
    }

    @Test
    fun meshDeliveryParsesOnlyJsonLikePayloadsAndPassesPersistThrough() = runBlocking {
        var offered: String? = null
        val persist: suspend (String, InboundRecord) -> Unit = { _, _ -> }
        var persistSeen: Any? = null
        val offer: suspend (String, suspend (String, InboundRecord) -> Unit) -> Boolean = { raw, p ->
            offered = raw; persistSeen = p; true
        }
        assertFalse(MeshDelivery.deliver(ByteArray(0), offer, persist))
        assertFalse(MeshDelivery.deliver("[1]".toByteArray(), offer, persist))
        assertFalse(MeshDelivery.deliver(" {".toByteArray(), offer, persist))
        assertEquals(null, offered)
        assertTrue(MeshDelivery.deliver("{\"a\":1}".toByteArray(), offer, persist))
        assertEquals("{\"a\":1}", offered)
        assertTrue(persistSeen === persist)
        val refusing: suspend (String, suspend (String, InboundRecord) -> Unit) -> Boolean = { _, _ -> false }
        assertFalse(MeshDelivery.deliver("{}".toByteArray(), refusing, persist))
    }
}
