package com.codeyantra.armorchat.feature.identity

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.domain.identity.IdentityQrPayload
import com.codeyantra.armorchat.ui.qr.QrBitmapEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MyIdentityRoute(
    onBack: () -> Unit,
    viewModel: IdentityViewModel = hiltViewModel(),
) {
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    MyIdentityScreen(
        armorChatId = identity?.armorChatId,
        publicIdentityKey = identity?.publicIdentityKey,
        version = identity?.version ?: 1,
        backupState = backupState,
        onCreateBackup = viewModel::createBackup,
        onBackupSaved = viewModel::markBackupSaved,
        onClearBackupState = viewModel::clearBackupState,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyIdentityScreen(
    armorChatId: String?,
    publicIdentityKey: String?,
    version: Int,
    backupState: IdentityBackupState,
    onCreateBackup: (String, String) -> Unit,
    onBackupSaved: () -> Unit,
    onClearBackupState: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var backupPassword by remember { mutableStateOf("") }
    var backupConfirmPassword by remember { mutableStateOf("") }
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
    val qrContent = remember(armorChatId, publicIdentityKey, version) {
        if (armorChatId != null && publicIdentityKey != null) {
            IdentityQrPayload.serialize(
                IdentityQrPayload(
                    id = armorChatId,
                    publicKeyBase64Url = publicIdentityKey,
                    version = version,
                ),
            )
        } else {
            null
        }
    }
    val qrBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, qrContent) {
        value = qrContent?.let { content ->
            withContext(Dispatchers.Default) { QrBitmapEncoder.encode(content, sizePx = 640) }
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identity_my_id_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.identity_your_id),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = armorChatId,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
                val bitmap = qrBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_identity_qr),
                        modifier = Modifier.size(240.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, armorChatId)
                        Toast.makeText(context, R.string.identity_copied, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.action_copy_id))
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, armorChatId)
                        }
                        context.startActivity(
                            Intent.createChooser(send, context.getString(R.string.action_share_id)),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.action_share_id))
                }
                if (qrContent != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, qrContent)
                            }
                            context.startActivity(Intent.createChooser(send, "Share public contact payload"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Share public contact payload") }
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    text = stringResource(R.string.identity_backup_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.identity_backup_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = backupPassword,
                    onValueChange = { backupPassword = it },
                    label = { Text(stringResource(R.string.identity_backup_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = backupConfirmPassword,
                    onValueChange = { backupConfirmPassword = it },
                    label = { Text(stringResource(R.string.identity_backup_confirm_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onCreateBackup(backupPassword, backupConfirmPassword) },
                    enabled = backupState !is IdentityBackupState.Creating,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
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
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = backupState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is IdentityBackupState.Saved -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = backupState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    IdentityBackupState.Creating,
                    IdentityBackupState.Idle,
                    is IdentityBackupState.Ready -> Unit
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.identity_security_line),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.brand_secured),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.brand_byline),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
