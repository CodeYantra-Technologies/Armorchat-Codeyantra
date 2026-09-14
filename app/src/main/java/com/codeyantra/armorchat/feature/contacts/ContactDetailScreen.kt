package com.codeyantra.armorchat.feature.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.feature.chats.DeterministicAvatar
import com.codeyantra.armorchat.messaging.SecureSessionState
import com.codeyantra.armorchat.messaging.SecureSessionView
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailRoute(
    onBack: () -> Unit,
    onOpenConversation: (conversationId: String) -> Unit,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val contact by viewModel.contact.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Contact Info", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = colors.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
    ) { padding ->
        val current = contact
        if (current == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Loading contact info…", color = colors.onSurfaceVariant)
            }
        } else {
            ContactDetailBody(
                contact = current,
                session = session,
                onMessage = { viewModel.openConversation(onOpenConversation) },
                onRenameAlias = viewModel::renameAlias,
                onRemoveContact = { viewModel.removeContact(onBack) },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ContactDetailBody(
    contact: Contact,
    session: SecureSessionView,
    onMessage: () -> Unit,
    onRenameAlias: (String) -> Unit,
    onRemoveContact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current

    var showEditAliasDialog by rememberSaveable { mutableStateOf(false) }
    var showRemoveConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showAdvancedDetailsDialog by rememberSaveable { mutableStateOf(false) }
    var showQrDialog by rememberSaveable { mutableStateOf(false) }
    var aliasText by rememberSaveable(contact.id) { mutableStateOf(contact.displayName) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Profile Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            DeterministicAvatar(
                name = contact.displayName,
                size = 80.dp,
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = contact.displayName.ifBlank { "ArmorChat contact" },
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
            )

            contact.handleDisplay?.let { handle ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = handle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(10.dp))

            Surface(
                color = colors.tertiaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = "Verified",
                        tint = colors.tertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "ArmorChat identity verified",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onTertiaryContainer,
                    )
                }
            }

            if (contact.previousHandle != null && contact.previousHandle != contact.handle) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Handle updated from ${contact.previousHandle}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        // Primary Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onMessage,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Message", style = MaterialTheme.typography.labelLarge)
            }

            OutlinedButton(
                onClick = { showQrDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Share QR", style = MaterialTheme.typography.labelLarge)
            }
        }

        // Security Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Security Overview",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                }

                HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Session State",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = if (session.state == SecureSessionState.ESTABLISHED) "Secure session active" else "Securing session…",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (session.state == SecureSessionState.ESTABLISHED) colors.tertiary else colors.primary,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "ArmorChat ID",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = ArmorChatId.shortDisplay(contact.armorChatId),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Added On",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(contact.createdAtEpochMs)),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface,
                    )
                }

                TextButton(
                    onClick = { showAdvancedDetailsDialog = true },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("View security details")
                }
            }
        }

        // Contact Management Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Contact Options",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                TextButton(
                    onClick = {
                        aliasText = contact.displayName
                        showEditAliasDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Edit local alias",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurface,
                        )
                    }
                }

                TextButton(
                    onClick = { showRemoveConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Remove contact",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.error,
                        )
                    }
                }
            }
        }
    }

    // Edit Alias Dialog
    if (showEditAliasDialog) {
        AlertDialog(
            onDismissRequest = { showEditAliasDialog = false },
            title = { Text("Edit Local Alias") },
            text = {
                OutlinedTextField(
                    value = aliasText,
                    onValueChange = { if (it.length <= 100) aliasText = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameAlias(aliasText)
                        showEditAliasDialog = false
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditAliasDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Remove Confirmation Dialog
    if (showRemoveConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirmDialog = false },
            title = { Text("Remove Contact?") },
            text = {
                Text("This will remove ${contact.displayName} from your ArmorChat contacts list. Chat history will remain intact in your messages.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveConfirmDialog = false
                        onRemoveContact()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Advanced Security Details Dialog
    if (showAdvancedDetailsDialog) {
        AlertDialog(
            onDismissRequest = { showAdvancedDetailsDialog = false },
            title = { Text("Advanced Security Details") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Full ArmorChat ID", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    Text(contact.armorChatId, style = MaterialTheme.typography.bodySmall)

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    Text("Pinned Ed25519 Identity Key", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    Text(contact.publicIdentityKey, style = MaterialTheme.typography.bodySmall)

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    Text("Trust Anchor", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    Text("Ed25519 Key Pinned (Strict verification). Handles are metadata only.", style = MaterialTheme.typography.bodySmall)

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    Text("Protocol & Session", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    Text("Protocol Version: ArmorChat v3\nSession: MLS E2EE Established", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ArmorChat ID", contact.armorChatId))
                        Toast.makeText(context, "ArmorChat ID copied", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy ID")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdvancedDetailsDialog = false }) {
                    Text("Close")
                }
            },
        )
    }

    // Share Contact QR Dialog
    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("ArmorChat Contact ID") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                    contact.handleDisplay?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }

                    Spacer(Modifier.height(16.dp))

                    Surface(
                        color = colors.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = contact.armorChatId,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ArmorChat ID", contact.armorChatId))
                        Toast.makeText(context, "ArmorChat ID copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Copy ID")
                }
            },
            dismissButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
}
