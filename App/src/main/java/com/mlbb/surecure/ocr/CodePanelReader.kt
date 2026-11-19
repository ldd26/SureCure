package com.mlbb.surecure.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mlbb.surecure.rules.ean13Valid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device reading of the code-panel photo with ML Kit's bundled models (no network, no Play
 * services download): text recognition for the printed codes and barcode scanning (EAN-13 only)
 * for the bars. Returns suggestions only; CodePanelText does the extraction.
 */
object CodePanelReader {

    /**
     * The photo is decoded at the largest power-of-two reduction whose long side stays at least
     * this many pixels: about 12 MB instead of 48 MB for a 12 MP photo, while code-panel text stays
     * above ML Kit's 16 px per character guideline.
     */
    private const val MIN_LONG_SIDE_PX = 1920

    /**
     * Centre share of the photo (per side) scanned again at full resolution when the downsampled
     * photo shows no barcode: a barcode filling only part of the frame can drop below the about
     * 190 px an EAN-13 needs once the photo is downsampled.
     */
    private const val CENTRE_CROP = 0.6

    /** Largest side of the full-resolution centre crop; bigger photos are decoded at half size or less. */
    private const val MAX_CROP_SIDE_PX = 2600

    suspend fun read(context: Context, photo: Uri): CodePanelScan {
        val decoded = withContext(Dispatchers.IO) { decode(context, photo) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13).build()
        )
        try {
            val lines: List<String>
            var bars: List<String>
            try {
                val image = InputImage.fromBitmap(decoded.bitmap, decoded.rotation)
                // Recognized text, one string per line, in ML Kit's block order.
                lines = recognizer.process(image).await().textBlocks.flatMap { block -> block.lines.map { it.text } }
                bars = scanBars(scanner, image)
            } finally {
                decoded.bitmap.recycle()  // freed before the full-resolution crop below
            }
            if (decoded.sample > 1 && bars.none(::ean13Valid)) {
                bars = scanCentreAtFullResolution(context, photo, decoded.rotation, scanner)
            }
            return CodePanelText.scan(lines, bars)
        } finally {
            recognizer.close()
            scanner.close()
        }
    }

    /** EAN-13 values in [image]. A failed bar scan is not fatal: the printed digits are still read from the text. */
    private suspend fun scanBars(scanner: BarcodeScanner, image: InputImage): List<String> =
        runCatching { scanner.process(image).await().mapNotNull { it.rawValue } }
            .getOrElse { e -> if (e is CancellationException) throw e else emptyList() }

    /** Second barcode pass on the centre of the photo, decoded at (up to) full resolution. */
    private suspend fun scanCentreAtFullResolution(
        context: Context, photo: Uri, rotation: Int, scanner: BarcodeScanner,
    ): List<String> {
        val crop: Bitmap? = try {
            withContext(Dispatchers.IO) { decodeCentre(context, photo) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (crop == null) return emptyList()
        try {
            return scanBars(scanner, InputImage.fromBitmap(crop, rotation))
        } finally {
            crop.recycle()
        }
    }

    /** The centre [CENTRE_CROP] of the photo, at full resolution unless that would be too large. */
    @Suppress("DEPRECATION")  // newInstance(InputStream, Boolean) is the only form available on API 24 to 30
    private fun decodeCentre(context: Context, photo: Uri): Bitmap? {
        val stream = context.contentResolver.openInputStream(photo) ?: return null
        val decoder = stream.use { BitmapRegionDecoder.newInstance(it, false) } ?: return null
        try {
            val w = decoder.width
            val h = decoder.height
            val cw = (w * CENTRE_CROP).toInt()
            val ch = (h * CENTRE_CROP).toInt()
            val rect = Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2)
            var sample = 1
            while (maxOf(cw, ch) / sample > MAX_CROP_SIDE_PX) sample *= 2
            return decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            decoder.recycle()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

    /** A downsampled photo, the clockwise rotation that makes it upright, and the downsampling factor. */
    private class Decoded(val bitmap: Bitmap, val rotation: Int, val sample: Int)

    /** The photo, downsampled, and the clockwise rotation that makes it upright (from its EXIF tag). */
    private fun decode(context: Context, photo: Uri): Decoded {
        val resolver = context.contentResolver
        // Size only. decodeStream returns null by design here and fills in outWidth / outHeight,
        // so its result must not be read as a failure (that bug made every photo fail before
        // 2026-11-19).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(photo) ?: throw IOException("Can't open $photo")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) throw IOException("Not an image: $photo")
        var sample = 1
        while (longSide / (sample * 2) >= MIN_LONG_SIDE_PX) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(photo)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Can't decode $photo")
        val orientation = runCatching {
            resolver.openInputStream(photo)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        // Mirrored orientations (rare from a camera) can't be expressed as a rotation; their
        // rotation part is used and the text stays mirrored, so OCR finds little and the user types.
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE -> 270
            else -> 0
        }
        return Decoded(bitmap, rotation, sample)
    }
}
