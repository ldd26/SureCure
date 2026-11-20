package com.mlbb.surecure.ui.camera

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mlbb.surecure.ui.components.PhotoThumbnail

@Composable
fun PhotoReviewScreen(
    frontPhoto: Uri,
    codePanelPhoto: Uri,
    onRetake: () -> Unit,
    onDone: () -> Unit
) {
    // Below the status bar and above the home bar; width capped on tablets and in landscape.
    Box(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Review your photos",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Medicine front",
            style = MaterialTheme.typography.titleMedium
        )

        CapturedPhoto(
            uri = frontPhoto,
            contentDescription = "Medicine front photo"
        )

        Text(
            text = "Barcode, lot number and expiry panel",
            style = MaterialTheme.typography.titleMedium
        )

        CapturedPhoto(
            uri = codePanelPhoto,
            contentDescription = "Medicine code panel photo"
        )

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Check this medicine")
        }

        OutlinedButton(
            onClick = onRetake,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Retake photos")
        }

        Text(
            text = "The images are saved locally.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    }
}

@Composable
private fun CapturedPhoto(
    uri: Uri,
    contentDescription: String
) {
    PhotoThumbnail(
        uri = uri,
        contentDescription = contentDescription,
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
    )
}
