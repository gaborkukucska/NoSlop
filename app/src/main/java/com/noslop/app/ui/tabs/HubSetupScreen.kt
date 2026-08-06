// FILE: app/src/main/java/com/noslop/app/ui/tabs/HubSetupScreen.kt
package com.noslop.app.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import com.noslop.app.ui.QRScanScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.noslop.app.util.tr

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
fun HubSetupScreen(viewModel: NoSlopViewModel, onBack: () -> Unit = {}, initialScanMode: String? = null) {
    val hubDeploymentStatus by viewModel.hubDeploymentStatus.collectAsState()

    // Active Dashboard View (Native Compose)
    if (!hubDeploymentStatus.isNullOrBlank()) {
        val isLegacy = hubDeploymentStatus == "Active (Legacy Connection)"
        val hubIp = if (isLegacy) "" else hubDeploymentStatus?.substringAfter("Active at ")?.trim() ?: ""

        var showQRScanner by remember { mutableStateOf(false) }
        var showUpdateDialog by remember { mutableStateOf(false) }
        var isUpdating by remember { mutableStateOf(false) }
        var updateLogs by remember { mutableStateOf("") }
        var updateError by remember { mutableStateOf<String?>(null) }
        var sshUser by remember { mutableStateOf("pi") }
        var sshPass by remember { mutableStateOf("") }
        val dashScope = rememberCoroutineScope()

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
                Text("HAI-Net Home Hub".tr, style = MaterialTheme.typography.headlineMedium, color = TextLight, fontWeight = FontWeight.Bold)
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
                        Text("Hub Online & Linked".tr, color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    if (hubIp.isNotBlank()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Access your Hub's Web UI from any browser on your network:".tr, color = TextLight, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = PrimaryBlack,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "http://$hubIp:8080",
                                color = AccentGreen,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(16.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        val context = LocalContext.current
                        OutlinedButton(
                            onClick = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("http://$hubIp:8080"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                            border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Web Dashboard".tr)
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = { showQRScanner = true },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan to Login to Web UI".tr, fontWeight = FontWeight.Bold)
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        OutlinedButton(
                            onClick = { showUpdateDialog = true },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                            border = BorderStroke(1.dp, TextMuted)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = TextMuted)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Update Hub Software".tr, color = TextMuted)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text("Sync Metrics".tr, color = TextLight, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Active".tr, "Identity".tr, Modifier.weight(1f))
                MetricCard("Synced".tr, "Mesh Data".tr, Modifier.weight(1f))
                MetricCard("Running".tr, "Media AI".tr, Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        if (showUpdateDialog) {
            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
            AlertDialog(
                onDismissRequest = { if (!isUpdating) { showUpdateDialog = false; updateError = null; updateLogs = "" } },
                containerColor = SurfaceDark,
                title = { Text("Update Hub".tr, color = TextLight) },
                text = {
                    if (isUpdating || updateError != null || updateLogs.isNotBlank()) {
                        Column {
                            Text("Update Logs:".tr, color = AccentGreen, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(PrimaryBlack, RoundedCornerShape(4.dp))
                                .padding(8.dp)
                            ) {
                                val scrollState = rememberScrollState()
                                LaunchedEffect(updateLogs) {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                                Text(
                                    text = updateLogs, 
                                    color = if (updateError != null) DestructiveRed else AccentGreen, 
                                    fontSize = 10.sp, 
                                    modifier = Modifier.verticalScroll(scrollState)
                                )
                            }
                            if (updateError != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(updateError!!, color = DestructiveRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(updateLogs)) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                                    ) { Text("Copy Logs".tr, fontSize = 12.sp) }
                                }
                            }
                        }
                    } else {
                        Column {
                            Text("Enter the SSH credentials for your Hub to pull the latest code and recompile.", color = TextMuted, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = sshUser,
                                onValueChange = { sshUser = it },
                                label = { Text("SSH Username".tr, color = TextMuted) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = sshPass,
                                onValueChange = { sshPass = it },
                                label = { Text("SSH Password".tr, color = TextMuted) },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                            )
                        }
                    }
                },
                confirmButton = {
                    if (updateError == null && (!isUpdating || updateLogs.contains("Hub Update Complete!"))) {
                        Button(
                            onClick = {
                                if (updateLogs.contains("Hub Update Complete!")) {
                                    showUpdateDialog = false
                                    updateError = null
                                    updateLogs = ""
                                } else {
                                    isUpdating = true
                                    updateError = null
                                    updateLogs = ""
                                    dashScope.launch {
                                        val result = com.noslop.app.net.SshDeployer.deployHaiNetHub(
                                            ip = hubIp,
                                            user = sshUser,
                                            pass = sshPass,
                                            sharedFolder = "",
                                            identity = null,
                                            strategy = com.noslop.app.net.OverwriteStrategy.UPDATE_HUB,
                                            onLog = { chunk -> updateLogs += chunk }
                                        )
                                        if (result.isSuccess) {
                                            updateError = null
                                        } else {
                                            updateError = result.exceptionOrNull()?.message ?: "Unknown Error"
                                        }
                                        isUpdating = false
                                    }
                                }
                            },
                            enabled = (!isUpdating && sshUser.isNotBlank() && sshPass.isNotBlank()) || updateLogs.contains("Hub Update Complete!"),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                        ) {
                            if (isUpdating && !updateLogs.contains("Hub Update Complete!")) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PrimaryBlack)
                            } else {
                                Text(if (updateLogs.contains("Hub Update Complete!")) "Done".tr else "Update".tr)
                            }
                        }
                    }
                },
                dismissButton = {
                    if (!isUpdating) {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("Close".tr, color = TextMuted)
                        }
                    }
                }
            )
        }

        return
    }

    // --- Setup Wizard UI (Below) ---
    var isDeploying by remember { mutableStateOf(false) }
    var deployResult by remember { mutableStateOf<String?>(null) }
    var deployError by remember { mutableStateOf<String?>(null) }

    var showSetupDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var targetIp by remember { mutableStateOf("") }
    var isVerifyingLink by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var showExistingHubDialog by remember { mutableStateOf(false) }
    
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var sharedFolder by remember { mutableStateOf("~/.hainet/storage") }
    var deploymentLogs by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val discoveryService = remember { HubDiscoveryService(context) }
    val discoveredHubs by discoveryService.discoveredHubs.collectAsState()

    var scanMode by remember { mutableStateOf<String?>(initialScanMode) }
    var scanTimeoutReached by remember { mutableStateOf(false) }

    LaunchedEffect(scanMode) {
        if (scanMode != null) {
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
            Text("HAI-Net Hub Setup".tr, style = MaterialTheme.typography.headlineMedium, color = TextLight, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (scanMode == null) {
            Icon(
                imageVector = Icons.Default.Router,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("No Home Hub Configured".tr, color = TextLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Deploy HAI-Net to a device on your local network to keep your node online 24/7.".tr,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { scanMode = "deploy" },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Deploy New Hub".tr, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = { scanMode = "link" },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("I already have a Hub running".tr, color = TextMuted)
            }
        } else {
            if (discoveredHubs.isEmpty()) {
                if (!scanTimeoutReached) {
                    Text("Scanning local network for hubs...".tr, color = TextMuted, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentGreen)
                } else {
                    Text("No hubs found on local network.".tr, color = DestructiveRed, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Ensure your device is connected to the same network.".tr, color = TextMuted, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(32.dp))
            } else {
                Text("Select a Discovered Hub".tr, color = AccentGreen, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                discoveredHubs.forEach { hub ->
                    Surface(
                        onClick = {
                            targetIp = hub.ipAddress
                            if (scanMode == "deploy") {
                                username = "pi" // Default common SSH user
                                showSetupDialog = true
                            } else {
                                isVerifyingLink = true
                                viewModel.verifyAndLinkHub(targetIp) { success, error ->
                                    isVerifyingLink = false
                                    if (success) {
                                        viewModel.setHubDeploymentStatus("Active at $targetIp")
                                    } else {
                                        linkError = error
                                    }
                                }
                            }
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
                    if (scanMode == "deploy") {
                        username = ""
                        showSetupDialog = true
                    } else {
                        showLinkDialog = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight)
            ) {
                Text("Manual IP Entry".tr)
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = { scanMode = null },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel".tr, color = TextMuted)
            }
        }
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Link Existing Hub".tr, color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Enter the local IP address of your existing HAI-Net Hub to connect to it.".tr, color = TextMuted, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = targetIp,
                        onValueChange = { targetIp = it },
                        label = { Text("Hub IP Address".tr, color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isVerifyingLink = true
                        viewModel.verifyAndLinkHub(targetIp) { success, error ->
                            isVerifyingLink = false
                            if (success) {
                                showLinkDialog = false
                                viewModel.setHubDeploymentStatus("Active at $targetIp")
                            } else {
                                linkError = error
                            }
                        }
                    },
                    enabled = targetIp.isNotBlank() && !isVerifyingLink,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) {
                    if (isVerifyingLink) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PrimaryBlack)
                    } else {
                        Text("Link Hub".tr, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) {
                    Text("Cancel".tr, color = TextMuted)
                }
            }
        )
    }

    if (showSetupDialog) {
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { if (!isDeploying) { showSetupDialog = false; deployError = null; deploymentLogs = "" } },
            containerColor = SurfaceDark,
            title = { Text(if (targetIp.isNotBlank()) "Deploy to ".tr + targetIp else "Manual Deployment".tr, color = TextLight) },
            text = {
                if (isDeploying || deployError != null || deploymentLogs.isNotBlank()) {
                    Column {
                        Text("Deployment Logs:".tr, color = AccentGreen, fontWeight = FontWeight.Bold)
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
                                    Text("Copy Logs".tr, fontSize = 12.sp)
                                }
                                Button(
                                    onClick = {
                                        deployError = null
                                        deploymentLogs = ""
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                                ) {
                                    Text("Back to Settings".tr, fontSize = 12.sp)
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
                                label = { Text("Hub IP Address".tr, color = TextMuted) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("SSH Username".tr, color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("SSH Password".tr, color = TextMuted) },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = sharedFolder,
                            onValueChange = { sharedFolder = it },
                            label = { Text("Shared Media Folder".tr, color = TextMuted) },
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
                val unknownErrorMsg = "Unknown Error".tr
                if (deployError == null) {
                    Button(
                        onClick = {
                            isDeploying = true
                            deployError = null
                            deployResult = null
                            deploymentLogs = ""
                            coroutineScope.launch {
                                val localIdentity = viewModel.localKeys.value
                                val result = com.noslop.app.net.SshDeployer.deployHaiNetHub(
                                    ip = targetIp,
                                    user = username,
                                    pass = password,
                                    sharedFolder = sharedFolder,
                                    identity = localIdentity,
                                    strategy = com.noslop.app.net.OverwriteStrategy.PROMPT,
                                    onLog = { chunk -> deploymentLogs += chunk }
                                )
                                if (result.isSuccess) {
                                    showSetupDialog = false
                                    viewModel.setHubDeploymentStatus("Active at $targetIp")
                                } else {
                                    if (result.exceptionOrNull() is com.noslop.app.net.ExistingDeploymentException) {
                                        showExistingHubDialog = true
                                        showSetupDialog = false
                                    } else {
                                        deployError = result.exceptionOrNull()?.message ?: unknownErrorMsg
                                    }
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
                            Text("Deploy".tr)
                        }
                    }
                }
            },
            dismissButton = {
                if (!isDeploying && deployError == null) {
                    TextButton(onClick = { showSetupDialog = false }) {
                        Text("Cancel".tr, color = TextMuted)
                    }
                }
            }
        )
    }
    
    if (linkError != null) {
        AlertDialog(
            onDismissRequest = { linkError = null },
            containerColor = SurfaceDark,
            title = { Text("Link Error".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
            text = { Text(linkError ?: "", color = TextLight) },
            confirmButton = {
                Button(
                    onClick = { linkError = null },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) {
                    Text("OK".tr)
                }
            }
        )
    }

    if (showExistingHubDialog) {
        AlertDialog(
            onDismissRequest = { showExistingHubDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Existing Deployment Found".tr, color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("An existing HAI-Net deployment was found on this device. What would you like to do?".tr, color = TextMuted)
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            showExistingHubDialog = false
                            isVerifyingLink = true
                            viewModel.verifyAndLinkHub(targetIp) { success, error ->
                                isVerifyingLink = false
                                if (success) {
                                    viewModel.setHubDeploymentStatus("Active at $targetIp")
                                } else {
                                    linkError = error
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                    ) {
                        Text("Sign In (Link to Hub)".tr, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            showExistingHubDialog = false
                            showSetupDialog = true
                            isDeploying = true
                            deployError = null
                            deployResult = null
                            deploymentLogs = ""
                            coroutineScope.launch {
                                val localIdentity = viewModel.localKeys.value
                                val result = com.noslop.app.net.SshDeployer.deployHaiNetHub(
                                    ip = targetIp,
                                    user = username,
                                    pass = password,
                                    sharedFolder = sharedFolder,
                                    identity = localIdentity,
                                    strategy = com.noslop.app.net.OverwriteStrategy.RESET_IDENTITY,
                                    onLog = { chunk -> deploymentLogs += chunk }
                                )
                                if (result.isSuccess) {
                                    showSetupDialog = false
                                    viewModel.setHubDeploymentStatus("Active at $targetIp")
                                } else {
                                    deployError = result.exceptionOrNull()?.message ?: com.noslop.app.util.LanguageManager.translate("Unknown Error")
                                }
                                isDeploying = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = AccentGreen)
                    ) {
                        Text("Reset Identity (Keep Media)".tr)
                    }
                    Button(
                        onClick = {
                            showExistingHubDialog = false
                            showSetupDialog = true
                            isDeploying = true
                            deployError = null
                            deployResult = null
                            deploymentLogs = ""
                            coroutineScope.launch {
                                val localIdentity = viewModel.localKeys.value
                                val result = com.noslop.app.net.SshDeployer.deployHaiNetHub(
                                    ip = targetIp,
                                    user = username,
                                    pass = password,
                                    sharedFolder = sharedFolder,
                                    identity = localIdentity,
                                    strategy = com.noslop.app.net.OverwriteStrategy.FULL_WIPE,
                                    onLog = { chunk -> deploymentLogs += chunk }
                                )
                                if (result.isSuccess) {
                                    showSetupDialog = false
                                    viewModel.setHubDeploymentStatus("Active at $targetIp")
                                } else {
                                    deployError = result.exceptionOrNull()?.message ?: com.noslop.app.util.LanguageManager.translate("Unknown Error")
                                }
                                isDeploying = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed.copy(alpha=0.2f), contentColor = DestructiveRed)
                    ) {
                        Text("Full Re-deploy (Wipe All)".tr)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExistingHubDialog = false }) {
                    Text("Cancel".tr, color = TextMuted)
                }
            }
        )
    }
}
