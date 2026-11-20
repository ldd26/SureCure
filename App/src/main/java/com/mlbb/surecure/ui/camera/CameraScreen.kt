package com.mlbb.surecure.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.Instant
import java.util.UUID
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onPhotosCaptured: (String, String, Uri, Uri?) -> Unit
) {
    val scanId = rememberSaveable {
        UUID.randomUUID().toString()
    }

    val scanStartedAtIso = rememberSaveable {
        Instant.now().toString()
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var imageCapture by remember {
        mutableStateOf<ImageCapture?>(null)
    }
    var cameraReady by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(0) }
    var frontPhoto by remember { mutableStateOf<Uri?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Initialize CameraX when permission is available.
    DisposableEffect(
        hasCameraPermission,
        lifecycleOwner,
        previewView
    ) {
        var boundProvider: ProcessCameraProvider? = null
        var disposed = false

        if (hasCameraPermission) {
            val cameraProviderFuture =
                ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener(
                {
                    if (!disposed) {
                        try {
                            val provider = cameraProviderFuture.get()
                            boundProvider = provider

                            val preview = Preview.Builder()
                                .build()
                                .also {
                                    it.setSurfaceProvider(
                                        previewView.surfaceProvider
                                    )
                                }

                            val capture = ImageCapture.Builder()
                                .setCaptureMode(
                                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                                )
                                .build()

                            provider.unbindAll()

                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture
                            )

                            imageCapture = capture
                            cameraReady = true
                            errorMessage = null
                        } catch (exception: Exception) {
                            cameraReady = false
                            errorMessage =
                                "Unable to start the camera: " +
                                        (exception.localizedMessage
                                            ?: "Unknown error")
                        }
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

        onDispose {
            disposed = true
            boundProvider?.unbindAll()
            imageCapture = null
            cameraReady = false
        }
    }

    val title = if (step == 0) {
        "Photograph the medicine front"
    } else {
        "Photograph the code panel"
    }

    Scaffold(
        containerColor = Color.Black
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(innerPadding)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("Cancel", color = Color.White)
                }

                Text(
                    text = "Photo ${step + 1} of 2",
                    color = Color.White
                )
            }

            // Camera preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (hasCameraPermission) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Visual alignment guide. This does not crop
                    // the saved photograph.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .height(300.dp)
                            .border(
                                width = 2.dp,
                                color = Color.White,
                                shape = RoundedCornerShape(18.dp)
                            )
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "SureCure needs camera access to " +
                                    "photograph medicine packaging.",
                            color = Color.White
                        )

                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    Manifest.permission.CAMERA
                                )
                            }
                        ) {
                            Text("Grant camera permission")
                        }
                    }
                }
            }

            // Capture instructions and controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )

                Text(
                    text = if (step == 0) {
                        "Keep the entire front of the package visible."
                    } else {
                        "Fill the frame with the pack's code panel: barcode, lot number and expiry " +
                            "date. Hold steady and avoid glare."
                    },
                    color = Color.LightGray
                )

                errorMessage?.let {
                    Text(
                        text = it,
                        color = Color(0xFFFF8A80)
                    )
                }

                Button(
                    onClick = {
                        val capture = imageCapture
                            ?: return@Button

                        val capturedStep = step
                        isCapturing = true
                        errorMessage = null

                        val directory = File(
                            context.filesDir,
                            "scan_captures"
                        ).apply {
                            mkdirs()
                        }

                        val prefix = if (capturedStep == 0) {
                            "front"
                        } else {
                            "code_panel"
                        }

                        val photoFile = File(
                            directory,
                            "${prefix}_${System.currentTimeMillis()}.jpg"
                        )

                        val outputOptions =
                            ImageCapture.OutputFileOptions.Builder(
                                photoFile
                            ).build()

                        capture.takePicture(
                            outputOptions,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(
                                    outputFileResults:
                                    ImageCapture.OutputFileResults
                                ) {
                                    isCapturing = false
                                    val savedUri = Uri.fromFile(photoFile)

                                    if (capturedStep == 0) {
                                        frontPhoto = savedUri
                                        step = 1
                                    } else {
                                        val savedFront = frontPhoto

                                        if (savedFront != null) {
                                            onPhotosCaptured(
                                                scanId,
                                                scanStartedAtIso,
                                                savedFront,
                                                savedUri
                                            )
                                        } else {
                                            errorMessage =
                                                "Please photograph the " +
                                                        "front of the package first."
                                        }
                                    }
                                }

                                override fun onError(
                                    exception: ImageCaptureException
                                ) {
                                    isCapturing = false
                                    errorMessage =
                                        "Photo capture failed: " +
                                                (exception.localizedMessage
                                                    ?: "Unknown error")
                                }
                            }
                        )
                    },
                    enabled = cameraReady && !isCapturing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            isCapturing -> "Capturing..."
                            step == 0 -> "Capture front image"
                            else -> "Capture code panel"
                        }
                    )
                }

                if (step == 1) {
                    TextButton(
                        onClick = {
                            // Return to the first photo step.
                            frontPhoto = null
                            step = 0
                            errorMessage = null
                        }
                    ) {
                        Text("Retake front image")
                    }
                }
            }
        }
    }
}
