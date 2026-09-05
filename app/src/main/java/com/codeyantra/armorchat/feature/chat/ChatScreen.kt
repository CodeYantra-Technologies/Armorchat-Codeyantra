package com.codeyantra.armorchat.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.domain.model.Message
import com.codeyantra.armorchat.ui.components.EmptyState
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun ChatRoute(
    onBack: () -> Unit,
    onOpenContact: (contactId: String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatScreen(
        state = state,
        onBack = onBack,
        onOpenContact = {
            state.contact?.id?.let(onOpenContact)
        },
        onDraftChange = viewModel::onDraftChange,
        onSend = viewModel::send,
        onClearNotice = viewModel::clearNotice,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onOpenContact: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onClearNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenContact),
                    ) {
                        Text(
                            text = state.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.shortArmorChatId?.let { shortId ->
                            Text(
                                text = shortId,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = stringResource(R.string.chat_identity_pinned),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            ChatComposer(
                draft = state.draft,
                canSend = state.canSend,
                transportAvailable = state.transportAvailable,
                onDraftChange = onDraftChange,
                onSend = onSend,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.notice?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClearNotice)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (state.messages.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = stringResource(R.string.chat_empty_title),
                    body = stringResource(R.string.chat_empty_body),
                    modifier = Modifier.weight(1f),
                )
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(
                        items = withDateSeparators(state.messages),
                        key = { row ->
                            when (row) {
                                is ChatRow.Separator -> "sep-${row.label}-${row.key}"
                                is ChatRow.Bubble -> row.message.id
                            }
                        },
                    ) { row ->
                        when (row) {
                            is ChatRow.Separator -> MessageDateSeparator(row.label)
                            is ChatRow.Bubble -> MessageBubble(row.message)
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

private sealed interface ChatRow {
    data class Separator(val label: String, val key: Long) : ChatRow
    data class Bubble(val message: Message) : ChatRow
}

private fun withDateSeparators(messages: List<Message>): List<ChatRow> {
    if (messages.isEmpty()) return emptyList()
    val out = ArrayList<ChatRow>(messages.size + 4)
    var lastDay: Int? = null
    val cal = Calendar.getInstance()
    for (message in messages) {
        cal.timeInMillis = message.createdAtEpochMs
        val day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        if (day != lastDay) {
            out += ChatRow.Separator(
                label = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(message.createdAtEpochMs)),
                key = message.createdAtEpochMs,
            )
            lastDay = day
        }
        out += ChatRow.Bubble(message)
    }
    return out
}
