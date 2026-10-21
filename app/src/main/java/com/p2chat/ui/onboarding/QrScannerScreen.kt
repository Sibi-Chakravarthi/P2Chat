package com.p2chat.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.MultiFormatReader
import com.p2chat.ui.common.EmptyState
import com.p2chat.ui.theme.CanvasBackground
import com.p2chat.ui.theme.TextPrimary
import com.p2chat.ui.theme.TextSecondary
import com.p2chat.ui.theme.VerifiedGreen
import com.p2chat.util.SecureLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "QrScanner"

/**
 * CameraX QR code scanner with offline ZXing MultiFormatReader (ID-4, D-16).
 * Incorporates row-stride normalization, sensor rotation handling, and dual normal/inverted decode passes.
 * Zero Google Play Services dependencies.
 */
@Composable
fun QrScannerScreen(
    onQrScanned: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBackground)
    ) {
        if (hasCameraPermission) {
            CameraPreviewWithScanner(
                onQrScanned = onQrScanned,
                onClose = onClose
            )
        } else {
            EmptyState(
                title = "Camera Permission Required",
                message = "P2Chat requires camera access to perform offline, in-person optical QR verification with nearby peers (ID-4).",
                actionLabel = "Grant Permission",
                onActionClick = { launcher.launch(Manifest.permission.CAMERA) }
            )
        }

        // Top bar with close button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Optical Peer Verification",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "Close scanner" }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun CameraPreviewWithScanner(
    onQrScanned: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val isScanned = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Throwable) {
            }
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val reader = QrDecoder.createReader()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!isScanned.get()) {
                            scanFrame(imageProxy, reader) { rawText ->
                                if (isScanned.compareAndSet(false, true)) {
                                    ContextCompat.getMainExecutor(ctx).execute {
                                        onQrScanned(rawText)
                                        onClose()
                                    }
                                }
                            }
                        } else {
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        SecureLog.e(TAG, "Failed to bind camera: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Tactical Reticle Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .border(2.dp, VerifiedGreen, RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "QR Scanner reticle viewfinder" }
            )
        }

        Text(
            text = "Point camera at peer's identity QR code",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        )
    }
}

@OptIn(ExperimentalGetImage::class)
private fun scanFrame(
    imageProxy: ImageProxy,
    reader: MultiFormatReader,
    onSuccess: (String) -> Unit
) {
    try {
        val mediaImage = imageProxy.image
        if (mediaImage != null && mediaImage.planes.isNotEmpty()) {
            val plane = mediaImage.planes[0]
            val buffer = plane.buffer
            val text = QrDecoder.decodePlane(
                buffer = buffer,
                width = imageProxy.width,
                height = imageProxy.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                reader = reader
            )
            if (text != null && text.isNotEmpty()) {
                onSuccess(text)
            }
        }
    } catch (e: Exception) {
        SecureLog.w(TAG, "Frame decode error: ${e.message}")
    } finally {
        imageProxy.close()
    }
}
