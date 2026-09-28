package com.codeyantra.armorchat.feature.chat

import com.codeyantra.armorchat.core.blob.BlobLayout
import com.codeyantra.armorchat.core.config.AttachmentConfig
import com.codeyantra.armorchat.messaging.InnerRecord

internal enum class AttachmentRoute {
    /** Existing compress-or-small-blob path. Safe only inside the legacy size window. */
    LegacySmall,
    /** Chunked ciphertext transport. */
    Chunked,
    /** Above the 100 MiB plaintext cap. */
    OverLimit,
}

internal object AttachmentRouting {
    const val OVER_LIMIT_NOTICE = "File is larger than the 100 MB limit."
    const val STALE_TRANSPORT_NOTICE = "Transport unavailable"

    fun decide(isImage: Boolean, sizeBytes: Long, mimeType: String = ""): AttachmentRoute {
        if (sizeBytes > AttachmentConfig.MAX_STREAM_BYTES) return AttachmentRoute.OverLimit
        if (mimeType.startsWith("audio/")) return voiceRoute(sizeBytes)
        if (sizeBytes <= 0L) return AttachmentRoute.Chunked
        val smallImage = isImage && sizeBytes <= AttachmentConfig.LEGACY_IMAGE_COMPRESS_MAX_BYTES
        val smallOther = !isImage && sizeBytes <= AttachmentConfig.LEGACY_TRANSPORT_MAX_BYTES
        return if (smallImage || smallOther) AttachmentRoute.LegacySmall else AttachmentRoute.Chunked
    }

    /** Plaintext bytes whose AES-GCM ciphertext still fits the existing inline MLS attachment. */
    val maxInlineVoicePlaintextBytes: Long =
        InnerRecord.MAX_INLINE_CIPHERTEXT_BYTES.toLong() - BlobLayout.GCM_TAG_BYTES

    private fun voiceRoute(sizeBytes: Long): AttachmentRoute {
        if (sizeBytes <= 0L) return AttachmentRoute.Chunked
        return if (sizeBytes <= maxInlineVoicePlaintextBytes) {
            AttachmentRoute.LegacySmall
        } else {
            AttachmentRoute.Chunked
        }
    }

    fun noticeAfterQueued(current: String?): String? =
        if (current == STALE_TRANSPORT_NOTICE) null else current
}
