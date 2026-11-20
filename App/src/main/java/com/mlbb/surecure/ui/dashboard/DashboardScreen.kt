package com.mlbb.surecure.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import com.mlbb.surecure.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlbb.surecure.util.formatScanDate

import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource

private val SureCureMint = Color(0xFFDEFFE8)
private val SureCureDarkGreen = Color(0xFF02542D)
private val SureCureGreen = Color(0xFF14AE5C)
private val SureCureGray = Color(0xFF757575)
private val SureCureRed = Color(0xFFC00F0C)
private val UnsafeRowBackground = Color(0xFFFFE8E8)

/**
 * Native Jetpack Compose version of the dashboard exported from Figma.
 *
 * @param onCameraClick Called when the user taps the green camera button. Connect this
 * to your navigation flow (for example, navigate to CaptureScreen).
 * @param onScanClick Optional callback for a scan-history row.
 * @param sections Data to display. Defaults to sample content matching the HTML export.
 */
@Composable
fun DashboardScreen(
    sections: List<ScanHistorySection>,
    isRefreshing: Boolean = false,
    onCameraClick: () -> Unit = {},
    onRefreshClick: () -> Unit = {},
    onScanClick: (ScanHistoryEntry) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandHeader()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 30.dp)
        ) {
            Text(
                text = "Scan History",
                color = Color.Black,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 29.sp,
                modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)
            )

            sections.forEach { section ->
                Text(
                    text = section.timePeriod,
                    color = Color.Black,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )

                section.entries.forEach { entry ->
                    ScanHistoryRow(
                        entry = entry,
                        onClick = { onScanClick(entry) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }


        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(64.dp))

            // Replace CameraButton with your existing camera button composable.
            CameraButton(
                onClick = onCameraClick,
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onRefreshClick,
                enabled = !isRefreshing
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.refresh_icon),
                        contentDescription = "Refresh scan history"
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SureCureMint)
            .padding(horizontal = 20.dp, vertical = 25.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark()
        Spacer(modifier = Modifier.width(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Sure",
                color = SureCureDarkGreen,
                fontSize = 39.sp,
                lineHeight = 47.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp
            )
            Text(
                text = "Cure",
                color = SureCureGreen,
                fontSize = 39.sp,
                lineHeight = 47.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp
            )
        }
    }
}

@Composable
private fun BrandMark() {
    Image(
        painter = painterResource(
            id = R.drawable.logo
        ),
        contentDescription = "SureCure logo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(width = 50.dp, height = 50.dp)
    )
}

@Composable
private fun ScanHistoryRow(
    entry: ScanHistoryEntry,
    onClick: () -> Unit
) {
    val isSafe = entry.safety == ScanSafety.SAFE
    val statusColor = if (isSafe) SureCureGreen else SureCureRed
    val rowBackground = if (isSafe) Color.Transparent else UnsafeRowBackground

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MedicineThumbnail(entry = entry)
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isSafe) "No match with FDA records" else "Flagged: check with FDA",
                color = statusColor,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = formatScanDate(entry.scannedOn),
                color = SureCureGray,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MedicineThumbnail(entry: ScanHistoryEntry) {
//    Image(
//        painter = painterResource(
//            id = entry.
//        ),
//        contentDescription = "Medicine package thumbnail",
//        contentScale = ContentScale.Crop,
//        modifier = Modifier
//            .size(width = 58.dp, height = 48.dp)
//            .clip(RoundedCornerShape(7.dp))
//    )
    AsyncImage(
        model = entry.frontPhotoUri,
        contentDescription = "Medicine thumbnail",
        placeholder = painterResource(R.drawable.test_thumbnail),
        error = painterResource(R.drawable.test_thumbnail),
        modifier = Modifier.size(64.dp),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun CameraButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(SureCureGreen)
            .border(width = 1.dp, color = Color.Black, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CameraGlyph()
    }
}

/**
 * An imported glyph function that draws a Camera symbol.
 */
@Composable
private fun CameraGlyph() {
    Canvas(modifier = Modifier.size(29.dp)) {
        val stroke = size.width * 0.075f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(size.width * 0.08f, size.height * 0.28f),
            size = Size(size.width * 0.84f, size.height * 0.58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.09f),
            style = Stroke(width = stroke)
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(size.width * 0.26f, size.height * 0.15f),
            size = Size(size.width * 0.28f, size.height * 0.18f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.04f)
        )
        drawCircle(
            color = Color.White,
            radius = size.width * 0.15f,
            center = Offset(size.width * 0.50f, size.height * 0.57f),
            style = Stroke(width = stroke)
        )
    }
}
