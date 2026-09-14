package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.NetworkAvailability
import com.codeyantra.armorchat.domain.identity.InviteRedemption
import com.codeyantra.armorchat.domain.identity.ContactQrV2
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.repository.SendTextResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI-facing messaging boundary. Chat composables never touch MLS snapshots,
 * mailbox tokens, or raw relay clients.
 */
interface ChatMessagingController {
    val network: StateFlow<NetworkAvailability>
    fun observeSession(contactId: String): Flow<SecureSessionView>
    suspend fun ensureProvisioned(contact: Contact)
    suspend fun getMyContactQrV2(): String?
    suspend fun getMyContactQrV3(refresh: Boolean = false): String? = null
    suspend fun resolveContactInviteV3(rawQr: String): Result<InviteRedemption> =
        Result.failure(IllegalStateException("v3 invite lookup unavailable"))
    suspend fun importPeerContactV3(redemption: InviteRedemption): RouteImportResult = RouteImportResult.Rejected
    suspend fun importPeerContactV2(payload: ContactQrV2): RouteImportResult
    suspend fun importPeerRoute(raw: String, contactId: String? = null): RouteImportResult
    fun localRouteQr(contactId: String): String?
    suspend fun sendText(conversationId: String, text: String): SendTextResult =
        SendTextResult.TransportUnavailable
    suspend fun sendText(conversationId: String, text: String, replyTo: ReplyMetadata?): SendTextResult =
        SendTextResult.TransportUnavailable
    suspend fun sendAttachment(
        conversationId: String,
        fileBytes: ByteArray,
        originalName: String,
        mimeType: String,
        isImage: Boolean,
    ): SendTextResult = SendTextResult.TransportUnavailable
    suspend fun sendAttachment(
        conversationId: String,
        fileBytes: ByteArray,
        originalName: String,
        mimeType: String,
        isImage: Boolean,
        replyTo: ReplyMetadata?,
    ): SendTextResult = SendTextResult.TransportUnavailable
    suspend fun downloadAttachmentBlob(
        conversationId: String,
        attachmentId: String,
        blobId: String,
        wrappedKeyBase64Url: String,
        ivBase64Url: String,
        sha256DigestHex: String,
    ): Boolean = false
    suspend fun syncContact(contactId: String)
    suspend fun markOpened(conversationId: String, messageId: String)
    suspend fun refreshPolicy()
    suspend fun pollIncomingInvites() = Unit
}
