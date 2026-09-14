package com.codeyantra.armorchat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.codeyantra.armorchat.BuildConfig
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.feature.chat.ChatRoute
import com.codeyantra.armorchat.feature.contacts.ContactDetailRoute
import com.codeyantra.armorchat.feature.debug.TwoPhoneTestRoute
import com.codeyantra.armorchat.feature.home.HomeScreen
import com.codeyantra.armorchat.feature.identity.CreateIdentityRoute
import com.codeyantra.armorchat.feature.identity.IdentityCreatedScreen
import com.codeyantra.armorchat.feature.identity.MyIdentityRoute
import com.codeyantra.armorchat.feature.identity.RestoreIdentityRoute
import com.codeyantra.armorchat.feature.onboarding.WelcomeScreen
import com.codeyantra.armorchat.feature.settings.AboutScreen
import com.codeyantra.armorchat.feature.settings.EmergencyMeshScreen
import com.codeyantra.armorchat.feature.settings.NetworkPrivacyScreen
import com.codeyantra.armorchat.feature.settings.OpenSourceLicensesScreen
import com.codeyantra.armorchat.feature.settings.PrivacySecuritySettingsScreen
import com.codeyantra.armorchat.feature.splash.SplashRoute
import com.codeyantra.armorchat.feature.support.SupportScreen
import kotlinx.coroutines.flow.StateFlow

@Composable
fun ArmorChatNavGraph(
    navController: NavHostController = rememberNavController(),
    pendingConversationIdFlow: StateFlow<String?>? = null,
    onPendingConversationConsumed: (() -> Unit)? = null,
) {
    var pendingCreatedIdentity by remember { mutableStateOf<UserIdentity?>(null) }
    val pendingConversationId by pendingConversationIdFlow?.collectAsStateWithLifecycle(null)
        ?: remember { mutableStateOf(null) }

    LaunchedEffect(pendingConversationId) {
        val targetId = pendingConversationId
        if (targetId != null) {
            navController.navigate(Routes.chat(targetId)) {
                launchSingleTop = true
            }
            onPendingConversationConsumed?.invoke()
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.Splash,
    ) {
        composable(Routes.Splash) {
            SplashRoute(
                onFinished = { hasIdentity ->
                    val dest = if (hasIdentity) Routes.Home else Routes.Welcome
                    navController.navigate(dest) {
                        popUpTo(Routes.Splash) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.Welcome) {
            WelcomeScreen(
                onCreateIdentity = { navController.navigate(Routes.CreateIdentity) },
                onRestoreIdentity = { navController.navigate(Routes.RestoreIdentity) },
            )
        }
        composable(Routes.CreateIdentity) {
            CreateIdentityRoute(
                onBack = { navController.popBackStack() },
                onCreated = { identity ->
                    pendingCreatedIdentity = identity
                    navController.navigate(Routes.IdentityCreated) {
                        popUpTo(Routes.Welcome) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.IdentityCreated) {
            val identity = pendingCreatedIdentity
            LaunchedEffect(identity) {
                if (identity == null) {
                    navController.navigate(Routes.Home) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            if (identity != null) {
                IdentityCreatedScreen(
                    identity = identity,
                    onContinue = {
                        navController.navigate(Routes.Home) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
        }
        composable(Routes.RestoreIdentity) {
            RestoreIdentityRoute(
                onBack = { navController.popBackStack() },
                onRestored = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Welcome) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.Home) {
            HomeScreen(
                onAbout = { navController.navigate(Routes.About) },
                onSupport = { navController.navigate(Routes.Support) },
                onMyIdentity = { navController.navigate(Routes.MyIdentity) },
                onPrivacySecurity = { navController.navigate(Routes.PrivacySecurity) },
                onNetworkPrivacy = { navController.navigate(Routes.NetworkPrivacy) },
                onEmergencyMesh = { navController.navigate(Routes.EmergencyMesh) },
                onTwoPhoneTest = if (BuildConfig.DEBUG) {
                    { navController.navigate(Routes.TwoPhoneTest) }
                } else {
                    null
                },
                onOpenConversation = { conversationId ->
                    navController.navigate(Routes.chat(conversationId))
                },
            )
        }
        composable(
            route = Routes.Chat,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) {
            ChatRoute(
                onBack = { navController.popBackStack() },
                onOpenContact = { contactId ->
                    navController.navigate(Routes.contactDetail(contactId))
                },
            )
        }
        composable(
            route = Routes.ContactDetail,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType }),
        ) {
            ContactDetailRoute(
                onBack = { navController.popBackStack() },
                onOpenConversation = { conversationId ->
                    navController.navigate(Routes.chat(conversationId)) {
                        // Prefer a single chat instance for this conversation.
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.MyIdentity) {
            MyIdentityRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.PrivacySecurity) {
            PrivacySecuritySettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.NetworkPrivacy) {
            NetworkPrivacyScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.EmergencyMesh) {
            EmergencyMeshScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.About) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onOpenSourceLicenses = { navController.navigate(Routes.OpenSourceLicenses) },
            )
        }
        composable(Routes.OpenSourceLicenses) {
            OpenSourceLicensesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Support) {
            SupportScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TwoPhoneTest) {
            TwoPhoneTestRoute(onBack = { navController.popBackStack() })
        }
    }
}
