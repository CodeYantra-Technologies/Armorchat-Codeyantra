package com.codeyantra.armorchat.feature.contacts

import com.codeyantra.armorchat.core.network.NetworkAvailability
import com.codeyantra.armorchat.domain.identity.ContactQrV3
import com.codeyantra.armorchat.domain.identity.InviteRedemption
import com.codeyantra.armorchat.domain.identity.InviteToken
import com.codeyantra.armorchat.domain.model.ContactExchangeResult
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.messaging.ChatMessagingController
import com.codeyantra.armorchat.messaging.RouteImportResult
import com.codeyantra.armorchat.messaging.SecureSessionView
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactQrV3ScannerRegressionTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun validGeneratedV3Qr_parsesSuccessfully() {
        val token = InviteToken.generate()
        val qr = ContactQrV3(token).toQr()

        val parsed = ContactQrV3.parse(qr)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.token).isEqualTo(token)
    }

    @Test
    fun scannerParserBoundary_handlesLegitimateSurroundingWhitespace() {
        val token = InviteToken.generate()
        val canonicalQr = ContactQrV3(token).toQr()
        val noisyScannerOutput = "  \r\n $canonicalQr \t\n "

        val parsed = ContactQrV3.parse(noisyScannerOutput)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.token).isEqualTo(token)
    }

    @Test
    fun malformedScheme_failsParse() {
        val token = InviteToken.generate()
        assertThat(ContactQrV3.parse("http://add/$token")).isNull()
        assertThat(ContactQrV3.parse("https://armorchat.com/add/$token")).isNull()
        assertThat(ContactQrV3.parse("armorchat://v2/add/$token")).isNull()
    }

    @Test
    fun wrongTokenLength_failsParse() {
        assertThat(ContactQrV3.parse("armorchat://add/shorttoken")).isNull()
        assertThat(ContactQrV3.parse("armorchat://add/${"a".repeat(44)}")).isNull()
    }

    @Test
    fun invalidBase64UrlCharset_failsParse() {
        assertThat(ContactQrV3.parse("armorchat://add/${"a".repeat(42)}+")).isNull()
        assertThat(ContactQrV3.parse("armorchat://add/${"a".repeat(42)}/")).isNull()
    }

    @Test
    fun validQr_networkFailure_returnsNetworkUnavailable() = runTest(dispatcher) {
        val token = InviteToken.generate()
        val qr = ContactQrV3(token).toQr()

        val messaging = FakeMessaging(networkError = IOException("Failed to connect to relay"))
        val viewModel = ContactsViewModel(FakeRepo(), FakeConversations(), messaging)

        viewModel.scan(qr)
        advanceUntilIdle()

        assertThat(viewModel.state.value.result).isEqualTo(ContactExchangeResult.NetworkUnavailable)
    }

    @Test
    fun validQr_unavailableInviteEndpoint_returnsInviteUnknown() = runTest(dispatcher) {
        val token = InviteToken.generate()
        val qr = ContactQrV3(token).toQr()

        val messaging = FakeMessaging(lookupError = IllegalStateException("unknown invite"))
        val viewModel = ContactsViewModel(FakeRepo(), FakeConversations(), messaging)

        viewModel.scan(qr)
        advanceUntilIdle()

        assertThat(viewModel.state.value.result).isEqualTo(ContactExchangeResult.InviteUnknown)
    }

    @Test
    fun validQr_expiredInvite_returnsInviteExpired() = runTest(dispatcher) {
        val token = InviteToken.generate()
        val qr = ContactQrV3(token).toQr()

        val messaging = FakeMessaging(lookupError = IllegalStateException("invite expired"))
        val viewModel = ContactsViewModel(FakeRepo(), FakeConversations(), messaging)

        viewModel.scan(qr)
        advanceUntilIdle()

        assertThat(viewModel.state.value.result).isEqualTo(ContactExchangeResult.InviteExpired)
    }

    @Test
    fun validQr_revokedInvite_returnsInviteUnavailable() = runTest(dispatcher) {
        val token = InviteToken.generate()
        val qr = ContactQrV3(token).toQr()

        val messaging = FakeMessaging(lookupError = IllegalStateException("invite revoked"))
        val viewModel = ContactsViewModel(FakeRepo(), FakeConversations(), messaging)

        viewModel.scan(qr)
        advanceUntilIdle()

        assertThat(viewModel.state.value.result).isEqualTo(ContactExchangeResult.InviteUnavailable)
    }

    @Test
    fun validQr_tamperedSignedBundle_returnsSecurityVerificationFailed() = runTest(dispatcher) {
        val token = InviteToken.generate()
        val qr = ContactQrV3(token).toQr()

        val messaging = FakeMessaging(lookupError = IllegalArgumentException("Invalid signature"))
        val viewModel = ContactsViewModel(FakeRepo(), FakeConversations(), messaging)

        viewModel.scan(qr)
        advanceUntilIdle()

        assertThat(viewModel.state.value.result).isEqualTo(ContactExchangeResult.SecurityVerificationFailed)
    }

    @Test
    fun validQr_mustNotBecomeInvalidQr_onLookupOrNetworkFailure() = runTest(dispatcher) {
        val token = InviteToken.generate()
        val qr = ContactQrV3(token).toQr()

        val messaging = FakeMessaging(networkError = IOException("Socket timeout"))
        val viewModel = ContactsViewModel(FakeRepo(), FakeConversations(), messaging)

        viewModel.scan(qr)
        advanceUntilIdle()

        assertThat(viewModel.state.value.result).isNotEqualTo(ContactExchangeResult.Invalid)
    }

    private class FakeRepo : ContactRepository {
        override fun observeContacts() = MutableStateFlow<List<com.codeyantra.armorchat.domain.model.Contact>>(emptyList())
        override fun observeContact(id: String) = MutableStateFlow<com.codeyantra.armorchat.domain.model.Contact?>(null)
        override suspend fun getContact(id: String) = null
        override suspend fun findByPublicKey(publicIdentityKey: String) = null
        override suspend fun inspectQr(raw: String) = ContactExchangeResult.Invalid
        override suspend fun addQr(raw: String, localName: String) = ContactExchangeResult.Invalid
        override suspend fun addVerifiedBundle(
            parsed: com.codeyantra.armorchat.domain.identity.ParsedContactQr,
            localName: String,
        ) = ContactExchangeResult.Invalid
        override suspend fun rename(id: String, localName: String) = Unit
        override suspend fun addInboundPinned(
            publicIdentityKey: String,
            displayName: String,
            handle: String?,
        ) = com.codeyantra.armorchat.domain.model.Contact("id", "ac1:id", publicIdentityKey, displayName, 1)
    }

    private class FakeConversations : ConversationRepository {
        override fun observeConversations() = MutableStateFlow<List<com.codeyantra.armorchat.domain.model.Conversation>>(emptyList())
        override fun observeConversation(id: String) = MutableStateFlow<com.codeyantra.armorchat.domain.model.Conversation?>(null)
        override suspend fun getConversation(id: String) = null
        override suspend fun getOrCreateForContact(contact: com.codeyantra.armorchat.domain.model.Contact) =
            com.codeyantra.armorchat.domain.model.Conversation("c-1", contact.id, contact.displayName, 1)
    }

    private class FakeMessaging(
        val networkError: Throwable? = null,
        val lookupError: Throwable? = null,
    ) : ChatMessagingController {
        override val network = MutableStateFlow(NetworkAvailability())
        override fun observeSession(contactId: String) = flowOf(SecureSessionView())
        override suspend fun ensureProvisioned(contact: com.codeyantra.armorchat.domain.model.Contact) = Unit
        override suspend fun getMyContactQrV2(): String? = null
        override suspend fun importPeerContactV2(payload: com.codeyantra.armorchat.domain.identity.ContactQrV2) = RouteImportResult.Rejected
        override suspend fun importPeerRoute(raw: String, contactId: String?) = RouteImportResult.Rejected
        override fun localRouteQr(contactId: String) = null
        override suspend fun sendText(conversationId: String, text: String) =
            com.codeyantra.armorchat.domain.repository.SendTextResult.TransportUnavailable
        override suspend fun syncContact(contactId: String) = Unit
        override suspend fun markOpened(conversationId: String, messageId: String) = Unit
        override suspend fun refreshPolicy() = Unit

        override suspend fun resolveContactInviteV3(rawQr: String): Result<InviteRedemption> {
            if (networkError != null) return Result.failure(networkError)
            if (lookupError != null) return Result.failure(lookupError)
            return Result.failure(IllegalStateException("not implemented"))
        }
    }
}
