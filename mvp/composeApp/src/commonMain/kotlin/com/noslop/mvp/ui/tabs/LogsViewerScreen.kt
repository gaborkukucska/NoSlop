package com.noslop.mvp.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.mvp.debug.Logger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsViewerScreen(onBack: () -> Unit) {
    var logs by remember { mutableStateOf("Loading logs...") }
    
    LaunchedEffect(Unit) {
        logs = "System logs will appear here in future updates.\n"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            item {
                Text(text = logs, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
