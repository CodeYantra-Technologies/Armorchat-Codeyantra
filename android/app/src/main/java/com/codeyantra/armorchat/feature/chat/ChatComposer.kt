package com.codeyantra.armorchat.feature.chat

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.codeyantra.armorchat.domain.model.AttachmentMetadata
import com.codeyantra.armorchat.domain.model.Message
import com.codeyantra.armorchat.domain.model.MessageType
import java.io.File

data class PendingAttachment(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isImage: Boolean,
)

private class VoiceCapture {
    private var recorder: MediaRecorder? = null
    var file: File? = null
        private set

    fun start(context: Context): Boolean {
        cancel()
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.amr")
        return try {
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            rec.setAudioEncodingBitRate(12_200)
            rec.setAudioSamplingRate(8_000)
            rec.setAudioChannels(1)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            file = out
            true
        } catch (_: Exception) {
            runCatching { recorder?.release() }
            recorder = null
            out.delete()
            file = null
            false
        }
    }

    fun stop(): File? {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        val out = file
        file = null
        return out?.takeIf { it.exists() && it.length() > 0L }
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        file?.delete()
        file = null
    }
}

private fun voiceUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

@Composable
fun ChatComposer(
    draft: String,
    canSend: Boolean,
    onDraftChange: (String) -> Unit,
    onSendText: () -> Unit,
    onSendAttachment: (PendingAttachment) -> Unit = {},
    replyingToMessage: Message? = null,
    onCancelReply: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var pendingMicStart by remember { mutableStateOf(false) }
    val capture = remember { VoiceCapture() }

    DisposableEffect(Unit) {
        onDispose { capture.cancel() }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pendingMicStart = true else {
            Toast.makeText(context, "Microphone permission needed", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(pendingMicStart) {
        if (!pendingMicStart) return@LaunchedEffect
        pendingMicStart = false
        if (capture.start(context)) {
            recordSeconds = 0
            recording = true
        } else {
            Toast.makeText(context, "Could not start recording", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (recording) {
            kotlinx.coroutines.delay(1000)
            recordSeconds += 1
            if (recordSeconds >= 60) {
                val file = capture.stop()
                recording = false
                if (file != null) {
                    pendingAttachment = PendingAttachment(
                        uri = voiceUri(context, file),
                        name = "voice-note.amr",
                        mimeType = "audio/amr",
                        sizeBytes = file.length(),
                        isImage = false,
                    )
                }
            }
        }
    }

    fun processUri(uri: Uri, isPhoto: Boolean) {
        try {
            var name = if (isPhoto) "photo.jpg" else "document"
            var size = 0L
            val mime = context.contentResolver.getType(uri)
                ?: if (isPhoto) "image/jpeg" else "application/octet-stream"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
            pendingAttachment = PendingAttachment(
                uri = uri,
                name = name,
                mimeType = mime,
                sizeBytes = size,
                isImage = isPhoto || mime.startsWith("image/"),
            )
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open selected file", Toast.LENGTH_SHORT).show()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) processUri(uri, isPhoto = true) }
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) processUri(uri, isPhoto = false) }

    fun requestVoiceStart() {
        if (recording) return
        val granted = Build.VERSION.SDK_INT < 23 ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) pendingMicStart = true else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    fun finishRecording(sendNow: Boolean) {
        val file = capture.stop()
        recording = false
        if (file == null) return
        val pending = PendingAttachment(
            uri = voiceUri(context, file),
            name = "voice-note.amr",
            mimeType = "audio/amr",
            sizeBytes = file.length(),
            isImage = false,
        )
        if (sendNow) onSendAttachment(pending) else pendingAttachment = pending
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            replyingToMessage?.let { replyMsg ->
                val previewSnippet = when (replyMsg.type) {
                    MessageType.TEXT -> replyMsg.localPlaintext ?: "Message"
                    MessageType.IMAGE -> "Photo"
                    MessageType.VOICE -> "Voice message"
                    MessageType.DOCUMENT -> {
                        val meta = AttachmentMetadata.parseOrNull(replyMsg.localPlaintext)
                        meta?.originalName?.ifBlank { "Document" } ?: "Document"
                    }
                }
                val senderTitle = if (replyMsg.isOutgoing) "You" else "Contact"
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    color = colors.surfaceVariant.copy(alpha = 0.8f),
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(0.5.dp, colors.primary.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(colors.primary, MaterialTheme.shapes.small)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Reply,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = senderTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = previewSnippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = onCancelReply, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "Cancel reply", tint = colors.onSurfaceVariant)
                        }
                    }
                }
            }

            if (recording) {
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Recording ${recordSeconds}s",
                        color = colors.error,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        capture.cancel()
                        recording = false
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel recording")
                    }
                }
            }
            pendingAttachment?.let { attachment ->
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (attachment.isImage) Icons.Outlined.Image else Icons.Outlined.Description,
                        contentDescription = null,
                        tint = colors.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = attachment.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(onClick = { pendingAttachment = null }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove attachment")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(
                    onClick = { requestVoiceStart() },
                    enabled = canSend,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = "Voice note",
                        tint = if (recording) colors.error else if (canSend) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
                Box {
                    IconButton(
                        onClick = { showAttachmentMenu = true },
                        enabled = canSend && !recording,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AttachFile,
                            contentDescription = "Attach file",
                            tint = if (canSend) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                    DropdownMenu(
                        expanded = showAttachmentMenu,
                        onDismissRequest = { showAttachmentMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Photo") },
                            onClick = {
                                showAttachmentMenu = false
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Document / File") },
                            onClick = {
                                showAttachmentMenu = false
                                documentPickerLauncher.launch("*/*")
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Message…",
                            color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    },
                    maxLines = 5,
                    enabled = !recording,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = colors.surfaceVariant.copy(alpha = 0.5f),
                        disabledContainerColor = colors.surfaceVariant.copy(alpha = 0.3f),
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outline.copy(alpha = 0.4f),
                    ),
                )

                val hasSendAction = canSend && (draft.trim().isNotEmpty() || pendingAttachment != null || recording)

                FilledIconButton(
                    onClick = {
                        if (recording) {
                            finishRecording(sendNow = true)
                            return@FilledIconButton
                        }
                        val attachment = pendingAttachment
                        if (attachment != null) {
                            pendingAttachment = null
                            onSendAttachment(attachment)
                        } else {
                            onSendText()
                        }
                    },
                    enabled = hasSendAction,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                        disabledContainerColor = colors.surfaceVariant,
                        disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.4f),
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
