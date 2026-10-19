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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.p2chat.ui.common.EmptyState
import com.p2chat.ui.theme.CanvasBackground
import com.p2chat.ui.theme.HairlineBorder
import com.p2chat.ui.theme.OnStarkPrimary
import com.p2chat.ui.theme.StarkPrimary
import com.p2chat.ui.theme.TextPrimary
import com.p2chat.ui.theme.TextSecondary
import com.p2chat.ui.theme.VerifiedGreen
import java.util.concurrent.Executors

/**
 * CameraX QR code scanner with offline ZXing MultiFormatReader (ID-4, D-16).
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
    var scanned by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val reader = MultiFormatReader()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!scanned) {
                            scanFrame(imageProxy, reader) { rawText ->
                                if (!scanned) {
                                    scanned = true
                                    onQrScanned(rawText)
                                    onClose()
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
                    } catch (_: Exception) {
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
        if (mediaImage != null) {
            val buffer = mediaImage.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val width = imageProxy.width
            val height = imageProxy.height
            val source = PlanarYUVLuminanceSource(
                bytes, width, height, 0, 0, width, height, false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(binaryBitmap)
            if (result != null && result.text.isNotEmpty()) {
                onSuccess(result.text)
            }
        }
    } catch (_: NotFoundException) {
        // Frame did not contain a QR code
    } catch (_: Exception) {
    } finally {
        imageProxy.close()
    }
}
