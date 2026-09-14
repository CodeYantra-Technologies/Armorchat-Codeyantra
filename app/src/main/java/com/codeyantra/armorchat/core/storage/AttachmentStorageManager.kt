package com.codeyantra.armorchat.core.storage

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AttachmentStorageManager {
    private const val ATTACHMENTS_DIR = "attachments"

    fun getAttachmentFile(context: Context, attachmentId: String): File {
        val dir = File(context.filesDir, ATTACHMENTS_DIR).apply { if (!exists()) mkdirs() }
        return File(dir, attachmentId)
    }

    fun hasAttachment(context: Context, attachmentId: String): Boolean {
        val file = getAttachmentFile(context, attachmentId)
        return file.exists() && file.length() > 0L
    }

    suspend fun saveAttachment(context: Context, attachmentId: String, data: ByteArray): File =
        withContext(Dispatchers.IO) {
            val file = getAttachmentFile(context, attachmentId)
            file.writeBytes(data)
            file
        }
}
