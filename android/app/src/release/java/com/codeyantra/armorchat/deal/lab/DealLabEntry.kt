package com.codeyantra.armorchat.deal.lab

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/**
 * Release builds compile this stub instead of the debug lab. It has no route,
 * no label, and no deal action.
 */
object DealLabDestination {
    fun route(): String = ""
}

fun NavGraphBuilder.installDealLabDestination(
    @Suppress("UNUSED_PARAMETER") navController: NavHostController,
) = Unit

@Composable
fun DealLabSettingsEntry(@Suppress("UNUSED_PARAMETER") onOpen: (() -> Unit)?) = Unit
