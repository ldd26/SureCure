package com.mlbb.surecure.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR of the code-panel photo with ML Kit's bundled Latin model (no network, no Play
 * services download). Returns suggestions only; CodePanelText does the extraction.
 */
object CodePanelReader {

    suspend fun read(context: Context, photo: Uri): CodePanelReading =
        CodePanelText.read(recognizeLines(context, photo))

    /** Recognized text, one string per line, in ML Kit's block order. */
    private suspend fun recognizeLines(context: Context, photo: Uri): List<String> {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, photo) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            return text.textBlocks.flatMap { block -> block.lines.map { it.text } }
        } finally {
            recognizer.close()
        }
    }
}
