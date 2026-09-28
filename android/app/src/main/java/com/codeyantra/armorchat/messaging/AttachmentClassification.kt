package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.domain.model.MessageType

internal object AttachmentClassification {
    fun mediaClass(mimeType: String, isImage: Boolean): String = when {
        mimeType.startsWith("audio/") -> "voice"
        isImage || mimeType.startsWith("image/") -> "image"
        else -> "document"
    }

    fun messageTypeName(mediaClass: String): String = when (mediaClass) {
        "image" -> MessageType.IMAGE.name
        "voice" -> MessageType.VOICE.name
        else -> MessageType.DOCUMENT.name
    }
}
