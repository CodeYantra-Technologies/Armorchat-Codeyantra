package com.codeyantra.armorchat.deal.origin

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/**
 * Release builds compile this stub instead of the debug chat-deal entry.
 * It has no route and no create action.
 */
object ChatDealDestination {
    fun route(@Suppress("UNUSED_PARAMETER") conversationId: String): String = ""
}

fun NavGraphBuilder.installChatDealDestination(
    @Suppress("UNUSED_PARAMETER") navController: NavHostController,
) = Unit

@Composable
fun ChatDealTopBarAction(@Suppress("UNUSED_PARAMETER") onCreate: (() -> Unit)?) = Unit
