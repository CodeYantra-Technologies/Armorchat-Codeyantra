package com.codeyantra.armorchat.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codeyantra.armorchat.domain.model.AttachmentMetadata
import com.codeyantra.armorchat.domain.model.Message
import com.codeyantra.armorchat.domain.model.MessageType
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInfoBottomSheet(
    message: Message,
    contactName: String,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = "Message info",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = if (message.isOutgoing) "You" else contactName,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = getMessagePreviewSnippet(message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            if (message.isOutgoing) {
                TimelineRow(
                    icon = Icons.Outlined.Check,
                    label = "Sent",
                    time = dateFormat.format(Date(message.createdAtEpochMs)),
                    isDone = true,
                )
                Spacer(Modifier.height(16.dp))
                TimelineRow(
                    icon = Icons.Outlined.DoneAll,
                    label = "Delivered",
                    time = message.deliveredAtEpochMs?.let { dateFormat.format(Date(it)) } ?: "Waiting…",
                    isDone = message.deliveredAtEpochMs != null,
                )
                Spacer(Modifier.height(16.dp))
                TimelineRow(
                    icon = Icons.Outlined.DoneAll,
                    label = "Read",
                    time = message.readAtEpochMs?.let { dateFormat.format(Date(it)) } ?: "Not read yet",
                    isDone = message.readAtEpochMs != null,
                    highlightColor = if (message.readAtEpochMs != null) Color(0xFF29C5F6) else null,
                )
            } else {
                TimelineRow(
                    icon = Icons.Outlined.Check,
                    label = "Received",
                    time = dateFormat.format(Date(message.createdAtEpochMs)),
                    isDone = true,
                )
                Spacer(Modifier.height(16.dp))
                TimelineRow(
                    icon = Icons.Outlined.DoneAll,
                    label = "Read locally",
                    time = message.readAtEpochMs?.let { dateFormat.format(Date(it)) } ?: "Not read yet",
                    isDone = message.readAtEpochMs != null,
                    highlightColor = if (message.readAtEpochMs != null) Color(0xFF29C5F6) else null,
                )
            }

            Spacer(Modifier.height(32.dp))
            Text(
                text = "Delivery and read confirmations are authenticated by the recipient device.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TimelineRow(
    icon: ImageVector,
    label: String,
    time: String,
    isDone: Boolean,
    highlightColor: Color? = null,
) {
    val colors = MaterialTheme.colorScheme
    val activeColor = highlightColor ?: if (isDone) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = activeColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = time,
                style = MaterialTheme.typography.bodySmall,
                color = activeColor,
            )
        }
    }
}

private fun getMessagePreviewSnippet(msg: Message): String {
    return when (msg.type) {
        MessageType.TEXT -> msg.localPlaintext ?: "Message"
        MessageType.IMAGE -> "Photo"
        MessageType.VOICE -> "Voice message"
        MessageType.DOCUMENT -> {
            val meta = AttachmentMetadata.parseOrNull(msg.localPlaintext)
            meta?.originalName?.ifBlank { "Document" } ?: "Document"
        }
    }
}
