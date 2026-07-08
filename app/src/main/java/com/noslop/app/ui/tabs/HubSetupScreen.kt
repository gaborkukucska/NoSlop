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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var cloudflareToken by remember { mutableStateOf("") }
    
    var isDeploying by remember { mutableStateOf(false) }
    var deployResult by remember { mutableStateOf<String?>(null) }
    var deployError by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

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

        // Cloudflare Warning
        Card(
            colors = CardDefaults.cardColors(containerColor = DestructiveRed.copy(alpha = 0.1f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = DestructiveRed)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Remote Access Requires Tunnel", color = DestructiveRed, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Unless your home ISP provides a static IP and you configure port forwarding, a Cloudflare Tunnel token is strictly required for your mobile app to access this hub remotely.",
                        color = TextLight,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

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
            value = cloudflareToken,
            onValueChange = { cloudflareToken = it },
            label = { Text("Cloudflare Tunnel Token", color = TextMuted) },
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
                    val result = SshDeployer.deployHaiNetHub(ipAddress, username, password, cloudflareToken)
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
