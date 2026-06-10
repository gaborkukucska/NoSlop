package com.noslop.app.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.data.ApiKeyRepository
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*

@Composable
fun ApiKeysScreen(viewModel: NoSlopViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val apiKeyRepo = remember { ApiKeyRepository(context) }
    
    // Track keys to refresh UI when changed
    var keysUpdateTrigger by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AccentGreen)
            }
            Text("API Keys", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextLight)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Some content sources require API keys to function. Keys are stored securely in EncryptedSharedPreferences.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(ApiKeyRepository.SERVICES) { service ->
                val currentKey = remember(keysUpdateTrigger) { apiKeyRepo.getKey(service.id) }
                var isEditing by remember { mutableStateOf(false) }
                var draftKey by remember { mutableStateOf(currentKey ?: "") }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(service.displayName, fontWeight = FontWeight.Bold, color = TextLight)
                            if (!service.requiresUserKey) {
                                Box(modifier = Modifier.background(AccentGreen.copy(alpha=0.2f), RoundedCornerShape(4.dp)).padding(horizontal=6.dp, vertical=2.dp)) {
                                    Text("Optional", color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Get key: ${service.signupUrl}", fontSize = 10.sp, color = TextMuted)
                        
                        Spacer(modifier = Modifier.height(12.dp))

                        if (isEditing) {
                            OutlinedTextField(
                                value = draftKey,
                                onValueChange = { draftKey = it },
                                placeholder = { Text("Enter API Key") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                                    focusedTextColor = TextLight, unfocusedTextColor = TextLight
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { isEditing = false; draftKey = currentKey ?: "" }) {
                                    Text("Cancel", color = TextMuted)
                                }
                                Button(
                                    onClick = {
                                        if (draftKey.isBlank()) {
                                            apiKeyRepo.removeKey(service.id)
                                        } else {
                                            apiKeyRepo.setKey(service.id, draftKey)
                                        }
                                        keysUpdateTrigger++
                                        isEditing = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                                ) {
                                    Text("Save", fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            if (currentKey.isNullOrBlank()) {
                                Button(
                                    onClick = { isEditing = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                                    border = BorderStroke(1.dp, AccentGreen)
                                ) {
                                    Text("Configure Key")
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    val masked = currentKey.take(4) + "••••••••" + currentKey.takeLast(4)
                                    Text(masked, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextLight)
                                    
                                    Row {
                                        IconButton(onClick = { isEditing = true }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = AccentGreen, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = { 
                                                apiKeyRepo.removeKey(service.id)
                                                keysUpdateTrigger++ 
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = DestructiveRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
