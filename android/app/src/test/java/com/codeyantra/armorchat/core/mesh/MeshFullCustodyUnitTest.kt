package com.codeyantra.armorchat.core.mesh

import com.codeyantra.armorchat.messaging.MeshDelivery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mesh-1K-R: pure JVM tests of the REAL production MeshCourier direct path (fix A), MeshDelivery's
 * mailbox pre-filter / outcome mapping, and MeshHoldRetry (fix D1 bounds). No Android, no MLS.
 * The full authenticated chain (Room, ReceiptMessagingPeer, engine) is in messaging/MeshFullCustodyAckTest.
 * Robolectric only so org.json (Android framework class used by the envelope parsers) is real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MeshFullCustodyUnitTest {
    private val now = 1_800_000_000_000L
    private val mine = "mailbox-mine"

    private fun envelope(mailbox: String?, n: Int): ByteArray {
        val m = if (mailbox == null) "" else "\"recipient_mailbox\":\"$mailbox\","
        return "{\"encrypted_payload\":\"AAAA$n\",\"message_id\":\"m$n\",$m\"payload_type\":\"application\"}".toByteArray()
    }

    private fun bundle(payload: ByteArray, emergency: Boolean = true) = BundleCodec.encode(
        CustodyBundle(MeshIds.idFor(payload), now, MeshPolicy.expireAt(now), 0, emergency, payload),
    )

    private class Run {
        val delivered = mutableListOf<String>()
        val direct = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        var report: Map<MeshDirect, Int> = emptyMap()
    }

    private fun ingest(
        payloads: List<ByteArray>,
        admission: (ByteArray) -> MeshAdmission,
        enabled: Boolean = true,
        budget: Int = MeshCourier.MAX_DIRECT_PER_INGEST,
        directResult: (ByteArray) -> MeshDirect = { MeshDirect.REJECTED },
    ): Run {
        val r = Run()
        runBlocking {
            MeshCourier.ingest(
                MeshFrames.encodeBundles(payloads.map { bundle(it) }), RadioPath.BLE,
                store = { e, _ -> admission((BundleCodec.decode(e) as BundleDecode.Ok).bundle.payload) },
                deliver = { p -> r.delivered += String(p); true },
                dropDelivered = { r.dropped += it },
                addressedHere = if (enabled) { p -> MeshDelivery.addressedHere(p, setOf(mine)) } else null,
                deliverDirect = if (enabled) { p -> r.direct += String(p); directResult(p) } else null,
                directBudget = budget,
                reportDirect = { r.report = it },
            )
        }
        return r
    }

    private val full: (ByteArray) -> MeshAdmission = { MeshAdmission.Rejected(MeshRejection.FULL) }

    @Test
    fun fullAndLocallyAddressedGoesDirectWithoutStoreOrDrop() {
        val p = envelope(mine, 1)
        val r = ingest(listOf(p), full) { MeshDirect.DELIVERED }
        assertEquals(listOf(String(p)), r.direct)
        assertTrue("normal deliver path not used", r.delivered.isEmpty())
        assertTrue("nothing was held, so nothing is dropped", r.dropped.isEmpty())
        assertEquals(mapOf(MeshDirect.DELIVERED to 1), r.report)
    }

    @Test
    fun foreignTransitMissingOrMalformedMailboxNeverGoesDirect() {
        val payloads = listOf(envelope("mailbox-other", 1), envelope(null, 2), "not json".toByteArray(), envelope("", 3))
        val r = ingest(payloads, full) { fail("must not be called"); MeshDirect.FAILED }
        assertTrue(r.direct.isEmpty())
        assertTrue(r.delivered.isEmpty())
        assertTrue(r.dropped.isEmpty())
        assertTrue("no direct outcome to report", r.report.isEmpty())
    }

    @Test
    fun onlyFullRejectionQualifies() {
        val p = envelope(mine, 1)
        for (reason in MeshRejection.values().filter { it != MeshRejection.FULL }) {
            val r = ingest(listOf(p), { MeshAdmission.Rejected(reason) }) { fail("$reason"); MeshDirect.FAILED }
            assertTrue("$reason", r.direct.isEmpty())
        }
        val dup = ingest(listOf(p), { MeshAdmission.Duplicate("x") }) { fail("dup"); MeshDirect.FAILED }
        assertTrue("already held: never delivered here (recovery handles it)", dup.direct.isEmpty() && dup.delivered.isEmpty())
    }

    @Test
    fun admittedPathIsUnchanged() {
        val p = envelope(mine, 1)
        val r = ingest(listOf(p), { MeshAdmission.Admitted(MeshIds.hex(MeshIds.idFor(it))) }) { fail("direct"); MeshDirect.FAILED }
        assertEquals(listOf(String(p)), r.delivered)
        assertEquals(listOf(MeshIds.hex(MeshIds.idFor(p))), r.dropped)
        assertTrue(r.direct.isEmpty())
    }

    @Test
    fun disabledDirectPathIsTheOldBehaviour() {
        val r = ingest(listOf(envelope(mine, 1)), full, enabled = false)
        assertTrue(r.direct.isEmpty() && r.delivered.isEmpty() && r.dropped.isEmpty())
    }

    @Test
    fun budgetBoundsAttemptsPerIngestAndCountsTheRest() {
        val payloads = (1..20).map { envelope(mine, it) } + envelope("mailbox-other", 99)
        val r = ingest(payloads, full)
        assertEquals(8, MeshCourier.MAX_DIRECT_PER_INGEST)
        assertEquals("first 8 attempted, in order", payloads.take(8).map { String(it) }, r.direct)
        assertEquals(mapOf(MeshDirect.REJECTED to 8, MeshDirect.BUDGET_EXHAUSTED to 12), r.report)
        // A fresh ingest has a fresh budget (bounded per ingest operation).
        assertEquals(8, ingest(payloads, full).direct.size)
    }

    @Test
    fun throwingDirectDeliveryIsFailedAndCancellationPropagates() {
        val r = ingest(listOf(envelope(mine, 1), envelope(mine, 2)), full) { if (String(it).contains("AAAA1")) throw IllegalStateException("x") else MeshDirect.DUPLICATE }
        assertEquals(mapOf(MeshDirect.FAILED to 1, MeshDirect.DUPLICATE to 1), r.report)
        try {
            ingest(listOf(envelope(mine, 1)), full) { throw CancellationException("stop") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun outcomeMappingIsTruthful() {
        val o = MeshDelivery.Outcome.values().associateWith { MeshDelivery.direct(it.inInbox, it) }
        assertEquals(MeshDirect.DELIVERED, o[MeshDelivery.Outcome.DELIVERED])
        assertEquals(MeshDirect.DUPLICATE, o[MeshDelivery.Outcome.DUPLICATE])
        assertEquals(MeshDirect.REJECTED, o[MeshDelivery.Outcome.CONFLICT])
        assertEquals(MeshDirect.REJECTED, o[MeshDelivery.Outcome.DECLINED])
        assertEquals(MeshDirect.FAILED, o[MeshDelivery.Outcome.PERSIST_FAILED])
        assertEquals(MeshDirect.FAILED, o[MeshDelivery.Outcome.CANCELLED])
        assertEquals(MeshDirect.FAILED, o[MeshDelivery.Outcome.LOCK_TIMEOUT])
        assertEquals("true without an inbox outcome is not trusted", MeshDirect.FAILED, MeshDelivery.direct(true, null))
        assertEquals(MeshDirect.FAILED, MeshDelivery.direct(true, MeshDelivery.Outcome.DECLINED))
        assertEquals(MeshDirect.FAILED, MeshDelivery.direct(false, null))
    }

    @Test
    fun preFilterUsesKnownMailboxesAndDefersWhenUnknown() {
        assertTrue(MeshDelivery.addressedHere(envelope(mine, 1), setOf(mine)))
        assertFalse(MeshDelivery.addressedHere(envelope("x", 1), setOf(mine)))
        assertFalse("known-empty: nothing is ours", MeshDelivery.addressedHere(envelope(mine, 1), emptySet()))
        assertTrue("unknown (cold start): in-lock gate decides", MeshDelivery.addressedHere(envelope("x", 1), null))
        assertFalse("unparsable never qualifies", MeshDelivery.addressedHere("{".toByteArray(), null))
        assertEquals(mine, MeshDelivery.outerRecipientMailbox(envelope(mine, 1)))
    }

    // D1 MeshHoldRetry bounds ------------------------------------------------------------------------

    @Test
    fun retryListIsBoundedAndNeverEvictsOlderEntries() {
        val r = MeshHoldRetry()
        assertEquals(16, MeshHoldRetry.MAX_ENTRIES)
        val payloads = (1..20).map { "receipt-$it".toByteArray() }
        val queued = payloads.map { r.enqueue(it, false, now) }
        assertEquals(List(16) { true } + List(4) { false }, queued)
        assertEquals(payloads.take(16).map { MeshIds.hex(MeshIds.idFor(it)) }, r.ids())
        assertTrue("identical bytes are idempotent", r.enqueue(payloads[0], false, now + 1))
        assertEquals(now, r.pending().first().queuedAtMs)
        assertFalse(r.enqueue(ByteArray(0), false, now))
        assertFalse(r.enqueue(ByteArray(MeshPolicy.MAX_PAYLOAD_BYTES + 1), false, now))
    }

    @Test
    fun retryKeepsIdenticalBytesAndExhaustsAfterMaxAttempts() {
        val r = MeshHoldRetry()
        val p = "sealed-receipt".toByteArray()
        r.enqueue(p, false, now)
        p[0] = 'X'.code.toByte() // caller mutation cannot change the queued copy
        assertEquals("sealed-receipt", String(r.pending().single().payload))
        repeat(MeshHoldRetry.MAX_ATTEMPTS - 1) { assertFalse(r.failed(r.ids().single())) }
        assertTrue("dropped on the 8th failed attempt", r.failed(r.ids().single()))
        assertTrue(r.isEmpty())
    }

    @Test
    fun retryDeadlineAndClockRollback() {
        val r = MeshHoldRetry()
        r.enqueue("a".toByteArray(), false, now)
        assertEquals(0, r.prune(now + MeshPolicy.ABSOLUTE_TTL_MS - 1))
        assertEquals(1, r.prune(now + MeshPolicy.ABSOLUTE_TTL_MS))
        r.enqueue("b".toByteArray(), false, now)
        assertEquals("small rollback within skew keeps it", 0, r.prune(now - 5 * 60_000L))
        assertEquals("rollback beyond skew drops it", 1, r.prune(now - MeshPolicy.CLOCK_SKEW_MS - 1))
        assertTrue(r.isEmpty())
    }
}
