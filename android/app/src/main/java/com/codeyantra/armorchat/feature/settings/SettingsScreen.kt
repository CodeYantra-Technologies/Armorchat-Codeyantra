package com.codeyantra.armorchat.feature.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Support
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.BuildConfig
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.deal.lab.DealLabSettingsEntry
import com.codeyantra.armorchat.domain.model.ThemeMode

@Composable
fun SettingsScreen(
    onAbout: () -> Unit,
    onSupport: () -> Unit,
    onMyIdentity: () -> Unit,
    onPrivacySecurity: () -> Unit,
    onNetworkPrivacy: () -> Unit,
    onEmergencyMesh: () -> Unit,
    onTwoPhoneTest: (() -> Unit)? = null,
    onDealLab: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val harness by viewModel.developerMlsHarness.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.tab_settings),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground,
            )
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = colors.tertiary,
                modifier = Modifier.size(20.dp),
            )
        }

        // Account & Security Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                SettingsCardRow(
                    title = stringResource(R.string.settings_my_identity),
                    subtitle = "Display name, handle & QR code",
                    icon = Icons.Outlined.Person,
                    onClick = onMyIdentity,
                )
                HorizontalDivider(color = colors.outline.copy(alpha = 0.15f), modifier = Modifier.padding(start = 56.dp))
                SettingsCardRow(
                    title = "Privacy & Security",
                    subtitle = "App Lock, biometrics & notification privacy",
                    icon = Icons.Outlined.Lock,
                    onClick = onPrivacySecurity,
                )
                HorizontalDivider(color = colors.outline.copy(alpha = 0.15f), modifier = Modifier.padding(start = 56.dp))
                SettingsCardRow(
                    title = "Network & Privacy",
                    subtitle = "Relay routes, encryption & FCM wake privacy",
                    icon = Icons.Outlined.Router,
                    onClick = onNetworkPrivacy,
                )
                HorizontalDivider(color = colors.outline.copy(alpha = 0.15f), modifier = Modifier.padding(start = 56.dp))
                SettingsCardRow(
                    title = "Emergency Mesh",
                    subtitle = "Bluetooth and Wi-Fi Direct carry a sealed message for 4 hours",
                    icon = Icons.Outlined.Hub,
                    badge = "4 hour hold",
                    onClick = onEmergencyMesh,
                )
            }
        }

        // Appearance Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Palette,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_appearance),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                }

                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = themeMode == mode,
                                onClick = { viewModel.onThemeSelected(mode) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = null)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onBackground,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }

        // Help & Support Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                SettingsCardRow(
                    title = stringResource(R.string.settings_about),
                    subtitle = "Version, open-source licenses & brand info",
                    icon = Icons.Outlined.Info,
                    onClick = onAbout,
                )
                HorizontalDivider(color = colors.outline.copy(alpha = 0.15f), modifier = Modifier.padding(start = 56.dp))
                SettingsCardRow(
                    title = stringResource(R.string.settings_support),
                    subtitle = "Help & troubleshooting",
                    icon = Icons.Outlined.Support,
                    onClick = onSupport,
                )
            }
        }

        // Developer Options (If Enabled)
        if (BuildConfig.TWO_PHONE_TEST) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_developer),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.settings_mls_harness), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_mls_harness_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Switch(checked = harness, onCheckedChange = viewModel::onDeveloperMlsHarness)
                    }
                    if (harness && onTwoPhoneTest != null) {
                        Spacer(Modifier.height(8.dp))
                        SettingsCardRow(
                            title = "Two-phone MLS test",
                            subtitle = "Interactive multi-device MLS session testing",
                            icon = Icons.AutoMirrored.Outlined.HelpOutline,
                            onClick = onTwoPhoneTest,
                        )
                    }
                }
            }
        }

        DealLabSettingsEntry(onOpen = onDealLab)

        Spacer(Modifier.height(8.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.brand_secured),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.brand_byline),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsCardRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = colors.primaryContainer.copy(alpha = 0.5f),
            shape = CircleShape,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                badge?.let { label ->
                    Surface(
                        color = colors.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
        )
    }
}
