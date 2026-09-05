package com.codeyantra.armorchat.feature.identity

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.domain.model.UserIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RestoreIdentityRoute(
    onBack: () -> Unit,
    onRestored: (UserIdentity) -> Unit,
    viewModel: IdentityViewModel = hiltViewModel(),
) {
    val restoreState by viewModel.restoreState.collectAsStateWithLifecycle()
    RestoreIdentityScreen(
        restoreState = restoreState,
        onRestore = viewModel::restoreFromBackup,
        onClearRestoreState = viewModel::clearRestoreState,
        onRestored = onRestored,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreIdentityScreen(
    restoreState: IdentityRestoreState,
    onRestore: (ByteArray, String, (UserIdentity) -> Unit) -> Unit,
    onClearRestoreState: () -> Unit,
    onRestored: (UserIdentity) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedBackupName by remember { mutableStateOf<String?>(null) }
    var selectedBackupBytes by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                selectedBackupBytes = bytes
                selectedBackupName = uri.lastPathSegment ?: context.getString(R.string.identity_restore_file_selected)
                onClearRestoreState()
            }
        }
    }
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identity_restore_title)) },
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
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.identity_restore_screen_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.identity_restore_body),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = { openBackupLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = selectedBackupName ?: stringResource(R.string.action_choose_backup),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.identity_backup_password)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val bytes = selectedBackupBytes
                    if (bytes != null) {
                        onRestore(bytes, password, onRestored)
                    }
                },
                enabled = selectedBackupBytes != null && restoreState !is IdentityRestoreState.Restoring,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.onBackground,
                    contentColor = colors.background,
                ),
            ) {
                Text(
                    text = if (restoreState is IdentityRestoreState.Restoring) {
                        stringResource(R.string.identity_restore_restoring)
                    } else {
                        stringResource(R.string.action_restore_identity)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (restoreState is IdentityRestoreState.Error) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = restoreState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.onBackground,
                    contentColor = colors.background,
                ),
            ) {
                Text(
                    text = stringResource(R.string.action_back),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (restoreState is IdentityRestoreState.Success) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = { onRestored(restoreState.identity) }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            title = { Text(stringResource(R.string.identity_restore_success_title)) },
            text = { Text(stringResource(R.string.identity_restore_success_body)) },
        )
    }
}
