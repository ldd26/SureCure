package com.mlbb.surecure.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
                onPhotosCaptured = { frontPhoto, codePanelPhoto ->
                    // Pass the local photo URIs to the review screen.
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("frontPhotoUri", frontPhoto.toString())

                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("codePanelPhotoUri", codePanelPhoto.toString())

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
                Text(
                    text = "The captured photos could not be loaded.",
                )
            }
        }

        composable("scan-result") {
            // Photo URIs were stored on the camera entry, as for photo-review.
            val cameraEntry = runCatching {
                navController.getBackStackEntry("camera")
            }.getOrNull()

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
                Text(
                    text = "The captured photos could not be loaded.",
                )
            }
        }
    }
}
