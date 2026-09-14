package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.domain.identity.Base64Url

class MessagingProtocolException(message: String) : SecurityException(message)

enum class InnerKind {
    TEXT,
    DELIVERY,
    READ,
    SETUP_ROUTE,
    ATTACHMENT,
    ;

    val wire: String
        get() = when (this) {
            TEXT -> "text"
            DELIVERY -> "delivery"
            READ -> "read"
            SETUP_ROUTE -> "setup_route"
            ATTACHMENT -> "attachment"
        }

    val isUserMessage: Boolean
        get() = this == TEXT || this == ATTACHMENT

    companion object {
        fun fromWire(raw: String): InnerKind = when (raw) {
            "text" -> TEXT
            "delivery" -> DELIVERY
            "read" -> READ
            "setup_route" -> SETUP_ROUTE
            "attachment" -> ATTACHMENT
            else -> throw MessagingProtocolException("unknown inner kind")
        }
    }
}

sealed class InnerBody {
    data class Text(
        val text: String,
        val clientSequence: Long,
        val replyTo: ReplyMetadata? = null,
    ) : InnerBody()

    data class Receipt(
        val targetMessageId: String,
        val targetSessionId: String,
        val targetCiphertextSha256: String,
        val targetEpoch: Long,
        val eventAt: Long,
    ) : InnerBody()

    data class SetupRoute(val routeJson: String) : InnerBody()

    data class Attachment(
        val clientSequence: Long,
        val attachmentId: String,
        val blobId: String,
        val originalName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val ciphertextSha256: String,
        val ivBase64Url: String,
        val wrappedKeyBase64Url: String,
        val cryptoVersion: Long,
        val mediaClass: String,
        val inlineCiphertext: String,
        val replyTo: ReplyMetadata? = null,
    ) : InnerBody()
}

data class InnerRecord(
    val kind: InnerKind,
    val messageId: String,
    val sessionId: String,
    val senderDeviceId: String,
    val senderGeneration: Long,
    val recipientDeviceId: String,
    val recipientGeneration: Long,
    val senderIdentityKey: String,
    val recipientIdentityKey: String,
    val recipientMailbox: String,
    val createdAt: Long,
    val expiresAt: Long,
    val epoch: Long,
    val body: InnerBody,
) {
    fun encode(): String = encodeCanonical()

    fun toMap(): Map<String, Any?> = mapOf(
        "body" to bodyMap(),
        "created_at" to createdAt,
        "epoch" to epoch,
        "expires_at" to expiresAt,
        "kind" to kind.wire,
        "message_id" to messageId,
        "recipient_device_id" to recipientDeviceId,
        "recipient_generation" to recipientGeneration,
        "recipient_identity_key" to recipientIdentityKey,
        "recipient_mailbox" to recipientMailbox,
        "sender_device_id" to senderDeviceId,
        "sender_generation" to senderGeneration,
        "sender_identity_key" to senderIdentityKey,
        "session_id" to sessionId,
        "suite" to SUITE,
        "v" to 1L,
    )

    private fun bodyMap(): Map<String, Any?> = when (val b = body) {
        is InnerBody.Text -> mapOf(
            "client_sequence" to b.clientSequence,
            "text" to b.text,
        ).let { if (b.replyTo != null) it + ("reply_to" to b.replyTo.toMap()) else it }
        is InnerBody.Receipt -> mapOf(
            "event_at" to b.eventAt,
            "target_ciphertext_sha256" to b.targetCiphertextSha256,
            "target_epoch" to b.targetEpoch,
            "target_message_id" to b.targetMessageId,
            "target_session_id" to b.targetSessionId,
        )
        is InnerBody.SetupRoute -> mapOf("route" to b.routeJson)
        is InnerBody.Attachment -> mapOf(
            "attachment_id" to b.attachmentId,
            "blob_id" to b.blobId,
            "client_sequence" to b.clientSequence,
            "crypto_version" to b.cryptoVersion,
            "ciphertext_sha256" to b.ciphertextSha256,
            "inline_ciphertext" to b.inlineCiphertext,
            "iv" to b.ivBase64Url,
            "media_class" to b.mediaClass,
            "mime_type" to b.mimeType,
            "original_name" to b.originalName,
            "size_bytes" to b.sizeBytes,
            "wrapped_key" to b.wrappedKeyBase64Url,
        ).let { if (b.replyTo != null) it + ("reply_to" to b.replyTo.toMap()) else it }
    }

    fun encodeCanonical(): String {
        val json = CanonicalJson.of(toMap())
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "inner record too large" }
        return json
    }

    companion object {
        const val SUITE = "ac-mls-v1"
        const val MAX_BYTES = 20 * 1024
        const val MAX_INLINE_CIPHERTEXT_BYTES = 12 * 1024
        const val MAX_TEXT_BYTES = 4096
        private val RECORD_KEYS = setOf(
            "body",
            "created_at",
            "epoch",
            "expires_at",
            "kind",
            "message_id",
            "recipient_device_id",
            "recipient_generation",
            "recipient_identity_key",
            "recipient_mailbox",
            "sender_device_id",
            "sender_generation",
            "sender_identity_key",
            "session_id",
            "suite",
            "v",
        )
        private val TEXT_BODY_REQUIRED = setOf("client_sequence", "text")
        private val TEXT_BODY_ALLOWED = setOf("client_sequence", "text", "reply_to")
        private val RECEIPT_BODY_KEYS = setOf(
            "event_at",
            "target_ciphertext_sha256",
            "target_epoch",
            "target_message_id",
            "target_session_id",
        )
        private val SETUP_ROUTE_BODY_KEYS = setOf("route")
        private val ATTACHMENT_BODY_REQUIRED = setOf(
            "attachment_id",
            "blob_id",
            "client_sequence",
            "crypto_version",
            "ciphertext_sha256",
            "inline_ciphertext",
            "iv",
            "media_class",
            "mime_type",
            "original_name",
            "size_bytes",
            "wrapped_key",
        )
        private val ATTACHMENT_BODY_ALLOWED = ATTACHMENT_BODY_REQUIRED + "reply_to"
        private val MEDIA_CLASSES = setOf("image", "document", "voice")

        fun parse(raw: String): InnerRecord {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "inner record too large" }
            val map = try {
                CanonicalJson.parseObjectStrict(raw)
            } catch (e: Exception) {
                throw MessagingProtocolException("inner record rejected")
            }
            if (map.keys != RECORD_KEYS) throw MessagingProtocolException("inner record fields")
            if (map["v"] != 1L || map["suite"] != SUITE) {
                throw MessagingProtocolException("inner version/suite")
            }
            val kind = InnerKind.fromWire(map["kind"] as? String ?: throw MessagingProtocolException("kind"))
            @Suppress("UNCHECKED_CAST")
            val bodyMap = map["body"] as? Map<String, Any?> ?: throw MessagingProtocolException("body")
            val body = when (kind) {
                InnerKind.TEXT -> parseTextBody(bodyMap)
                InnerKind.DELIVERY, InnerKind.READ -> parseReceiptBody(bodyMap)
                InnerKind.SETUP_ROUTE -> parseSetupRouteBody(bodyMap)
                InnerKind.ATTACHMENT -> parseAttachmentBody(bodyMap)
            }
            val record = InnerRecord(
                kind = kind,
                messageId = requireId16(map["message_id"]),
                sessionId = requireSessionId(map["session_id"]),
                senderDeviceId = requireId16(map["sender_device_id"]),
                senderGeneration = requirePositive(map["sender_generation"]),
                recipientDeviceId = requireId16(map["recipient_device_id"]),
                recipientGeneration = requirePositive(map["recipient_generation"]),
                senderIdentityKey = requireIdentity(map["sender_identity_key"]),
                recipientIdentityKey = requireIdentity(map["recipient_identity_key"]),
                recipientMailbox = requireMailbox(map["recipient_mailbox"]),
                createdAt = requireNonNeg(map["created_at"]),
                expiresAt = requireNonNeg(map["expires_at"]),
                epoch = requireNonNeg(map["epoch"]),
                body = body,
            )
            if (record.expiresAt < record.createdAt) throw MessagingProtocolException("expiry")
            if (record.senderIdentityKey == record.recipientIdentityKey) {
                throw MessagingProtocolException("reflection")
            }
            return record
        }

        private fun parseTextBody(map: Map<String, Any?>): InnerBody.Text {
            if (!map.keys.containsAll(TEXT_BODY_REQUIRED) || !TEXT_BODY_ALLOWED.containsAll(map.keys)) {
                throw MessagingProtocolException("text body")
            }
            val text = map["text"] as? String ?: throw MessagingProtocolException("text")
            val utf8 = text.toByteArray(Charsets.UTF_8)
            if (utf8.isEmpty() || utf8.size > MAX_TEXT_BYTES) throw MessagingProtocolException("text size")
            if (String(utf8, Charsets.UTF_8) != text) throw MessagingProtocolException("text unicode")
            @Suppress("UNCHECKED_CAST")
            val replyMap = map["reply_to"] as? Map<*, *>
            val replyTo = ReplyMetadata.fromMap(replyMap)
            return InnerBody.Text(text, requirePositive(map["client_sequence"]), replyTo)
        }

        private fun parseReceiptBody(map: Map<String, Any?>): InnerBody.Receipt {
            if (map.keys != RECEIPT_BODY_KEYS) throw MessagingProtocolException("receipt body")
            return InnerBody.Receipt(
                targetMessageId = requireId16(map["target_message_id"]),
                targetSessionId = requireSessionId(map["target_session_id"]),
                targetCiphertextSha256 = requireSha256(map["target_ciphertext_sha256"]),
                targetEpoch = requireNonNeg(map["target_epoch"]),
                eventAt = requireNonNeg(map["event_at"]),
            )
        }

        private fun parseAttachmentBody(map: Map<String, Any?>): InnerBody.Attachment {
            if (!map.keys.containsAll(ATTACHMENT_BODY_REQUIRED) || !ATTACHMENT_BODY_ALLOWED.containsAll(map.keys)) {
                throw MessagingProtocolException("attachment body")
            }
            val media = map["media_class"] as? String ?: throw MessagingProtocolException("media class")
            if (media !in MEDIA_CLASSES) throw MessagingProtocolException("media class")
            val name = map["original_name"] as? String ?: throw MessagingProtocolException("name")
            val mime = map["mime_type"] as? String ?: throw MessagingProtocolException("mime")
            if (name.isEmpty() || name.toByteArray(Charsets.UTF_8).size > 256) {
                throw MessagingProtocolException("name size")
            }
            if (mime.isEmpty() || mime.toByteArray(Charsets.UTF_8).size > 128) {
                throw MessagingProtocolException("mime size")
            }
            val inline = map["inline_ciphertext"] as? String ?: throw MessagingProtocolException("inline")
            if (inline.isNotEmpty()) {
                val raw = decodeCanonical(inline)
                if (raw.isEmpty() || raw.size > MAX_INLINE_CIPHERTEXT_BYTES) {
                    throw MessagingProtocolException("inline size")
                }
            }
            @Suppress("UNCHECKED_CAST")
            val replyMap = map["reply_to"] as? Map<*, *>
            val replyTo = ReplyMetadata.fromMap(replyMap)
            return InnerBody.Attachment(
                clientSequence = requirePositive(map["client_sequence"]),
                attachmentId = requireToken(map["attachment_id"], 8, 64),
                blobId = requireToken(map["blob_id"], 16, 64),
                originalName = name,
                mimeType = mime,
                sizeBytes = requirePositive(map["size_bytes"]),
                ciphertextSha256 = requireSha256(map["ciphertext_sha256"]),
                ivBase64Url = requireToken(map["iv"], 8, 32),
                wrappedKeyBase64Url = requireToken(map["wrapped_key"], 16, 64),
                cryptoVersion = requirePositive(map["crypto_version"]),
                mediaClass = media,
                inlineCiphertext = inline,
                replyTo = replyTo,
            )
        }

        private fun requireToken(raw: Any?, min: Int, max: Int): String {
            val value = raw as? String ?: throw MessagingProtocolException("token")
            val decoded = decodeCanonical(value)
            if (decoded.size < min || decoded.size > max) throw MessagingProtocolException("token length")
            return value
        }

        private fun parseSetupRouteBody(map: Map<String, Any?>): InnerBody.SetupRoute {
            if (map.keys != SETUP_ROUTE_BODY_KEYS) throw MessagingProtocolException("setup route body")
            val route = map["route"] as? String ?: throw MessagingProtocolException("setup route")
            if (route.isEmpty() || route.toByteArray(Charsets.UTF_8).size > RouteInvitation.MAX_BYTES) {
                throw MessagingProtocolException("setup route size")
            }
            return InnerBody.SetupRoute(route)
        }

        private fun requireId16(raw: Any?): String {
            val value = raw as? String ?: throw MessagingProtocolException("id")
            val decoded = decodeCanonical(value)
            if (decoded.size != 16) throw MessagingProtocolException("id length")
            return value
        }

        private fun requireSessionId(raw: Any?): String {
            val value = raw as? String ?: throw MessagingProtocolException("session")
            val decoded = decodeCanonical(value)
            if (decoded.isEmpty() || decoded.size > 128) throw MessagingProtocolException("session length")
            return value
        }

        private fun requireIdentity(raw: Any?): String {
            val value = raw as? String ?: throw MessagingProtocolException("identity")
            val decoded = decodeCanonical(value)
            if (decoded.size != 32) throw MessagingProtocolException("identity length")
            return value
        }

        private fun requireMailbox(raw: Any?): String {
            val value = raw as? String ?: throw MessagingProtocolException("mailbox")
            val decoded = decodeCanonical(value)
            if (decoded.size != 32) throw MessagingProtocolException("mailbox length")
            return value
        }

        private fun requireSha256(raw: Any?): String {
            val value = raw as? String ?: throw MessagingProtocolException("digest")
            val decoded = decodeCanonical(value)
            if (decoded.size != 32) throw MessagingProtocolException("digest length")
            return value
        }

        private fun decodeCanonical(value: String): ByteArray {
            val decoded = try {
                Base64Url.decode(value)
            } catch (_: Exception) {
                throw MessagingProtocolException("encoding")
            }
            if (Base64Url.encode(decoded) != value) throw MessagingProtocolException("noncanonical")
            return decoded
        }

        private fun requirePositive(raw: Any?): Long {
            val value = raw as? Long ?: throw MessagingProtocolException("integer")
            if (value <= 0L) throw MessagingProtocolException("integer")
            return value
        }

        private fun requireNonNeg(raw: Any?): Long {
            val value = raw as? Long ?: throw MessagingProtocolException("integer")
            if (value < 0L) throw MessagingProtocolException("integer")
            return value
        }
    }
}

fun libraryCiphertextSha256(ciphertextB64: String): String {
    val raw = try {
        Base64Url.decode(ciphertextB64)
    } catch (_: Exception) {
        throw MessagingProtocolException("ciphertext encoding")
    }
    if (raw.isEmpty()) throw MessagingProtocolException("ciphertext")
    return Base64Url.encode(sha256(raw))
}
