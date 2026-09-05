package com.codeyantra.armorchat.feature.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    contactRepository: ContactRepository,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    private val contactId: String = checkNotNull(savedStateHandle["contactId"])
    val contact = contactRepository.observeContact(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun openConversation(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val current = contact.value ?: return@launch
            val conversation = conversationRepository.getOrCreateForContact(current)
            onReady(conversation.id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailRoute(
    onBack: () -> Unit,
    onOpenConversation: (conversationId: String) -> Unit,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val contact by viewModel.contact.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val current = contact
        if (current == null) {
            Text("Loading contact…", Modifier.padding(padding).padding(24.dp))
        } else {
            ContactDetailBody(
                contact = current,
                onMessage = { viewModel.openConversation(onOpenConversation) },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ContactDetailBody(
    contact: Contact,
    onMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var advanced by rememberSaveable(contact.id) { mutableStateOf(false) }
    var copied by rememberSaveable(contact.id) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            contact.displayName.ifBlank { "ArmorChat contact" },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text("ArmorChat ID verified", style = MaterialTheme.typography.titleMedium)
        Text(contact.armorChatId)
        Button(onClick = onMessage, modifier = Modifier.fillMaxWidth()) { Text("Message") }
        Button(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("ArmorChat ID", contact.armorChatId))
            copied = true
        }) { Text(if (copied) "Copied" else "Copy ArmorChat ID") }
        Text(
            "Date added: " + remember(contact.createdAtEpochMs) {
                DateFormat.getDateTimeInstance().format(Date(contact.createdAtEpochMs))
            },
        )
        Text("Added via: QR scan")
        Text(
            "QR validation verifies the ArmorChat cryptographic identifier, not the real-world identity of the person presenting it.",
        )
        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Hide security details" else "Show security details")
        }
        if (advanced) {
            Text("Pinned Ed25519 public key (Base64URL)", style = MaterialTheme.typography.labelMedium)
            Text(contact.publicIdentityKey)
        }
    }
}
