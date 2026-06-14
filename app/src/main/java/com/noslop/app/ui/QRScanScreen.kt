@file:kotlin.OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class)
package com.noslop.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.gson.Gson
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import com.noslop.app.ui.theme.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class QRScannedPeer(
    val handle: String,
    val publicKey: String,
    val encPublicKey: String?,
    val onionAddress: String
)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QRScanScreen(
    onPeerScannedAndAccepted: (handle: String, publicKeyB64: String, onionAddress: String, encPublicKeyB64: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    // Handle scanned state
    var scannedRawData by remember { mutableStateOf<String?>(null) }
    var parsedPeer by remember { mutableStateOf<QRScannedPeer?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    // Image picker launcher for gallery QR scanning
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && scannedRawData == null) {
            decodeQrFromUri(context, uri) { result ->
                if (result != null) {
                    scannedRawData = result
                } else {
                    Toast.makeText(context, "No QR code found in this image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Parse payload once scanned
    LaunchedEffect(scannedRawData) {
        val raw = scannedRawData
        if (raw != null) {
            try {
                val peer = Gson().fromJson(raw, QRScannedPeer::class.java)
                if (peer.handle.isNotBlank() && peer.publicKey.isNotBlank() && peer.onionAddress.isNotBlank()) {
                    parsedPeer = peer
                    showConfirmDialog = true
                } else {
                    scannedRawData = null // Reset scanning
                }
            } catch (e: Exception) {
                Logger.warn("QR_SCAN", "Scanned raw data is not a valid peer payload: $raw")
                scannedRawData = null // Reset scanning
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = PrimaryBlack
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Header Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(16.dp)
                        .zIndex(2f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scan Mesh Peer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextLight
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .background(SurfaceDark.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = AccentGreen)
                    }
                }

                // Main body based on permission
                if (cameraPermissionState.status.isGranted) {
                    if (!showConfirmDialog) {
                        CameraScanPreview(
                            onBarcodeDetected = { barcode ->
                                if (scannedRawData == null) {
                                    scannedRawData = barcode
                                }
                            }
                        )

                        // Scanner target box HUD mockup overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(260.dp)
                                        .border(2.dp, AccentGreen, RoundedCornerShape(16.dp))
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Center the peer's QR code in the grid",
                                    color = AccentGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .background(PrimaryBlack.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }

                        // Gallery picker button at the bottom
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(2f)
                                .padding(bottom = 48.dp)
                                .navigationBarsPadding(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Button(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceDark.copy(alpha = 0.9f),
                                    contentColor = AccentGreen
                                ),
                                border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Select from Gallery",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        // Keep camera frozen (blurred/hidden background) or black during confirm dialog
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(PrimaryBlack),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AccentGreen)
                        }
                    }
                } else {
                    // Request Permission Frame
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Camera Permission Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextLight
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "NoSlop needs access to the camera to scan contact node QR codes and initiate handshakes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { cameraPermissionState.launchPermissionRequest() },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                        ) {
                            Text("Grant Access", fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Gallery option even without camera permission
                        OutlinedButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                        ) {
                            Icon(
                                Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select QR from Gallery", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Confirmation card displayed when peer QR is decoded
                if (showConfirmDialog) {
                    parsedPeer?.let { peer ->
                        AlertDialog(
                            onDismissRequest = {
                                scannedRawData = null
                                showConfirmDialog = false
                            },
                            properties = DialogProperties(dismissOnClickOutside = false),
                            containerColor = SurfaceDark,
                            title = {
                                Text(
                                    text = "Send Connection Request?",
                                    color = TextLight,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            text = {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Handle: ${peer.handle}",
                                        color = AccentGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "ONION ADDRESS:",
                                        color = TextMuted,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = peer.onionAddress,
                                        color = TextLight,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "PUBLIC KEY:",
                                        color = TextMuted,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = peer.publicKey.take(24) + "...",
                                        color = TextLight,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Sending a request will notify the peer. If they accept, you will establish a secure E2EE mesh connection.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        modifier = Modifier.padding(top = 12.dp)
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        onPeerScannedAndAccepted(
                                            peer.handle,
                                            peer.publicKey,
                                            peer.onionAddress,
                                            peer.encPublicKey ?: ""
                                        )
                                        Logger.info("ONBOARDING", "Connection request initiated for peer via QR scan: ${peer.handle}")
                                        Toast.makeText(context, "Connection request sent!", Toast.LENGTH_LONG).show()
                                        showConfirmDialog = false
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                                ) {
                                    Text("Send Request", fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        scannedRawData = null
                                        showConfirmDialog = false
                                    }
                                ) {
                                    Text("Reject", color = DestructiveRed)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Decode a QR code from a content URI (gallery image) using ML Kit.
 */
private fun decodeQrFromUri(context: Context, uri: Uri, onResult: (String?) -> Unit) {
    try {
        val inputImage = InputImage.fromFilePath(context, uri)
        val scanner = BarcodeScanning.getClient()
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val qrValue = barcodes.firstOrNull()?.rawValue
                onResult(qrValue)
            }
            .addOnFailureListener {
                Logger.warn("QR_SCAN", "Failed to decode QR from gallery image: ${it.message}")
                onResult(null)
            }
    } catch (e: Exception) {
        Logger.error("QR_SCAN", "Error reading gallery image for QR decode: ${e.message}")
        onResult(null)
    }
}

@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScanPreview(
    onBarcodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    ) { view ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview configuration
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }

            // Image analyzer setup using Google ML Kit Barcode Scanner
            val barcodeScanner = BarcodeScanning.getClient()
            
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val inputImage = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )
                    barcodeScanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                barcode.rawValue?.let { value ->
                                    onBarcodeDetected(value)
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            // Fail silently, retry next frame
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
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
                Logger.error("QR_SCAN", "Failed to bind camera use cases: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
