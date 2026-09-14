package com.codeyantra.armorchat.feature.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.core.network.RelayRegistry
import com.codeyantra.armorchat.core.network.RelayTransportFactory
import com.codeyantra.armorchat.core.security.SecureIdentityKeyStore
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import com.codeyantra.armorchat.messaging.ArmorChatContexts
import com.codeyantra.armorchat.messaging.IdentitySigner
import com.codeyantra.armorchat.messaging.ReceiptMessagingPeer
import com.codeyantra.armorchat.messaging.TwoPhonePeer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TwoPhoneUiState(
    val origin: String = "",
    val peerPin: String = "",
    val peerRoute: String = "",
    val myRoute: String = "",
    val log: String = "Test-only MLS receipts harness. Production chat send stays disabled.",
    val epoch: Long? = null,
    val messageId: String? = null,
    val relayState: String? = null,
    val outboundStatus: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class TwoPhoneTestViewModel @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val keyStore: SecureIdentityKeyStore,
    private val relayFactory: RelayTransportFactory,
    registry: RelayRegistry,
) : ViewModel() {
    private val _state = MutableStateFlow(TwoPhoneUiState(origin = registry.bootstrap().origin))
    val state: StateFlow<TwoPhoneUiState> = _state
    private var peer: TwoPhonePeer? = null
    private var session: ReceiptMessagingPeer? = null
    private var groupStarted = false

    fun onOrigin(value: String) {
        _state.value = _state.value.copy(origin = value.trim())
    }

    fun onPeerPin(value: String) {
        _state.value = _state.value.copy(peerPin = value.trim())
    }

    fun onPeerRoute(value: String) {
        _state.value = _state.value.copy(peerRoute = value)
    }

    fun prepare() {
        viewModelScope.launch {
            runCatching {
                _state.value = _state.value.copy(busy = true)
                val identity = identityRepository.getIdentity() ?: error("create identity first")
                val signer = object : IdentitySigner {
                    override val pin = identity.publicIdentityKey
                    override fun sign(context: String, bodyCanonical: ByteArray): ByteArray {
                        require(context in ArmorChatContexts.allowed)
                        return runBlocking { keyStore.signFramed(context, bodyCanonical) }
                    }
                }
                val relay = relayFactory.create(_state.value.origin)
                val p = TwoPhonePeer(signer, relay, nowSeconds = { System.currentTimeMillis() / 1000 })
                withContext(Dispatchers.IO) {
                    p.createMlsDevice()
                    p.registerAndPublish()
                }
                peer = p
                session = ReceiptMessagingPeer(p, { System.currentTimeMillis() / 1000 })
                val peerPin = _state.value.peerPin
                require(peerPin.isNotEmpty()) { "pinned peer identity key required" }
                require(peerPin != identity.publicIdentityKey) { "peer pin must be the other device" }
                val route = p.exportRoute(peerPin)
                append("mailbox ready. Share the signed route privately with that pinned peer.")
                _state.value = _state.value.copy(
                    myRoute = route.toQr(),
                    busy = false,
                    epoch = p.diagnostics.epoch,
                )
            }.onFailure { fail(it) }
        }
    }

    fun importPeer() {
        viewModelScope.launch {
            runCatching {
                val p = peer ?: error("prepare this device first")
                val identity = identityRepository.getIdentity() ?: error("identity required")
                val raw = _state.value.peerRoute
                val parsed = com.codeyantra.armorchat.messaging.RouteInvitation.parse(
                    raw,
                    identity.publicIdentityKey,
                    System.currentTimeMillis() / 1000,
                )
                p.importRoute(raw, parsed.inviterPin)
                append("imported route for pinned peer")
            }.onFailure { fail(it) }
        }
    }

    fun sendHello() {
        viewModelScope.launch {
            runCatching {
                val p = peer ?: error("prepare first")
                val s = session ?: error("prepare first")
                _state.value = _state.value.copy(busy = true)
                withContext(Dispatchers.IO) {
                    if (!groupStarted) {
                        p.initiateGroup()
                        groupStarted = true
                    }
                    s.sendText("Hello ArmorChat")
                }
                showDiag(p, s, "sent Hello ArmorChat (SENT=relay accepted, not DELIVERED)")
            }.onFailure { fail(it) }
        }
    }

    fun fetch() {
        viewModelScope.launch {
            runCatching {
                val p = peer ?: error("prepare first")
                val s = session ?: error("prepare first")
                _state.value = _state.value.copy(busy = true)
                val added = withContext(Dispatchers.IO) {
                    p.joinFromInbox()
                    s.receiveAll()
                }
                val note = if (added.isEmpty()) {
                    "fetched receipts/status only"
                } else {
                    "fetched: ${added.joinToString()}"
                }
                showDiag(p, s, note)
            }.onFailure { fail(it) }
        }
    }

    fun reply() {
        viewModelScope.launch {
            runCatching {
                val p = peer ?: error("prepare first")
                val s = session ?: error("prepare first")
                _state.value = _state.value.copy(busy = true)
                withContext(Dispatchers.IO) { s.sendText("Hello from B") }
                showDiag(p, s, "sent Hello from B")
            }.onFailure { fail(it) }
        }
    }

    fun markOpened() {
        viewModelScope.launch {
            runCatching {
                val p = peer ?: error("prepare first")
                val s = session ?: error("prepare first")
                _state.value = _state.value.copy(busy = true)
                withContext(Dispatchers.IO) {
                    s.markOpened(s.lastAcceptedTextId())
                    s.flushOutbox()
                }
                showDiag(p, s, "marked last inbound text opened (read receipt queued/sent)")
            }.onFailure { fail(it) }
        }
    }

    fun flushRetry() {
        viewModelScope.launch {
            runCatching {
                val p = peer ?: error("prepare first")
                val s = session ?: error("prepare first")
                _state.value = _state.value.copy(busy = true)
                withContext(Dispatchers.IO) { s.flushOutbox() }
                showDiag(p, s, "flushed outbox / retry")
            }.onFailure { fail(it) }
        }
    }

    private fun showDiag(p: TwoPhonePeer, s: ReceiptMessagingPeer, note: String) {
        val d = s.diagnostics.lastMessageId?.let { s.diagnostics } ?: p.diagnostics
        val status = runCatching { s.status(s.lastTextId()).name }.getOrNull()
        _state.value = _state.value.copy(
            busy = false,
            epoch = d.epoch,
            messageId = d.lastMessageId,
            relayState = d.relayState,
            outboundStatus = status,
            log = _state.value.log + "\n$note",
        )
    }

    private fun append(note: String) {
        _state.value = _state.value.copy(log = _state.value.log + "\n$note", busy = false)
    }

    private fun fail(t: Throwable) {
        _state.value = _state.value.copy(busy = false, log = _state.value.log + "\nerror: ${t.message}")
    }
}
