package com.codeyantra.armorchat.feature.settings

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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkPrivacyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NetworkPrivacyViewModel = hiltViewModel(),
) {
    val colors = MaterialTheme.colorScheme
    val networkState by viewModel.networkAvailability.collectAsStateWithLifecycle()
    var showAdvancedDetails by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Network & Privacy", style = MaterialTheme.typography.titleLarge) },
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
            // Overview Card
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
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = colors.tertiary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "Secure Route Status",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                    }

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Secure Route", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text("Active", style = MaterialTheme.typography.labelLarge, color = colors.tertiary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("End-to-End Encryption", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text("Active (MLS v2)", style = MaterialTheme.typography.labelLarge, color = colors.tertiary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Current Relay", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text(
                            viewModel.bootstrapNode.canonicalOrigin.removePrefix("https://"),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurface,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Relay Hops", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text("${viewModel.actualHopCount} Hop", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("FCM Wake Signal", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text("Generic Only (Zero Content)", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Keys Location", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text("Android Keystore", style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                    }
                }
            }

            // Message Route Visualizer
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
                            imageVector = Icons.Outlined.Router,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "Message Route Flow",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                    }

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Node 1: Sender Device
                        RouteNodeCard(
                            title = "Your Device",
                            subtitle = "Local MLS E2EE Encryption",
                            icon = Icons.Outlined.PhoneAndroid,
                            active = true,
                        )

                        Icon(
                            imageVector = Icons.Outlined.ArrowDownward,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp),
                        )

                        // Node 2: Authorized Relay(s)
                        viewModel.authorizedRelays.forEach { node ->
                            RouteNodeCard(
                                title = "Authorized Relay (${node.id})",
                                subtitle = node.canonicalOrigin.removePrefix("https://"),
                                icon = Icons.Outlined.Router,
                                active = true,
                            )
                        }

                        Icon(
                            imageVector = Icons.Outlined.ArrowDownward,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp),
                        )

                        // Node 3: Recipient Device
                        RouteNodeCard(
                            title = "Recipient Device",
                            subtitle = "Local Decryption & Authenticated Receipt",
                            icon = Icons.Outlined.PhoneAndroid,
                            active = true,
                        )
                    }
                }
            }

            // Privacy Guarantees Card
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
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "Privacy Guarantees",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                    }

                    HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                    PrivacyBulletPoint("Messages are encrypted on your device before leaving it.")
                    PrivacyBulletPoint("ArmorChat relays route encrypted data and cannot read your message content.")
                    PrivacyBulletPoint("Delivery and read status come from authenticated recipient-device receipts.")
                    PrivacyBulletPoint("Firebase is used only as a generic wake-up signal and does not carry message content.")
                }
            }

            // Advanced Network Details Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Advanced Technical Details",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )

                        TextButton(onClick = { showAdvancedDetails = !showAdvancedDetails }) {
                            Text(if (showAdvancedDetails) "Hide" else "Show")
                        }
                    }

                    if (showAdvancedDetails) {
                        HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

                        DetailRow("Relay Origin", viewModel.bootstrapNode.canonicalOrigin)
                        DetailRow("Route Type", "Direct Authorized Relay Route")
                        DetailRow("Protocol Version", "ArmorChat Protocol v3")
                        DetailRow("Session State", "MLS E2EE Established")
                        DetailRow("Service Policy", networkState.policyState.name)
                        DetailRow("Actual Hop Count", "${viewModel.actualHopCount}")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RouteNodeCard(
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
                        tint = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }

            if (active) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = colors.tertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun PrivacyBulletPoint(text: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
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

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
    }
}
