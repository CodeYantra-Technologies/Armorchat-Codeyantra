package com.codeyantra.armorchat.domain.model

import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.messaging.ReplyMetadata

data class AttachmentMetadata(
    val attachmentId: String,
    val blobId: String,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val ciphertextDigestSha256: String,
    val ivBase64Url: String,
    val wrappedKeyBase64Url: String,
    val cryptoVersion: Int = 1,
    val inlineCiphertext: String = "",
    val mediaClass: String = "document",
    val replyTo: ReplyMetadata? = null,
) {
    fun toJson(): String = CanonicalJson.of(
        mapOf(
            "type" to "ATTACHMENT",
            "attachmentId" to attachmentId,
            "blobId" to blobId,
            "originalName" to originalName,
            "mimeType" to mimeType,
            "sizeBytes" to sizeBytes,
            "ciphertextDigestSha256" to ciphertextDigestSha256,
            "ivBase64Url" to ivBase64Url,
            "wrappedKeyBase64Url" to wrappedKeyBase64Url,
            "cryptoVersion" to cryptoVersion.toLong(),
            "inlineCiphertext" to inlineCiphertext,
            "mediaClass" to mediaClass,
        ).let { if (replyTo != null) it + ("reply_to" to replyTo.toMap()) else it },
    )

    fun toInnerBody(clientSequence: Long): com.codeyantra.armorchat.messaging.InnerBody.Attachment =
        com.codeyantra.armorchat.messaging.InnerBody.Attachment(
            clientSequence = clientSequence,
            attachmentId = attachmentId,
            blobId = blobId,
            originalName = originalName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            ciphertextSha256 = ciphertextDigestSha256,
            ivBase64Url = ivBase64Url,
            wrappedKeyBase64Url = wrappedKeyBase64Url,
            cryptoVersion = cryptoVersion.toLong(),
            mediaClass = mediaClass,
            inlineCiphertext = inlineCiphertext,
            replyTo = replyTo,
        )

    companion object {
        fun fromInner(body: com.codeyantra.armorchat.messaging.InnerBody.Attachment): AttachmentMetadata =
            AttachmentMetadata(
                attachmentId = body.attachmentId,
                blobId = body.blobId,
                originalName = body.originalName,
                mimeType = body.mimeType,
                sizeBytes = body.sizeBytes,
                ciphertextDigestSha256 = body.ciphertextSha256,
                ivBase64Url = body.ivBase64Url,
                wrappedKeyBase64Url = body.wrappedKeyBase64Url,
                cryptoVersion = body.cryptoVersion.toInt(),
                inlineCiphertext = body.inlineCiphertext,
                mediaClass = body.mediaClass,
                replyTo = body.replyTo,
            )

        fun parseOrNull(json: String?): AttachmentMetadata? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = CanonicalJson.parseObject(json)
                if (obj["type"] != "ATTACHMENT") return null
                val mime = (obj["mimeType"] as? String).orEmpty()
                val media = (obj["mediaClass"] as? String).orEmpty().ifBlank {
                    when {
                        mime.startsWith("image/") -> "image"
                        mime.startsWith("audio/") -> "voice"
                        else -> "document"
                    }
                }
                @Suppress("UNCHECKED_CAST")
                val replyMap = obj["reply_to"] as? Map<*, *>
                val replyTo = ReplyMetadata.fromMap(replyMap)
                AttachmentMetadata(
                    attachmentId = (obj["attachmentId"] as? String).orEmpty(),
                    blobId = (obj["blobId"] as? String).orEmpty(),
                    originalName = (obj["originalName"] as? String).orEmpty(),
                    mimeType = mime,
                    sizeBytes = (obj["sizeBytes"] as? Number)?.toLong() ?: 0L,
                    ciphertextDigestSha256 = (obj["ciphertextDigestSha256"] as? String).orEmpty(),
                    ivBase64Url = (obj["ivBase64Url"] as? String).orEmpty(),
                    wrappedKeyBase64Url = (obj["wrappedKeyBase64Url"] as? String).orEmpty(),
                    cryptoVersion = ((obj["cryptoVersion"] as? Number)?.toInt()) ?: 1,
                    inlineCiphertext = (obj["inlineCiphertext"] as? String).orEmpty(),
                    mediaClass = media,
                    replyTo = replyTo,
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
