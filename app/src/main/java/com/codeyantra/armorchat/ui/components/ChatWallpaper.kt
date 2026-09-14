package com.codeyantra.armorchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.rememberVectorPainter

private enum class WallpaperMotifType {
    SHIELD,
    LOCK,
    KEY,
    FINGERPRINT,
    CHAT,
    ENVELOPE,
    DEVICE,
    WIFI,
    SECURITY,
}

private data class WallpaperMotif(
    val xRatio: Float,
    val yRatio: Float,
    val sizeDp: Float,
    val rotation: Float,
    val type: WallpaperMotifType,
    val alphaFactor: Float,
)

// Rich, dense, irregular cyber-security motif distribution (non-grid)
private val DETERMINISTIC_MOTIFS = listOf(
    WallpaperMotif(0.12f, 0.04f, 42f, -14f, WallpaperMotifType.SHIELD, 1.0f),
    WallpaperMotif(0.78f, 0.06f, 36f, 18f, WallpaperMotifType.LOCK, 0.7f),
    WallpaperMotif(0.45f, 0.09f, 34f, -8f, WallpaperMotifType.KEY, 0.6f),
    WallpaperMotif(0.25f, 0.15f, 38f, 12f, WallpaperMotifType.FINGERPRINT, 0.65f),
    WallpaperMotif(0.88f, 0.18f, 32f, -22f, WallpaperMotifType.CHAT, 0.6f),
    WallpaperMotif(0.10f, 0.22f, 36f, 25f, WallpaperMotifType.ENVELOPE, 0.55f),
    WallpaperMotif(0.55f, 0.25f, 46f, -15f, WallpaperMotifType.SHIELD, 1.0f),
    WallpaperMotif(0.32f, 0.31f, 34f, 10f, WallpaperMotifType.DEVICE, 0.5f),
    WallpaperMotif(0.70f, 0.35f, 38f, -12f, WallpaperMotifType.WIFI, 0.6f),
    WallpaperMotif(0.15f, 0.40f, 44f, 16f, WallpaperMotifType.SECURITY, 0.7f),
    WallpaperMotif(0.50f, 0.44f, 36f, -18f, WallpaperMotifType.LOCK, 0.7f),
    WallpaperMotif(0.85f, 0.48f, 48f, 14f, WallpaperMotifType.SHIELD, 1.0f),
    WallpaperMotif(0.22f, 0.52f, 32f, -20f, WallpaperMotifType.KEY, 0.6f),
    WallpaperMotif(0.62f, 0.57f, 36f, 22f, WallpaperMotifType.FINGERPRINT, 0.65f),
    WallpaperMotif(0.08f, 0.62f, 38f, -10f, WallpaperMotifType.CHAT, 0.6f),
    WallpaperMotif(0.40f, 0.67f, 34f, 15f, WallpaperMotifType.ENVELOPE, 0.55f),
    WallpaperMotif(0.75f, 0.71f, 42f, -16f, WallpaperMotifType.SHIELD, 1.0f),
    WallpaperMotif(0.18f, 0.76f, 36f, 12f, WallpaperMotifType.DEVICE, 0.5f),
    WallpaperMotif(0.58f, 0.80f, 38f, -24f, WallpaperMotifType.WIFI, 0.6f),
    WallpaperMotif(0.90f, 0.83f, 34f, 18f, WallpaperMotifType.SECURITY, 0.7f),
    WallpaperMotif(0.30f, 0.88f, 46f, -14f, WallpaperMotifType.SHIELD, 1.0f),
    WallpaperMotif(0.72f, 0.92f, 34f, 10f, WallpaperMotifType.LOCK, 0.7f),
    WallpaperMotif(0.12f, 0.96f, 36f, -12f, WallpaperMotifType.KEY, 0.6f),
    WallpaperMotif(0.48f, 0.98f, 38f, 20f, WallpaperMotifType.FINGERPRINT, 0.65f),
)

@Composable
fun ChatWallpaper(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.3f

    // Motif vector painters cached via rememberVectorPainter
    val shieldPainter = rememberVectorPainter(Icons.Outlined.Shield)
    val lockPainter = rememberVectorPainter(Icons.Outlined.Lock)
    val keyPainter = rememberVectorPainter(Icons.Outlined.Key)
    val fingerprintPainter = rememberVectorPainter(Icons.Outlined.Fingerprint)
    val chatPainter = rememberVectorPainter(Icons.Outlined.ChatBubbleOutline)
    val envelopePainter = rememberVectorPainter(Icons.Outlined.Email)
    val devicePainter = rememberVectorPainter(Icons.Outlined.PhoneAndroid)
    val wifiPainter = rememberVectorPainter(Icons.Outlined.Wifi)
    val securityPainter = rememberVectorPainter(Icons.Outlined.Security)

    // Theme-specific background and icon colors
    val backgroundColor = if (isDark) Color(0xFF07101B) else Color(0xFFF0F4F8)
    val primaryShieldColor = if (isDark) Color(0xFF29C5F6) else Color(0xFF0077B6)
    val secondaryMotifColor = if (isDark) Color(0xFF16324D) else Color(0xFF7A9BB5)

    val shieldAlpha = if (isDark) 0.13f else 0.09f
    val secondaryAlpha = if (isDark) 0.06f else 0.045f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .drawBehind {
                val canvasWidth = size.width
                val canvasHeight = size.height

                for (motif in DETERMINISTIC_MOTIFS) {
                    val x = motif.xRatio * canvasWidth
                    val y = motif.yRatio * canvasHeight
                    val sizePx = motif.sizeDp * density

                    val isShield = motif.type == WallpaperMotifType.SHIELD
                    val motifColor = if (isShield) primaryShieldColor else secondaryMotifColor
                    val baseAlpha = if (isShield) shieldAlpha else secondaryAlpha
                    val finalAlpha = baseAlpha * motif.alphaFactor

                    val painter = when (motif.type) {
                        WallpaperMotifType.SHIELD -> shieldPainter
                        WallpaperMotifType.LOCK -> lockPainter
                        WallpaperMotifType.KEY -> keyPainter
                        WallpaperMotifType.FINGERPRINT -> fingerprintPainter
                        WallpaperMotifType.CHAT -> chatPainter
                        WallpaperMotifType.ENVELOPE -> envelopePainter
                        WallpaperMotifType.DEVICE -> devicePainter
                        WallpaperMotifType.WIFI -> wifiPainter
                        WallpaperMotifType.SECURITY -> securityPainter
                    }

                    withTransform({
                        translate(left = x - sizePx / 2f, top = y - sizePx / 2f)
                        rotate(degrees = motif.rotation, pivot = Offset(sizePx / 2f, sizePx / 2f))
                    }) {
                        with(painter) {
                            draw(
                                size = Size(sizePx, sizePx),
                                alpha = finalAlpha,
                                colorFilter = ColorFilter.tint(motifColor),
                            )
                        }
                    }
                }
            },
    ) {
        content()
    }
}
