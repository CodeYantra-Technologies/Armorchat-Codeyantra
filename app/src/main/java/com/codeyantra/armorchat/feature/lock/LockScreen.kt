package com.codeyantra.armorchat.feature.lock

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.domain.model.AppLockType
import com.codeyantra.armorchat.ui.components.BrandMark

@Composable
fun LockScreen(
    appLockType: AppLockType,
    biometricAvailable: Boolean,
    cooldownSeconds: Int,
    errorMessage: String?,
    onUnlockCandidate: (String) -> Unit,
    onBiometricUnlock: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var pinInput by rememberSaveable { mutableStateOf("") }
    var passwordInput by rememberSaveable { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            BrandMark(size = 80.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Unlock ArmorChat",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Locally secured conversation storage",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            if (cooldownSeconds > 0) {
                Surface(
                    color = colors.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Text(
                        text = "Too many attempts. Try again in ${cooldownSeconds}s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            if (appLockType == AppLockType.PIN) {
                PinDotsIndicator(pinLength = pinInput.length)
                Spacer(Modifier.height(32.dp))
                PinKeypad(
                    enabled = cooldownSeconds == 0,
                    onDigit = { digit ->
                        if (pinInput.length < 12) {
                            val newPin = pinInput + digit
                            pinInput = newPin
                            if (newPin.length >= 6) {
                                onUnlockCandidate(newPin)
                            }
                        }
                    },
                    onBackspace = {
                        if (pinInput.isNotEmpty()) {
                            pinInput = pinInput.dropLast(1)
                        }
                    },
                )
            } else {
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = cooldownSeconds == 0,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onUnlockCandidate(passwordInput) },
                    enabled = cooldownSeconds == 0 && passwordInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    ),
                ) {
                    Text("Unlock", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(Modifier.weight(1f))

            if (biometricAvailable && cooldownSeconds == 0) {
                IconButton(
                    onClick = onBiometricUnlock,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Fingerprint,
                        contentDescription = "Unlock with Biometrics",
                        tint = colors.primary,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Text(
                text = stringResource(R.string.brand_secured),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PinDotsIndicator(pinLength: Int) {
    val colors = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val totalDots = maxOf(6, pinLength)
        for (i in 0 until totalDots) {
            val filled = i < pinLength
            Box(
                modifier = Modifier
                    .size(if (filled) 16.dp else 14.dp)
                    .clip(CircleShape)
                    .background(if (filled) colors.primary else colors.surfaceVariant),
            )
        }
    }
}

@Composable
private fun PinKeypad(
    enabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val digits = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "back"),
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (row in digits) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                for (cell in row) {
                    when (cell) {
                        "" -> Spacer(Modifier.size(72.dp))
                        "back" -> KeypadButton(enabled = enabled, onClick = onBackspace) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Backspace,
                                contentDescription = "Backspace",
                                tint = colors.onSurface,
                            )
                        }
                        else -> KeypadButton(enabled = enabled, onClick = { onDigit(cell) }) {
                            Text(
                                text = cell,
                                style = MaterialTheme.typography.headlineMedium,
                                color = colors.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(colors.surfaceVariant.copy(alpha = if (enabled) 0.6f else 0.2f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
