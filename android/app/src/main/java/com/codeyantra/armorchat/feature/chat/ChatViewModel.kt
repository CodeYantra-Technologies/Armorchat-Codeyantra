package com.codeyantra.armorchat.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.core.blob.BlobTransferUi
import com.codeyantra.armorchat.core.network.NetworkAvailability
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.model.AttachmentMetadata
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.Message
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.codeyantra.armorchat.domain.model.MessageType
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.domain.repository.MessageRepository
import com.codeyantra.armorchat.domain.repository.SendTextResult
import com.codeyantra.armorchat.messaging.ChatMessagingController
import com.codeyantra.armorchat.messaging.ReplyMetadata
import com.codeyantra.armorchat.messaging.SecureSessionState
import com.codeyantra.armorchat.messaging.SecureSessionView
import com.codeyantra.armorchat.notification.ArmorChatNotificationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversation: Conversation? = null,
    val contact: Contact? = null,
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val session: SecureSessionView = SecureSessionView(),
    val network: NetworkAvailability = NetworkAvailability(),
    val notice: String? = null,
    val loading: Boolean = true,
    val scanningRoute: Boolean = false,
    val transfer: BlobTransferUi = BlobTransferUi(),
    val downloadProgress: Map<String, Float> = emptyMap(),
) {
    val displayName: String
        get() = contact?.displayName?.takeIf { it.isNotBlank() }
            ?: conversation?.title?.takeIf { it.isNotBlank() }
            ?: "ArmorChat contact"

    val shortArmorChatId: String?
        get() = contact?.armorChatId?.let { ArmorChatId.shortDisplay(it) }

    val contactHandle: String?
        get() = contact?.handleDisplay

    val networkUnavailable: Boolean
        get() = network.blocksNetwork

    val sessionReady: Boolean
        get() = session.state == SecureSessionState.ESTABLISHED && !networkUnavailable

    /** Send stays disabled until a verified MLS session exists and policy is ACTIVE. */
    val canSend: Boolean
        get() = sessionReady && conversation != null
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    contactRepository: ContactRepository,
    private val messaging: ChatMessagingController,
    private val notificationManager: ArmorChatNotificationManager? = null,
) : ViewModel() {
    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    private val draft = MutableStateFlow("")
    private val notice = MutableStateFlow<String?>(null)
    private val scanning = MutableStateFlow(false)

    var replyingToMessage by mutableStateOf<Message?>(null)
        private set
    var highlightedMessageId by mutableStateOf<String?>(null)
        private set
    var messageForInfo by mutableStateOf<Message?>(null)
        private set

    fun setReplyingTo(message: Message?) {
        replyingToMessage = message
    }

    fun highlightMessage(messageId: String?) {
        highlightedMessageId = messageId
    }

    fun setInfoMessage(message: Message?) {
        messageForInfo = message
    }

    fun showNotice(msg: String) {
        notice.value = msg
    }

    private fun buildReplyMetadata(): ReplyMetadata? {
        val msg = replyingToMessage ?: return null
        val contactName = core.value.contact?.displayName?.ifBlank { "Contact" } ?: "Contact"
        val senderName = if (msg.isOutgoing) "You" else contactName
        val kind = msg.type.name.lowercase()
        val preview = when (msg.type) {
            MessageType.TEXT -> msg.localPlaintext ?: "Message"
            MessageType.IMAGE -> "Photo"
            MessageType.VOICE -> "Voice message"
            MessageType.DOCUMENT -> {
                val meta = AttachmentMetadata.parseOrNull(msg.localPlaintext)
                meta?.originalName?.ifBlank { "Document" } ?: "Document"
            }
        }
        return ReplyMetadata(
            messageId = msg.id,
            senderName = senderName,
            kind = kind,
            preview = preview,
        )
    }

    private val conversationFlow = conversationRepository.observeConversation(conversationId)
    private val contactFlow = conversationFlow.flatMapLatest { conversation ->
        val contactId = conversation?.contactId
        if (contactId == null) flowOf(null) else contactRepository.observeContact(contactId)
    }
    private val sessionFlow = contactFlow.flatMapLatest { contact ->
        val contactId = contact?.id
        if (contactId == null) flowOf(SecureSessionView()) else messaging.observeSession(contactId)
    }

    private data class ChatCore(
        val conversation: Conversation?,
        val contact: Contact?,
        val messages: List<Message>,
        val session: SecureSessionView,
        val network: NetworkAvailability,
    )

    private val core = combine(
        conversationFlow,
        contactFlow,
        messageRepository.observeMessages(conversationId),
        sessionFlow,
        messaging.network,
    ) { conv, contact, messages, session, network ->
        ChatCore(conv, contact, messages, session, network)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ChatCore(null, null, emptyList(), SecureSessionView(), NetworkAvailability()),
    )

    private val transferFlow = messaging.observeTransfer(conversationId)
    private val downloadsFlow = messaging.observeDownloads()

    val state: StateFlow<ChatUiState> = combine(
        combine(core, draft, notice, scanning) { c, d, n, s ->
            ChatUiState(
                conversation = c.conversation,
                contact = c.contact,
                messages = c.messages,
                draft = d,
                session = c.session,
                network = c.network,
                notice = n,
                loading = false,
                scanningRoute = s,
            )
        },
        transferFlow,
        downloadsFlow,
    ) { base, transfer, downloads ->
        base.copy(transfer = transfer, downloadProgress = downloads)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ChatUiState(),
    )

    private var chatVisible = false

    init {
        viewModelScope.launch {
            contactFlow.collect { contact ->
                if (contact == null) return@collect
                runCatching { messaging.ensureProvisioned(contact) }
                runCatching { messaging.syncContact(contact.id) }
            }
        }
    }

    fun onDraftChange(newDraft: String) {
        draft.value = newDraft
    }

    fun clearNotice() {
        notice.value = null
    }

    fun setChatVisible(visible: Boolean) {
        chatVisible = visible
        if (visible) {
            viewModelScope.launch {
                markVisibleInbound()
            }
        } else {
            notificationManager?.setActiveConversation(null)
        }
    }

    suspend fun markVisibleInbound() {
        notificationManager?.setActiveConversation(conversationId)
        val unread = state.value.messages.filter { !it.isOutgoing && it.status != MessageStatus.READ }
        if (unread.isNotEmpty()) {
            for (message in unread) {
                messaging.markOpened(conversationId, message.id)
            }
        }
    }

    fun syncNow() {
        val contactId = state.value.contact?.id ?: return
        viewModelScope.launch {
            // Offline / DNS failure must not crash the chat screen (Main dispatcher).
            runCatching { messaging.syncContact(contactId) }
            if (chatVisible) markVisibleInbound()
        }
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        draft.value = ""
        val replyMeta = buildReplyMetadata()
        replyingToMessage = null
        viewModelScope.launch {
            when (messaging.sendText(conversationId, text, replyMeta)) {
                is SendTextResult.Queued -> syncNow()
                SendTextResult.Empty -> notice.value = "Cannot send empty message"
                SendTextResult.ConversationMissing -> notice.value = "Conversation missing"
                SendTextResult.NetworkUnavailable -> notice.value = "Message not sent — network unavailable"
                SendTextResult.SessionNotReady -> notice.value = "Message not sent — session not ready"
                SendTextResult.TransportUnavailable -> notice.value = "Message not sent — transport unavailable"
            }
        }
    }

    fun cancelTransfer() {
        messaging.cancelLargeTransfer(conversationId)
    }

    fun sendAttachment(
        context: android.content.Context,
        uri: android.net.Uri,
        originalName: String,
        mimeType: String,
        sizeBytes: Long,
    ) {
        val isImage = mimeType.startsWith("image/")
        val replyMeta = buildReplyMetadata()
        replyingToMessage = null
        when (AttachmentRouting.decide(isImage, sizeBytes, mimeType)) {
            AttachmentRoute.OverLimit -> {
                notice.value = AttachmentRouting.OVER_LIMIT_NOTICE
            }
            AttachmentRoute.Chunked -> {
                viewModelScope.launch {
                    applyAttachmentResult(
                        messaging.sendLargeAttachment(
                            conversationId,
                            uri,
                            originalName,
                            mimeType,
                            sizeBytes,
                            isImage,
                            replyMeta,
                        ),
                    )
                }
            }
            AttachmentRoute.LegacySmall -> {
                viewModelScope.launch {
                    val fileBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: uri.path?.let { java.io.File(it).takeIf { f -> f.exists() }?.readBytes() }
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (fileBytes == null || fileBytes.isEmpty()) {
                        notice.value = "Unable to read attachment file."
                        return@launch
                    }
                    val actual = fileBytes.size.toLong()
                    when (AttachmentRouting.decide(isImage, actual, mimeType)) {
                        AttachmentRoute.OverLimit -> notice.value = AttachmentRouting.OVER_LIMIT_NOTICE
                        AttachmentRoute.Chunked -> applyAttachmentResult(
                            messaging.sendLargeAttachment(
                                conversationId,
                                uri,
                                originalName,
                                mimeType,
                                actual,
                                isImage,
                                replyMeta,
                            ),
                        )
                        AttachmentRoute.LegacySmall -> applyAttachmentResult(
                            messaging.sendAttachment(
                                conversationId,
                                fileBytes,
                                originalName,
                                mimeType,
                                isImage,
                                replyMeta,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun applyAttachmentResult(result: SendTextResult) {
        when (result) {
            is SendTextResult.Queued -> {
                notice.value = AttachmentRouting.noticeAfterQueued(notice.value)
                syncNow()
            }
            SendTextResult.Empty -> notice.value = "Attachment is empty"
            SendTextResult.ConversationMissing -> notice.value = "Conversation missing"
            SendTextResult.NetworkUnavailable -> notice.value = "Network unavailable"
            SendTextResult.SessionNotReady -> notice.value = "Session not ready"
            SendTextResult.TransportUnavailable -> notice.value = "Transport unavailable"
        }
    }

    fun downloadAttachment(
        metadata: com.codeyantra.armorchat.domain.model.AttachmentMetadata,
        onDone: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val success = messaging.downloadAttachmentBlob(
                conversationId = conversationId,
                attachmentId = metadata.attachmentId,
                blobId = metadata.blobId,
                wrappedKeyBase64Url = metadata.wrappedKeyBase64Url,
                ivBase64Url = metadata.ivBase64Url,
                sha256DigestHex = metadata.ciphertextDigestSha256,
            )
            onDone(success)
        }
    }

    override fun onCleared() {
        super.onCleared()
        notificationManager?.setActiveConversation(null)
    }
}
