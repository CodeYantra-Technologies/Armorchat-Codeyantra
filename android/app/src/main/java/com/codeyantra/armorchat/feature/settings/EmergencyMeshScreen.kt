package com.codeyantra.armorchat.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.domain.model.EmergencyMeshStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyMeshScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmergencyMeshViewModel = hiltViewModel(),
) {
    val colors = MaterialTheme.colorScheme
    val runtime by viewModel.runtime.collectAsStateWithLifecycle()
    var showHowItWorksDetails by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) viewModel.setArmed(true)
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Emergency Mesh", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = colors.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Overview & Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            color = colors.primaryContainer.copy(alpha = 0.5f),
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Hub,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Emergency Mesh",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurface,
                            )
                            Text(
                                text = "Stay reachable when internet access is unavailable or unstable.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Status",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Surface(
                            color = colors.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = runtime.status.displayTitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "On this phone",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            text = holdLabel(runtime.holding, runtime.soonestExpireAtMs),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurface,
                        )
                    }
                }
            }

            // Future Auto-Mode Setting Card (Disabled)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Listen for nearby devices",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.onSurface,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "While this is on, a sealed message the relay did not accept stays on this phone for 4 hours and can leave with the next ArmorChat device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }

                        Switch(
                            checked = runtime.armed,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    viewModel.setArmed(false)
                                } else {
                                    val missing = viewModel.missingPermissions()
                                    if (missing.isEmpty()) viewModel.setArmed(true)
                                    else permissionLauncher.launch(missing.toTypedArray())
                                }
                            },
                        )
                    }
                }
            }

            // Future Route Visualization Architecture Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Hub,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Mesh Route Topology (Architecture Ready)",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                    }

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MeshNodeCard(
                            title = "Your Device",
                            subtitle = "Local MLS E2EE Encryption",
                            icon = Icons.Outlined.PhoneAndroid,
                            active = true,
                        )

                        Icon(
                            imageVector = Icons.Outlined.ArrowDownward,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp),
                        )

                        MeshNodeCard(
                            title = "Nearby ArmorChat device",
                            subtitle = "Bluetooth, or Wi-Fi Direct for a large batch",
                            icon = Icons.Outlined.Hub,
                            active = runtime.status == EmergencyMeshStatus.CONNECTING ||
                                runtime.status == EmergencyMeshStatus.CONNECTED,
                        )

                        Icon(
                            imageVector = Icons.Outlined.ArrowDownward,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp),
                        )

                        MeshNodeCard(
                            title = "Primary Relay or Recipient",
                            subtitle = "CodeYantra Authorized Transport",
                            icon = Icons.Outlined.Router,
                            active = true,
                        )
                    }

                    Surface(
                        color = colors.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Carried ${runtime.forwarded} · delivered on this phone ${runtime.deliveredHere} · path ${runtime.lastPath}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // How Emergency Mesh Will Work Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "How Emergency Mesh Will Work",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                    }

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    MeshBulletPoint("With nobody nearby, the sealed message stays on your phone. It is not floating in the air.")
                    MeshBulletPoint("It remains carryable for 4 hours from the moment it was sealed. A device that arrives at 3 hours 59 minutes can still take it. At 4 hours it is deleted.")
                    MeshBulletPoint("A hop does not restart those 4 hours. Every phone holding a copy uses the same deadline.")
                    MeshBulletPoint("Bluetooth finds the other phone and carries small bundles. A batch over 8 KB tries Wi-Fi Direct, then falls back to Bluetooth.")
                    MeshBulletPoint("A phone in the middle cannot read the message. The internet relay is still used when it accepts the send.")

                    TextButton(
                        onClick = { showHowItWorksDetails = !showHowItWorksDetails },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (showHowItWorksDetails) "Hide privacy details" else "View privacy details")
                    }

                    if (showHowItWorksDetails) {
                        HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))
                        Text(
                            text = "• Couriers store opaque ciphertext only.\n" +
                                   "• Discovery uses a rotating nonce, not your ArmorChat id.\n" +
                                   "• ${runtime.note}\n" +
                                   "• ${runtime.radioDetail.ifBlank { "Last path: ${runtime.lastPath}. Carried ${runtime.forwarded}. Delivered here ${runtime.deliveredHere}." }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MeshNodeCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (active) colors.primaryContainer else colors.surfaceVariant,
                shape = CircleShape,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            if (active) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = colors.tertiary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Surface(
                    color = colors.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "Future",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

private fun holdLabel(holding: Int, expireAtMs: Long?): String {
    if (holding == 0 || expireAtMs == null) return "Nothing waiting"
    val left = expireAtMs - System.currentTimeMillis()
    if (left <= 0L) return "Deadline reached"
    val minutes = left / 60_000L
    val hours = minutes / 60L
    val mins = minutes % 60L
    val remain = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    return "$holding sealed · $remain left"
}

@Composable
private fun MeshBulletPoint(text: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Shield,
            contentDescription = null,
            tint = colors.tertiary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}
