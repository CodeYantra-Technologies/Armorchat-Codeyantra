package com.codeyantra.armorchat.feature.identity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.domain.identity.ArmorChatHandle
import com.codeyantra.armorchat.domain.model.UserIdentity

@Composable
fun CreateIdentityRoute(
    onBack: () -> Unit,
    onCreated: (UserIdentity) -> Unit,
    viewModel: IdentityViewModel = hiltViewModel(),
) {
    val creationState by viewModel.creationState.collectAsStateWithLifecycle()
    val busy = creationState is IdentityCreationState.Creating
    val error = (creationState as? IdentityCreationState.RecoverableError)?.message
        ?: (creationState as? IdentityCreationState.FatalStorageError)?.message
    CreateIdentityScreen(
        busy = busy,
        error = error,
        onBack = onBack,
        onContinue = { name, handle -> viewModel.create(name, handle, onCreated) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateIdentityScreen(
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onContinue: (displayName: String, handle: String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var displayName by rememberSaveable { mutableStateOf("") }
    var handle by rememberSaveable { mutableStateOf("") }
    val parsedHandle = ArmorChatHandle.parse(handle)
    val canCreate = !busy && displayName.trim().isNotEmpty() && parsedHandle != null
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identity_create_title)) },
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
                text = stringResource(R.string.identity_create_body),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    if (it.length <= 100) {
                        displayName = it
                        if (handle.isBlank() || ArmorChatHandle.suggest(handle.removePrefix("@")) == null) {
                            ArmorChatHandle.suggest(it)?.let { suggested -> handle = suggested }
                        }
                    }
                },
                label = { Text("Your name") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = if (handle.startsWith("@") || handle.isBlank()) handle else "@$handle",
                onValueChange = { value ->
                    handle = value.removePrefix("@").lowercase().take(ArmorChatHandle.MAX_LENGTH)
                },
                label = { Text("ArmorChat handle") },
                placeholder = { Text("@neeraj-veltrix") },
                supportingText = {
                    Text(
                        if (handle.isBlank()) {
                            "Letters, numbers, and hyphens. This is not your secure identity."
                        } else if (parsedHandle == null) {
                            "Use 3–30 characters: a–z, 0–9, hyphen."
                        } else {
                            parsedHandle.display()
                        },
                    )
                },
                isError = handle.isNotBlank() && parsedHandle == null,
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(text = error, color = colors.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { parsedHandle?.let { onContinue(displayName.trim(), it.canonical) } },
                enabled = canCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                ),
            ) {
                Text(
                    text = if (busy) {
                        stringResource(R.string.identity_creating)
                    } else {
                        stringResource(R.string.action_create_identity)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
