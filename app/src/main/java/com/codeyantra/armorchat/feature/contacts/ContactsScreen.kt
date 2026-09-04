package com.codeyantra.armorchat.feature.contacts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.ui.components.EmptyState

@Composable
fun ContactsScreen(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Outlined.PeopleOutline,
        title = stringResource(R.string.contacts_empty_title),
        body = stringResource(R.string.contacts_empty_body),
        modifier = modifier,
    )
}
