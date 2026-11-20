package com.mlbb.surecure.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A captured photo shown on screen, decoded small (long side about 1080 px, roughly 1 MB instead of
 * 48 MB for a 12 MP photo) and turned upright from its EXIF tag. ImageView.setImageURI, used before
 * 2026-11-20, decoded the full photo and ignored the tag, so portrait shots could show sideways.
 */
@Composable
fun PhotoThumbnail(uri: Uri, contentDescription: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var image by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        try {
            image = withContext(Dispatchers.IO) { decodeUpright(context, uri, MAX_SIDE_PX) }?.asImageBitmap()
            failed = image == null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        } catch (e: OutOfMemoryError) {
            failed = true
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFEFEFEF)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = image
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize()
            )
            failed -> Text("Photo couldn't be shown", style = MaterialTheme.typography.bodySmall)
            else -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

private const val MAX_SIDE_PX = 1080

/** The photo at most [maxSide] px on its long side (power-of-two downsampling), rotated upright. */
private fun decodeUpright(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // decodeStream returns null by design for a size-only decode; only the bounds matter here, so
    // the null check is on the stream, never on decodeStream's result.
    val boundsStream = resolver.openInputStream(uri) ?: return null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longSide = maxOf(bounds.outWidth, bounds.outHeight)
    if (longSide <= 0) return null
    var sample = 1
    while (longSide / (sample * 2) >= maxSide) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        .also { if (it !== bitmap) bitmap.recycle() }
}
