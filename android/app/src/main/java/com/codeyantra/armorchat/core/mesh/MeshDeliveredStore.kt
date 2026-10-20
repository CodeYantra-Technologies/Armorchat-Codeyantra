package com.codeyantra.armorchat.core.mesh

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Mesh-1K-N (P2): bounded, durable "delivered here" markers (tombstones).
 *
 * After a Mesh envelope is durably in this phone's inbox, its custody id (MeshIds.idFor(payload),
 * the 16-byte id the existing inventory frame already carries) is kept here for the sender's
 * custody TTL plus [MARGIN_MS]. EmergencyMeshEngine.phaseFor appends up to [MAX_ADVERTISED] of
 * them to the local inventory, so a peer that still holds the bundle skips offering it to THIS
 * phone (CustodyLedger.offer only filters; it never deletes). Wire format, frame types and protocol
 * version are unchanged; e19e30f6 peers read the extra ids exactly like held ids.
 *
 * Trust: an advertised id is unauthenticated. It is NOT a delivery receipt and must never release
 * or delete sender custody (nothing in the codebase does so from remote ids). A local marker only
 * lets this phone classify an exact replay (same payload bytes => same custody id) as DUPLICATE
 * before any decrypt, e.g. after the user deleted the inbox row.
 *
 * Storage: one private file (noBackupFilesDir in production), no Room table, no migration.
 * Contents are custody ids, keyed hashes of the message id and of (conversation, session,
 * message), and two timestamps: no plaintext, no peer key, no session id in clear.
 *
 * Durability: every change is written to `<file>.tmp`, fsynced, then atomically renamed over
 * `<file>` (then a best-effort directory fsync). Memory changes only after that rename succeeded,
 * so a failed write never claims suppression. A crash leaves either the old or the new file; a
 * stale `.tmp` is never read and is replaced by the next write.
 *
 * Missing file: empty. Unreadable (I/O error): empty for this call, retried next call, and no
 * write happens while unreadable (never clobber a file we could not read). Corrupt (bad header,
 * count, field, duplicate id or checksum): empty; the file is quarantined by RENAME to
 * `<file>.corrupt-N` (N = 1..[MAX_QUARANTINE]); nothing is deleted. If every quarantine slot is
 * taken the corrupt file stays in place and markers stay disabled (record = false) instead of
 * overwriting it. Logs carry counts and fixed codes only.
 *
 * Clocks: retention is computed from the bundle's absolute expireAtMs (clamped to
 * [now, now + TTL + skew]) plus [MARGIN_MS]. Every read clamps a stored deadline to
 * now + [MAX_RETAIN_MS], so a wall-clock rollback can never stretch a marker beyond the longest
 * legal custody window seen from the current clock; a forward jump only expires markers early
 * (fail open: the sender re-offers and the P1 durable pre-check answers DUPLICATE).
 *
 * Bounds: at most [MAX_ENTRIES] entries; pruning drops expired entries first, then the oldest by
 * (recordedAtMs, retainUntilMs, custodyIdHex). Advertisement picks at most [MAX_ADVERTISED] live
 * entries ordered by (recordedAtMs desc, retainUntilMs desc, custodyIdHex asc).
 *
 * Thread safety: all public methods synchronize on one private lock.
 */
class MeshDeliveredStore(
    private val file: File,
    private val io: Io = FileIo,
    private val log: (String) -> Unit = {},
) {
    data class Entry(
        val custodyIdHex: String,
        val messageKey: String,
        val binding: String,
        val recordedAtMs: Long,
        val retainUntilMs: Long,
    )

    enum class LoadState { NOT_LOADED, MISSING, OK, UNREADABLE, CORRUPT_QUARANTINED, CORRUPT_IN_PLACE }

    /** File operations, replaceable in tests for fault injection. */
    interface Io {
        /** Whole file, or null when it does not exist. Throws IOException when unreadable. */
        fun read(file: File): ByteArray?

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
    private var entries: List<Entry> = emptyList()
    private var state: LoadState = LoadState.NOT_LOADED

    val loadState: LoadState get() = synchronized(lock) { state }

    /** Live entries as seen at [nowMs] (deadlines clamped); for diagnostics and tests. */
    fun snapshot(nowMs: Long): List<Entry> = synchronized(lock) {
        ensureLoaded()
        entries.map { it.clamped(nowMs) }.filter { it.retainUntilMs > nowMs }
    }

    /**
     * Records that the envelope with custody id [custodyIdHex] and outer message id [messageId] is
     * durably in this inbox. Call only after the inbox commit succeeded. Returns true only when the
     * marker is on disk (and therefore advertised); false = nothing claimed.
     */
    fun record(
        custodyIdHex: String,
        messageId: String,
        binding: String,
        bundleExpireAtMs: Long?,
        nowMs: Long,
    ): Boolean = synchronized(lock) {
        ensureLoaded()
        if (state == LoadState.UNREADABLE || state == LoadState.CORRUPT_IN_PLACE) {
            log("record=disabled state=$state")
            return false
        }
        if (!isHex32(custodyIdHex) || messageId.isEmpty() || !isHex32(binding)) return false
        val key = messageKey(messageId)
        val existing = entries.firstOrNull { it.custodyIdHex == custodyIdHex }
        if (existing != null && existing.messageKey != key) {
            log("record=conflict")
            return false
        }
        val base = bundleExpireAtMs?.coerceIn(nowMs, nowMs + MeshPolicy.ABSOLUTE_TTL_MS + MeshPolicy.CLOCK_SKEW_MS)
            ?: (nowMs + MeshPolicy.ABSOLUTE_TTL_MS)
        val retain = base + MARGIN_MS
        val entry = if (existing == null) {
            Entry(custodyIdHex, key, binding, nowMs, retain)
        } else {
            existing.copy(retainUntilMs = maxOf(existing.retainUntilMs, retain))
        }
        val next = prune(entries.filter { it.custodyIdHex != custodyIdHex } + entry, nowMs)
        if (next.none { it.custodyIdHex == custodyIdHex }) {
            log("record=pruned entries=${next.size}")
            return false
        }
        try {
            io.writeAtomic(file, encode(next))
        } catch (_: Exception) {
            log("record=write_failed entries=${entries.size}")
            return false
        }
        entries = next
        state = LoadState.OK
        log("record=ok entries=${next.size}")
        true
    }

    /** True when a live marker exists for this custody id and message id. Never throws. */
    fun contains(custodyIdHex: String, messageId: String, nowMs: Long): Boolean = synchronized(lock) {
        runCatching {
            ensureLoaded()
            val key = messageKey(messageId)
            entries.any { it.custodyIdHex == custodyIdHex && it.messageKey == key && it.clamped(nowMs).retainUntilMs > nowMs }
        }.getOrDefault(false)
    }

    /** Up to [limit] live custody ids to append to the inventory, deterministic order. Never throws. */
    fun advertised(nowMs: Long, exclude: Set<String>, limit: Int = MAX_ADVERTISED): List<String> = synchronized(lock) {
        runCatching {
            ensureLoaded()
            entries.asSequence()
                .map { it.clamped(nowMs) }
                .filter { it.retainUntilMs > nowMs && it.custodyIdHex !in exclude }
                .sortedWith(
                    compareByDescending<Entry> { it.recordedAtMs }
                        .thenByDescending { it.retainUntilMs }
                        .thenBy { it.custodyIdHex },
                )
                .take(limit.coerceIn(0, MAX_ADVERTISED))
                .map { it.custodyIdHex }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun Entry.clamped(nowMs: Long): Entry {
        val cap = nowMs + MAX_RETAIN_MS
        return if (retainUntilMs > cap) copy(retainUntilMs = cap) else this
    }

    private fun prune(list: List<Entry>, nowMs: Long): List<Entry> {
        val live = list.map { it.clamped(nowMs) }.filter { it.retainUntilMs > nowMs }
        if (live.size <= MAX_ENTRIES) return live
        val drop = live.sortedWith(
            compareBy<Entry> { it.recordedAtMs }.thenBy { it.retainUntilMs }.thenBy { it.custodyIdHex },
        ).take(live.size - MAX_ENTRIES).map { it.custodyIdHex }.toSet()
        return live.filter { it.custodyIdHex !in drop }
    }

    private fun ensureLoaded() {
        if (state != LoadState.NOT_LOADED && state != LoadState.UNREADABLE) return
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
            entries = parsed
            state = LoadState.OK
            log("load=ok entries=${parsed.size}")
            return
        }
        entries = emptyList()
        val slot = (1..MAX_QUARANTINE).map { File(file.path + CORRUPT_SUFFIX + it) }.firstOrNull { !it.exists() }
        val moved = slot != null && runCatching { io.rename(file, slot) }.getOrDefault(false)
        state = if (moved) LoadState.CORRUPT_QUARANTINED else LoadState.CORRUPT_IN_PLACE
        log("load=corrupt quarantined=${if (moved) 1 else 0}")
    }

    companion object {
        const val MAX_ENTRIES: Int = 512
        const val MAX_ADVERTISED: Int = 64
        const val MARGIN_MS: Long = 15L * 60L * 1000L
        const val MAX_RETAIN_MS: Long = MeshPolicy.ABSOLUTE_TTL_MS + MeshPolicy.CLOCK_SKEW_MS + MARGIN_MS
        const val MAX_QUARANTINE: Int = 3
        const val TMP_SUFFIX: String = ".tmp"
        const val CORRUPT_SUFFIX: String = ".corrupt-"
        const val FILE_NAME: String = "mesh_delivered.v1"
        private const val MAGIC = "ACMT1"

        /** Keyed hash of the outer message id (16 bytes, hex). */
        fun messageKey(messageId: String): String = hash32("armorchat-mesh-delivered-msg-v1\n$messageId")

        /** Keyed hash binding the marker to its conversation (= peer), MLS session and message. */
        fun bindingOf(conversationId: String, sessionId: String?, messageId: String): String =
            hash32("armorchat-mesh-delivered-bind-v1\n$conversationId\n${sessionId.orEmpty()}\n$messageId")

        private fun hash32(text: String): String = MeshIds.hex(MeshIds.sha256(text.toByteArray(Charsets.UTF_8)).copyOf(16))

        private fun isHex32(s: String): Boolean = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

        internal fun encode(list: List<Entry>): ByteArray {
            val body = StringBuilder()
            body.append(MAGIC).append('\n')
            body.append("n=").append(list.size).append('\n')
            for (e in list) {
                body.append(e.custodyIdHex).append(' ').append(e.messageKey).append(' ').append(e.binding)
                    .append(' ').append(e.recordedAtMs).append(' ').append(e.retainUntilMs).append('\n')
            }
            val head = body.toString().toByteArray(Charsets.UTF_8)
            val sum = MeshIds.hex(MeshIds.sha256(head))
            return head + "sum=$sum\n".toByteArray(Charsets.UTF_8)
        }

        /** Strict parse; null on any deviation. */
        internal fun decode(bytes: ByteArray): List<Entry>? = runCatching {
            val text = String(bytes, Charsets.UTF_8)
            if (!text.endsWith("\n")) return null
            val sumAt = text.lastIndexOf("sum=")
            if (sumAt <= 0 || text[sumAt - 1] != '\n') return null
            val head = text.substring(0, sumAt)
            val sumLine = text.substring(sumAt, text.length - 1)
            val expected = MeshIds.hex(MeshIds.sha256(head.toByteArray(Charsets.UTF_8)))
            if (sumLine != "sum=$expected") return null
            val lines = head.split('\n').dropLast(1)
            if (lines.size < 2 || lines[0] != MAGIC || !lines[1].startsWith("n=")) return null
            val n = lines[1].removePrefix("n=").toIntOrNull() ?: return null
            if (n < 0 || n > MAX_ENTRIES || lines.size != n + 2) return null
            val out = ArrayList<Entry>(n)
            val seen = HashSet<String>()
            for (line in lines.drop(2)) {
                val f = line.split(' ')
                if (f.size != 5) return null
                if (!isHex32(f[0]) || !isHex32(f[1]) || !isHex32(f[2])) return null
                val rec = f[3].toLongOrNull() ?: return null
                val until = f[4].toLongOrNull() ?: return null
                if (rec < 0 || until <= 0) return null
                if (!seen.add(f[0])) return null
                out += Entry(f[0], f[1], f[2], rec, until)
            }
            out
        }.getOrNull()
    }
}
