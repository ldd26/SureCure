package com.mlbb.surecure.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import com.mlbb.surecure.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlbb.surecure.util.formatScanDate
import com.mlbb.surecure.ui.components.CameraButton
import com.mlbb.surecure.ui.theme.RedBackground
import com.mlbb.surecure.ui.theme.SureCureDarkGreen
import com.mlbb.surecure.ui.theme.SureCureGray
import com.mlbb.surecure.ui.theme.SureCureGreen
import com.mlbb.surecure.ui.theme.SureCureMint
import com.mlbb.surecure.ui.theme.SureCureRed

/**
 * The home screen: brand header, scan history (from ScanHistoryRepository, grouped by month) and the
 * camera button that starts a scan.
 *
 * @param onCameraClick Called when the user taps the green camera button.
 * @param onScanClick Called when the user taps a scan-history row.
 * @param sections Scan history to show, newest month first.
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
                // Capped width so rows don't stretch across tablets and landscape screens.
                .widthIn(max = 640.dp)
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
            // Above the home bar / navigation buttons (the app draws edge to edge).
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(64.dp))

            CameraButton(onClick = onCameraClick, contentDescription = "Scan a medicine")

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
            // The mint header runs under the status bar; its content stays below it.
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark()
        Spacer(modifier = Modifier.width(7.dp))
        // The wordmark is a logo: its size is fixed in dp so large font settings can't push it off
        // narrow screens.
        val wordmark = with(LocalDensity.current) { 39.dp.toSp() }
        val wordmarkLine = with(LocalDensity.current) { 47.dp.toSp() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Sure",
                color = SureCureDarkGreen,
                fontSize = wordmark,
                lineHeight = wordmarkLine,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp,
                maxLines = 1
            )
            Text(
                text = "Cure",
                color = SureCureGreen,
                fontSize = wordmark,
                lineHeight = wordmarkLine,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp,
                maxLines = 1
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
    val rowBackground = if (isSafe) Color.Transparent else RedBackground

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
    Image(
        painter = painterResource(
            id = entry.thumbnailResId
        ),
        contentDescription = "Medicine package thumbnail",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(width = 58.dp, height = 48.dp)
            .clip(RoundedCornerShape(7.dp))
    )
}
