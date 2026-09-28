package com.codeyantra.armorchat.feature.identity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.core.image.ProfilePhotoManager
import com.codeyantra.armorchat.domain.identity.ArmorChatHandle
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.ui.components.ArmorChatAvatar
import com.codeyantra.armorchat.ui.qr.QrBitmapEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shares the QR itself as an image (not the underlying contact text/id), so the
 * recipient gets a picture they can scan or import from their gallery, the same
 * way "My ArmorChat QR" is meant to be added.
 */
private fun shareQrImage(context: Context, qrBitmap: Bitmap, caption: String) {
    runCatching {
        val file = File(context.cacheDir, "armorchat_qr.png")
        FileOutputStream(file).use { out -> qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share ArmorChat QR"))
    }.onFailure {
        Toast.makeText(context, "Couldn't share QR", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MyIdentityRoute(
    onBack: () -> Unit,
    viewModel: IdentityViewModel = hiltViewModel(),
) {
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val aboutStatus by viewModel.aboutStatus.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()

    var refreshNonce by remember { mutableStateOf(0) }
    var rotateRequested by remember { mutableStateOf(false) }
    var qrLoading by remember { mutableStateOf(true) }
    var qrError by remember { mutableStateOf<String?>(null) }
    var v3QrContent by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(identity?.publicIdentityKey, refreshNonce) {
        if (identity == null) return@LaunchedEffect
        qrLoading = true
        qrError = null
        v3QrContent = null
        val rotate = rotateRequested
        rotateRequested = false
        val qr = try {
            viewModel.getMyContactQrV3(refresh = rotate)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            com.codeyantra.armorchat.messaging.SafeLog.event("stage=QR_RETURNED_TO_UI result=FAIL exception=${err::class.java.name}")
            null
        }
        if (qr != null) {
            v3QrContent = qr
            qrError = null
        } else {
            qrError = "Couldn’t prepare QR. Retry."
        }
        qrLoading = false
    }

    MyIdentityScreen(
        displayName = identity?.displayName,
        handle = identity?.handle,
        aboutStatus = aboutStatus,
        armorChatId = identity?.armorChatId,
        publicIdentityKey = identity?.publicIdentityKey,
        version = identity?.version ?: 1,
        v3Qr = v3QrContent,
        qrLoading = qrLoading,
        qrError = qrError,
        backupState = backupState,
        onCreateBackup = viewModel::createBackup,
        onBackupSaved = viewModel::markBackupSaved,
        onClearBackupState = viewModel::clearBackupState,
        onRefreshQr = { rotateRequested = true; refreshNonce++ },
        onRetryQr = { refreshNonce++ },
        onSaveProfile = viewModel::saveProfile,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyIdentityScreen(
    displayName: String?,
    handle: String?,
    aboutStatus: String,
    armorChatId: String?,
    publicIdentityKey: String?,
    version: Int,
    v3Qr: String? = null,
    qrLoading: Boolean = false,
    qrError: String? = null,
    backupState: IdentityBackupState,
    onCreateBackup: (String, String) -> Unit,
    onBackupSaved: () -> Unit,
    onClearBackupState: () -> Unit,
    onRefreshQr: () -> Unit = {},
    onRetryQr: () -> Unit = {},
    onSaveProfile: (String, String, String, () -> Unit) -> Unit = { _, _, _, _ -> },
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photoRefreshNonce by remember { mutableIntStateOf(0) }
    var showAvatarOptionsDialog by rememberSaveable { mutableStateOf(false) }
    var showEditProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showAdvancedDetailsDialog by rememberSaveable { mutableStateOf(false) }

    var backupPassword by remember { mutableStateOf("") }
    var backupConfirmPassword by remember { mutableStateOf("") }

    var nameDraft by remember(displayName) { mutableStateOf(displayName.orEmpty()) }
    var handleDraft by remember(handle) { mutableStateOf(handle.orEmpty()) }
    var aboutDraft by remember(aboutStatus) { mutableStateOf(aboutStatus) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val success = ProfilePhotoManager.savePhotoFromUri(context, uri)
                if (success) {
                    photoRefreshNonce++
                    Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not process image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val saveBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val ready = backupState as? IdentityBackupState.Ready
        if (uri != null && ready != null) {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(ready.bytes)
            }
            Toast.makeText(context, R.string.identity_backup_created, Toast.LENGTH_SHORT).show()
            onBackupSaved()
            backupPassword = ""
            backupConfirmPassword = ""
        } else {
            onClearBackupState()
        }
    }

    LaunchedEffect(backupState) {
        val ready = backupState as? IdentityBackupState.Ready
        if (ready != null) {
            saveBackupLauncher.launch(ready.fileName)
        }
    }

    val qrContent = v3Qr
    var qrEncodingFailed by remember(qrContent) { mutableStateOf(false) }
    val qrBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, qrContent) {
        value = null
        value = qrContent?.let { content ->
            withContext(Dispatchers.Default) {
                com.codeyantra.armorchat.messaging.SafeLog.event("stage=QR_ENCODER_ENTERED result=PASS")
                runCatching { QrBitmapEncoder.encode(content, sizePx = 512) }
                    .onSuccess {
                        com.codeyantra.armorchat.messaging.SafeLog.event("stage=QR_BITMAP_CREATED result=PASS")
                    }
                    .onFailure { err ->
                        qrEncodingFailed = true
                        com.codeyantra.armorchat.messaging.SafeLog.event(
                            "stage=QR_BITMAP_CREATED result=FAIL exception=${err::class.java.name}",
                        )
                    }
                    .getOrNull()
            }
        }
    }

    val photoFile = remember(photoRefreshNonce) { ProfilePhotoManager.getPhotoFile(context) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("My Profile", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = colors.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (armorChatId == null) {
                Spacer(Modifier.height(40.dp))
                Text(
                    text = stringResource(R.string.identity_missing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                val shownName = displayName?.takeIf { it.isNotBlank() } ?: "ArmorChat"
                val shownHandle = handle?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "@user"

                // Profile Header
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        ArmorChatAvatar(
                            displayName = shownName,
                            size = 84.dp,
                            photoFile = photoFile,
                        )
                        Surface(
                            color = colors.primary,
                            shape = CircleShape,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .size(30.dp)
                                .align(Alignment.BottomEnd),
                        ) {
                            IconButton(onClick = { showAvatarOptionsDialog = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.CameraAlt,
                                    contentDescription = "Edit Avatar",
                                    tint = colors.onPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = shownName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onBackground,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(2.dp))

                    Text(
                        text = shownHandle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = aboutStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )

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
                                text = "Verified local identity",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onTertiaryContainer,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            nameDraft = displayName.orEmpty()
                            handleDraft = handle.orEmpty()
                            aboutDraft = aboutStatus
                            showEditProfileDialog = true
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Edit profile")
                    }
                }

                // My ArmorChat QR Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = "My ArmorChat QR",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )

                        if (qrLoading || (qrContent != null && qrBitmap == null && !qrEncodingFailed)) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Text(
                                text = "Preparing ArmorChat QR…",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        } else if (qrError != null || qrEncodingFailed) {
                            Text(
                                text = qrError ?: "Couldn’t render QR. Retry.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                            )
                            Button(onClick = onRetryQr, enabled = !qrLoading) { Text("Retry") }
                        } else if (qrBitmap != null) {
                            Surface(
                                color = colors.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.padding(8.dp),
                            ) {
                                Image(
                                    bitmap = qrBitmap!!.asImageBitmap(),
                                    contentDescription = stringResource(R.string.cd_identity_qr),
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .size(180.dp),
                                )
                            }
                        }

                        Text(
                            text = "Scan to add me on ArmorChat.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val bitmap = qrBitmap
                                    if (bitmap != null) {
                                        val caption = "$shownName ($shownHandle) — scan this QR to add me on ArmorChat."
                                        shareQrImage(context, bitmap, caption)
                                    } else {
                                        Toast.makeText(context, "QR not ready yet", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = qrBitmap != null,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share")
                            }

                            OutlinedButton(
                                onClick = onRefreshQr,
                                enabled = !qrLoading,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Refresh")
                            }
                        }
                    }
                }

                // ArmorChat Identity Section
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
                                text = "ArmorChat Identity",
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
                                text = "ArmorChat ID",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                            Text(
                                text = ArmorChatId.shortDisplay(armorChatId),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurface,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Key Status",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                            Text(
                                text = "Hardware-backed Ed25519",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.tertiary,
                            )
                        }

                        TextButton(
                            onClick = { showAdvancedDetailsDialog = true },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("View security details")
                        }
                    }
                }

                // Identity Backup Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.identity_backup_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )

                        Text(
                            text = stringResource(R.string.identity_backup_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )

                        OutlinedTextField(
                            value = backupPassword,
                            onValueChange = { backupPassword = it },
                            label = { Text(stringResource(R.string.identity_backup_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )

                        OutlinedTextField(
                            value = backupConfirmPassword,
                            onValueChange = { backupConfirmPassword = it },
                            label = { Text(stringResource(R.string.identity_backup_confirm_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )

                        Button(
                            onClick = { onCreateBackup(backupPassword, backupConfirmPassword) },
                            enabled = backupState !is IdentityBackupState.Creating,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = if (backupState is IdentityBackupState.Creating) {
                                    stringResource(R.string.identity_backup_creating)
                                } else {
                                    stringResource(R.string.action_create_backup)
                                },
                            )
                        }

                        when (backupState) {
                            is IdentityBackupState.Error -> {
                                Text(
                                    text = backupState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.error,
                                )
                            }
                            is IdentityBackupState.Saved -> {
                                Text(
                                    text = backupState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.primary,
                                )
                            }
                            else -> Unit
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // Avatar Options Dialog
    if (showAvatarOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showAvatarOptionsDialog = false },
            title = { Text("Profile Photo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showAvatarOptionsDialog = false
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = colors.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = if (ProfilePhotoManager.hasPhoto(context)) "Change photo" else "Choose photo",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurface,
                            )
                        }
                    }

                    if (ProfilePhotoManager.hasPhoto(context)) {
                        TextButton(
                            onClick = {
                                showAvatarOptionsDialog = false
                                scope.launch {
                                    ProfilePhotoManager.removePhoto(context)
                                    photoRefreshNonce++
                                    Toast.makeText(context, "Profile photo removed", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = colors.error)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "Remove photo",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.error,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAvatarOptionsDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Edit Profile Modal Dialog
    if (showEditProfileDialog) {
        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = { Text("Edit Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Your Display Name, Handle, and About are metadata only. Changing them will NEVER replace your pinned Ed25519 identity key or change your ArmorChat ID.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { if (it.length <= 100) nameDraft = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = if (handleDraft.startsWith("@") || handleDraft.isBlank()) handleDraft else "@$handleDraft",
                        onValueChange = { handleDraft = it.removePrefix("@").lowercase() },
                        label = { Text("Handle") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = aboutDraft,
                        onValueChange = { if (it.length <= 140) aboutDraft = it },
                        label = { Text("About / Bio") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = ArmorChatHandle.parse(handleDraft)
                        if (parsed != null && nameDraft.trim().isNotEmpty()) {
                            onSaveProfile(nameDraft.trim(), parsed.canonical, aboutDraft.trim()) {
                                showEditProfileDialog = false
                            }
                        } else {
                            Toast.makeText(context, "Please enter a valid name and handle", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) {
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
                    Text(armorChatId.orEmpty(), style = MaterialTheme.typography.bodySmall)

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    Text("Ed25519 Public Key (Base64URL)", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    Text(publicIdentityKey.orEmpty(), style = MaterialTheme.typography.bodySmall)

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    Text("Trust Guarantee", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    Text("Identity is anchored to your Ed25519 key pair stored in Android Keystore. Name, handle, or photo changes do not alter your cryptographic identity.", style = MaterialTheme.typography.bodySmall)

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    Text("Protocol Version", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    Text("ArmorChat Protocol v$version", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ArmorChat ID", armorChatId.orEmpty()))
                        Toast.makeText(context, "ArmorChat ID copied to clipboard", Toast.LENGTH_SHORT).show()
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
}
