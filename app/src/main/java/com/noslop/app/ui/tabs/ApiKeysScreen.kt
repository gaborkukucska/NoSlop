package com.noslop.app.ui.tabs

import com.noslop.app.util.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.data.ApiKeyRepository
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.api.ApiKeyTester
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ApiKeysScreen(viewModel: NoSlopViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val apiKeyRepo = remember { ApiKeyRepository(context) }

    // Track keys to refresh UI when changed
    var keysUpdateTrigger by remember { mutableStateOf(0) }
    var configuring by remember { mutableStateOf<ApiKeyRepository.ServiceInfo?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back".tr, tint = AccentGreen)
            }
            Text("API Keys".tr, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextLight)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Most sources work without a key. A free key of your own adds the ones below. Keys are stored encrypted on this device.".tr,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(ApiKeyRepository.SERVICES) { service ->
                val currentKey = remember(keysUpdateTrigger) { apiKeyRepo.getKey(service.id) }

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
                                    Text("Optional".tr, color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(service.unlocks.tr, fontSize = 12.sp, color = TextMuted)

                        Spacer(modifier = Modifier.height(12.dp))

                        if (currentKey.isNullOrBlank()) {
                            Button(
                                onClick = { configuring = service },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                                border = BorderStroke(1.dp, AccentGreen)
                            ) {
                                Text("Configure Key".tr)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                val masked = currentKey.take(4) + "••••••••" + currentKey.takeLast(4)
                                Text(masked, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextLight)

                                Row {
                                    IconButton(onClick = { configuring = service }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit".tr, tint = AccentGreen, modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            apiKeyRepo.removeCredentials(service)
                                            keysUpdateTrigger++
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove".tr, tint = DestructiveRed, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    configuring?.let { service ->
        ConfigureKeySheet(
            service = service,
            apiKeyRepo = apiKeyRepo,
            onDismiss = { configuring = null },
            onSaved = {
                configuring = null
                keysUpdateTrigger++
                viewModel.enableSourcesFor(service.id)
            }
        )
    }
}

/** How to get a service's key, where to paste it, and a way to check it before saving. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigureKeySheet(
    service: ApiKeyRepository.ServiceInfo,
    apiKeyRepo: ApiKeyRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val hasSecret = service.secretLabel != null
    val stored = remember(service.id) {
        if (hasSecret) apiKeyRepo.podcastIndexCredentials() else apiKeyRepo.getKey(service.id)?.let { it to "" }
    }
    var key by remember { mutableStateOf(stored?.first ?: apiKeyRepo.getKey(service.id).orEmpty()) }
    var secret by remember { mutableStateOf(stored?.second.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ApiKeyTester.Outcome?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .imePadding()
        ) {
            Text(service.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextLight)
            Spacer(modifier = Modifier.height(4.dp))
            Text(service.unlocks.tr, fontSize = 13.sp, color = TextMuted)

            if (service.steps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                service.steps.forEachIndexed { index, step ->
                    Row(modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("${index + 1}.", color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                        Text(step.tr, fontSize = 14.sp, color = TextLight)
                    }
                }
            }

            if (service.signupUrl.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        try {
                            uriHandler.openUri(service.signupUrl)
                        } catch (e: Exception) {
                            Logger.warn("API_KEYS", "Could not open the sign-up page: ${e.message}")
                        }
                    },
                    border = BorderStroke(1.dp, AccentGreen)
                ) {
                    Text("Open sign-up page".tr, color = AccentGreen)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            KeyField(value = key, label = service.keyLabel.tr, onValueChange = { key = it; outcome = null })
            if (hasSecret) {
                Spacer(modifier = Modifier.height(8.dp))
                KeyField(value = secret, label = service.secretLabel.orEmpty().tr, onValueChange = { secret = it; outcome = null })
            }

            outcome?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    outcomeMessage(it),
                    fontSize = 13.sp,
                    color = if (it == ApiKeyTester.Outcome.WORKS) AccentGreen else DestructiveRed
                )
            }
            service.credit?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it.tr, fontSize = 12.sp, color = TextMuted)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel".tr, color = TextMuted)
                }
                if (ApiKeyTester.canTest(service.id)) {
                    TextButton(
                        enabled = key.isNotBlank() && !testing,
                        onClick = {
                            testing = true
                            outcome = null
                            scope.launch {
                                outcome = ApiKeyTester.test(service.id, key.trim(), secret.trim())
                                testing = false
                            }
                        }
                    ) {
                        Text(if (testing) "Testing...".tr else "Test key".tr, color = AccentGreen)
                    }
                }
                Button(
                    onClick = {
                        apiKeyRepo.saveCredentials(service, key, secret)
                        onSaved()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) {
                    Text("Save".tr, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun KeyField(value: String, label: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
            focusedTextColor = TextLight, unfocusedTextColor = TextLight
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun outcomeMessage(outcome: ApiKeyTester.Outcome): String = when (outcome) {
    ApiKeyTester.Outcome.WORKS -> "This key works."
    ApiKeyTester.Outcome.REFUSED -> "The service refused this key. Check it and try again."
    ApiKeyTester.Outcome.REFUSED_OVER_TOR -> "The service refused the request. It may be blocking Tor, so the key could still be fine. Turn off Route Clearnet via Tor to check."
    ApiKeyTester.Outcome.LIMIT_REACHED -> "This key has used up its requests for now. Try again later."
    ApiKeyTester.Outcome.UNREACHABLE -> "Couldn't reach the service. Check your connection and try again."
}.tr
