package com.codeyantra.armorchat.core.mesh

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Mesh-1K-AW: crash-durable snapshot of sealed delivery-receipt bytes waiting for an own custody slot.
 *
 * One private file (noBackupFilesDir in production). No Room table and no migration. The payload is the
 * exact sealed envelope the caller already held; this store never builds, re-seals, or logs it.
 * Metadata is the custody id, the emergency flag, queuedAt, the original deadline, and the attempt count.
 *
 * A change is written to `<file>.tmp`, fsynced, then atomically renamed over `<file>` (best-effort
 * directory fsync). The in-memory snapshot is replaced only after that rename. A failed write leaves the
 * previous file in place. A crash leaves either the old or the new file. A stale `.tmp` is never read.
 *
 * Missing file: empty, and writes are allowed. Unreadable: empty for this call, retried next call, and no
 * write (never clobber a file we could not read). Corrupt (bad magic, version, count, record, duplicate
 * id, checksum, extra bytes, or a file longer than [MAX_FILE_BYTES]): empty. The file is renamed to
 * `<file>.corrupt-N` (N = 1..[MAX_QUARANTINE]) and is not deleted. If every quarantine slot is taken the
 * corrupt file stays in place and writes are disabled. An empty queue is a valid n=0 document, distinct
 * from a missing file. At most [MAX_ENTRIES] records, attempts in 0 until [MAX_ATTEMPTS].
 *
 * Thread safety: every public method synchronizes on one lock. Callers that also hold another lock
 * (EmergencyMeshEngine's custody mutex) take that lock first.
 */
class MeshHoldRetryStore(
    private val file: File,
    private val io: Io = FileIo,
    private val log: (String) -> Unit = {},
) {
    class Saved(
        val id: ByteArray,
        val emergency: Boolean,
        val queuedAtMs: Long,
        val deadlineMs: Long,
        val attempts: Int,
        val payload: ByteArray,
    ) {
        fun isolated(): Saved = Saved(id.copyOf(), emergency, queuedAtMs, deadlineMs, attempts, payload.copyOf())

        override fun toString(): String = "Saved(bytes=${payload.size}, attempts=$attempts, emergency=$emergency)"
    }

    enum class LoadState { NOT_LOADED, MISSING, OK, UNREADABLE, CORRUPT_QUARANTINED, CORRUPT_IN_PLACE }

    /** File operations, replaceable in tests for fault injection. */
    interface Io {
        /** Whole file, or null when it does not exist. Throws IOException when unreadable. */
        fun read(file: File): ByteArray?

        /** Byte length, or -1 when [file] does not exist. */
        fun length(file: File): Long = if (file.exists()) file.length() else -1L

        /** Crash-safe replace: temp + fsync + atomic rename. Throws on any failure. */
        fun writeAtomic(file: File, bytes: ByteArray)

        /** Rename [file] to [target] without replacing an existing [target]. */
        fun rename(file: File, target: File): Boolean
    }

    object FileIo : Io {
        override fun read(file: File): ByteArray? = if (!file.exists()) null else file.readBytes()

        override fun writeAtomic(file: File, bytes: ByteArray) {
            val dir = file.absoluteFile.parentFile ?: throw IOException("no parent")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdirs")
            val tmp = File(dir, file.name + TMP_SUFFIX)
            FileOutputStream(tmp, false).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
        }

        override fun rename(file: File, target: File): Boolean {
            if (target.exists()) return false
            return runCatching {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                true
            }.getOrDefault(false)
        }
    }

    private val lock = Any()
    private var entries: List<Saved> = emptyList()
    private var state: LoadState = LoadState.NOT_LOADED

    val loadState: LoadState get() = synchronized(lock) { state }

    /** Snapshot currently on disk (empty when missing, unreadable, or quarantined). Never throws. */
    fun load(): List<Saved> = synchronized(lock) {
        ensureLoaded()
        entries.map { it.isolated() }
    }

    /**
     * Replace the durable snapshot with [next]. False when [next] is not a legal snapshot, the current
     * file cannot be trusted, or the atomic write fails. In every false case the previous file and the
     * in-memory snapshot are unchanged.
     */
    fun replace(next: List<Saved>): Boolean = synchronized(lock) {
        ensureLoaded()
        if (state == LoadState.UNREADABLE || state == LoadState.CORRUPT_IN_PLACE) {
            log("replace=disabled state=$state")
            return false
        }
        if (!acceptable(next)) {
            log("replace=rejected entries=${next.size}")
            return false
        }
        val encoded = encode(next)
        val decoded = decode(encoded)
        if (decoded == null || encoded.size > MAX_FILE_BYTES) {
            log("replace=rejected entries=${next.size}")
            return false
        }
        try {
            io.writeAtomic(file, encoded)
        } catch (_: Exception) {
            log("replace=write_failed entries=${entries.size}")
            return false
        }
        entries = decoded.map { it.isolated() }
        state = LoadState.OK
        log("replace=ok entries=${entries.size}")
        true
    }

    private fun acceptable(next: List<Saved>): Boolean {
        if (next.size > MeshHoldRetry.MAX_ENTRIES) return false
        val seen = HashSet<String>(next.size)
        for (e in next) {
            if (e.id.size != ID_LEN) return false
            if (e.payload.isEmpty() || e.payload.size > MeshPolicy.MAX_PAYLOAD_BYTES) return false
            if (e.attempts !in 0 until MeshHoldRetry.MAX_ATTEMPTS) return false
            if (e.deadlineMs < e.queuedAtMs) return false
            if (!e.id.contentEquals(MeshIds.idFor(e.payload))) return false
            if (!seen.add(MeshIds.hex(e.id))) return false
        }
        return true
    }

    private fun ensureLoaded() {
        if (state != LoadState.NOT_LOADED && state != LoadState.UNREADABLE) return
        val length = try {
            io.length(file)
        } catch (_: Exception) {
            entries = emptyList()
            state = LoadState.UNREADABLE
            log("load=unreadable")
            return
        }
        if (length > MAX_FILE_BYTES) {
            entries = emptyList()
            quarantine()
            return
        }
        val bytes = try {
            io.read(file)
        } catch (_: Exception) {
            entries = emptyList()
            state = LoadState.UNREADABLE
            log("load=unreadable")
            return
        }
        if (bytes == null) {
            entries = emptyList()
            state = LoadState.MISSING
            log("load=missing")
            return
        }
        val parsed = decode(bytes)
        if (parsed != null) {
            entries = parsed.map { it.isolated() }
            state = LoadState.OK
            log("load=ok entries=${parsed.size}")
            return
        }
        entries = emptyList()
        quarantine()
    }

    private fun quarantine() {
        val slot = (1..MAX_QUARANTINE).map { File(file.path + CORRUPT_SUFFIX + it) }.firstOrNull { !it.exists() }
        val moved = slot != null && runCatching { io.rename(file, slot) }.getOrDefault(false)
        state = if (moved) LoadState.CORRUPT_QUARANTINED else LoadState.CORRUPT_IN_PLACE
        log("load=corrupt quarantined=${if (moved) 1 else 0}")
    }

    companion object {
        const val MAX_QUARANTINE: Int = 3
        const val TMP_SUFFIX: String = ".tmp"
        const val CORRUPT_SUFFIX: String = ".corrupt-"
        const val FILE_NAME: String = "mesh_hold_retry.v1"
        const val MAX_FILE_BYTES: Int = MeshHoldRetry.MAX_ENTRIES * (MeshPolicy.MAX_PAYLOAD_BYTES + 64) + 128

        private const val VERSION: Int = 1
        private const val ID_LEN: Int = 16
        private const val HEADER_LEN: Int = 8
        private const val CHECKSUM_LEN: Int = 32
        private const val RECORD_FIXED: Int = 38
        private const val FLAG_EMERGENCY: Int = 0x01
        private val MAGIC: ByteArray = "ACHR1".toByteArray(Charsets.US_ASCII)

        internal fun encode(list: List<Saved>): ByteArray {
            var bodyLen = HEADER_LEN
            for (e in list) bodyLen += RECORD_FIXED + e.payload.size
            val body = ByteBuffer.allocate(bodyLen).order(ByteOrder.BIG_ENDIAN)
            body.put(MAGIC)
            body.put(VERSION.toByte())
            body.putShort(list.size.toShort())
            for (e in list) {
                body.put(e.id.copyOf(ID_LEN))
                body.put((if (e.emergency) FLAG_EMERGENCY else 0).toByte())
                body.putLong(e.queuedAtMs)
                body.putLong(e.deadlineMs)
                body.put(e.attempts.toByte())
                body.putInt(e.payload.size)
                body.put(e.payload)
            }
            val head = body.array()
            return head + MeshIds.sha256(head)
        }

        /** Strict parse. Null on any deviation, including a trailing byte or a bad checksum. */
        internal fun decode(bytes: ByteArray): List<Saved>? {
            if (bytes.size > MAX_FILE_BYTES) return null
            if (bytes.size < HEADER_LEN + CHECKSUM_LEN) return null
            val bodyLen = bytes.size - CHECKSUM_LEN
            val body = bytes.copyOfRange(0, bodyLen)
            val sum = bytes.copyOfRange(bodyLen, bytes.size)
            if (!MeshIds.sha256(body).contentEquals(sum)) return null
            return parseBody(body)
        }

        private fun parseBody(body: ByteArray): List<Saved>? {
            val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
            if (buf.remaining() < HEADER_LEN) return null
            val magic = ByteArray(MAGIC.size)
            buf.get(magic)
            if (!magic.contentEquals(MAGIC)) return null
            if ((buf.get().toInt() and 0xff) != VERSION) return null
            val n = buf.short.toInt() and 0xffff
            if (n > MeshHoldRetry.MAX_ENTRIES) return null
            val out = ArrayList<Saved>(n)
            val seen = HashSet<String>(n)
            repeat(n) {
                val rec = readRecord(buf) ?: return null
                if (!seen.add(MeshIds.hex(rec.id))) return null
                out.add(rec)
            }
            if (buf.hasRemaining()) return null
            return out
        }

        private fun readRecord(buf: ByteBuffer): Saved? {
            if (buf.remaining() < RECORD_FIXED) return null
            val id = ByteArray(ID_LEN)
            buf.get(id)
            val flags = buf.get().toInt() and 0xff
            if ((flags and 0xFE) != 0) return null
            val emergency = (flags and FLAG_EMERGENCY) != 0
            val queuedAt = buf.getLong()
            val deadline = buf.getLong()
            val attempts = buf.get().toInt() and 0xff
            val len = buf.getInt()
            if (attempts >= MeshHoldRetry.MAX_ATTEMPTS) return null
            if (deadline < queuedAt) return null
            if (len <= 0 || len > MeshPolicy.MAX_PAYLOAD_BYTES) return null
            if (buf.remaining() < len) return null
            val payload = ByteArray(len)
            buf.get(payload)
            if (!id.contentEquals(MeshIds.idFor(payload))) return null
            return Saved(id, emergency, queuedAt, deadline, attempts, payload)
        }
    }
}
