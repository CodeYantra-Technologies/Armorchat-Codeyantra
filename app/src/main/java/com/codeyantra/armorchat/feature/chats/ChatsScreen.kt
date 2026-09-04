package com.codeyantra.armorchat.feature.chats

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.ui.components.EmptyState

@Composable
fun ChatsScreen(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Outlined.ChatBubbleOutline,
        title = stringResource(R.string.chats_empty_title),
        body = stringResource(R.string.chats_empty_body),
        modifier = modifier,
    )
}
