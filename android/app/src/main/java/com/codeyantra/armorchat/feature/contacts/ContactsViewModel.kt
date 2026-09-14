package com.codeyantra.armorchat.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.ContactExchangeResult
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.messaging.ChatMessagingController
import com.codeyantra.armorchat.messaging.RouteImportResult
import com.codeyantra.armorchat.messaging.RouteInvitation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExchangeState(
    val result: ContactExchangeResult? = null,
    val routeImport: RouteImportResult? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: ContactRepository,
    private val conversationRepository: ConversationRepository,
    private val messaging: ChatMessagingController,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExchangeState())
    val state = mutableState.asStateFlow()
    val contacts = repository.observeContacts().catch {
        mutableState.value = state.value.copy(error = "Could not load local contacts. Please try again.")
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var pendingQr: String? = null

    fun reset() {
        if (!state.value.busy) {
            pendingQr = null
            mutableState.value = ExchangeState()
        }
    }

    fun retry() {
        val raw = pendingQr
        if (raw != null) {
            scan(raw)
        } else {
            mutableState.value = ExchangeState()
        }
    }

    fun scan(raw: String) {
        if (state.value.busy) return
        pendingQr = raw
        execute {
            val invite = com.codeyantra.armorchat.domain.identity.ContactQrV3.parse(raw)
            if (invite != null) {
                val redemption = messaging.resolveContactInviteV3(raw).getOrElse { err ->
                    return@execute classifyInviteError(err)
                }
                val parsed = com.codeyantra.armorchat.domain.identity.ParsedContactQr.V3(redemption.bundle)
                val result = repository.addVerifiedBundle(parsed)
                val contact = when (result) {
                    is ContactExchangeResult.Saved -> result.contact
                    is ContactExchangeResult.Duplicate -> result.contact
                    is ContactExchangeResult.HandleChanged -> result.contact
                    else -> null
                }
                if (contact != null) {
                    runCatching { messaging.importPeerContactV3(redemption) }
                    runCatching { messaging.ensureProvisioned(contact) }
                }
                result
            } else {
                repository.inspectQr(raw)
            }
        }
    }

    private fun classifyInviteError(err: Throwable): ContactExchangeResult {
        val message = err.message.orEmpty().lowercase()
        val isNetwork = err is java.io.IOException ||
                "network" in message || "connect" in message || "timeout" in message ||
                "unknownhost" in message || "unreachable" in message || "failed to connect" in message ||
                "messaging disabled" in message
        if (isNetwork) {
            return ContactExchangeResult.NetworkUnavailable
        }

        val isSecurity = "signature" in message || "verification" in message ||
                "public key" in message || "id does not match" in message ||
                "tampered" in message || "bound to" in message || "not an armorchat bundle" in message
        if (isSecurity) {
            return ContactExchangeResult.SecurityVerificationFailed
        }

        if ("expired" in message) return ContactExchangeResult.InviteExpired
        if ("unknown" in message || "not found" in message) return ContactExchangeResult.InviteUnknown
        if ("revoked" in message) return ContactExchangeResult.InviteUnavailable

        return ContactExchangeResult.InviteUnavailable
    }

    fun save(name: String) {
        val raw = pendingQr ?: return
        if (state.value.busy) return
        execute {
            val result = repository.addQr(raw, name)
            val contact = when (result) {
                is ContactExchangeResult.Saved -> result.contact
                is ContactExchangeResult.Duplicate -> result.contact
                is ContactExchangeResult.HandleChanged -> result.contact
                else -> null
            }
            val parsed = when (result) {
                is ContactExchangeResult.Saved -> result.parsed
                is ContactExchangeResult.Duplicate -> result.parsed
                is ContactExchangeResult.HandleChanged -> result.parsed
                else -> null
            }
            if (contact != null) {
                runCatching {
                    when (parsed) {
                        is com.codeyantra.armorchat.domain.identity.ParsedContactQr.V3 ->
                            messaging.resolveContactInviteV3(raw).getOrNull()?.let {
                                messaging.importPeerContactV3(it)
                            }
                        is com.codeyantra.armorchat.domain.identity.ParsedContactQr.V2 ->
                            messaging.importPeerContactV2(parsed.payload)
                        else -> messaging.ensureProvisioned(contact)
                    }
                }
            }
            result
        }
    }

    fun removeContact(id: String) {
        viewModelScope.launch {
            runCatching { repository.deleteContact(id) }
        }
    }

    fun openConversation(contact: Contact, onReady: (conversationId: String) -> Unit) {
        viewModelScope.launch {
            try {
                val conversation = conversationRepository.getOrCreateForContact(contact)
                onReady(conversation.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = state.value.copy(
                    error = "Could not open conversation. Please try again.",
                )
            }
        }
    }

    private fun execute(block: suspend () -> ContactExchangeResult) {
        mutableState.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                mutableState.value = ExchangeState(result = block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = state.value.copy(
                    busy = false,
                    error = "Could not access local contacts. Please try again.",
                )
            }
        }
    }
}
