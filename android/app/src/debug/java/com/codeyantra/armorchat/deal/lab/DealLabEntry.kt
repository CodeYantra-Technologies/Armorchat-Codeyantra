package com.codeyantra.armorchat.deal.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.codeyantra.armorchat.BuildConfig
import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.DealLabGate

object DealLabDestination {
    const val TITLE = "Armor Deal Lab"
    const val ROUTE = "debug/deal_lab"
    const val BANNER = "LOCAL DEMO — NOT A LEGAL ESIGN OR CLOUD BACKUP"

    fun route(): String = ROUTE
}

fun NavGraphBuilder.installDealLabDestination(navController: NavHostController) {
    composable(DealLabDestination.ROUTE) {
        DealLabRoute(onBack = { navController.popBackStack() })
    }
}

@Composable
fun DealLabSettingsEntry(onOpen: (() -> Unit)?) {
    if (onOpen == null) return
    if (!DealLabGate.visible(BuildConfig.DEBUG, DealFeature.PRODUCTION_UI_ENABLED)) return
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.tertiaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = colors.tertiary.copy(alpha = 0.18f),
                shape = CircleShape,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Science,
                        contentDescription = null,
                        tint = colors.onTertiaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = DealLabDestination.TITLE,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onTertiaryContainer,
                )
                Text(
                    text = "Debug only. Synthetic agreements on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onTertiaryContainer,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onTertiaryContainer,
            )
        }
    }
}
