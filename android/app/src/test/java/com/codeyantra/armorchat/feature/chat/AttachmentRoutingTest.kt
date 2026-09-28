package com.codeyantra.armorchat.feature.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.codeyantra.armorchat.core.config.AttachmentConfig
import com.codeyantra.armorchat.core.network.NetworkAvailability
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.ContactExchangeResult
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.Message
import com.codeyantra.armorchat.domain.model.MessageType
import com.codeyantra.armorchat.domain.model.VerificationMethod
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.domain.repository.MessageRepository
import com.codeyantra.armorchat.domain.repository.SendTextResult
import com.codeyantra.armorchat.messaging.AttachmentClassification
import com.codeyantra.armorchat.messaging.ChatMessagingController
import com.codeyantra.armorchat.messaging.ReplyMetadata
import com.codeyantra.armorchat.messaging.RouteImportResult
import com.codeyantra.armorchat.messaging.SecureSessionView
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class AttachmentRoutingTest {
    private val mib = 1024L * 1024L

    @Test
    fun smallImageStaysOnExistingPath() {
        assertEquals(AttachmentRoute.LegacySmall, AttachmentRouting.decide(isImage = true, sizeBytes = 32 * 1024))
        assertEquals(
            AttachmentRoute.LegacySmall,
            AttachmentRouting.decide(isImage = true, sizeBytes = AttachmentConfig.LEGACY_IMAGE_COMPRESS_MAX_BYTES),
        )
    }

    @Test
    fun imageJustAboveSmallPathUsesChunkedTransport() {
        assertEquals(
            AttachmentRoute.Chunked,
            AttachmentRouting.decide(
                isImage = true,
                sizeBytes = AttachmentConfig.LEGACY_IMAGE_COMPRESS_MAX_BYTES + 1,
            ),
        )
    }

    @Test
    fun tenMegabyteImageUsesChunkedTransport() {
        assertEquals(AttachmentRoute.Chunked, AttachmentRouting.decide(isImage = true, sizeBytes = 10 * mib))
    }

    @Test
    fun twentyFiveMegabyteFileUsesChunkedTransport() {
        assertEquals(AttachmentRoute.Chunked, AttachmentRouting.decide(isImage = false, sizeBytes = 25 * mib))
    }

    @Test
    fun fiftyMegabyteFileUsesChunkedTransport() {
        assertEquals(AttachmentRoute.Chunked, AttachmentRouting.decide(isImage = false, sizeBytes = 50 * mib))
    }

    @Test
    fun exactlyOneHundredMebibytesUsesChunkedTransport() {
        assertEquals(
            AttachmentRoute.Chunked,
            AttachmentRouting.decide(isImage = false, sizeBytes = AttachmentConfig.MAX_STREAM_BYTES),
        )
    }

    @Test
    fun aboveOneHundredMebibytesIsAnExplicitSizeRejection() {
        assertEquals(
            AttachmentRoute.OverLimit,
            AttachmentRouting.decide(isImage = false, sizeBytes = AttachmentConfig.MAX_STREAM_BYTES + 1),
        )
        assertEquals(
            AttachmentRoute.OverLimit,
            AttachmentRouting.decide(isImage = true, sizeBytes = AttachmentConfig.MAX_STREAM_BYTES + 1),
        )
    }

    @Test
    fun queuedAttemptClearsOnlyTheStaleTransportNotice() {
        assertNull(AttachmentRouting.noticeAfterQueued("Transport unavailable"))
        assertEquals("Network unavailable", AttachmentRouting.noticeAfterQueued("Network unavailable"))
        assertNull(AttachmentRouting.noticeAfterQueued(null))
    }

    @Test
    fun veryShortAmrVoiceStaysOnInlinePath() {
        assertEquals(AttachmentRoute.LegacySmall, voiceRoute(amrNbBytes(1)))
    }

    @Test
    fun voiceImmediatelyBelowInlineThresholdStaysInline() {
        assertEquals(
            AttachmentRoute.LegacySmall,
            voiceRoute(AttachmentRouting.maxInlineVoicePlaintextBytes),
        )
    }

    @Test
    fun voiceImmediatelyAboveInlineThresholdUsesChunkedTransport() {
        assertEquals(
            AttachmentRoute.Chunked,
            voiceRoute(AttachmentRouting.maxInlineVoicePlaintextBytes + 1),
        )
    }

    @Test
    fun tenSecondVoiceUsesChunkedTransport() {
        assertEquals(AttachmentRoute.Chunked, voiceRoute(amrNbBytes(10)))
    }

    @Test
    fun thirtySecondVoiceUsesChunkedTransport() {
        assertEquals(AttachmentRoute.Chunked, voiceRoute(amrNbBytes(30)))
    }

    @Test
    fun sixtySecondVoiceUsesChunkedTransport() {
        assertEquals(AttachmentRoute.Chunked, voiceRoute(amrNbBytes(60)))
    }

    @Test
    fun unknownVoiceSizeFailsSafeToChunkedTransport() {
        assertEquals(AttachmentRoute.Chunked, voiceRoute(0))
        assertEquals(AttachmentRoute.Chunked, voiceRoute(-1))
    }

    @Test
    fun chunkedVoicePreservesVoiceMessageMetadata() {
        val mediaClass = AttachmentClassification.mediaClass("audio/amr", isImage = false)
        assertEquals("voice", mediaClass)
        assertEquals(MessageType.VOICE.name, AttachmentClassification.messageTypeName(mediaClass))
    }

    private fun voiceRoute(sizeBytes: Long): AttachmentRoute =
        AttachmentRouting.decide(isImage = false, sizeBytes = sizeBytes, mimeType = "audio/amr")

    private fun amrNbBytes(seconds: Int): Long = 6L + seconds * 50L * 32L
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AttachmentSendRoutingTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private val conversation = Conversation("conv-1", "contact-1", "Amit", 10)
    private val contact = Contact(
        id = "contact-1",
        armorChatId = "ac1:0123456789ABCDEFGHJKMNPQRSTVWXYZ",
        publicIdentityKey = "pk",
        displayName = "Amit",
        createdAtEpochMs = 1,
        verificationMethod = VerificationMethod.QR_SCAN,
    )

    private class Messaging : ChatMessagingController {
        override val network = MutableStateFlow(NetworkAvailability())
        var legacyCalls = 0
        var largeCalls = 0
        var largeSize = 0L
        var largeMime = ""
        var largeName = ""
        override fun observeSession(contactId: String): Flow<SecureSessionView> = MutableStateFlow(SecureSessionView())
        override suspend fun ensureProvisioned(contact: Contact) = Unit
        override suspend fun getMyContactQrV2(): String? = null
        override suspend fun importPeerContactV2(payload: com.codeyantra.armorchat.domain.identity.ContactQrV2) =
            RouteImportResult.Rejected
        override suspend fun importPeerRoute(raw: String, contactId: String?) = RouteImportResult.Rejected
        override fun localRouteQr(contactId: String): String? = null
        override suspend fun sendText(conversationId: String, text: String, replyTo: ReplyMetadata?) =
            SendTextResult.TransportUnavailable
        override suspend fun sendAttachment(
            conversationId: String,
            fileBytes: ByteArray,
            originalName: String,
            mimeType: String,
            isImage: Boolean,
            replyTo: ReplyMetadata?,
        ): SendTextResult {
            legacyCalls++
            return SendTextResult.Queued("small", com.codeyantra.armorchat.domain.model.MessageStatus.SENDING)
        }
        override suspend fun sendLargeAttachment(
            conversationId: String,
            uri: Uri,
            originalName: String,
            mimeType: String,
            sizeBytes: Long,
            isImage: Boolean,
            replyTo: ReplyMetadata?,
        ): SendTextResult {
            largeCalls++
            largeSize = sizeBytes
            largeMime = mimeType
            largeName = originalName
            return SendTextResult.Queued("blob-pending", com.codeyantra.armorchat.domain.model.MessageStatus.SENDING)
        }
        override suspend fun syncContact(contactId: String) = Unit
        override suspend fun markOpened(conversationId: String, messageId: String) = Unit
        override suspend fun refreshPolicy() = Unit
    }

    private fun viewModel(messaging: Messaging): ChatViewModel {
        val messages = object : MessageRepository {
            override fun observeMessages(conversationId: String) = MutableStateFlow<List<Message>>(emptyList())
            override suspend fun sendText(conversationId: String, text: String) = SendTextResult.TransportUnavailable
        }
        val conversations = object : ConversationRepository {
            override fun observeConversations() = MutableStateFlow(listOf(conversation))
            override fun observeConversation(id: String) = MutableStateFlow(conversation.takeIf { id == conversation.id })
            override suspend fun getConversation(id: String) = conversation.takeIf { id == conversation.id }
            override suspend fun getOrCreateForContact(contact: Contact) = conversation
        }
        val contacts = object : ContactRepository {
            override fun observeContacts() = MutableStateFlow(listOf(contact))
            override fun observeContact(id: String) = MutableStateFlow(contact.takeIf { it.id == id })
            override suspend fun getContact(id: String) = contact.takeIf { it.id == id }
            override suspend fun findByPublicKey(publicIdentityKey: String) = contact.takeIf { it.publicIdentityKey == publicIdentityKey }
            override suspend fun inspectQr(raw: String) = ContactExchangeResult.Invalid
            override suspend fun addQr(raw: String, localName: String) = ContactExchangeResult.Invalid
            override suspend fun addVerifiedBundle(
                parsed: com.codeyantra.armorchat.domain.identity.ParsedContactQr,
                localName: String,
            ) = ContactExchangeResult.Invalid
            override suspend fun rename(id: String, localName: String) = Unit
            override suspend fun addInboundPinned(publicIdentityKey: String, displayName: String, handle: String?) = contact
        }
        return ChatViewModel(
            savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversation.id)),
            conversationRepository = conversations,
            messageRepository = messages,
            contactRepository = contacts,
            messaging = messaging,
        )
    }

    @Test
    fun smallImageSendUsesLegacyPath() = runTest(dispatcher) {
        val messaging = Messaging()
        val viewModel = viewModel(messaging)
        advanceUntilIdle()
        val bytes = ByteArray(64) { 1 }
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://attachments/small-photo")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        viewModel.sendAttachment(context, uri, "photo.jpg", "image/jpeg", bytes.size.toLong())
        awaitSend(messaging)
        assertEquals(viewModel.state.value.notice, 1, messaging.legacyCalls)
        assertEquals(0, messaging.largeCalls)
    }

    @Test
    fun tenMegabyteImageSendUsesLargeBlobTransfer() = runTest(dispatcher) {
        val messaging = Messaging()
        val viewModel = viewModel(messaging)
        advanceUntilIdle()
        viewModel.showNotice(AttachmentRouting.STALE_TRANSPORT_NOTICE)
        viewModel.sendAttachment(
            RuntimeEnvironment.getApplication(),
            Uri.parse("content://attachments/ten-mb"),
            "photo.jpg",
            "image/jpeg",
            10L * 1024L * 1024L,
        )
        advanceUntilIdle()
        assertEquals(0, messaging.legacyCalls)
        assertEquals(1, messaging.largeCalls)
        assertEquals(10L * 1024L * 1024L, messaging.largeSize)
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun overLimitSendDoesNotCallEitherTransport() = runTest(dispatcher) {
        val messaging = Messaging()
        val viewModel = viewModel(messaging)
        advanceUntilIdle()
        viewModel.sendAttachment(
            RuntimeEnvironment.getApplication(),
            Uri.parse("content://attachments/too-big"),
            "huge.bin",
            "application/octet-stream",
            AttachmentConfig.MAX_STREAM_BYTES + 1,
        )
        advanceUntilIdle()
        assertEquals(0, messaging.legacyCalls)
        assertEquals(0, messaging.largeCalls)
        assertEquals(AttachmentRouting.OVER_LIMIT_NOTICE, viewModel.state.value.notice)
        assertTrue(viewModel.state.value.notice != "Transport unavailable")
    }

    @Test
    fun reportedSmallSizeThatIsActuallyLargeUsesChunkedTransport() = runTest(dispatcher) {
        val messaging = Messaging()
        val viewModel = viewModel(messaging)
        advanceUntilIdle()
        val bytes = ByteArray(AttachmentConfig.LEGACY_TRANSPORT_MAX_BYTES.toInt() + 8) { 2 }
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://attachments/lied-size")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        viewModel.sendAttachment(context, uri, "notes.bin", "application/octet-stream", 128)
        awaitSend(messaging)
        assertEquals(viewModel.state.value.notice, 0, messaging.legacyCalls)
        assertEquals(viewModel.state.value.notice, 1, messaging.largeCalls)
        assertEquals(bytes.size.toLong(), messaging.largeSize)
    }

    @Test
    fun shortVoiceSendUsesInlineLegacyPath() = runTest(dispatcher) {
        val messaging = Messaging()
        val viewModel = viewModel(messaging)
        advanceUntilIdle()
        val bytes = ByteArray(1_606)
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://attachments/short-voice")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        viewModel.sendAttachment(context, uri, "voice-note.amr", "audio/amr", bytes.size.toLong())
        awaitSend(messaging)
        assertEquals(1, messaging.legacyCalls)
        assertEquals(0, messaging.largeCalls)
    }

    @Test
    fun tenSecondVoiceSendUsesChunkedTransportAndKeepsAmrMetadata() = runTest(dispatcher) {
        val messaging = Messaging()
        val viewModel = viewModel(messaging)
        advanceUntilIdle()
        val size = 6L + 10 * 50L * 32L
        viewModel.sendAttachment(
            RuntimeEnvironment.getApplication(),
            Uri.parse("content://attachments/ten-sec-voice"),
            "voice-note.amr",
            "audio/amr",
            size,
        )
        advanceUntilIdle()
        assertEquals(0, messaging.legacyCalls)
        assertEquals(1, messaging.largeCalls)
        assertEquals(size, messaging.largeSize)
        assertEquals("audio/amr", messaging.largeMime)
        assertEquals("voice-note.amr", messaging.largeName)
        val mediaClass = AttachmentClassification.mediaClass(messaging.largeMime, isImage = false)
        assertEquals("voice", mediaClass)
        assertEquals(MessageType.VOICE.name, AttachmentClassification.messageTypeName(mediaClass))
    }

    @Test
    fun longVoiceNeverEntersLegacySinglePut() = runTest(dispatcher) {
        val messaging = Messaging()
        val viewModel = viewModel(messaging)
        advanceUntilIdle()
        viewModel.sendAttachment(
            RuntimeEnvironment.getApplication(),
            Uri.parse("content://attachments/long-voice"),
            "voice-note.amr",
            "audio/amr",
            6L + 30 * 50L * 32L,
        )
        advanceUntilIdle()
        assertEquals(0, messaging.legacyCalls)
        assertEquals(1, messaging.largeCalls)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.awaitSend(messaging: Messaging) {
        val deadline = System.currentTimeMillis() + 5_000
        while (messaging.legacyCalls + messaging.largeCalls == 0 && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        advanceUntilIdle()
    }
}
