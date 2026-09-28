package com.codeyantra.armorchat.core.blob

import com.codeyantra.armorchat.core.json.CanonicalJson
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * HTTPS client for ciphertext chunks only. It does not submit envelopes,
 * invites, or push tokens, and it uses its own timeouts so a stalled upload
 * cannot hold the messaging client.
 */
class BlobTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    data class Session(
        val received: Set<Int>,
        val sealed: Boolean,
        val chunkSize: Int,
        val chunkCount: Int,
        val totalBytes: Long,
        val ciphertextSha256: String,
    )

    data class Manifest(
        val totalBytes: Long,
        val chunkSize: Int,
        val chunkCount: Int,
        val ciphertextSha256: String,
        val chunkSha256: List<String>,
    )

    class Rejected(val http: Int) : IOException("blob http $http")

    suspend fun openSession(
        origin: String,
        ownerToken: String,
        blobId: String,
        totalBytes: Long,
        sha256: String,
    ): Session = withContext(Dispatchers.IO) {
        val count = BlobLayout.chunkCount(totalBytes)
        val json = CanonicalJson.of(
            mapOf(
                "chunk_count" to count.toLong(),
                "chunk_size" to BlobLayout.CHUNK_SIZE.toLong(),
                "ciphertext_sha256" to sha256,
                "protocol_version" to 1L,
                "total_bytes" to totalBytes,
            ),
        )
        val response = execute(url(origin, blobId, "sessions"), "POST", ownerToken, json.toRequestBody(JSON))
        if (response.code != 200 && response.code != 201) throw statusFailure(response.code)
        parseSession(response.body)
    }

    suspend fun putChunk(
        origin: String,
        ownerToken: String,
        blobId: String,
        index: Int,
        file: File,
        offset: Long,
        length: Int,
    ) = withContext(Dispatchers.IO) {
        val response = execute(
            url(origin, blobId, "chunks", index.toString()),
            "PUT",
            ownerToken,
            FileSliceBody(file, offset, length),
        )
        if (response.code != 200 && response.code != 201) throw statusFailure(response.code)
    }

    suspend fun complete(origin: String, ownerToken: String, blobId: String, sha256: String) =
        withContext(Dispatchers.IO) {
            val json = CanonicalJson.of(
                mapOf(
                    "ciphertext_sha256" to sha256,
                    "protocol_version" to 1L,
                ),
            )
            val response = execute(url(origin, blobId, "complete"), "POST", ownerToken, json.toRequestBody(JSON))
            if (response.code != 200 && response.code != 201) throw statusFailure(response.code)
        }

    /** 404/400 means this id is not a sealed chunked blob (legacy single-PUT or absent). */
    suspend fun manifest(origin: String, blobId: String): Manifest? = withContext(Dispatchers.IO) {
        val response = execute(url(origin, blobId, "manifest"), "GET", token = null, body = null)
        when (response.code) {
            404, 400 -> null
            200 -> parseManifest(response.body)
            else -> throw statusFailure(response.code)
        }
    }

    suspend fun downloadChunk(origin: String, blobId: String, index: Int, dest: File, expectedLength: Int) =
        withContext(Dispatchers.IO) {
            val response = execute(url(origin, blobId, "chunks", index.toString()), "GET", token = null, body = null)
            if (response.code != 200) throw statusFailure(response.code)
            val bytes = response.bytes ?: throw IOException("empty chunk")
            if (bytes.size != expectedLength) throw IOException("chunk length")
            dest.parentFile?.mkdirs()
            val partial = File(dest.parentFile, dest.name + ".part")
            partial.writeBytes(bytes)
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
        }

    suspend fun deleteSession(origin: String, ownerToken: String, blobId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                execute(url(origin, blobId, "sessions"), "DELETE", ownerToken, null)
            }
        }
    }

    private fun parseSession(body: String): Session {
        val obj = CanonicalJson.parseObject(body)
        val received = (obj["received"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }.toSet()
        return Session(
            received = received,
            sealed = obj["upload_state"] == "sealed",
            chunkSize = (obj["chunk_size"] as Number).toInt(),
            chunkCount = (obj["chunk_count"] as Number).toInt(),
            totalBytes = (obj["total_bytes"] as Number).toLong(),
            ciphertextSha256 = obj["ciphertext_sha256"] as String,
        )
    }

    private fun parseManifest(body: String): Manifest {
        val obj = CanonicalJson.parseObject(body)
        val hashes = (obj["chunk_sha256"] as? List<*>).orEmpty().map { it as String }
        return Manifest(
            totalBytes = (obj["total_bytes"] as Number).toLong(),
            chunkSize = (obj["chunk_size"] as Number).toInt(),
            chunkCount = (obj["chunk_count"] as Number).toInt(),
            ciphertextSha256 = obj["ciphertext_sha256"] as String,
            chunkSha256 = hashes,
        )
    }

    private fun statusFailure(code: Int): IOException {
        if (code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504) {
            return IOException("blob http $code")
        }
        return Rejected(code)
    }

    private data class HttpResult(val code: Int, val body: String, val bytes: ByteArray?)

    private suspend fun execute(url: okhttp3.HttpUrl, method: String, token: String?, body: RequestBody?): HttpResult {
        require(url.scheme == "https")
        val request = Request.Builder()
            .url(url)
            .method(method, if (method == "GET" || method == "DELETE") null else body)
            .apply {
                if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token")
            }
            .build()
        val call = client.newCall(request)
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val cancel = job?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                val bytes = response.body?.bytes()
                val text = if (response.header("content-type").orEmpty().contains("json")) {
                    bytes?.toString(Charsets.UTF_8).orEmpty()
                } else {
                    ""
                }
                return HttpResult(response.code, text, bytes)
            }
        } finally {
            cancel?.dispose()
        }
    }

    private fun url(origin: String, blobId: String, vararg extra: String): okhttp3.HttpUrl {
        val builder = origin.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("v1")
            .addPathSegment("blobs")
            .addPathSegment(blobId)
        extra.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private class FileSliceBody(
        private val file: File,
        private val offset: Long,
        private val length: Int,
    ) : RequestBody() {
        override fun contentType() = OCTET
        override fun contentLength() = length.toLong()
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(8192)
                var left = length
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size, left))
                    if (n < 0) throw IOException("short ciphertext slice")
                    sink.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
    }
}
