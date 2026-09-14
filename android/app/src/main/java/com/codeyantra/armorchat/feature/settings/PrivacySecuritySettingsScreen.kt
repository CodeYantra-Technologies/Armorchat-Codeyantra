package com.codeyantra.armorchat.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.domain.model.AppLockType
import com.codeyantra.armorchat.domain.model.AutoLockTimeout
import com.codeyantra.armorchat.domain.model.NotificationPrivacy
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySecuritySettingsScreen(
    onBack: () -> Unit,
    viewModel: PrivacySecurityViewModel = hiltViewModel(),
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val appLockType by viewModel.appLockType.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val autoLockTimeout by viewModel.autoLockTimeout.collectAsStateWithLifecycle()
    val obscureRecents by viewModel.obscureRecents.collectAsStateWithLifecycle()
    val notificationPrivacy by viewModel.notificationPrivacy.collectAsStateWithLifecycle()

    var showSetupDialog by remember { mutableStateOf(false) }
    var showAuthForDisableDialog by remember { mutableStateOf(false) }
    var showAuthForChangeDialog by remember { mutableStateOf(false) }
    var showForgotPinDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Security") },
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (appLockEnabled) {
                Button(
                    onClick = viewModel::lockNow,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Lock ArmorChat now")
                }
                Spacer(Modifier.height(20.dp))
            }

            Text(
                text = "LOCAL APP LOCK",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "App Lock Status",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onBackground,
                    )
                    Text(
                        text = if (appLockEnabled) "Enabled (${appLockType.name})" else "Disabled",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (appLockEnabled) colors.tertiary else colors.onSurfaceVariant,
                    )
                }
            }

            if (!appLockEnabled) {
                Button(
                    onClick = { showSetupDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Enable App Lock")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { showAuthForChangeDialog = true },
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Change PIN")
                    }
                    OutlinedButton(
                        onClick = { showAuthForDisableDialog = true },
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Disable")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Biometric Unlock",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = biometricEnabled,
                        onCheckedChange = viewModel::setBiometricEnabled,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "AUTO-LOCK TIMEOUT",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                AutoLockTimeout.entries.forEach { option ->
                    val label = when (option) {
                        AutoLockTimeout.IMMEDIATELY -> "Immediately when backgrounded"
                        AutoLockTimeout.AFTER_1_MIN -> "After 1 minute"
                        AutoLockTimeout.AFTER_5_MIN -> "After 5 minutes"
                        AutoLockTimeout.AFTER_15_MIN -> "After 15 minutes"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = autoLockTimeout == option,
                                onClick = { viewModel.setAutoLockTimeout(option) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = autoLockTimeout == option, onClick = null)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onBackground,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = colors.outline)
            Spacer(Modifier.height(16.dp))

            Text(
                text = "NOTIFICATION PRIVACY",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            NotificationPrivacy.entries.forEach { privacyOption ->
                val label = when (privacyOption) {
                    NotificationPrivacy.SHOW_ALL -> "Show sender name and message text"
                    NotificationPrivacy.SENDER_ONLY -> "Show sender name only"
                    NotificationPrivacy.GENERIC -> "Show 'New ArmorChat message' only"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = notificationPrivacy == privacyOption,
                            onClick = { viewModel.setNotificationPrivacy(privacyOption) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = notificationPrivacy == privacyOption, onClick = null)
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onBackground,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = colors.outline)
            Spacer(Modifier.height(16.dp))

            Text(
                text = "APP SWITCHER & SCREENSHOTS",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Obscure preview in Recents (FLAG_SECURE)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onBackground,
                    )
                    Text(
                        text = "Prevents Android app switcher from showing chat previews and blocks screenshots.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = obscureRecents,
                    onCheckedChange = viewModel::setObscureRecents,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = colors.outline)
            Spacer(Modifier.height(16.dp))

            TextButton(
                onClick = { showForgotPinDialog = true },
            ) {
                Text("Forgot PIN or recovery information?")
            }
        }
    }

    if (showSetupDialog) {
        AppLockSetupDialog(
            onDismiss = { showSetupDialog = false },
            onConfirmSetup = { secret, type, biometric ->
                viewModel.setupAppLock(secret, type, biometric, AutoLockTimeout.IMMEDIATELY)
                showSetupDialog = false
            },
        )
    }

    if (showAuthForDisableDialog) {
        AppLockAuthDialog(
            title = "Confirm Current Secret to Disable",
            onDismiss = { showAuthForDisableDialog = false },
            onVerifyCandidate = { candidate ->
                scope.launch {
                    val valid = viewModel.verifyCandidate(candidate)
                    if (valid) {
                        viewModel.disableAppLock()
                        showAuthForDisableDialog = false
                    }
                }
            },
        )
    }

    if (showAuthForChangeDialog) {
        AppLockAuthDialog(
            title = "Confirm Current Secret First",
            onDismiss = { showAuthForChangeDialog = false },
            onVerifyCandidate = { candidate ->
                scope.launch {
                    val valid = viewModel.verifyCandidate(candidate)
                    if (valid) {
                        showAuthForChangeDialog = false
                        showSetupDialog = true
                    }
                }
            },
        )
    }

    if (showForgotPinDialog) {
        AlertDialog(
            onDismissRequest = { showForgotPinDialog = false },
            title = { Text("App Lock Recovery") },
            text = {
                Text(
                    "App Lock is purely local to this device. ArmorChat does not store your PIN on any server.\n\n" +
                    "If you forget your PIN, you can restore your ArmorChat identity on a fresh install using your encrypted Identity Backup file and backup password.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showForgotPinDialog = false }) {
                    Text("Understood")
                }
            },
        )
    }
}

@Composable
private fun AppLockSetupDialog(
    onDismiss: () -> Unit,
    onConfirmSetup: (secret: String, type: AppLockType, biometric: Boolean) -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    var confirmSecret by remember { mutableStateOf("") }
    var isPasswordMode by remember { mutableStateOf(false) }
    var enableBiometric by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val lockType = if (isPasswordMode) AppLockType.PASSWORD else AppLockType.PIN

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isPasswordMode) "Set Up Password" else "Set Up 6+ Digit PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(if (isPasswordMode) "Password" else "6+ Digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmSecret,
                    onValueChange = { confirmSecret = it },
                    label = { Text("Confirm Secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use alphanumeric password mode", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = isPasswordMode, onCheckedChange = { isPasswordMode = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable biometric unlock", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = enableBiometric, onCheckedChange = { enableBiometric = it })
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (secret.length < 6) {
                        error = "PIN/Password must be at least 6 characters"
                    } else if (secret != confirmSecret) {
                        error = "Secrets do not match"
                    } else {
                        onConfirmSetup(secret, lockType, enableBiometric)
                    }
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AppLockAuthDialog(
    title: String,
    onDismiss: () -> Unit,
    onVerifyCandidate: (String) -> Unit,
) {
    var candidate by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = candidate,
                onValueChange = { candidate = it },
                label = { Text("Current PIN or Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onVerifyCandidate(candidate) },
                enabled = candidate.isNotBlank(),
            ) {
                Text("Verify")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
