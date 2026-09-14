package com.codeyantra.armorchat.feature.chats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.feature.chat.MessageStatusIndicator
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ChatsScreen(
    onOpenConversation: (conversationId: String) -> Unit,
    onAddContact: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    var isSearchActive by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // Clean Material3 Header
        Surface(
            color = colors.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                if (isSearchActive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = viewModel::onSearchQueryChange,
                            placeholder = { Text("Search conversations…") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    if (searchQuery.isNotEmpty()) viewModel.onSearchQueryChange("")
                                    else isSearchActive = false
                                }) {
                                    Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = colors.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = colors.surfaceVariant.copy(alpha = 0.5f),
                                focusedBorderColor = colors.primary,
                                unfocusedBorderColor = colors.outline.copy(alpha = 0.4f),
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "ArmorChat",
                                style = MaterialTheme.typography.headlineMedium,
                                color = colors.onSurface,
                            )
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = "Secured",
                                tint = colors.tertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = "Search",
                                    tint = colors.onSurface,
                                )
                            }
                            IconButton(onClick = onAddContact) {
                                Icon(
                                    imageVector = Icons.Outlined.PersonAdd,
                                    contentDescription = "Add Contact",
                                    tint = colors.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        color = colors.surfaceVariant,
                        shape = CircleShape,
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

                    Spacer(Modifier.height(20.dp))

                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching conversations" else stringResource(R.string.chats_empty_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onBackground,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = if (searchQuery.isNotBlank()) "Try searching for another name or handle." else stringResource(R.string.chats_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    if (searchQuery.isBlank()) {
                        Spacer(Modifier.height(28.dp))

                        Button(
                            onClick = onAddContact,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = colors.onPrimary,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Scan ArmorChat QR", style = MaterialTheme.typography.labelLarge)
                        }

                        Spacer(Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = onAddContact,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Add contact", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(items, key = { it.conversationId }) { item ->
                    ChatListItemRow(
                        item = item,
                        onClick = { onOpenConversation(item.conversationId) },
                    )
                    HorizontalDivider(
                        color = colors.outline.copy(alpha = 0.2f),
                        modifier = Modifier.padding(start = 86.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ChatListItemRow(
    item: ChatListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeterministicAvatar(
            name = item.displayName,
            size = 52.dp,
        )

        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Text(
                    text = formatConversationTimestamp(item.lastMessageEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.unreadCount > 0) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.lastMessageText != null) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (item.isLastMessageOutgoing) {
                            item.lastMessageStatus?.let { status ->
                                MessageStatusIndicator(status = status)
                            }
                        }
                        Text(
                            text = if (item.isLastMessageOutgoing) "You: ${item.lastMessageText}" else item.lastMessageText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (item.unreadCount > 0) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = item.handleDisplay,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (item.unreadCount > 0) {
                    Surface(
                        color = colors.primary,
                        shape = CircleShape,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onPrimary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeterministicAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) {
        listOf(
            colors.primaryContainer to colors.onPrimaryContainer,
            colors.secondaryContainer to colors.onSecondaryContainer,
            colors.tertiaryContainer to colors.onTertiaryContainer,
            colors.surfaceVariant to colors.onSurfaceVariant,
        )
    }

    val hash = remember(name) { name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) } }
    val (bgColor, textColor) = palette[hash % palette.size]

    val initials = remember(name) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) "AC"
        else {
            val parts = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (parts.size >= 2) {
                "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
            } else {
                trimmed.take(2).uppercase()
            }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatConversationTimestamp(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (timestampMs <= 0L) return ""
    val calMsg = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val calNow = Calendar.getInstance().apply { timeInMillis = nowMs }

    val isSameYear = calMsg.get(Calendar.YEAR) == calNow.get(Calendar.YEAR)
    val dayMsg = calMsg.get(Calendar.DAY_OF_YEAR)
    val dayNow = calNow.get(Calendar.DAY_OF_YEAR)

    return when {
        isSameYear && dayMsg == dayNow -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampMs))
        isSameYear && dayNow - dayMsg == 1 -> "Yesterday"
        isSameYear && dayNow - dayMsg in 2..6 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestampMs))
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(timestampMs))
    }
}
