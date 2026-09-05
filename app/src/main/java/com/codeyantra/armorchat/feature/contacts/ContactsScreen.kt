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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
        val saved = state.result as? ContactExchangeResult.Saved
        if (saved != null) {
            selectedId = saved.contact.id
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
                        Card(
                            Modifier.fillMaxWidth().clickable {
                                selectedId = contact.id
                                page = "details"
                            },
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    contact.displayName.ifBlank { "ArmorChat contact" },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(contact.armorChatId, style = MaterialTheme.typography.bodySmall)
                                Text("ArmorChat ID verified", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            "add" -> {
                Text("Add Contact", style = MaterialTheme.typography.headlineMedium)
                Text("Scan the public QR from their My ArmorChat ID screen. QR decoding happens entirely on this device.")
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
                    Text("Checking public identity…")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when (val result = state.result) {
                    is ContactExchangeResult.Ready -> {
                        Text("ArmorChat ID verified", style = MaterialTheme.typography.titleMedium)
                        Text(result.payload.id)
                        Text(
                            "The scanned public key corresponds to this ArmorChat ID. This does not verify the person’s real-world identity.",
                        )
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
                    is ContactExchangeResult.Duplicate -> {
                        Text("This ArmorChat ID is already in your contacts.")
                        Button(onClick = {
                            selectedId = result.contact.id
                            page = "details"
                        }) { Text("View existing contact") }
                    }
                    ContactExchangeResult.Self -> Text("This is your ArmorChat ID.")
                    ContactExchangeResult.Conflict ->
                        Text("Identity conflict. The saved public key will not be replaced.")
                    ContactExchangeResult.Invalid ->
                        Text("Invalid or unsupported ArmorChat QR. No contact was added.")
                    ContactExchangeResult.NoLocalIdentity ->
                        Text("Create or restore your ArmorChat identity before adding contacts.")
                    else -> Unit
                }
                if (!state.busy) {
                    TextButton(onClick = {
                        viewModel.reset()
                        page = "add"
                    }) { Text("Scan another QR") }
                }
            }
            "details" -> {
                val saved = (state.result as? ContactExchangeResult.Saved)?.contact
                val contact = contacts.firstOrNull { it.id == selectedId }
                    ?: saved?.takeIf { it.id == selectedId }
                if (contact == null) {
                    Text("Loading contact…")
                } else {
                    ContactDetails(
                        contact = contact,
                        onMessage = {
                            viewModel.openConversation(contact) { conversationId ->
                                onOpenConversation(conversationId)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactDetails(
    contact: Contact,
    onMessage: () -> Unit,
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
        Text("Local display name", style = MaterialTheme.typography.labelMedium)
        Text("ArmorChat ID verified", style = MaterialTheme.typography.titleMedium)
        Text(contact.armorChatId)
        Button(onClick = onMessage, modifier = Modifier.fillMaxWidth()) {
            Text("Message")
        }
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
            Text("This key cannot be silently replaced. Your local name is not cryptographically bound to it.")
        }
        Spacer(Modifier.height(24.dp))
    }
}
