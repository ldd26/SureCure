package com.mlbb.surecure.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The code-panel reader end to end on a device: JPEG file -> decode (downsampled, EXIF rotation)
 * -> ML Kit text recognition and barcode scanning -> CodePanelText. The panel is drawn here (label
 * text and a real EAN-13 barcode), so no image assets are needed. Run with
 * `./gradlew :app:connectedDebugAndroidTest` or the instrument command in the handoff.
 */
@RunWith(AndroidJUnit4::class)
class CodePanelReaderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun readsAnUprightPanel() {
        val scan = read(drawPanel(), exifOrientation = null)
        assertPanel(scan)
    }

    @Test
    fun readsAPanelStoredSidewaysWithItsExifRotation() {
        // Stored rotated 90 degrees counter-clockwise, tagged "rotate 90 clockwise to view", like a
        // phone held upright whose camera sensor is landscape.
        val sideways = drawPanel().let { b ->
            Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(-90f) }, true)
        }
        val scan = read(sideways, exifOrientation = ExifInterface.ORIENTATION_ROTATE_90)
        assertPanel(scan)
    }

    private fun assertPanel(scan: CodePanelScan) {
        val text = scan.lines.joinToString(" | ")
        assertEquals("barcode (lines: $text)", "4807788526762", scan.reading.barcode)
        assertTrue("barcode should come from the bars (lines: $text)", scan.barcodeFromBars)
        assertEquals("lot (lines: $text)", "23651568", scan.reading.lot)
        assertEquals("reg no (lines: $text)", "DR-XY37402", scan.reading.regNo)
        assertEquals("expiry (lines: $text)", "MAR 2028", scan.reading.expiry)
    }

    private fun read(bitmap: Bitmap, exifOrientation: Int?): CodePanelScan {
        val file = File(context.cacheDir, "code_panel_test_${System.nanoTime()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (exifOrientation != null) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                saveAttributes()
            }
        }
        try {
            return runBlocking { CodePanelReader.read(context, Uri.fromFile(file)) }
        } finally {
            file.delete()
        }
    }

    /** A 2400 x 1800 code panel, about a 4 MP phone photo, so the reader also downsamples it. */
    private fun drawPanel(): Bitmap {
        val bitmap = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 90f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("Reg. No.: DR-XY37402", 120f, 220f, paint)
        canvas.drawText("LOT NO.: 23651568", 120f, 380f, paint)
        canvas.drawText("EXP. MAR 2028", 120f, 540f, paint)
        drawEan13(canvas, "4807788526762", left = 300f, top = 760f, module = 12f, height = 600f)
        return bitmap
    }

    /** Draws an EAN-13 barcode (guard bars, left half in L/G codes by the first digit, right half in R codes). */
    private fun drawEan13(canvas: Canvas, code: String, left: Float, top: Float, module: Float, height: Float) {
        val l = listOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
        val g = listOf("0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101", "0010001", "0001001", "0010111")
        val r = listOf("1110010", "1100110", "1101100", "1000010", "1011100", "1001110", "1010000", "1000100", "1001000", "1110100")
        val parity = listOf("LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL")
        val d = code.map { it - '0' }
        val modules = buildString {
            append("101")
            for (i in 1..6) append(if (parity[d[0]][i - 1] == 'L') l[d[i]] else g[d[i]])
            append("01010")
            for (i in 7..12) append(r[d[i]])
            append("101")
        }
        val bar = Paint().apply { color = Color.BLACK }
        modules.forEachIndexed { i, m ->
            if (m == '1') canvas.drawRect(left + i * module, top, left + (i + 1) * module, top + height, bar)
        }
    }
}
