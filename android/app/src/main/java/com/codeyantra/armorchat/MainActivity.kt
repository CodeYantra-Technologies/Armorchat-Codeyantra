package com.codeyantra.armorchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.core.security.AppLockManager
import com.codeyantra.armorchat.feature.lock.LockScreen
import com.codeyantra.armorchat.notification.ArmorChatNotificationManager
import com.codeyantra.armorchat.ui.navigation.ArmorChatNavGraph
import com.codeyantra.armorchat.ui.theme.ArmorChatTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appLockManager: AppLockManager

    private val _pendingConversationId = MutableStateFlow<String?>(null)
    val pendingConversationId = _pendingConversationId.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            ArmorChatTheme {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { _ -> }
                    LaunchedEffect(Unit) {
                        val granted = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                val isLocked by appLockManager.isLocked.collectAsStateWithLifecycle(false)
                val appLockType by appLockManager.appLockType.collectAsStateWithLifecycle(com.codeyantra.armorchat.domain.model.AppLockType.PIN)
                val biometricEnabled by appLockManager.biometricEnabled.collectAsStateWithLifecycle(false)
                val obscureRecents by appLockManager.obscureRecents.collectAsStateWithLifecycle(false)
                val cooldownSeconds by appLockManager.remainingCooldownSeconds.collectAsStateWithLifecycle(0)

                val scope = rememberCoroutineScope()
                var errorMessage by remember { mutableStateOf<String?>(null) }

                val biometricManager = remember { BiometricManager.from(this@MainActivity) }
                var biometricAvailable by remember { mutableStateOf(false) }

                LaunchedEffect(biometricEnabled) {
                    if (biometricEnabled) {
                        biometricAvailable = withContext(Dispatchers.Default) {
                            biometricManager.canAuthenticate(
                                BiometricManager.Authenticators.BIOMETRIC_STRONG,
                            ) == BiometricManager.BIOMETRIC_SUCCESS
                        }
                    } else {
                        biometricAvailable = false
                    }
                }

                LaunchedEffect(obscureRecents) {
                    if (obscureRecents) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE,
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }

                if (isLocked) {
                    LockScreen(
                        appLockType = appLockType,
                        biometricAvailable = biometricAvailable,
                        cooldownSeconds = cooldownSeconds,
                        errorMessage = errorMessage,
                        onUnlockCandidate = { candidate ->
                            scope.launch {
                                errorMessage = null
                                val valid = appLockManager.verifyCandidate(candidate)
                                if (!valid && cooldownSeconds == 0) {
                                    errorMessage = "Incorrect PIN or Password"
                                }
                            }
                        },
                        onBiometricUnlock = {
                            showBiometricPrompt {
                                appLockManager.unlockWithBiometric()
                            }
                        },
                    )
                } else {
                    ArmorChatNavGraph(
                        pendingConversationIdFlow = pendingConversationId,
                        onPendingConversationConsumed = {
                            _pendingConversationId.value = null
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock ArmorChat")
            .setSubtitle("Locally secured on this device")
            .setNegativeButtonText("Use PIN/Password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun handleIntent(intent: Intent?) {
        val convId = intent?.getStringExtra(ArmorChatNotificationManager.EXTRA_CONVERSATION_ID)
        if (!convId.isNull_or_blank()) {
            _pendingConversationId.value = convId
        }
    }

    private fun String?.isNull_or_blank(): Boolean {
        return this == null || this.trim().isEmpty()
    }
}
