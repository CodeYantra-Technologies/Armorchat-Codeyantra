package com.codeyantra.armorchat.feature.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun TwoPhoneTestRoute(onBack: () -> Unit, viewModel: TwoPhoneTestViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TwoPhoneTestScreen(
        state = state,
        onBack = onBack,
        onOrigin = viewModel::onOrigin,
        onPeerPin = viewModel::onPeerPin,
        onPeerRoute = viewModel::onPeerRoute,
        onPrepare = viewModel::prepare,
        onImport = viewModel::importPeer,
        onSend = viewModel::sendHello,
        onFetch = viewModel::fetch,
        onReply = viewModel::reply,
        onMarkOpened = viewModel::markOpened,
        onFlush = viewModel::flushRetry,
    )
}

@Composable
fun TwoPhoneTestScreen(
    state: TwoPhoneUiState,
    onBack: () -> Unit,
    onOrigin: (String) -> Unit,
    onPeerPin: (String) -> Unit,
    onPeerRoute: (String) -> Unit,
    onPrepare: () -> Unit,
    onImport: () -> Unit,
    onSend: () -> Unit,
    onFetch: () -> Unit,
    onReply: () -> Unit,
    onMarkOpened: () -> Unit,
    onFlush: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Two-phone MLS test", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
        Text(
            "Developer harness only. Normal testing uses contact QR + chat. This screen still allows paste for troubleshooting.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.origin,
            onValueChange = onOrigin,
            label = { Text("Relay origin (https://)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.peerPin,
            onValueChange = onPeerPin,
            label = { Text("Pinned peer identity key (base64url)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onPrepare,
            enabled = !state.busy && state.origin.startsWith("https://") && state.peerPin.isNotEmpty(),
        ) {
            Text("1. Register mailbox + KeyPackage")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.myRoute,
            onValueChange = {},
            readOnly = true,
            label = { Text("My signed route (share privately, not identity QR)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.peerRoute,
            onValueChange = onPeerRoute,
            label = { Text("Peer signed route") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        Button(onClick = onImport, enabled = !state.busy) { Text("2. Import peer route") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSend, enabled = !state.busy) { Text("3. Send Hello ArmorChat") }
        Button(onClick = onFetch, enabled = !state.busy) { Text("4. Fetch / decrypt / receipts") }
        Button(onClick = onReply, enabled = !state.busy) { Text("5. Reply Hello from B") }
        Button(onClick = onMarkOpened, enabled = !state.busy) { Text("6. Mark last inbound opened (read)") }
        Button(onClick = onFlush, enabled = !state.busy) { Text("7. Flush outbox / retry") }
        Spacer(Modifier.height(12.dp))
        Text("message_id: ${state.messageId ?: "—"}", color = colors.onBackground)
        Text("MLS epoch: ${state.epoch ?: "—"}", color = colors.onBackground)
        Text("relay state: ${state.relayState ?: "—"}", color = colors.onBackground)
        Text("outbound status: ${state.outboundStatus ?: "—"}", color = colors.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(state.log, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}
