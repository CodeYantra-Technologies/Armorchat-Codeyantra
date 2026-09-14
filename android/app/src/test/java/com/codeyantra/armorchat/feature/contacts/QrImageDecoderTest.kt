package com.codeyantra.armorchat.feature.contacts

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class QrImageDecoderTest {
    @Test
    fun decodesQrFromBitmap() {
        val payload = """{"p":"armorchat","v":1,"id":"ac1:TEST","pk":"x"}"""
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        assertEquals(payload, QrImageDecoder.decodeBitmap(bitmap))
    }

    @Test
    fun rejectsBlankBitmap() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val result = runCatching { QrImageDecoder.decodeBitmap(bitmap) }
        assertTrue(result.isFailure)
    }
}
