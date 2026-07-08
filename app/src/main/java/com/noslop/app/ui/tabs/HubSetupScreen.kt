// FILE: app/src/main/java/com/noslop/app/ui/tabs/HubSetupScreen.kt
package com.noslop.app.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.noslop.app.net.HubDiscoveryService
import com.noslop.app.net.SshDeployer
import com.noslop.app.ui.theme.*
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.QRScanScreen
import kotlinx.coroutines.launch

@Composable
fun MetricCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(color = SurfaceDark, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(label, color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun HubSetupScreen(viewModel: NoSlopViewModel, onBack: () -> Unit = {}) {
    val hubDeploymentStatus by viewModel.hubDeploymentStatus.collectAsState()
    var showQRScanner by remember { mutableStateOf(false) }

    if (showQRScanner) {
        QRScanScreen(
            onPeerScannedAndAccepted = { handle, pub, onion, encPub ->
                viewModel.requestConnection(handle, pub, onion, encPub)
            },
            viewModel = viewModel,
            onDismiss = { showQRScanner = false }
        )
        return
    }

    if (!hubDeploymentStatus.isNullOrBlank()) {
        val isLegacy = hubDeploymentStatus == "Active (Legacy Connection)"
        val hubIp = if (isLegacy) "" else hubDeploymentStatus?.substringAfter("Active at ")?.trim() ?: ""

        // Show Dashboard Control Panel
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PrimaryBlack)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("HAI-Net Home Hub", style = MaterialTheme.typography.headlineMedium, color = TextLight, fontWeight = FontWeight.Bold)
                IconButton(onClick = { viewModel.setHubDeploymentStatus("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Disconnect", tint = TextMuted)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status Card
            Surface(
                color = SurfaceDark,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Hub Online & Linked", color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    if (hubIp.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("IP Address: $hubIp", color = TextLight, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        Text("Web UI: http://$hubIp:3000", color = TextMuted, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Web Dashboard Access
            if (hubIp.isNotBlank()) {
                val context = LocalContext.current
                OutlinedButton(
                    onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("http://$hubIp:3000"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                    border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Web Dashboard")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = { showQRScanner = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan to Login to Web UI", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text("Sync Metrics", color = TextLight, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Active", "Identity", Modifier.weight(1f))
                MetricCard("Synced", "Mesh Data", Modifier.weight(1f))
                MetricCard("Running", "Media AI", Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { viewModel.setHubDeploymentStatus("") },
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = DestructiveRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect Hub")
            }
        }
        return
    }

    var isDeploying by remember { mutableStateOf(false) }
    var deployResult by remember { mutableStateOf<String?>(null) }
    var deployError by remember { mutableStateOf<String?>(null) }

    var showSetupDialog by remember { mutableStateOf(false) }
    var targetIp by remember { mutableStateOf("") }
    
    // Dialog state
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var sharedFolder by remember { mutableStateOf("~/.hainet/storage") }
    var deploymentLogs by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val discoveryService = remember { HubDiscoveryService(context) }
    val discoveredHubs by discoveryService.discoveredHubs.collectAsState()

    var isScanning by remember { mutableStateOf(false) }
    var scanTimeoutReached by remember { mutableStateOf(false) }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            scanTimeoutReached = false
            discoveryService.startDiscovery()
            kotlinx.coroutines.delay(10000L)
            scanTimeoutReached = true
        } else {
            discoveryService.stopDiscovery()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            discoveryService.stopDiscovery()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("HAI-Net Hub Setup", style = MaterialTheme.typography.headlineMedium, color = TextLight, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (!isScanning) {
            Icon(
                imageVector = Icons.Default.Router,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("No Home Hub Configured", color = TextLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Deploy HAI-Net to a device on your local network to keep your node online 24/7.",
                color = TextMuted,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { isScanning = true },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Deploy New Hub", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = { viewModel.setHubDeploymentStatus("Active (Legacy Connection)") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("I already have a Hub running", color = TextMuted)
            }
        } else {
            if (discoveredHubs.isEmpty()) {
                if (!scanTimeoutReached) {
                    Text("Scanning local network for hubs...", color = TextMuted, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentGreen)
                } else {
                    Text("No hubs found on local network.", color = DestructiveRed, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Ensure your device is connected to the same network.", color = TextMuted, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(32.dp))
            } else {
                Text("Select a Discovered Hub", color = AccentGreen, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                discoveredHubs.forEach { hub ->
                    Surface(
                        onClick = {
                            targetIp = hub.ipAddress
                            username = "pi" // Default common SSH user
                            showSetupDialog = true
                        },
                        color = SurfaceDark,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(hub.hostName, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("${hub.ipAddress}:${hub.port}", color = TextMuted, fontSize = 14.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            OutlinedButton(
                onClick = {
                    targetIp = ""
                    username = ""
                    showSetupDialog = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight)
            ) {
                Text("Manual IP Entry")
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = { isScanning = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel", color = TextMuted)
            }
        }
    }

    if (showSetupDialog) {
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { if (!isDeploying) { showSetupDialog = false; deployError = null; deploymentLogs = "" } },
            containerColor = SurfaceDark,
            title = { Text(if (targetIp.isNotBlank()) "Deploy to $targetIp" else "Manual Deployment", color = TextLight) },
            text = {
                if (isDeploying || deployError != null || deploymentLogs.isNotBlank()) {
                    Column {
                        Text("Deployment Logs:", color = AccentGreen, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(PrimaryBlack, RoundedCornerShape(4.dp))
                            .padding(8.dp)
                        ) {
                            val scrollState = rememberScrollState()
                            LaunchedEffect(deploymentLogs) {
                                scrollState.animateScrollTo(scrollState.maxValue)
                            }
                            Text(
                                text = deploymentLogs, 
                                color = if (deployError != null) DestructiveRed else AccentGreen, 
                                fontSize = 10.sp, 
                                modifier = Modifier.verticalScroll(scrollState)
                            )
                        }
                        if (deployError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(deployError!!, color = DestructiveRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(deploymentLogs))
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                                ) {
                                    Text("Copy Logs", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = {
                                        deployError = null
                                        deploymentLogs = ""
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                                ) {
                                    Text("Back to Settings", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        if (targetIp.isBlank()) {
                            OutlinedTextField(
                                value = targetIp,
                                onValueChange = { targetIp = it },
                                label = { Text("Hub IP Address", color = TextMuted) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("SSH Username", color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("SSH Password", color = TextMuted) },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = sharedFolder,
                            onValueChange = { sharedFolder = it },
                            label = { Text("Shared Media Folder", color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                        )

                        if (deployError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(deployError!!, color = DestructiveRed, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                if (deployError == null) {
                    Button(
                        onClick = {
                            isDeploying = true
                            deployError = null
                            deployResult = null
                            deploymentLogs = ""
                            coroutineScope.launch {
                                val localIdentity = viewModel.localKeys.value
                                val result = SshDeployer.deployHaiNetHub(
                                    ip = targetIp,
                                    user = username,
                                    pass = password,
                                    sharedFolder = sharedFolder,
                                    identity = localIdentity,
                                    onLog = { chunk -> deploymentLogs += chunk }
                                )
                                if (result.isSuccess) {
                                    showSetupDialog = false
                                    viewModel.setHubDeploymentStatus("Active at $targetIp")
                                } else {
                                    deployError = result.exceptionOrNull()?.message ?: "Unknown Error"
                                }
                                isDeploying = false
                            }
                        },
                        enabled = !isDeploying && targetIp.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                    ) {
                        if (isDeploying) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PrimaryBlack)
                        } else {
                            Text("Deploy")
                        }
                    }
                }
            },
            dismissButton = {
                if (!isDeploying && deployError == null) {
                    TextButton(onClick = { showSetupDialog = false }) {
                        Text("Cancel", color = TextMuted)
                    }
                }
            }
        )
    }
}
