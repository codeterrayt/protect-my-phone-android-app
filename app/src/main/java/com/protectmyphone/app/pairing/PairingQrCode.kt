package com.protectmyphone.app.pairing

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.Locale

/** QR payloads contain a public verification fingerprint only; pairing keys never leave memory. */
object PairingQrCode {
    private const val PREFIX = "PROTECT-MY-PHONE:PAIRING-FINGERPRINT:1:"
    private val fingerprintPattern = Regex("^[A-Fa-f0-9]{64}$")

    fun payload(fingerprint: String): String {
        require(fingerprintPattern.matches(fingerprint)) { "Fingerprint must be 64 hexadecimal characters." }
        return PREFIX + fingerprint.uppercase(Locale.ROOT)
    }

    fun parse(rawValue: String?): String? {
        val raw = rawValue?.trim() ?: return null
        if (!raw.startsWith(PREFIX, ignoreCase = true)) return null
        val fingerprint = raw.substring(PREFIX.length).uppercase(Locale.ROOT)
        return fingerprint.takeIf(fingerprintPattern::matches)
    }

    fun createBitmap(fingerprint: String, size: Int = 640): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(payload(fingerprint), BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (matrix[x, y]) 0xFF172A2B.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }
}
