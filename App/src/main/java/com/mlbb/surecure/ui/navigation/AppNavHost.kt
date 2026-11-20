package com.mlbb.surecure.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mlbb.surecure.ui.camera.CameraScreen
import com.mlbb.surecure.ui.camera.PhotoReviewScreen
import com.mlbb.surecure.ui.dashboard.DashboardRoute
import com.mlbb.surecure.ui.result.ScanResultScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "dashboard"
    ) {
        composable("dashboard") {
            DashboardRoute(
                onCameraClick = { hasBarcode ->
                    // Read by scan-result: "yes" starts with the barcode (decision order step 1).
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("hasBarcode", hasBarcode)
                    navController.navigate("camera")
                }
            )
        }

        composable("camera") {
            CameraScreen(
                onBack = {
                    navController.popBackStack()
                },
                onPhotosCaptured = { scanId, scanStartedAtIso, frontPhoto, codePanelPhoto ->
                    // Pass the local photo URIs to the review screen.

                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.apply {
                            set("scanId", scanId)
                            set("scanStartedAt", scanStartedAtIso)
                            set("frontPhotoUri", frontPhoto.toString())

                            if (codePanelPhoto != null) {
                                set(
                                    "codePanelPhotoUri",
                                    codePanelPhoto.toString()
                                )
                            } else {
                                remove<String>("codePanelPhotoUri")
                            }
                        }

                    // Keep your existing navigation to photo review.
                    navController.navigate("photo-review")
                }
            )
        }

        composable("photo-review") {
            val cameraEntry = navController.previousBackStackEntry

            val frontPhoto = cameraEntry
                ?.savedStateHandle
                ?.get<String>("frontPhotoUri")
                ?.let { Uri.parse(it) }

            val codePanelPhoto = cameraEntry
                ?.savedStateHandle
                ?.get<String>("codePanelPhotoUri")
                ?.let { Uri.parse(it) }

            if (frontPhoto != null && codePanelPhoto != null) {
                PhotoReviewScreen(
                    frontPhoto = frontPhoto,
                    codePanelPhoto = codePanelPhoto,
                    onRetake = {
                        // Return to the camera destination.
                        navController.popBackStack()
                    },
                    onDone = {
                        // Continue to the product check (Figure 1, stages 1 to 3).
                        navController.navigate("scan-result")
                    }
                )
            } else {
                PhotosMissing()
            }
        }

        composable("scan-result") {
            // Photo URIs were stored on the camera entry, as for photo-review.
            val cameraEntry = remember(navController.currentBackStackEntry) {
                runCatching {
                    navController.getBackStackEntry("camera")
                }.getOrNull()
            }

            val frontPhoto = cameraEntry
                ?.savedStateHandle
                ?.get<String>("frontPhotoUri")
                ?.let { Uri.parse(it) }

            val codePanelPhoto = cameraEntry
                ?.savedStateHandle
                ?.get<String>("codePanelPhotoUri")
                ?.let { Uri.parse(it) }

            val hasBarcode = runCatching {
                navController.getBackStackEntry("dashboard")
                    .savedStateHandle
                    .get<Boolean>("hasBarcode")
            }.getOrNull() ?: false

            val scanId = runCatching {
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.get<String>("scanId")
            }

            if (frontPhoto != null && codePanelPhoto != null) {
                ScanResultScreen(
                    frontPhoto = frontPhoto,
                    codePanelPhoto = codePanelPhoto,
                    hasBarcode = hasBarcode,
                    onDone = {
                        navController.popBackStack(
                            "dashboard",
                            inclusive = false
                        )
                    }
                )
            } else {
                PhotosMissing()
            }
        }
    }
}

/** Shown if the camera's photo addresses were lost (for example after the app was closed mid-scan). */
@Composable
private fun PhotosMissing() {
    Text(
        text = "The captured photos could not be loaded. Go back and take them again.",
        modifier = Modifier.safeDrawingPadding().padding(20.dp),
    )
}
