package com.codeyantra.armorchat.core.blob

import android.content.Context
import android.net.Uri
import com.codeyantra.armorchat.core.config.AttachmentConfig
import com.codeyantra.armorchat.core.crypto.AttachmentCrypto
import com.codeyantra.armorchat.core.storage.AttachmentStorageManager
import com.codeyantra.armorchat.domain.identity.Base64Url
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class BlobTransferUi(
    val active: Boolean = false,
    val detail: String = "",
    val progress: Float = 0f,
    val paused: Boolean = false,
    val cancellable: Boolean = false,
)

enum class DownloadOutcome { Saved, Failed, NotChunked }

sealed class PublishResult {
    data class Sent(val messageId: String) : PublishResult()
    data object Retry : PublishResult()
    data object GiveUp : PublishResult()
}

data class LargeBlobPending(
    val conversationId: String,
    val attachmentId: String,
    val blobId: String,
    val originalName: String,
    val mimeType: String,
    val mediaClass: String,
    val sizeBytes: Long,
    val cipherFile: String,
    val cipherBytes: Long,
    val sha256: String,
    val keyBase64Url: String,
    val ivBase64Url: String,
    val replyToJson: String?,
    val phase: String,
    val notBeforeEpochMs: Long,
    val attempts: Int,
    val attempted: Boolean,
    val messageId: String?,
)

data class LargeOutgoing(
    val conversationId: String,
    val uri: Uri,
    val originalName: String,
    val mimeType: String,
    val mediaClass: String,
    val sizeBytes: Long,
    val replyToJson: String?,
)

/**
 * Encrypts a file to disk and uploads ciphertext slices. The messaging mutex
 * is not held here. A network failure pauses this transfer and leaves text
 * send free to run.
 */
class LargeBlobTransfer(
    private val context: Context,
    private val transport: BlobTransport = BlobTransport(),
    private val origin: () -> String,
    private val ownerToken: suspend (conversationId: String) -> String?,
    private val publish: suspend (
        pending: LargeBlobPending,
        markAttempted: () -> Unit,
        assignMessageId: (String) -> Unit,
    ) -> PublishResult,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val conversationOf = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val publishing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val transfers = MutableStateFlow<Map<String, BlobTransferUi>>(emptyMap())
    private val downloads = MutableStateFlow<Map<String, Float>>(emptyMap())

    fun observe(conversationId: String): Flow<BlobTransferUi> =
        transfers.map { it[conversationId] ?: BlobTransferUi() }

    fun observeDownloads(): Flow<Map<String, Float>> = downloads

    fun enqueue(spec: LargeOutgoing) {
        val blobId = newId(32)
        val attachmentId = newId(16)
        conversationOf[blobId] = spec.conversationId
        val job = scope.launch {
            try {
                runOutgoing(spec, blobId, attachmentId)
            } finally {
                jobs.remove(blobId)
            }
        }
        jobs[blobId] = job
    }

    fun kick() {
        scope.launch {
            val now = System.currentTimeMillis()
            for (pending in loadAll()) {
                if (pending.phase == "failed" || pending.phase == "described") continue
                if (pending.notBeforeEpochMs > now) continue
                if (jobs[pending.blobId]?.isActive == true) continue
                if (loadAll().any { it.conversationId == pending.conversationId && jobs[it.blobId]?.isActive == true }) {
                    continue
                }
                val job = scope.launch {
                    try {
                        resume(pending)
                    } finally {
                        jobs.remove(pending.blobId)
                    }
                }
                jobs[pending.blobId] = job
            }
        }
    }

    fun cancel(conversationId: String) {
        jobs.forEach { (blobId, job) ->
            if (conversationOf[blobId] == conversationId && blobId !in publishing) job.cancel()
        }
        loadAll().filter {
            it.conversationId == conversationId && it.messageId == null && !it.attempted && it.blobId !in publishing
        }.forEach { pending ->
            scope.launch { abandon(pending) }
        }
        transfers.update { current -> current - conversationId }
    }

    suspend fun download(
        attachmentId: String,
        blobId: String,
        keyBase64Url: String,
        ivBase64Url: String,
        sha256: String,
    ): DownloadOutcome {
        if (AttachmentStorageManager.hasAttachment(context, attachmentId)) return DownloadOutcome.Saved
        val manifest = try {
            transport.manifest(origin(), blobId)
        } catch (_: IOException) {
            return DownloadOutcome.Failed
        } catch (_: RuntimeException) {
            return DownloadOutcome.Failed
        }
        if (manifest == null) return DownloadOutcome.NotChunked
        if (manifest.ciphertextSha256 != sha256) return DownloadOutcome.Failed
        if (manifest.totalBytes <= 0L || manifest.totalBytes > BlobLayout.MAX_CIPHERTEXT_BYTES) return DownloadOutcome.Failed
        if (manifest.chunkSize !in 64 * 1024..1024 * 1024) return DownloadOutcome.Failed
        if (manifest.chunkCount !in 1..BlobLayout.MAX_CHUNKS) return DownloadOutcome.Failed
        if (manifest.chunkSha256.size != manifest.chunkCount) return DownloadOutcome.Failed
        val dir = File(context.cacheDir, "blob-transfer/in/$attachmentId")
        dir.mkdirs()
        return try {
            val files = ArrayList<File>(manifest.chunkCount)
            for (index in 0 until manifest.chunkCount) {
                coroutineContext.ensureActive()
                val length = BlobLayout.chunkLength(
                    manifest.totalBytes,
                    index,
                    manifest.chunkSize,
                    manifest.chunkCount,
                )
                val file = File(dir, index.toString())
                if (!file.exists() || file.length() != length.toLong()) {
                    file.delete()
                    transport.downloadChunk(origin(), blobId, index, file, length)
                }
                val chunkHash = file.inputStream().use { AttachmentCrypto.sha256(it) }
                if (chunkHash != manifest.chunkSha256[index]) {
                    file.delete()
                    return DownloadOutcome.Failed
                }
                files.add(file)
                downloads.update { it + (attachmentId to (index + 1f) / manifest.chunkCount) }
            }
            val whole = ChunkInput(files).use { AttachmentCrypto.sha256(it) }
            if (whole != sha256) return DownloadOutcome.Failed
            val dest = AttachmentStorageManager.getAttachmentFile(context, attachmentId)
            val partial = File(dest.parentFile, dest.name + ".partial")
            partial.outputStream().use { output ->
                ChunkInput(files).use { input ->
                    AttachmentCrypto.decryptStream(input, output, keyBase64Url, ivBase64Url)
                }
            }
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
            dir.deleteRecursively()
            DownloadOutcome.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DownloadOutcome.Failed
        } finally {
            downloads.update { it - attachmentId }
        }
    }

    private suspend fun runOutgoing(spec: LargeOutgoing, blobId: String, attachmentId: String) {
        var cipher: File? = null
        var plainPartial: File? = null
        var saved: LargeBlobPending? = null
        try {
            show(spec.conversationId, "Encrypting ${label(spec.originalName)}", 0f, paused = false, cancellable = true)
            val plainDest = AttachmentStorageManager.getAttachmentFile(context, attachmentId)
            plainPartial = File(plainDest.parentFile, plainDest.name + ".partial")
            cipher = File(context.cacheDir, "blob-transfer/out/$blobId/cipher")
            cipher.parentFile?.mkdirs()
            val encrypted = context.contentResolver.openInputStream(spec.uri).use { raw ->
                if (raw == null) throw FailTransfer("unreadable")
                plainPartial.outputStream().use { plainOut ->
                    val tee = TeeInput(raw, plainOut)
                    AttachmentCrypto.encryptToFile(
                        input = tee,
                        ciphertextFile = cipher,
                        maxPlaintextBytes = AttachmentConfig.MAX_STREAM_BYTES,
                        shouldContinue = { jobs[blobId]?.isActive != false },
                    ) { read ->
                        val denom = if (spec.sizeBytes > 0) spec.sizeBytes else read.coerceAtLeast(1)
                        show(
                            spec.conversationId,
                            "Encrypting ${label(spec.originalName)}",
                            (read.toFloat() / denom.toFloat()).coerceIn(0f, 1f),
                            paused = false,
                            cancellable = true,
                        )
                    }
                }
            }
            if (encrypted.plaintextBytes <= 0L) throw FailTransfer("empty")
            if (!plainPartial.renameTo(plainDest)) {
                plainPartial.copyTo(plainDest, overwrite = true)
                plainPartial.delete()
            }
            plainPartial = null
            val pending = LargeBlobPending(
                conversationId = spec.conversationId,
                attachmentId = attachmentId,
                blobId = blobId,
                originalName = spec.originalName.ifBlank { spec.mediaClass }.take(64),
                mimeType = spec.mimeType.ifBlank { "application/octet-stream" }.take(80),
                mediaClass = spec.mediaClass,
                sizeBytes = encrypted.plaintextBytes,
                cipherFile = cipher.absolutePath,
                cipherBytes = cipher.length(),
                sha256 = encrypted.sha256Digest,
                keyBase64Url = encrypted.keyBase64Url,
                ivBase64Url = encrypted.ivBase64Url,
                replyToJson = spec.replyToJson,
                phase = "uploading",
                notBeforeEpochMs = 0L,
                attempts = 0,
                attempted = false,
                messageId = null,
            )
            save(pending)
            saved = pending
            resume(pending)
        } catch (cancelled: CancellationException) {
            saved?.let { abandon(it) }
            cipher?.delete()
            plainPartial?.delete()
            AttachmentStorageManager.getAttachmentFile(context, attachmentId).delete()
            clear(spec.conversationId)
            throw cancelled
        } catch (paused: PauseTransfer) {
            val current = saved
            if (current != null) {
                val wait = backoff(current.attempts + 1)
                val next = current.copy(
                    phase = "paused",
                    attempts = current.attempts + 1,
                    notBeforeEpochMs = System.currentTimeMillis() + wait,
                )
                save(next)
                show(
                    spec.conversationId,
                    "Paused — will resume · ${label(spec.originalName)}",
                    transfers.value[spec.conversationId]?.progress ?: 0f,
                    paused = true,
                    cancellable = true,
                )
            }
        } catch (_: Exception) {
            saved?.let { save(it.copy(phase = "failed")) }
            plainPartial?.delete()
            show(
                spec.conversationId,
                "Couldn't send ${label(spec.originalName)}",
                0f,
                paused = false,
                cancellable = true,
            )
        }
    }

    private suspend fun resume(start: LargeBlobPending) {
        var pending = start
        try {
            if (pending.phase == "failed" || pending.phase == "described") return
            if (pending.attempted && pending.messageId == null) {
                save(pending.copy(phase = "failed"))
                show(pending.conversationId, "Couldn't send ${label(pending.originalName)}", 0f, paused = false, cancellable = true)
                return
            }
            if (pending.messageId == null) {
                pending = uploadCipher(pending)
                save(pending.copy(phase = "publishing"))
            }
            val publishingRow = load(pending.blobId) ?: pending
            show(publishingRow.conversationId, "Sending ${label(publishingRow.originalName)}", 1f, paused = false, cancellable = false)
            publishing.add(publishingRow.blobId)
            val result = try {
                publish(
                    publishingRow,
                    {
                        save(publishingRow.copy(attempted = true, phase = "publishing"))
                    },
                    { messageId ->
                        save(publishingRow.copy(attempted = true, phase = "publishing", messageId = messageId))
                    },
                )
            } finally {
                publishing.remove(publishingRow.blobId)
            }
            val fresh = load(publishingRow.blobId) ?: publishingRow
            when (result) {
                is PublishResult.Sent -> {
                    File(fresh.cipherFile).delete()
                    pendingFile(fresh.blobId).delete()
                    clear(fresh.conversationId)
                }
                PublishResult.Retry -> {
                    val wait = backoff(fresh.attempts + 1)
                    save(
                        fresh.copy(
                            phase = "paused",
                            attempts = fresh.attempts + 1,
                            notBeforeEpochMs = System.currentTimeMillis() + wait,
                        ),
                    )
                    show(
                        fresh.conversationId,
                        "Paused — will resume · ${label(fresh.originalName)}",
                        1f,
                        paused = true,
                        cancellable = true,
                    )
                }
                PublishResult.GiveUp -> {
                    save(fresh.copy(phase = "failed", attempted = true))
                    show(
                        fresh.conversationId,
                        "Couldn't send ${label(fresh.originalName)}",
                        0f,
                        paused = false,
                        cancellable = true,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (paused: PauseTransfer) {
            val wait = backoff(pending.attempts + 1)
            save(
                pending.copy(
                    phase = "paused",
                    attempts = pending.attempts + 1,
                    notBeforeEpochMs = System.currentTimeMillis() + wait,
                ),
            )
            show(
                pending.conversationId,
                "Paused — will resume · ${label(pending.originalName)}",
                transfers.value[pending.conversationId]?.progress ?: 0f,
                paused = true,
                cancellable = true,
            )
        } catch (cancelledOuter: CancellationException) {
            throw cancelledOuter
        } catch (_: Exception) {
            save(pending.copy(phase = "failed"))
            show(pending.conversationId, "Couldn't send ${label(pending.originalName)}", 0f, paused = false, cancellable = true)
        }
    }

    private suspend fun uploadCipher(pending: LargeBlobPending): LargeBlobPending {
        val cipher = File(pending.cipherFile)
        if (!cipher.exists() || cipher.length() != pending.cipherBytes) throw FailTransfer("cipher missing")
        val token = ownerToken(pending.conversationId) ?: throw PauseTransfer()
        var session = transport.openSession(origin(), token, pending.blobId, pending.cipherBytes, pending.sha256)
        if (session.ciphertextSha256 != pending.sha256 || session.totalBytes != pending.cipherBytes) {
            throw FailTransfer("session mismatch")
        }
        if (!session.sealed) {
            for (index in 0 until session.chunkCount) {
                coroutineContext.ensureActive()
                if (index in session.received) continue
                val length = BlobLayout.chunkLength(session.totalBytes, index, session.chunkSize, session.chunkCount)
                val offset = BlobLayout.chunkOffset(index, session.chunkSize)
                var tries = 0
                while (true) {
                    try {
                        transport.putChunk(origin(), token, pending.blobId, index, cipher, offset, length)
                        break
                    } catch (rejected: BlobTransport.Rejected) {
                        if (rejected.http == 409) throw FailTransfer("chunk conflict")
                        throw rejected
                    } catch (io: IOException) {
                        tries += 1
                        if (tries >= 3) throw PauseTransfer()
                        delay(400L * tries)
                    }
                }
                val sent = (offset + length).coerceAtMost(session.totalBytes)
                show(
                    pending.conversationId,
                    "Sending ${label(pending.originalName)} · ${(100f * sent / session.totalBytes).toInt()}%",
                    sent.toFloat() / session.totalBytes.toFloat(),
                    paused = false,
                    cancellable = true,
                )
            }
        }
        var rounds = 0
        while (rounds < 3 && !session.sealed) {
            rounds += 1
            try {
                transport.complete(origin(), token, pending.blobId, pending.sha256)
                return pending
            } catch (rejected: BlobTransport.Rejected) {
                if (rejected.http != 409) throw FailTransfer("complete ${rejected.http}")
                session = transport.openSession(origin(), token, pending.blobId, pending.cipherBytes, pending.sha256)
                if (session.sealed) return pending
            } catch (_: IOException) {
                throw PauseTransfer()
            }
        }
        if (!session.sealed) throw PauseTransfer()
        return pending
    }

    private suspend fun abandon(pending: LargeBlobPending) {
        val token = runCatching { ownerToken(pending.conversationId) }.getOrNull()
        if (!token.isNullOrEmpty() && pending.messageId == null && !pending.attempted) {
            runCatching { transport.deleteSession(origin(), token, pending.blobId) }
        }
        File(pending.cipherFile).delete()
        pendingFile(pending.blobId).delete()
        if (pending.messageId == null) {
            AttachmentStorageManager.getAttachmentFile(context, pending.attachmentId).delete()
        }
    }

    private fun show(conversationId: String, detail: String, progress: Float, paused: Boolean, cancellable: Boolean) {
        transfers.update {
            it + (conversationId to BlobTransferUi(true, detail, progress.coerceIn(0f, 1f), paused, cancellable))
        }
    }

    private fun clear(conversationId: String) {
        transfers.update { it - conversationId }
    }

    private fun backoff(attempt: Int): Long = (2_000L shl (attempt - 1).coerceAtLeast(0)).coerceAtMost(30_000L)

    private fun pendingDir(): File = File(context.filesDir, "blob-transfer").apply { mkdirs() }

    private fun pendingFile(blobId: String): File = File(pendingDir(), "$blobId.json")

    private fun save(pending: LargeBlobPending) {
        val dest = pendingFile(pending.blobId)
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.writeText(JSONObject().apply {
            put("conversationId", pending.conversationId)
            put("attachmentId", pending.attachmentId)
            put("blobId", pending.blobId)
            put("originalName", pending.originalName)
            put("mimeType", pending.mimeType)
            put("mediaClass", pending.mediaClass)
            put("sizeBytes", pending.sizeBytes)
            put("cipherFile", pending.cipherFile)
            put("cipherBytes", pending.cipherBytes)
            put("sha256", pending.sha256)
            put("keyBase64Url", pending.keyBase64Url)
            put("ivBase64Url", pending.ivBase64Url)
            put("replyToJson", pending.replyToJson ?: "")
            put("phase", pending.phase)
            put("notBeforeEpochMs", pending.notBeforeEpochMs)
            put("attempts", pending.attempts)
            put("attempted", pending.attempted)
            put("messageId", pending.messageId ?: "")
        }.toString())
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun load(blobId: String): LargeBlobPending? = loadFile(pendingFile(blobId))

    private fun loadAll(): List<LargeBlobPending> =
        pendingDir().listFiles { file -> file.name.endsWith(".json") }?.mapNotNull { loadFile(it) }.orEmpty()

    private fun loadFile(file: File): LargeBlobPending? {
        if (!file.exists()) return null
        return runCatching {
            val obj = JSONObject(file.readText())
            LargeBlobPending(
                conversationId = obj.getString("conversationId"),
                attachmentId = obj.getString("attachmentId"),
                blobId = obj.getString("blobId"),
                originalName = obj.getString("originalName"),
                mimeType = obj.getString("mimeType"),
                mediaClass = obj.getString("mediaClass"),
                sizeBytes = obj.getLong("sizeBytes"),
                cipherFile = obj.getString("cipherFile"),
                cipherBytes = obj.getLong("cipherBytes"),
                sha256 = obj.getString("sha256"),
                keyBase64Url = obj.getString("keyBase64Url"),
                ivBase64Url = obj.getString("ivBase64Url"),
                replyToJson = obj.optString("replyToJson").ifBlank { null },
                phase = obj.getString("phase"),
                notBeforeEpochMs = obj.optLong("notBeforeEpochMs"),
                attempts = obj.optInt("attempts"),
                attempted = obj.optBoolean("attempted"),
                messageId = obj.optString("messageId").ifBlank { null },
            )
        }.getOrNull()
    }

    private fun label(name: String): String = name.ifBlank { "file" }.take(48)

    private fun newId(bytes: Int): String =
        Base64Url.encode(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private class PauseTransfer : Exception()
    private class FailTransfer(message: String) : Exception(message)

    private class TeeInput(private val source: InputStream, private val copy: java.io.OutputStream) : InputStream() {
        override fun read(): Int {
            val value = source.read()
            if (value >= 0) copy.write(value)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = source.read(b, off, len)
            if (n > 0) copy.write(b, off, n)
            return n
        }
    }

    private class ChunkInput(private val files: List<File>) : InputStream() {
        private var index = 0
        private var current: InputStream? = null

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val stream = current ?: openNext() ?: return -1
                val n = stream.read(b, off, len)
                if (n >= 0) return n
                stream.close()
                current = null
            }
        }

        override fun close() {
            current?.close()
            current = null
        }

        private fun openNext(): InputStream? {
            if (index >= files.size) return null
            val stream = files[index].inputStream()
            index += 1
            current = stream
            return stream
        }
    }
}
