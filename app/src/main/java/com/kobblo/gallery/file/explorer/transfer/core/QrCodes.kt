package com.kobblo.gallery.file.explorer.transfer.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code's modules, row by row, with no margin. The screen draws the margin it wants. */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
}

object QrCodes {
    fun encode(text: String): QrMatrix {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0
        )
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = bits.width
        return QrMatrix(size, BooleanArray(size * size) { bits[it % size, it / size] })
    }

    /** Reads a QR code out of packed ARGB pixels, or returns null if there isn't a readable one. */
    fun decode(width: Int, height: Int, argb: IntArray): String? =
        decode(RGBLuminanceSource(width, height, argb))

    fun decode(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
        // The first suits photos of screens, the second flat images. Light-on-dark codes need the last.
        val attempts = listOf(
            BinaryBitmap(HybridBinarizer(source)),
            BinaryBitmap(GlobalHistogramBinarizer(source)),
            BinaryBitmap(HybridBinarizer(source.invert()))
        )
        for (bitmap in attempts) {
            try {
                return reader.decodeWithState(bitmap).text
            } catch (e: NotFoundException) {
                reader.reset()
            } catch (e: ReaderException) {
                reader.reset()
            }
        }
        return null
    }
}
