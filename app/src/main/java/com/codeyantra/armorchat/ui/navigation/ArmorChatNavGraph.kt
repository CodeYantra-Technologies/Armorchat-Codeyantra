package com.codeyantra.armorchat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.feature.home.HomeScreen
import com.codeyantra.armorchat.feature.identity.CreateIdentityRoute
import com.codeyantra.armorchat.feature.identity.IdentityCreatedScreen
import com.codeyantra.armorchat.feature.identity.MyIdentityRoute
import com.codeyantra.armorchat.feature.identity.RestoreIdentityRoute
import com.codeyantra.armorchat.feature.onboarding.WelcomeScreen
import com.codeyantra.armorchat.feature.settings.AboutScreen
import com.codeyantra.armorchat.feature.settings.OpenSourceLicensesScreen
import com.codeyantra.armorchat.feature.splash.SplashRoute
import com.codeyantra.armorchat.feature.support.SupportScreen

@Composable
fun ArmorChatNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    var pendingCreatedIdentity by remember { mutableStateOf<UserIdentity?>(null) }

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
                        popUpTo(Routes.CreateIdentity) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.IdentityCreated) {
            val identity = pendingCreatedIdentity
            if (identity == null) {
                // Guard against process death without saved state — return to welcome.
                navController.navigate(Routes.Welcome) {
                    popUpTo(Routes.IdentityCreated) { inclusive = true }
                }
            } else {
                IdentityCreatedScreen(
                    identity = identity,
                    onContinue = {
                        navController.navigate(Routes.Home) {
                            popUpTo(Routes.Welcome) { inclusive = true }
                        }
                        pendingCreatedIdentity = null
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
            )
        }
        composable(Routes.MyIdentity) {
            MyIdentityRoute(onBack = { navController.popBackStack() })
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
    }
}
