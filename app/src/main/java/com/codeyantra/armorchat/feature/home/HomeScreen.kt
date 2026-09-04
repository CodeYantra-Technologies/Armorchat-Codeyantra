package com.codeyantra.armorchat.feature.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.feature.chats.ChatsScreen
import com.codeyantra.armorchat.feature.contacts.ContactsScreen
import com.codeyantra.armorchat.feature.settings.SettingsScreen

private data class HomeTab(
    val labelRes: Int,
    val icon: ImageVector,
)

@Composable
fun HomeScreen(
    onAbout: () -> Unit,
    onSupport: () -> Unit,
    onMyIdentity: () -> Unit,
) {
    val tabs = listOf(
        HomeTab(R.string.tab_chats, Icons.Outlined.ChatBubbleOutline),
        HomeTab(R.string.tab_contacts, Icons.Outlined.PeopleOutline),
        HomeTab(R.string.tab_settings, Icons.Outlined.Settings),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.onBackground,
                            selectedTextColor = colors.onBackground,
                            indicatorColor = colors.surfaceVariant,
                            unselectedIconColor = colors.onSurfaceVariant,
                            unselectedTextColor = colors.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (selected) {
            0 -> ChatsScreen(modifier)
            1 -> ContactsScreen(modifier)
            else -> SettingsScreen(
                modifier = modifier,
                onAbout = onAbout,
                onSupport = onSupport,
                onMyIdentity = onMyIdentity,
            )
        }
    }
}
