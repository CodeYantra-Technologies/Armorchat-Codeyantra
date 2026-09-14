package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.json.CanonicalJson

data class ReplyMetadata(
    val messageId: String,
    val senderName: String,
    val kind: String,
    val preview: String,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "message_id" to messageId,
        "sender_name" to senderName,
        "kind" to kind,
        "preview" to preview,
    )

    fun toJson(): String = CanonicalJson.of(toMap())

    companion object {
        fun fromMap(map: Map<*, *>?): ReplyMetadata? {
            if (map == null) return null
            return ReplyMetadata(
                messageId = (map["message_id"] as? String) ?: return null,
                senderName = (map["sender_name"] as? String) ?: "User",
                kind = (map["kind"] as? String) ?: "text",
                preview = (map["preview"] as? String) ?: "",
            )
        }

        fun fromJson(json: String?): ReplyMetadata? {
            if (json.isNullOrBlank()) return null
            return try {
                val map = CanonicalJson.parseObject(json)
                fromMap(map)
            } catch (_: Exception) {
                null
            }
        }
    }
}
