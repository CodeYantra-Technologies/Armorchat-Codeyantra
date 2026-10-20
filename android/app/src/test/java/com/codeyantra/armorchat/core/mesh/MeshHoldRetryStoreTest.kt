package com.codeyantra.armorchat.core.mesh

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Mesh-1K-AW: mesh_hold_retry.v1 round-trip, bounds, and crash/corrupt behavior.
 * Plain JVM. No Room, no engine, no sealed plaintext — payloads are opaque bytes.
 */
class MeshHoldRetryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FaultIo : MeshHoldRetryStore.Io {
        var failWrite = false
        var interruptWrite = false
        var failRead = false
        override fun read(file: File): ByteArray? {
            if (failRead) throw IOException("simulated read failure")
            return MeshHoldRetryStore.FileIo.read(file)
        }
        override fun writeAtomic(file: File, bytes: ByteArray) {
            if (failWrite) throw IOException("simulated write failure")
            if (interruptWrite) {
                File(file.parentFile, file.name + MeshHoldRetryStore.TMP_SUFFIX)
                    .writeBytes(bytes.copyOf(bytes.size / 2))
                throw IOException("simulated crash before rename")
            }
            MeshHoldRetryStore.FileIo.writeAtomic(file, bytes)
        }
        override fun rename(file: File, target: File): Boolean = MeshHoldRetryStore.FileIo.rename(file, target)
    }

    @Test
    fun exactBytesAndMetadataRoundTripAndEmptyDiffersFromMissing() {
        val file = tmp.newFile("mesh_hold_retry.v1")
        file.delete()
        val logs = mutableListOf<String>()
        val store = MeshHoldRetryStore(file) { logs += it }
        assertTrue(store.load().isEmpty())
        assertEquals(MeshHoldRetryStore.LoadState.MISSING, store.loadState)
        val payload = "sealed-receipt-bytes".toByteArray()
        val queuedAt = 1_700_000_000_000L
        val deadline = queuedAt + MeshPolicy.ABSOLUTE_TTL_MS
        assertTrue(store.replace(listOf(saved(payload, emergency = true, queuedAt = queuedAt, deadline = deadline, attempts = 7))))
        payload[0] = 'X'.code.toByte()
        val loaded = MeshHoldRetryStore(file).load().single()
        assertEquals("sealed-receipt-bytes", String(loaded.payload))
        assertTrue(loaded.id.contentEquals(MeshIds.idFor(loaded.payload)))
        assertTrue(loaded.emergency)
        assertEquals(queuedAt, loaded.queuedAtMs)
        assertEquals(deadline, loaded.deadlineMs)
        assertEquals(7, loaded.attempts)
        assertTrue(store.replace(emptyList()))
        assertEquals(MeshHoldRetryStore.LoadState.OK, store.loadState)
        assertTrue(file.exists())
        assertTrue(MeshHoldRetryStore(file).load().isEmpty())
        assertTrue(logs.none { it.contains("sealed-receipt") })
    }

    @Test
    fun attemptsZeroThroughSevenRoundTripAndEightOrASeventeenthIsRefused() {
        val file = tmp.newFile("bounds.v1")
        file.delete()
        val store = MeshHoldRetryStore(file)
        val sixteen = (0 until 16).map { saved("row-$it".toByteArray(), attempts = it % MeshHoldRetry.MAX_ATTEMPTS) }
        assertTrue(store.replace(sixteen))
        val again = MeshHoldRetryStore(file).load()
        assertEquals(16, again.size)
        again.forEachIndexed { i, row ->
            assertEquals(i % MeshHoldRetry.MAX_ATTEMPTS, row.attempts)
            assertTrue(row.payload.contentEquals("row-$i".toByteArray()))
        }
        val canonical = file.readBytes()
        assertFalse(store.replace(sixteen + saved("row-16".toByteArray())))
        assertTrue(file.readBytes().contentEquals(canonical))
        assertEquals(16, store.load().size)
        assertFalse(store.replace(listOf(saved("too-many-attempts".toByteArray(), attempts = MeshHoldRetry.MAX_ATTEMPTS))))
        assertTrue(file.readBytes().contentEquals(canonical))
        assertFalse(store.replace(listOf(saved("backwards".toByteArray(), queuedAt = 50L, deadline = 49L))))
        assertFalse(store.replace(listOf(saved("same".toByteArray()), saved("same".toByteArray()))))
        val wrongId = MeshHoldRetryStore.Saved(
            id = MeshIds.idFor("other".toByteArray()),
            emergency = false,
            queuedAtMs = 1L,
            deadlineMs = 2L,
            attempts = 0,
            payload = "payload".toByteArray(),
        )
        assertFalse(store.replace(listOf(wrongId)))
        assertTrue(file.readBytes().contentEquals(canonical))
    }

    @Test
    fun corruptFileIsQuarantinedAndAnOversizedFileIsNotParsedAsAQueue() {
        val file = tmp.newFile("mesh_hold_retry.v1")
        file.delete()
        val store = MeshHoldRetryStore(file)
        val payload = "keep-this-evidence".toByteArray()
        assertTrue(store.replace(listOf(saved(payload))))
        val damaged = file.readBytes()
        damaged[damaged.size - 1] = (damaged[damaged.size - 1].toInt() xor 0x5a).toByte()
        file.writeBytes(damaged)
        val reloaded = MeshHoldRetryStore(file)
        assertTrue(reloaded.load().isEmpty())
        assertEquals(MeshHoldRetryStore.LoadState.CORRUPT_QUARANTINED, reloaded.loadState)
        val quarantine = File(file.path + ".corrupt-1")
        assertTrue(quarantine.readBytes().contentEquals(damaged))
        assertFalse(file.exists())
        assertTrue(reloaded.replace(listOf(saved("fresh".toByteArray()))))
        assertTrue(file.exists())

        val huge = tmp.newFile("huge.v1")
        huge.writeBytes(ByteArray(MeshHoldRetryStore.MAX_FILE_BYTES + 1))
        val hugeStore = MeshHoldRetryStore(huge)
        assertTrue(hugeStore.load().isEmpty())
        assertEquals(MeshHoldRetryStore.LoadState.CORRUPT_QUARANTINED, hugeStore.loadState)
        assertTrue(File(huge.path + ".corrupt-1").exists())
    }

    @Test
    fun failedAndInterruptedWritesLeaveTheCanonicalFileUntouched() {
        val file = tmp.newFile("mesh_hold_retry.v1")
        file.delete()
        val io = FaultIo()
        val store = MeshHoldRetryStore(file, io)
        assertTrue(store.replace(listOf(saved("first".toByteArray(), attempts = 2))))
        val canonical = file.readBytes()
        io.failWrite = true
        assertFalse(store.replace(listOf(saved("second".toByteArray()))))
        assertTrue(file.readBytes().contentEquals(canonical))
        assertEquals(2, store.load().single().attempts)
        io.failWrite = false
        io.interruptWrite = true
        assertFalse(store.replace(listOf(saved("third".toByteArray()))))
        assertTrue(file.readBytes().contentEquals(canonical))
        assertTrue(File(file.path + MeshHoldRetryStore.TMP_SUFFIX).exists())
        assertEquals("first", String(store.load().single().payload))
        io.interruptWrite = false
        io.failRead = true
        val reader = MeshHoldRetryStore(file, io)
        assertTrue(reader.load().isEmpty())
        assertEquals(MeshHoldRetryStore.LoadState.UNREADABLE, reader.loadState)
        assertFalse(reader.replace(listOf(saved("clobber".toByteArray()))))
        assertTrue(file.readBytes().contentEquals(canonical))
        io.failRead = false
        assertEquals("first", String(reader.load().single().payload))
    }

    @Test
    fun fourthCorruptCopyStaysInPlaceAndDecodeRejectsAReservedFlag() {
        val file = tmp.newFile("mesh_hold_retry.v1")
        file.delete()
        val store = MeshHoldRetryStore(file)
        repeat(MeshHoldRetryStore.MAX_QUARANTINE) { n ->
            assertTrue(store.replace(listOf(saved("good-$n".toByteArray()))))
            val bytes = file.readBytes()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xff).toByte()
            file.writeBytes(bytes)
            val seen = MeshHoldRetryStore(file)
            assertTrue(seen.load().isEmpty())
            assertEquals(MeshHoldRetryStore.LoadState.CORRUPT_QUARANTINED, seen.loadState)
            assertFalse(file.exists())
            assertTrue(File(file.path + ".corrupt-${n + 1}").exists())
        }
        assertTrue(store.replace(listOf(saved("good-final".toByteArray()))))
        val stuck = file.readBytes()
        stuck[stuck.size - 1] = (stuck[stuck.size - 1].toInt() xor 0xff).toByte()
        file.writeBytes(stuck)
        val blocked = MeshHoldRetryStore(file)
        assertTrue(blocked.load().isEmpty())
        assertEquals(MeshHoldRetryStore.LoadState.CORRUPT_IN_PLACE, blocked.loadState)
        assertFalse(blocked.replace(listOf(saved("should-not-land".toByteArray()))))
        assertTrue(file.readBytes().contentEquals(stuck))
        repeat(MeshHoldRetryStore.MAX_QUARANTINE) { n ->
            assertTrue(File(file.path + ".corrupt-${n + 1}").exists())
        }

        val encoded = MeshHoldRetryStore.encode(listOf(saved("flag".toByteArray())))
        encoded[8 + 16] = 0x02
        val bodyLen = encoded.size - 32
        val sum = MeshIds.sha256(encoded.copyOf(bodyLen))
        sum.copyInto(encoded, bodyLen)
        assertNull(MeshHoldRetryStore.decode(encoded))
        val extra = encoded + 0
        assertNull(MeshHoldRetryStore.decode(extra))
    }

    @Test
    fun concurrentReplacesLeaveOneDecodableSnapshot() {
        val file = tmp.newFile("mesh_hold_retry.v1")
        file.delete()
        val store = MeshHoldRetryStore(file)
        val pool = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(4) { worker ->
            pool.submit {
                try {
                    ready.countDown()
                    start.await()
                    repeat(20) { n ->
                        val payload = "w$worker-$n".toByteArray()
                        if (!store.replace(listOf(saved(payload, attempts = n % 8)))) {
                            errors.add(AssertionError("replace failed"))
                        }
                    }
                } catch (err: Throwable) {
                    errors.add(err)
                }
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue(errors.toString(), errors.isEmpty())
        val loaded = MeshHoldRetryStore(file).load().single()
        assertTrue(loaded.id.contentEquals(MeshIds.idFor(loaded.payload)))
        assertTrue(loaded.attempts in 0 until MeshHoldRetry.MAX_ATTEMPTS)
    }

    private fun saved(
        payload: ByteArray,
        emergency: Boolean = false,
        queuedAt: Long = 1_000L,
        deadline: Long = 5_000L,
        attempts: Int = 0,
    ) = MeshHoldRetryStore.Saved(
        id = MeshIds.idFor(payload),
        emergency = emergency,
        queuedAtMs = queuedAt,
        deadlineMs = deadline,
        attempts = attempts,
        payload = payload.copyOf(),
    )
}
