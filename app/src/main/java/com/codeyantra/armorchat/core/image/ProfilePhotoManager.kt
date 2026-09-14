package com.codeyantra.armorchat.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ProfilePhotoManager {
    private const val PHOTO_FILE_NAME = "profile_photo.jpg"

    fun getPhotoFile(context: Context): File {
        return File(context.filesDir, PHOTO_FILE_NAME)
    }

    fun hasPhoto(context: Context): Boolean {
        val file = getPhotoFile(context)
        return file.exists() && file.length() > 0L
    }

    suspend fun savePhotoFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext false
            val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return@withContext false
            inputStream.close()

            // Handle EXIF orientation
            val rotatedBitmap = try {
                val exifStream = context.contentResolver.openInputStream(uri)
                val exif = exifStream?.use { ExifInterface(it) }
                val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                }
                if (!matrix.isIdentity) {
                    Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
                } else {
                    originalBitmap
                }
            } catch (_: Exception) {
                originalBitmap
            }

            // Square crop
            val minEdge = kotlin.math.min(rotatedBitmap.width, rotatedBitmap.height)
            val cropX = (rotatedBitmap.width - minEdge) / 2
            val cropY = (rotatedBitmap.height - minEdge) / 2
            val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, cropX, cropY, minEdge, minEdge)

            // Scale to max 512x512
            val finalBitmap = if (minEdge > 512) {
                Bitmap.createScaledBitmap(croppedBitmap, 512, 512, true)
            } else {
                croppedBitmap
            }

            // Compress and save to app-owned filesDir
            val outFile = getPhotoFile(context)
            FileOutputStream(outFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun removePhoto(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = getPhotoFile(context)
        if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }
}
