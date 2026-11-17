package com.mlbb.surecure.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR of the code-panel photo with ML Kit's bundled Latin model (no network, no Play
 * services download). Returns suggestions only; CodePanelText does the extraction.
 */
object CodePanelReader {

    /**
     * The photo is decoded at the largest power-of-two reduction whose long side stays at least
     * this many pixels: about 12 MB instead of 48 MB for a 12 MP photo, while code-panel text stays
     * above ML Kit's 16 px per character guideline.
     */
    private const val MIN_LONG_SIDE_PX = 1920

    suspend fun read(context: Context, photo: Uri): CodePanelReading =
        CodePanelText.read(recognizeLines(context, photo))

    /** Recognized text, one string per line, in ML Kit's block order. */
    private suspend fun recognizeLines(context: Context, photo: Uri): List<String> {
        val (bitmap, rotation) = withContext(Dispatchers.IO) { decode(context, photo) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromBitmap(bitmap, rotation)
            val text = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            return text.textBlocks.flatMap { block -> block.lines.map { it.text } }
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    /** The photo, downsampled, and the clockwise rotation that makes it upright (from its EXIF tag). */
    private fun decode(context: Context, photo: Uri): Pair<Bitmap, Int> {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(photo)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("Can't open $photo")
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
        return bitmap to rotation
    }
}
