package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.domain.identity.Base64Url
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class InnerRecordTest {
    @Test
    fun setupRouteRoundTrip() {
        val route = """{"p":"armorchat-route","v":1}"""
        val record = sampleText().copy(kind = InnerKind.SETUP_ROUTE, body = InnerBody.SetupRoute(route))
        val parsed = InnerRecord.parse(record.encode())
        assertThat((parsed.body as InnerBody.SetupRoute).routeJson).isEqualTo(route)
    }

    @Test
    fun textRoundTrip() {
        val record = sampleText()
        val parsed = InnerRecord.parse(record.encode())
        assertThat(parsed).isEqualTo(record)
    }

    @Test
    fun deliveryReceiptRoundTripBindsTarget() {
        val record = sampleReceipt(InnerKind.DELIVERY)
        val parsed = InnerRecord.parse(record.encode())
        val body = parsed.body as InnerBody.Receipt
        assertThat(body.targetMessageId).isEqualTo((record.body as InnerBody.Receipt).targetMessageId)
        assertThat(body.targetSessionId).isEqualTo((record.body as InnerBody.Receipt).targetSessionId)
        assertThat(body.targetCiphertextSha256).isEqualTo((record.body as InnerBody.Receipt).targetCiphertextSha256)
        assertThat(body.targetEpoch).isEqualTo(2L)
    }

    @Test
    fun unknownKindAndDuplicateKeysFailClosed() {
        assertThrows(MessagingProtocolException::class.java) {
            InnerRecord.parse(sampleText().encode().replace("\"text\"", "\"typing\""))
        }
        val duplicate = """{"body":{"client_sequence":1,"text":"Hi"},"created_at":1,"epoch":1,"expires_at":2,"kind":"text","message_id":"${b16(1)}","recipient_device_id":"${b16(2)}","recipient_generation":1,"recipient_identity_key":"${b32(3)}","recipient_mailbox":"${b32(4)}","sender_device_id":"${b16(5)}","sender_generation":1,"sender_identity_key":"${b32(6)}","session_id":"${b16(7)}","suite":"ac-mls-v1","v":1,"v":1}"""
        assertThrows(Exception::class.java) { InnerRecord.parse(duplicate) }
    }

    @Test
    fun reflectionAndMissingTargetRejected() {
        val reflected = sampleText().copy(senderIdentityKey = sampleText().recipientIdentityKey)
        assertThrows(MessagingProtocolException::class.java) {
            InnerRecord.parse(reflected.encode())
        }
        val receipt = sampleReceipt(InnerKind.READ)
        val json = receipt.encode().replace("target_message_id", "message_hint")
        assertThrows(MessagingProtocolException::class.java) { InnerRecord.parse(json) }
    }

    @Test
    fun attachmentInlineRoundTripFitsBudget() {
        val inline = Base64Url.encode(ByteArray(10 * 1024) { 1 })
        val record = sampleText().copy(
            kind = InnerKind.ATTACHMENT,
            body = InnerBody.Attachment(
                clientSequence = 1,
                attachmentId = b16(8),
                blobId = b32(9),
                originalName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 8000L,
                ciphertextSha256 = b32(10),
                ivBase64Url = Base64Url.encode(ByteArray(12) { 2 }),
                wrappedKeyBase64Url = b32(11),
                cryptoVersion = 1,
                mediaClass = "image",
                inlineCiphertext = inline,
            ),
        )
        val encoded = record.encode()
        assertThat(encoded.toByteArray(Charsets.UTF_8).size).isAtMost(InnerRecord.MAX_BYTES)
        val parsed = InnerRecord.parse(encoded)
        val body = parsed.body as InnerBody.Attachment
        assertThat(body.mediaClass).isEqualTo("image")
        assertThat(body.inlineCiphertext).isEqualTo(inline)
        assertThat(body.originalName).isEqualTo("photo.jpg")
    }

    @Test
    fun dealChunkRoundTripStaysOutOfThePreview() {
        val marker = "UNIQUE_DEAL_CHUNK_MARKER"
        val record = sampleText().copy(
            kind = InnerKind.DEAL,
            messageId = b16(12),
            body = InnerBody.Deal(
                protocol = 1,
                envelopeId = b16(13),
                chunkIndex = 0,
                chunkCount = 1,
                frameSha256 = b32(14),
                chunk = Base64Url.encode(marker.toByteArray(Charsets.UTF_8)),
            ),
        )
        assertThat(record.kind.isUserMessage).isFalse()
        assertThat((record.body as InnerBody.Deal).toString()).isEqualTo("Deal")
        assertThat(record.toString()).doesNotContain(marker)
        val parsed = InnerRecord.parse(record.encode())
        assertThat(parsed).isEqualTo(record)
        val carried = Base64Url.decode((parsed.body as InnerBody.Deal).chunk)
        assertThat(String(carried, Charsets.UTF_8)).isEqualTo(marker)
        assertThat(parsed.toString()).doesNotContain(marker)
    }

    private fun sampleText() = InnerRecord(
        kind = InnerKind.TEXT,
        messageId = b16(1),
        sessionId = b16(2),
        senderDeviceId = b16(3),
        senderGeneration = 1,
        recipientDeviceId = b16(4),
        recipientGeneration = 1,
        senderIdentityKey = b32(5),
        recipientIdentityKey = b32(6),
        recipientMailbox = b32(7),
        createdAt = 1,
        expiresAt = 10,
        epoch = 2,
        body = InnerBody.Text("Hello ArmorChat", 1),
    )

    private fun sampleReceipt(kind: InnerKind) = sampleText().copy(
        kind = kind,
        messageId = b16(8),
        body = InnerBody.Receipt(
            targetMessageId = b16(1),
            targetSessionId = b16(2),
            targetCiphertextSha256 = b32(9),
            targetEpoch = 2,
            eventAt = 3,
        ),
    )

    private fun b16(value: Byte) = Base64Url.encode(ByteArray(16) { value })
    private fun b32(value: Byte) = Base64Url.encode(ByteArray(32) { value })
}
