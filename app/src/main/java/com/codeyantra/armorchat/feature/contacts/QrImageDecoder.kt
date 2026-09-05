package com.codeyantra.armorchat.feature.contacts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Decodes a QR from a user-picked image entirely on-device.
 * The bitmap and decoded text are not logged or persisted here.
 */
object QrImageDecoder {
    private const val MAX_EDGE = 2048

    fun decode(context: Context, uri: Uri): Result<String> = runCatching {
        val bitmap = loadBitmap(context, uri) ?: error("Could not open image")
        try {
            decodeBitmap(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun decodeBitmap(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        require(width > 0 && height > 0) { "Empty image" }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } finally {
            reader.reset()
        }
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / sample > MAX_EDGE || h / sample > MAX_EDGE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }
}
