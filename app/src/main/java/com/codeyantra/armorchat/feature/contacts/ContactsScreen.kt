package com.codeyantra.armorchat.feature.contacts

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.ContactExchangeResult
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ContactsScreen(
    onOpenConversation: (conversationId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("list") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }
    var galleryError by rememberSaveable { mutableStateOf<String?>(null) }
    var galleryBusy by rememberSaveable { mutableStateOf(false) }
    var contactPendingDelete by remember { mutableStateOf<Contact?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraError = null
            page = "scan"
        } else {
            page = "add"
            cameraError = "Camera access is needed to scan a QR. You can allow it in Android app settings and try again."
        }
    }

    // SAF GetContent / photo picker — no READ_MEDIA / storage permission required.
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        galleryBusy = true
        galleryError = null
        scope.launch {
            val decoded = withContext(Dispatchers.Default) {
                QrImageDecoder.decode(context, uri)
            }
            galleryBusy = false
            decoded.fold(
                onSuccess = { raw ->
                    page = "confirm"
                    viewModel.scan(raw)
                },
                onFailure = {
                    galleryError = "No ArmorChat QR found in that image. Try another photo or use the camera scan."
                    page = "add"
                },
            )
        }
    }

    fun back() {
        if (!state.busy && !galleryBusy) {
            page = "list"
            viewModel.reset()
            cameraError = null
            galleryError = null
        }
    }
    BackHandler(page != "list") { back() }
    LaunchedEffect(state.result) {
        val contact = when (val result = state.result) {
            is ContactExchangeResult.Saved -> result.contact
            is ContactExchangeResult.HandleChanged -> result.contact
            else -> null
        }
        if (contact != null) {
            selectedId = contact.id
            page = "details"
        }
    }
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        if (page != "list") {
            TextButton(onClick = { back() }, enabled = !state.busy && !galleryBusy) {
                Text("Back to contacts")
            }
        }
        when (page) {
            "list" -> {
                Text("Contacts", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Exchange public identities in person. Contacts stay on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        viewModel.reset()
                        name = ""
                        galleryError = null
                        page = "add"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add Contact") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (contacts.isEmpty() && state.error == null) {
                    Text("No contacts yet. Scan someone’s ArmorChat QR to add them.")
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(contacts, key = { it.id }) { contact ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selectedId = contact.id
                                    page = "details"
                                }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        contact.displayName.ifBlank { "ArmorChat contact" },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        contact.handleDisplay ?: "Secure contact",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text("Verified", style = MaterialTheme.typography.labelMedium)
                                }
                                IconButton(onClick = { contactPendingDelete = contact }) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = "Delete ${contact.displayName.ifBlank { "contact" }}",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            "add" -> {
                Text("Add Contact", style = MaterialTheme.typography.headlineMedium)
                Text("Scan their ArmorChat QR. One scan connects you securely.")
                cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                galleryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (galleryBusy) {
                    CircularProgressIndicator()
                    Text("Reading QR from image…")
                }
                Button(
                    onClick = {
                        cameraError = null
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            page = "scan"
                        } else {
                            permission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !galleryBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan ArmorChat QR") }
                OutlinedButton(
                    onClick = {
                        galleryError = null
                        galleryPicker.launch("image/*")
                    },
                    enabled = !galleryBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Choose from Gallery") }
                Text(
                    "Manual ID entry is unavailable: an ID alone does not provide the public key.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            "scan" -> {
                Text("Scan ArmorChat QR", style = MaterialTheme.typography.headlineSmall)
                Text("Point the camera at the other phone’s QR.")
                QrScanner(
                    Modifier.fillMaxWidth().weight(1f),
                    onQr = { raw ->
                        page = "confirm"
                        viewModel.scan(raw)
                    },
                    onError = {
                        page = "add"
                        cameraError = "Camera unavailable. Check camera access and try again."
                    },
                )
                Spacer(Modifier.height(16.dp))
            }
            "confirm" -> Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Add ArmorChat Contact", style = MaterialTheme.typography.headlineSmall)
                if (state.busy) {
                    CircularProgressIndicator()
                    Text("Connecting securely…")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when (val result = state.result) {
                    is ContactExchangeResult.Ready -> {
                        if (result.parsed is com.codeyantra.armorchat.domain.identity.ParsedContactQr.V3Invite) {
                            Text("Looking up secure invite…")
                        } else {
                        Text(
                            result.parsed.displayName?.ifBlank { null } ?: "ArmorChat contact",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        result.parsed.handle?.let { Text("@$it") }
                        if (result.parsed.version < 3) {
                            Text(
                                "Compatibility QR. Secure chat may need a v3 invite from My ArmorChat.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { if (it.length <= 100) name = it },
                            label = { Text("Local name (optional)") },
                            supportingText = {
                                Text("Only a label on your device; not part of their identity.")
                            },
                            singleLine = true,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { viewModel.save(name) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add Contact") }
                        }
                    }
                    is ContactExchangeResult.Duplicate -> {
                        Text("Already in your contacts.")
                        Button(onClick = {
                            selectedId = result.contact.id
                            page = "details"
                        }) { Text("View existing contact") }
                    }
                    is ContactExchangeResult.HandleChanged -> {
                        Text("Handle changed")
                        Text(result.contact.handleDisplay ?: "")
                        Button(onClick = {
                            selectedId = result.contact.id
                            page = "details"
                        }) { Text("View contact") }
                    }
                    ContactExchangeResult.Self -> Text("This is your ArmorChat QR.")
                    ContactExchangeResult.Conflict ->
                        Text(
                            "This handle or ID is already tied to a different secure identity. The pinned key was not replaced.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    ContactExchangeResult.Invalid ->
                        Text("Invalid or unsupported ArmorChat QR. No contact was added.")
                    ContactExchangeResult.InviteExpired ->
                        Text("This invite link has expired. Ask for a new QR from My ArmorChat.")
                    ContactExchangeResult.InviteUnknown, ContactExchangeResult.InviteUnavailable ->
                        Text("Invite unavailable or unknown. Ask for a new QR from My ArmorChat.")
                    ContactExchangeResult.NetworkUnavailable ->
                        Text("Cannot reach the invite service. Check your network connection and try again.")
                    ContactExchangeResult.SecurityVerificationFailed ->
                        Text("Security verification failed for this contact bundle. Contact was not added.", color = MaterialTheme.colorScheme.error)
                    ContactExchangeResult.NoLocalIdentity ->
                        Text("Create or restore your ArmorChat identity before adding contacts.")
                    else -> Unit
                }
                if (!state.busy) {
                    if (state.error != null || state.result is ContactExchangeResult.NetworkUnavailable || state.result is ContactExchangeResult.InviteUnknown || state.result is ContactExchangeResult.InviteUnavailable || state.result is ContactExchangeResult.InviteExpired) {
                        Button(
                            onClick = { viewModel.retry() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Try again") }
                        Spacer(Modifier.height(8.dp))
                    }
                    TextButton(onClick = {
                        viewModel.reset()
                        page = "add"
                    }) { Text("Scan another QR") }
                }
            }
            "details" -> {
                val saved = (state.result as? ContactExchangeResult.Saved)?.contact
                    ?: (state.result as? ContactExchangeResult.HandleChanged)?.contact
                val justAdded = state.result is ContactExchangeResult.Saved
                val contact = contacts.firstOrNull { it.id == selectedId }
                    ?: saved?.takeIf { it.id == selectedId }
                if (contact == null) {
                    Text("Loading contact…")
                } else {
                    ContactDetails(
                        contact = contact,
                        justAdded = justAdded,
                        onMessage = {
                            viewModel.openConversation(contact) { conversationId ->
                                onOpenConversation(conversationId)
                            }
                        },
                        onDelete = { contactPendingDelete = contact },
                    )
                }
            }
        }
    }
    contactPendingDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { contactPendingDelete = null },
            title = { Text("Remove contact?") },
            text = {
                Text(
                    "This removes ${toDelete.displayName.ifBlank { "this contact" }} from your ArmorChat contacts. " +
                        "Chat history stays on this device.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeContact(toDelete.id)
                    contactPendingDelete = null
                    if (selectedId == toDelete.id) {
                        selectedId = null
                        page = "list"
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { contactPendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ContactDetails(
    contact: Contact,
    onMessage: () -> Unit,
    onDelete: () -> Unit,
    justAdded: Boolean = false,
) {
    val context = LocalContext.current
    var advanced by rememberSaveable(contact.id) { mutableStateOf(false) }
    var copied by rememberSaveable(contact.id) { mutableStateOf(false) }
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            contact.displayName.ifBlank { "ArmorChat contact" },
            style = MaterialTheme.typography.headlineMedium,
        )
        contact.handleDisplay?.let {
            Text(it, style = MaterialTheme.typography.titleMedium)
        }
        Text("Verified", style = MaterialTheme.typography.labelMedium)
        if (justAdded) {
            Text("${contact.displayName.ifBlank { "Contact" }} added")
        }
        if (contact.previousHandle != null && contact.handle != contact.previousHandle) {
            Text("Handle changed", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onMessage, modifier = Modifier.fillMaxWidth()) {
            Text("Message")
        }
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
            Text("ArmorChat ID", style = MaterialTheme.typography.labelMedium)
            Text(contact.armorChatId)
            Text("Pinned Ed25519 public key (Base64URL)", style = MaterialTheme.typography.labelMedium)
            Text(contact.publicIdentityKey)
            Text("The pinned key is the trust anchor. A handle is not identity proof.")
        }
        TextButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(6.dp))
            Text("Remove contact", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }
}
