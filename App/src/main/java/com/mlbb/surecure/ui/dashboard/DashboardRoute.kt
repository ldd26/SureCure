package com.mlbb.surecure.ui.dashboard

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mlbb.surecure.data.repository.ScanHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DashboardRoute(
    onCameraClick: (hasBarcode: Boolean) -> Unit
) {
    val context = LocalContext.current

    // Create one repository instance for this dashboard.
    val repository = remember(context) {
        ScanHistoryRepository(context)
    }

    // State containing the sections currently displayed.
    var sections by remember {
        mutableStateOf<List<ScanHistorySection>>(emptyList())
    }

    var isRefreshing by remember {
        mutableStateOf(true)
    }

    var showPackagingDialog by remember {
        mutableStateOf(false)
    }

    val scope = rememberCoroutineScope()

    // Load history when the dashboard first enters composition.
    LaunchedEffect(repository) {
        isRefreshing = true

        try {
            val entries = repository.loadEntries()
            sections = groupScanHistory(entries)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Toast.makeText(
                context,
                "Couldn't load scan history.",
                Toast.LENGTH_LONG
            ).show()
        } finally {
            isRefreshing = false
        }
    }

    DashboardScreen(
        sections = sections,
        onCameraClick = { showPackagingDialog = true },
        isRefreshing = isRefreshing,
        onRefreshClick = {
            if (!isRefreshing) {
                scope.launch {
                    isRefreshing = true

                    try {
                        val entries = repository.loadEntries(
                            forceRefresh = true
                        )

                        sections = groupScanHistory(entries)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        Toast.makeText(
                            context,
                            "Couldn't refresh scan history.",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        isRefreshing = false
                    }
                }
            }
        }
    )

    if (showPackagingDialog) {
        AlertDialog(
            onDismissRequest = { showPackagingDialog = false },
            title = {
                Text("Do you have the medicine's barcode?")
            },
            text = {
                Column {
                    Text(
                        text = "Having the original packaging and the barcode available can significantly improve scan accuracy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton( onClick = { onCameraClick(true) } ) {
                    Text("Yes, I have it")
                }
            },
            dismissButton = {
                TextButton( onClick = { onCameraClick(false) } ) {
                    Text("No")
                }
            }
        )
    }
}
