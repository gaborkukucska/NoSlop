// FILE: app/src/main/java/com/noslop/app/ui/QRScanScreen.kt
@file:kotlin.OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class)
package com.noslop.app.ui

import com.noslop.app.util.tr

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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect


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
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.gson.Gson
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
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
    dmStep: Int = 4,
    viewModel: com.noslop.app.ui.NoSlopViewModel? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    var scannedRawData by remember { mutableStateOf<String?>(null) }
    var parsedPeer by remember { mutableStateOf<QRScannedPeer?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    
    var showManualEntry by remember { mutableStateOf(false) }
    var manualEntryText by remember { mutableStateOf("") }
    var galleryRect by remember { mutableStateOf(Rect.Zero) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && scannedRawData == null) {
            decodeQrFromUri(context, uri) { result ->
                if (result != null) {
                    scannedRawData = result
                } else {
                    Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("No QR code found in this image"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(scannedRawData) {
        val raw = scannedRawData
        if (raw != null) {
            try {
                // First, check if it's a HAI-Net Hub Web Login QR code
                try {
                    val authJson = org.json.JSONObject(raw)
                    if (authJson.optString("type") == "hainet_auth") {
                        val sessionId = authJson.optString("session")
                        val ip = authJson.optString("ip")
                        
                        if (sessionId.isNotBlank() && ip.isNotBlank()) {
                            viewModel?.handleQrLogin(sessionId, ip)
                            Toast.makeText(context, "Authenticating with Hub...", Toast.LENGTH_SHORT).show()
                            onDismiss()
                            return@LaunchedEffect
                        }
                    }
                } catch (e: Exception) {
                    // Not a JSON object or not a login QR, fall through to peer parsing
                }

                val peer = Gson().fromJson(raw, QRScannedPeer::class.java)
                if (peer.handle.isNotBlank() && peer.publicKey.isNotBlank() && peer.onionAddress.isNotBlank()) {
                    parsedPeer = peer
                    showConfirmDialog = true
                } else {
                    scannedRawData = null
                }
            } catch (e: Exception) {
                Logger.warn("QR_SCAN", "Scanned raw data is not a valid peer payload: $raw")
                scannedRawData = null
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
            // Apply systemBarsPadding() to the outermost column to prevent falling off the screen!
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                
                // Header Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scan Mesh Peer".tr,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextLight
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(SurfaceDark, RoundedCornerShape(12.dp))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close".tr, tint = AccentGreen)
                    }
                }

                if (cameraPermissionState.status.isGranted) {
                    if (!showConfirmDialog) {
                        // Camera Preview Section with Overlaid Controls
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                                .clip(RoundedCornerShape(16.dp))
                        ) {
                            CameraScanPreview(
                                onBarcodeDetected = { barcode ->
                                    if (scannedRawData == null) {
                                        scannedRawData = barcode
                                    }
                                }
                            )

                            // HUD Overlay
                            Box(
                                modifier = Modifier.fillMaxSize(),
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
                                        text = "Center the peer's QR code in the grid".tr,
                                        color = AccentGreen,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .background(PrimaryBlack.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                    
                                    Spacer(modifier = Modifier.height(24.dp))
                                    

                                    // Bottom Controls Row (Padded beneath the grid to guarantee visibility)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 32.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Button(
                                            onClick = { 
                                                if (dmStep == 3) viewModel?.completeDmTutorial()
                                                imagePickerLauncher.launch("image/*") 
                                            },
                                            modifier = Modifier.weight(1f).height(50.dp).onGloballyPositioned { galleryRect = it.boundsInRoot() },
                                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = AccentGreen),
                                            border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f)),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Gallery".tr, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                        
                                        Button(
                                            onClick = { showManualEntry = true },
                                            modifier = Modifier.weight(1f).height(50.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = AccentGreen),
                                            border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f)),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Paste Raw".tr, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AccentGreen)
                        }
                    }
                } else {
                    // Request Permission Frame
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Camera Permission Required".tr, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextLight)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "NoSlop needs access to the camera to scan contact node QR codes and initiate handshakes.".tr,
                            style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { cameraPermissionState.launchPermissionRequest() },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                        ) {
                            Text("Grant Access".tr, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { 
                                    if (dmStep == 3) viewModel?.completeDmTutorial()
                                    imagePickerLauncher.launch("image/*") 
                                },
                                modifier = Modifier.weight(1f).onGloballyPositioned { galleryRect = it.boundsInRoot() },
                                border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                            ) {
                                Text("Gallery".tr, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { showManualEntry = true },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                            ) {
                                Text("Paste".tr, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Manual Entry Dialog
            if (showManualEntry) {
                AlertDialog(
                    onDismissRequest = { showManualEntry = false },
                    containerColor = SurfaceDark,
                    title = { Text("Paste Identity String".tr, color = TextLight, fontWeight = FontWeight.Bold) },
                    text = {
                        OutlinedTextField(
                            value = manualEntryText,
                            onValueChange = { manualEntryText = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            placeholder = { Text("Paste the raw JSON identity payload here...".tr, color = TextMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextLight,
                                unfocusedTextColor = TextLight,
                                focusedBorderColor = AccentGreen
                            )
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                scannedRawData = manualEntryText
                                showManualEntry = false
                                manualEntryText = ""
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                        ) {
                            Text("Process".tr, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showManualEntry = false }) {
                            Text("Cancel".tr, color = TextMuted)
                        }
                    }
                )
            }

            if (showConfirmDialog) {
                parsedPeer?.let { peer ->
                    AlertDialog(
                        onDismissRequest = {
                            scannedRawData = null
                            showConfirmDialog = false
                        },
                        properties = DialogProperties(dismissOnClickOutside = false),
                        containerColor = SurfaceDark,
                        title = { Text("Send Connection Request?".tr, color = TextLight, fontWeight = FontWeight.Bold) },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("Handle: ${peer.handle}", color = AccentGreen, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("ONION ADDRESS:".tr, color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text(peer.onionAddress, color = TextLight, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("PUBLIC KEY:".tr, color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text(peer.publicKey.take(24) + "...", color = TextLight, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Sending a request will notify the peer. If they accept, you will establish a secure E2EE mesh connection.".tr, style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(top = 12.dp))
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    onPeerScannedAndAccepted(peer.handle, peer.publicKey, peer.onionAddress, peer.encPublicKey ?: "")
                                    Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Connection request sent!"), Toast.LENGTH_LONG).show()
                                    showConfirmDialog = false
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                            ) {
                                Text("Send Request".tr, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                scannedRawData = null
                                showConfirmDialog = false
                            }) { Text("Reject".tr, color = DestructiveRed) }
                        }
                    )
                }
            }
        }

        if (dmStep == 3) {
            com.noslop.app.ui.tabs.TutorialSpotlight(
                targetRect = galleryRect, 
                text = "4. Scan or select from Gallery", 
                onClickTarget = { 
                    viewModel?.completeDmTutorial()
                    imagePickerLauncher.launch("image/*") 
                }
            )
        }
    }
}

private fun decodeQrFromUri(context: Context, uri: Uri, onResult: (String?) -> Unit) {
    try {
        val inputImage = InputImage.fromFilePath(context, uri)
        val scanner = BarcodeScanning.getClient()
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                onResult(barcodes.firstOrNull()?.rawValue)
            }
            .addOnFailureListener {
                onResult(null)
            }
    } catch (e: Exception) {
        onResult(null)
    }
}

@SuppressLint("UnrememberedMutableState")
@Composable
fun CameraScanPreview(onBarcodeDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize()) { view ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val barcodeScanner = BarcodeScanning.getClient()
            
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    barcodeScanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                barcode.rawValue?.let { value -> onBarcodeDetected(value) }
                            }
                        }
                        .addOnFailureListener { }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) {
                Logger.error("QR_SCAN", "Failed to bind camera use cases: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
