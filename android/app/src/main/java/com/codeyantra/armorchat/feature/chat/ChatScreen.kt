package com.codeyantra.armorchat.feature.chat

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.domain.model.AttachmentMetadata
import com.codeyantra.armorchat.deal.origin.ChatDealTopBarAction
import com.codeyantra.armorchat.domain.model.Message
import com.codeyantra.armorchat.messaging.SecureSessionState
import com.codeyantra.armorchat.messaging.SecureSessionView
import com.codeyantra.armorchat.ui.components.ArmorChatAvatar
import com.codeyantra.armorchat.ui.components.ChatWallpaper
import com.codeyantra.armorchat.ui.components.EmptyState
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun ChatRoute(
    onBack: () -> Unit,
    onOpenContact: (contactId: String) -> Unit,
    onCreateDeal: (() -> Unit)? = null,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setChatVisible(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setChatVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setChatVisible(false)
        }
    }
    LaunchedEffect(state.sessionReady, state.messages.size) {
        if (state.sessionReady &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            viewModel.markVisibleInbound()
        }
    }
    LaunchedEffect(state.contact?.id, lifecycleOwner) {
        if (state.contact?.id == null) return@LaunchedEffect
        while (true) {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                viewModel.syncNow()
            }
            kotlinx.coroutines.delay(3_000)
        }
    }
    val dealAction = if (
        onCreateDeal != null &&
        state.contact != null &&
        state.conversation != null &&
        state.conversation?.contactId == state.contact?.id
    ) {
        onCreateDeal
    } else {
        null
    }
    ChatScreen(
        state = state,
        onBack = onBack,
        onOpenContact = {
            state.contact?.id?.let(onOpenContact)
        },
        onCreateDeal = dealAction,
        onDraftChange = viewModel::onDraftChange,
        onSendText = viewModel::send,
        onSendAttachment = { pending ->
            viewModel.sendAttachment(
                context = context,
                uri = pending.uri,
                originalName = pending.name,
                mimeType = pending.mimeType,
                sizeBytes = pending.sizeBytes,
            )
        },
        onDownloadAttachment = viewModel::downloadAttachment,
        onClearNotice = viewModel::clearNotice,
        replyingToMessage = viewModel.replyingToMessage,
        onCancelReply = { viewModel.setReplyingTo(null) },
        onReply = viewModel::setReplyingTo,
        highlightedMessageId = viewModel.highlightedMessageId,
        onHighlightMessage = viewModel::highlightMessage,
        onShowNotice = viewModel::showNotice,
        messageForInfo = viewModel.messageForInfo,
        onOpenMessageInfo = viewModel::setInfoMessage,
        onDismissMessageInfo = { viewModel.setInfoMessage(null) },
        onCancelTransfer = viewModel::cancelTransfer,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onOpenContact: () -> Unit,
    onCreateDeal: (() -> Unit)? = null,
    onDraftChange: (String) -> Unit,
    onSendText: () -> Unit,
    onSendAttachment: (PendingAttachment) -> Unit,
    onDownloadAttachment: (AttachmentMetadata, (Boolean) -> Unit) -> Unit,
    onClearNotice: () -> Unit,
    replyingToMessage: Message? = null,
    onCancelReply: () -> Unit = {},
    onReply: (Message) -> Unit = {},
    highlightedMessageId: String? = null,
    onHighlightMessage: (String?) -> Unit = {},
    onShowNotice: (String) -> Unit = {},
    messageForInfo: Message? = null,
    onOpenMessageInfo: (Message) -> Unit = {},
    onDismissMessageInfo: () -> Unit = {},
    onCancelTransfer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalDownloadProgress provides state.downloadProgress) {

    messageForInfo?.let { infoMsg ->
        MessageInfoBottomSheet(
            message = infoMsg,
            contactName = state.displayName,
            onDismiss = onDismissMessageInfo,
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onOpenContact),
                    ) {
                        ArmorChatAvatar(
                            displayName = state.displayName,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = state.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = colors.tertiary,
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = state.contactHandle ?: state.shortArmorChatId ?: "Secure MLS Chat",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = colors.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                ),
                actions = {
                    ChatDealTopBarAction(onCreateDeal)
                },
            )
        },
        bottomBar = {
            when {
                state.networkUnavailable -> NetworkUnavailableBar()
                !state.sessionReady -> SecuringChatBar(session = state.session)
                else -> ChatComposer(
                    draft = state.draft,
                    canSend = state.canSend,
                    onDraftChange = onDraftChange,
                    onSendText = onSendText,
                    onSendAttachment = onSendAttachment,
                    replyingToMessage = replyingToMessage,
                    onCancelReply = onCancelReply,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.transfer.active) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.transfer.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.transfer.cancellable) {
                            TextButton(onClick = onCancelTransfer) { Text("Cancel") }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { state.transfer.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
            }
            state.notice?.let { message ->
                Text(
                    text = message,
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClearNotice)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (state.networkUnavailable) {
                EmptyState(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = stringResource(R.string.network_unavailable_title),
                    body = stringResource(R.string.network_unavailable_body),
                    modifier = Modifier.weight(1f),
                )
            } else {
                val listState = rememberLazyListState()
                val rows = remember(state.messages) { withDateSeparators(state.messages) }
                // Intro badge is item 0 and a spacer follows the rows, so the tail is rows.size + 1.
                val endIndex = rows.size + 1
                var followLatest by remember { mutableStateOf(true) }
                var scrollingProgrammatically by remember { mutableStateOf(false) }
                var previousMessageCount by remember { mutableIntStateOf(0) }

                fun tailIsHidden(): Boolean {
                    val info = listState.layoutInfo
                    val visible = info.visibleItemsInfo
                    if (visible.isEmpty() || info.totalItemsCount == 0) return false
                    val tail = visible.last()
                    if (tail.index < info.totalItemsCount - 1) return true
                    return tail.offset + tail.size > info.viewportEndOffset + 2
                }

                LaunchedEffect(listState) {
                    snapshotFlow { listState.isScrollInProgress to tailIsHidden() }
                        .collect { (inProgress, hidden) ->
                            if (scrollingProgrammatically) return@collect
                            if (inProgress && hidden) followLatest = false
                            if (!inProgress && !hidden) followLatest = true
                        }
                }

                LaunchedEffect(state.messages.size, endIndex) {
                    val count = state.messages.size
                    val ownSend = count > previousMessageCount &&
                        state.messages.lastOrNull()?.isOutgoing == true
                    if (count > 0 && rows.isNotEmpty() && (previousMessageCount == 0 || followLatest || ownSend)) {
                        followLatest = true
                        scrollingProgrammatically = true
                        try {
                            listState.animateScrollToItem(endIndex)
                        } finally {
                            scrollingProgrammatically = false
                        }
                    }
                    previousMessageCount = count
                }

                LaunchedEffect(followLatest) {
                    if (!followLatest) return@LaunchedEffect
                    snapshotFlow { tailIsHidden() to listState.layoutInfo.totalItemsCount }
                        .collect { (hidden, total) ->
                            if (!followLatest || !hidden || total <= 0) return@collect
                            scrollingProgrammatically = true
                            try {
                                listState.scrollToItem(total - 1)
                            } finally {
                                scrollingProgrammatically = false
                            }
                        }
                }

                ChatWallpaper(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            item {
                                EmptyChatIntroBadge()
                            }
                            items(
                                items = rows,
                                key = { row ->
                                    when (row) {
                                        is ChatRow.Separator -> "sep-${row.label}-${row.key}"
                                        is ChatRow.Bubble -> row.message.id
                                    }
                                },
                            ) { row ->
                                when (row) {
                                    is ChatRow.Separator -> MessageDateSeparator(row.label)
                                    is ChatRow.Bubble -> MessageBubble(
                                        message = row.message,
                                        onDownloadAttachment = onDownloadAttachment,
                                        onReply = onReply,
                                        onOpenMessageInfo = onOpenMessageInfo,
                                        onTapQuote = { targetId ->
                                            val index = rows.indexOfFirst { r -> r is ChatRow.Bubble && r.message.id == targetId }
                                            if (index != -1) {
                                                scope.launch {
                                                    listState.animateScrollToItem(index + 1)
                                                }
                                                onHighlightMessage(targetId)
                                                scope.launch {
                                                    kotlinx.coroutines.delay(1500)
                                                    onHighlightMessage(null)
                                                }
                                            } else {
                                                onShowNotice("Original message unavailable")
                                            }
                                        },
                                        isHighlighted = row.message.id == highlightedMessageId,
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }

                        if (!followLatest && rows.isNotEmpty()) {
                            SmallFloatingActionButton(
                                onClick = {
                                    followLatest = true
                                    scope.launch {
                                        scrollingProgrammatically = true
                                        try {
                                            listState.animateScrollToItem(endIndex)
                                        } finally {
                                            scrollingProgrammatically = false
                                        }
                                    }
                                },
                                containerColor = colors.surfaceVariant,
                                contentColor = colors.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.KeyboardArrowDown,
                                    contentDescription = "Scroll to bottom",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun NetworkUnavailableBar() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.errorContainer,
    ) {
        Text(
            text = "Network offline — relay unavailable",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onErrorContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SecuringChatBar(session: SecureSessionView) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = session.setupHint ?: "Securing chat…",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyChatIntroBadge() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colors.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "End-to-End Encrypted",
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Messages and calls are secured with mls-rs and verified cryptographic identity pins.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

sealed interface ChatRow {
    data class Separator(val label: String, val key: Long) : ChatRow
    data class Bubble(val message: Message) : ChatRow
}

private fun withDateSeparators(messages: List<Message>): List<ChatRow> {
    val out = mutableListOf<ChatRow>()
    var lastDate: String? = null
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

    for (msg in messages) {
        val dateStr = dateFormat.format(Date(msg.createdAtEpochMs))
        if (dateStr != lastDate) {
            lastDate = dateStr
            out.add(ChatRow.Separator(dateStr, msg.createdAtEpochMs))
        }
        out.add(ChatRow.Bubble(msg))
    }
    return out
}
