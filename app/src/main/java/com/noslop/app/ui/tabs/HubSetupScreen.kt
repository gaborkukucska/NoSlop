package com.noslop.app.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.net.HubDiscoveryService
import com.noslop.app.net.SshDeployer
import com.noslop.app.ui.theme.*
import com.noslop.app.ui.NoSlopViewModel
import kotlinx.coroutines.launch

@Composable
fun HubSetupScreen(viewModel: NoSlopViewModel, onBack: () -> Unit = {}) {
    val hubDeploymentStatus by viewModel.hubDeploymentStatus.collectAsState()

    if (!hubDeploymentStatus.isNullOrBlank()) {
        // Show Control Panel
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PrimaryBlack)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(80.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Hub Deployed", style = MaterialTheme.typography.headlineMedium, color = TextLight, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Status: $hubDeploymentStatus", color = TextMuted, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = { viewModel.setHubDeploymentStatus("") },
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = DestructiveRed)
            ) {
                Text("Reset Hub Connection")
            }
        }
        return
    }

    var ipAddress by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var sharedFolder by remember { mutableStateOf("~/.hainet/storage") }
    
    var isDeploying by remember { mutableStateOf(false) }
    var deployResult by remember { mutableStateOf<String?>(null) }
    var deployError by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val discoveryService = remember { HubDiscoveryService(context) }
    val discoveredHubs by discoveryService.discoveredHubs.collectAsState()

    DisposableEffect(Unit) {
        discoveryService.startDiscovery()
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

        if (discoveredHubs.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Discovered Hubs on Local Network", color = AccentGreen, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    discoveredHubs.forEach { hub ->
                        Surface(
                            onClick = { ipAddress = hub.ipAddress },
                            color = PrimaryBlack,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(hub.hostName, color = TextLight, fontWeight = FontWeight.Bold)
                                Text("${hub.ipAddress}:${hub.port}", color = TextMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        OutlinedTextField(
            value = ipAddress,
            onValueChange = { ipAddress = it },
            label = { Text("Hub IP Address", color = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
        )
        
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("SSH Username", color = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("SSH Password", color = TextMuted) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = sharedFolder,
            onValueChange = { sharedFolder = it },
            label = { Text("Shared Media Folder (Optional)", color = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextLight, unfocusedTextColor = TextLight)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                isDeploying = true
                deployError = null
                deployResult = null
                coroutineScope.launch {
                    val localIdentity = viewModel.localKeys.value
                    val result = SshDeployer.deployHaiNetHub(
                        ip = ipAddress,
                        user = username,
                        pass = password,
                        sharedFolder = sharedFolder,
                        identity = localIdentity
                    )
                    if (result.isSuccess) {
                        viewModel.setHubDeploymentStatus("Active at $ipAddress")
                    } else {
                        deployError = result.exceptionOrNull()?.message ?: "Unknown Error"
                    }
                    isDeploying = false
                }
            },
            enabled = !isDeploying && ipAddress.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
        ) {
            if (isDeploying) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = PrimaryBlack)
            } else {
                Text("Deploy HAI-Net Hub", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (deployResult != null) {
            Text(deployResult!!, color = AccentGreen, fontSize = 14.sp)
        }
        if (deployError != null) {
            Text(deployError!!, color = DestructiveRed, fontSize = 14.sp)
        }
    }
}
