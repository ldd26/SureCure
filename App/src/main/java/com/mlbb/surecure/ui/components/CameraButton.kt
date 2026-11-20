package com.mlbb.surecure.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mlbb.surecure.ui.theme.SureCureGreen

/**
 * The round green camera button, used on the dashboard (start a scan) and the camera screen (take
 * the photo). [busy] shows a spinner instead of the glyph, for example while a photo is saved.
 */
@Composable
fun CameraButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    size: Dp = 64.dp,
) {
    val active = enabled && !busy
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) SureCureGreen else SureCureGreen.copy(alpha = 0.45f))
            .border(width = 2.dp, color = Color.White.copy(alpha = 0.9f), shape = CircleShape)
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(size * 0.45f))
        } else {
            CameraGlyph(Modifier.size(size * 0.45f))
        }
    }
}

/** A camera outline: body, viewfinder bump and lens. */
@Composable
private fun CameraGlyph(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.width * 0.075f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(size.width * 0.08f, size.height * 0.28f),
            size = Size(size.width * 0.84f, size.height * 0.58f),
            cornerRadius = CornerRadius(size.width * 0.09f),
            style = Stroke(width = stroke)
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(size.width * 0.26f, size.height * 0.15f),
            size = Size(size.width * 0.28f, size.height * 0.18f),
            cornerRadius = CornerRadius(size.width * 0.04f)
        )
        drawCircle(
            color = Color.White,
            radius = size.width * 0.15f,
            center = Offset(size.width * 0.50f, size.height * 0.57f),
            style = Stroke(width = stroke)
        )
    }
}
