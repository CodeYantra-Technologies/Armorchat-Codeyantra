package com.codeyantra.armorchat.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeyantra.armorchat.core.image.ProfilePhotoManager
import java.io.File

@Composable
fun ArmorChatAvatar(
    displayName: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    photoFile: File? = null,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current

    val localPhoto = remember(photoFile, size) {
        val file = photoFile ?: ProfilePhotoManager.getPhotoFile(context)
        if (file.exists() && file.length() > 0L) {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        } else {
            null
        }
    }

    if (localPhoto != null) {
        Image(
            bitmap = localPhoto,
            contentDescription = "Profile Photo",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        val initialLetter = remember(displayName) {
            displayName?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString().orEmpty()
        }

        val palette = remember(colors) {
            listOf(
                colors.primaryContainer to colors.onPrimaryContainer,
                colors.secondaryContainer to colors.onSecondaryContainer,
                colors.tertiaryContainer to colors.onTertiaryContainer,
                colors.surfaceVariant to colors.onSurfaceVariant,
            )
        }

        val hash = remember(displayName) {
            val name = displayName.orEmpty()
            name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        }
        val (bgColor, textColor) = palette[hash % palette.size]

        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center,
        ) {
            // Shield Outline
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = colors.primary.copy(alpha = 0.35f),
                modifier = Modifier.size(size * 0.72f),
            )

            if (initialLetter.isNotBlank()) {
                Text(
                    text = initialLetter,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = (size.value * 0.38f).sp,
                    ),
                    color = textColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.offset(y = (-1).dp),
                )
            }
        }
    }
}
