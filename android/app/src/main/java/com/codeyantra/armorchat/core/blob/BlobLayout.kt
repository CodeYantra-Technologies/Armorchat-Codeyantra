package com.codeyantra.armorchat.core.blob

import com.codeyantra.armorchat.core.config.AttachmentConfig

/** Must match relay `BLOB_CHUNK_SIZE` / `BLOB_MAX_BYTES`. */
object BlobLayout {
    const val CHUNK_SIZE = 256 * 1024
    const val GCM_TAG_BYTES = 16
    const val MAX_CIPHERTEXT_BYTES = AttachmentConfig.MAX_STREAM_BYTES + GCM_TAG_BYTES
    const val MAX_CHUNKS = 512

    fun chunkCount(totalBytes: Long, chunkSize: Int = CHUNK_SIZE): Int {
        require(totalBytes > 0L)
        require(chunkSize > 0)
        val count = ((totalBytes + chunkSize - 1L) / chunkSize).toInt()
        require(count in 1..MAX_CHUNKS)
        return count
    }

    fun chunkLength(totalBytes: Long, index: Int, chunkSize: Int = CHUNK_SIZE, count: Int = chunkCount(totalBytes, chunkSize)): Int {
        require(index in 0 until count)
        val length = if (index == count - 1) totalBytes - index.toLong() * chunkSize else chunkSize.toLong()
        require(length in 1..chunkSize.toLong())
        return length.toInt()
    }

    fun chunkOffset(index: Int, chunkSize: Int = CHUNK_SIZE): Long = index.toLong() * chunkSize
}
