package com.codeyantra.armorchat.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.codeyantra.armorchat.core.storage.AttachmentStorageManager
import com.codeyantra.armorchat.domain.model.AttachmentMetadata
import com.codeyantra.armorchat.domain.model.Message
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.codeyantra.armorchat.domain.model.MessageType
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun MessageDateSeparator(label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.3f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = if (isDark) Color(0xFF0E2338) else Color(0xBBDFEBF2),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(0.5.dp, colors.outline.copy(alpha = 0.2f)),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    onDownloadAttachment: (AttachmentMetadata, (Boolean) -> Unit) -> Unit = { _, _ -> },
    onReply: (Message) -> Unit = {},
    onOpenMessageInfo: (Message) -> Unit = {},
    onTapQuote: (String) -> Unit = {},
    isHighlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val outgoing = message.isOutgoing
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.3f
    val context = LocalContext.current

    val bubbleColor = if (outgoing) {
        if (isDark) Color(0xFF0E3F58) else Color(0xFF0077B6)
    } else {
        if (isDark) Color(0xFF14283B) else Color(0xFFFFFFFF)
    }

    val textColor = if (outgoing) {
        Color.White
    } else {
        if (isDark) Color(0xFFF2F7FB) else Color(0xFF0A1C2A)
    }

    val bubbleShape = if (outgoing) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    val attachmentMetadata = remember(message.localPlaintext) {
        AttachmentMetadata.parseOrNull(message.localPlaintext)
    }

    var fullScreenImageFile by remember { mutableStateOf<File?>(null) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val triggerPx = with(density) { 60.dp.toPx() }
    val maxPx = with(density) { 90.dp.toPx() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .wrapContentWidth(if (outgoing) Alignment.End else Alignment.Start),
        ) {
            Surface(
                color = if (isHighlighted) colors.primary.copy(alpha = 0.3f) else bubbleColor,
                shape = bubbleShape,
                border = if (isHighlighted) BorderStroke(1.5.dp, colors.primary) else if (!outgoing && !isDark) BorderStroke(0.5.dp, Color(0xFFD4E3EB)) else null,
                tonalElevation = if (!outgoing) 1.dp else 2.dp,
                shadowElevation = 1.dp,
                modifier = Modifier
                    .offset { IntOffset(offsetX.toInt(), 0) }
                    .pointerInput(message.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (offsetX >= triggerPx) {
                                    onReply(message)
                                }
                                offsetX = 0f
                            },
                            onDragCancel = {
                                offsetX = 0f
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                if (dragAmount > 0) {
                                    offsetX = (offsetX + dragAmount).coerceIn(0f, maxPx)
                                } else if (offsetX > 0) {
                                    offsetX = (offsetX + dragAmount).coerceIn(0f, maxPx)
                                }
                            }
                        )
                    }
                    .clip(bubbleShape)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            showActionsMenu = true
                        },
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
                ) {
                    message.replyTo?.let { reply ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onTapQuote(reply.messageId) },
                            color = if (outgoing) Color.Black.copy(alpha = 0.2f) else colors.surfaceVariant.copy(alpha = 0.6f),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(28.dp)
                                        .background(if (outgoing) Color.White else colors.primary, MaterialTheme.shapes.small)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = reply.senderName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (outgoing) Color.White else colors.primary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = reply.preview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (outgoing) Color.White.copy(alpha = 0.85f) else colors.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    if (attachmentMetadata != null) {
                        AttachmentContent(
                            metadata = attachmentMetadata,
                            isOutgoing = outgoing,
                            messageType = message.type,
                            onDownloadAttachment = onDownloadAttachment,
                            onOpenImage = { file -> fullScreenImageFile = file },
                        )
                    } else {
                        Text(
                            text = message.localPlaintext.orEmpty().ifBlank { "—" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAtEpochMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (outgoing) Color.White.copy(alpha = 0.75f) else colors.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                        if (outgoing) {
                            MessageStatusIndicator(status = message.status)
                        }
                    }
                }
            }

            DropdownMenu(
                expanded = showActionsMenu,
                onDismissRequest = { showActionsMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Reply") },
                    onClick = {
                        showActionsMenu = false
                        onReply(message)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Message info") },
                    onClick = {
                        showActionsMenu = false
                        onOpenMessageInfo(message)
                    },
                )
                val text = message.localPlaintext.orEmpty()
                if (text.isNotBlank() && attachmentMetadata == null) {
                    DropdownMenuItem(
                        text = { Text("Copy text") },
                        onClick = {
                            showActionsMenu = false
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
                            Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }

    fullScreenImageFile?.let { file ->
        Dialog(
            onDismissRequest = { fullScreenImageFile = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val bitmap = remember(file) {
                        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            text = "Failed to load image",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    IconButton(
                        onClick = { fullScreenImageFile = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentContent(
    metadata: AttachmentMetadata,
    isOutgoing: Boolean,
    messageType: MessageType,
    onDownloadAttachment: (AttachmentMetadata, (Boolean) -> Unit) -> Unit,
    onOpenImage: (File) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.3f
    val context = LocalContext.current
    val file = remember(metadata.attachmentId) {
        AttachmentStorageManager.getAttachmentFile(context, metadata.attachmentId)
    }
    val exists = remember(metadata.attachmentId, file.exists(), file.length()) {
        AttachmentStorageManager.hasAttachment(context, metadata.attachmentId)
    }
    var downloadState by remember { mutableStateOf<String?>(null) }

    val isVoice = messageType == MessageType.VOICE ||
        metadata.mediaClass == "voice" ||
        metadata.mimeType.startsWith("audio/")

    if (isVoice) {
        VoiceNoteBar(
            metadata = metadata,
            isOutgoing = isOutgoing,
            exists = exists,
            downloadState = downloadState,
            onDownload = {
                downloadState = "DOWNLOADING"
                onDownloadAttachment(metadata) { success ->
                    downloadState = if (success) "DONE" else "ERROR"
                }
            },
        )
    } else if (metadata.mediaClass == "image" || metadata.mimeType.startsWith("image/")) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (exists) {
                val bitmap = remember(file) {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = metadata.originalName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenImage(file) },
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(colors.surfaceVariant, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Invalid image file", color = colors.onSurfaceVariant)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(colors.surfaceVariant, RoundedCornerShape(12.dp))
                        .clickable {
                            downloadState = "DOWNLOADING"
                            onDownloadAttachment(metadata) { success ->
                                downloadState = if (success) "DONE" else "ERROR"
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (downloadState == "DOWNLOADING") {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                contentDescription = "Download image",
                                tint = if (isOutgoing) Color.White else colors.primary,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (downloadState == "ERROR") "Couldn't verify attachment" else "Tap to download photo",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isOutgoing) Color.White.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (metadata.originalName.isNotBlank() && metadata.originalName != "photo.jpg") {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = metadata.originalName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOutgoing) Color.White.copy(alpha = 0.75f) else colors.onSurfaceVariant,
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isOutgoing) Color.Black.copy(alpha = 0.15f) else colors.surfaceVariant.copy(alpha = 0.5f))
                .clickable {
                    if (exists) {
                        openFile(context, file, metadata.mimeType)
                    } else {
                        downloadState = "DOWNLOADING"
                        onDownloadAttachment(metadata) { success ->
                            downloadState = if (success) "DONE" else "ERROR"
                        }
                    }
                }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (metadata.mimeType == "application/pdf") Icons.Outlined.PictureAsPdf else Icons.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = if (isOutgoing) Color.White else colors.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = metadata.originalName.ifBlank { "Document" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isOutgoing) Color.White else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (exists) formatFileSize(metadata.sizeBytes) else (if (downloadState == "ERROR") "Couldn't verify attachment" else "Download • ${formatFileSize(metadata.sizeBytes)}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (downloadState == "ERROR") colors.error else (if (isOutgoing) Color.White.copy(alpha = 0.8f) else colors.onSurfaceVariant),
                )
            }
            if (downloadState == "DOWNLOADING") {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = if (isOutgoing) Color.White else colors.primary,
                )
            } else if (!exists) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = "Download attachment",
                    tint = if (isOutgoing) Color.White else colors.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun VoiceNoteBar(
    metadata: AttachmentMetadata,
    isOutgoing: Boolean,
    exists: Boolean,
    downloadState: String?,
    onDownload: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.3f
    val context = LocalContext.current
    val file = remember(metadata.attachmentId) {
        AttachmentStorageManager.getAttachmentFile(context, metadata.attachmentId)
    }

    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.stop(); player?.release() }
            player = null
        }
    }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (playing) {
            kotlinx.coroutines.delay(200)
            player?.let { p ->
                if (p.isPlaying) {
                    progress = p.currentPosition.toFloat() / p.duration.coerceAtLeast(1).toFloat()
                } else {
                    playing = false
                    progress = 0f
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isOutgoing) Color.Black.copy(alpha = 0.15f) else colors.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                if (!exists) {
                    onDownload()
                    return@IconButton
                }
                if (playing) {
                    runCatching { player?.pause() }
                    playing = false
                } else {
                    runCatching {
                        player?.release()
                        player = android.media.MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            prepare()
                            start()
                            setOnCompletionListener {
                                playing = false
                                progress = 0f
                            }
                        }
                        playing = true
                    }
                }
            },
            modifier = Modifier.size(36.dp),
        ) {
            if (downloadState == "DOWNLOADING") {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = if (isOutgoing) Color.White else colors.primary)
            } else {
                Icon(
                    imageVector = if (playing) Icons.Outlined.Pause else (if (!exists) Icons.Outlined.FileDownload else Icons.Outlined.PlayArrow),
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = if (isOutgoing) Color.White else colors.primary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (!exists) "Voice message — tap to download" else "Voice message",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isOutgoing) Color.White else colors.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (isOutgoing) Color.White else colors.primary,
                trackColor = if (isOutgoing) Color.White.copy(alpha = 0.25f) else colors.surfaceVariant,
            )
        }
    }
}

@Composable
fun MessageStatusIndicator(status: MessageStatus) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.3f
    val mutedColor = Color.White.copy(alpha = 0.75f)
    val readColor = if (isDark) Color(0xFF29C5F6) else Color(0xFF64D8FF)

    when (status) {
        MessageStatus.SENDING -> Icon(Icons.Outlined.Schedule, "Sending", Modifier.size(14.dp), tint = mutedColor)
        MessageStatus.ENCRYPTING -> Icon(Icons.Outlined.Schedule, "Encrypting", Modifier.size(14.dp), tint = mutedColor)
        MessageStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, "Failed", Modifier.size(14.dp), tint = colors.error)
        MessageStatus.QUEUED -> Icon(Icons.Outlined.AccessTime, "Queued", Modifier.size(14.dp), tint = mutedColor)
        MessageStatus.SENT -> Icon(Icons.Outlined.Check, "Sent", Modifier.size(14.dp), tint = mutedColor)
        MessageStatus.DELIVERED -> Icon(Icons.Outlined.DoneAll, "Delivered", Modifier.size(14.dp), tint = mutedColor)
        MessageStatus.READ -> Icon(Icons.Outlined.DoneAll, "Read", Modifier.size(14.dp), tint = readColor)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.1f MB", mb)
}

private fun openFile(context: Context, file: File, mimeType: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "No app found to open file", Toast.LENGTH_SHORT).show()
    }
}
