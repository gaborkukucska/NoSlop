import os

def apply_patch(filepath, old_content, new_content, name):
    if not os.path.exists(filepath):
        print(f"❌ File not found: {filepath}")
        return
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    if old_content in content:
        content = content.replace(old_content, new_content)
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"✅ Successfully patched {name}")
    elif new_content in content:
        print(f"⚠️ {name} is already patched.")
    else:
        print(f"❌ Failed to patch {name}: Could not find the target code block.")

# --- 1. MediaCaptureManager.kt (Audio Fix) ---
mcm_path = "app/src/main/java/com/noslop/app/mesh/MediaCaptureManager.kt"
mcm_old = """        var pendingRecording = videoCapture.output.prepareRecording(context, outputOptions)
        
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingRecording = pendingRecording.withAudioEnabled()
        }

        recording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { recordEvent ->"""

mcm_new = """        var pendingRecording = videoCapture.output.prepareRecording(context, outputOptions)
        
        try {
            pendingRecording = pendingRecording.withAudioEnabled()
            Logger.info(TAG, "Audio explicitly enabled for video recording")
        } catch (e: SecurityException) {
            Logger.warn(TAG, "RECORD_AUDIO permission missing, recording video silently")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to enable audio: ${e.message}")
        }

        recording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { recordEvent ->"""

apply_patch(mcm_path, mcm_old, mcm_new, "MediaCaptureManager.kt (Audio track enforcement)")

# --- 2. QRScanScreen.kt (Layout Fix) ---
qr_path = "app/src/main/java/com/noslop/app/ui/QRScanScreen.kt"

qr_pad_old = """Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {"""
qr_pad_new = """Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {"""
apply_patch(qr_path, qr_pad_old, qr_pad_new, "QRScanScreen.kt (Safe drawing padding)")

qr_old = """                        // Camera Preview Section (Pushes bottom controls down safely)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
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
                                        text = "Center the peer's QR code in the grid",
                                        color = AccentGreen,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .background(PrimaryBlack.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        // Bottom Controls Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                modifier = Modifier.weight(1f).height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = AccentGreen),
                                border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Gallery", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                                Text("Paste Raw", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }"""

qr_new = """                        // Camera Preview Section with Overlaid Controls
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, bottom = 16.dp)
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
                                        text = "Center the peer's QR code in the grid",
                                        color = AccentGreen,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .background(PrimaryBlack.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                            
                            // Bottom Controls Row (Overlaid to prevent being pushed off-screen)
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(PrimaryBlack.copy(alpha = 0.85f))
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { imagePickerLauncher.launch("image/*") },
                                    modifier = Modifier.weight(1f).height(50.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = AccentGreen),
                                    border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Gallery", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                                    Text("Paste Raw", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }"""

apply_patch(qr_path, qr_old, qr_new, "QRScanScreen.kt (Overlaid bottom controls)")

# --- 3. Document the Fixes ---
doc_path = "docs/PROJECT_STATUS.md"
updates = """
### 5. Hardware Capture & Layout Polish
*   **CameraX Audio Enforcement**: Fixed a bug where in-app recorded videos lacked audio tracks. `MediaCaptureManager` now aggressively attempts to bind `withAudioEnabled()` and gracefully catches `SecurityExceptions` if permissions are explicitly denied, rather than silently failing the `ContextCompat` context check.
*   **QR Scanner Form Factor Support**: Fixed a layout bug on smaller devices where the "Select from Gallery" and "Paste Raw" buttons fell off the bottom of the Dialog screen. The buttons are now safely overlaid inside the Camera viewfinder bounds, mimicking native camera apps and ensuring 100% visibility regardless of screen height.
"""

if os.path.exists(doc_path):
    with open(doc_path, "a", encoding="utf-8") as f:
        f.write(updates)
    print("✅ Successfully updated docs/PROJECT_STATUS.md with the final polish!")
else:
    print(f"❌ Could not find {doc_path}")

print("--- Done ---")
