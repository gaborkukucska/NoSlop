package com.noslop.app.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.debug.Logger
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*

@Composable
fun LogsViewerScreen(
    viewModel: NoSlopViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedLevelFilter by remember { mutableStateOf<Logger.Level?>(null) }
    val logs = Logger.getLogs()

    val filteredLogs = if (selectedLevelFilter == null) logs else logs.filter { it.level == selectedLevelFilter }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack).padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AccentGreen)
                }
                Text("System Logs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextLight)
            }

            Row {
                IconButton(onClick = { viewModel.copyLogToClipboard(context) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs", tint = AccentGreen)
                }
                IconButton(onClick = { viewModel.clearLogFile() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear logs", tint = DestructiveRed)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val filters = listOf("All", "DEBUG", "INFO", "WARN", "ERROR")
            filters.forEach { label ->
                val level = when (label) {
                    "DEBUG" -> Logger.Level.DEBUG
                    "INFO" -> Logger.Level.INFO
                    "WARN" -> Logger.Level.WARN
                    "ERROR" -> Logger.Level.ERROR
                    else -> null
                }
                val isSelected = selectedLevelFilter == level
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) AccentGreen else SurfaceDark)
                        .clickable { selectedLevelFilter = level }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(text = label, color = if (isSelected) PrimaryBlack else TextLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "Log File: ${viewModel.logFilePath}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = TextMuted,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceDark)
                .padding(8.dp)
        ) {
            items(filteredLogs) { log ->
                val col = when (log.level) {
                    Logger.Level.DEBUG -> TextMuted
                    Logger.Level.INFO -> AccentGreen
                    Logger.Level.WARN -> Color(0xFFFFB300)
                    Logger.Level.ERROR -> DestructiveRed
                }
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "[${log.timestamp}] [${log.level}] [${log.module}]",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = col,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = log.message,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextLight,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    log.details?.let {
                        Text(
                            text = "Details: $it",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 2.dp, start = 8.dp)
                        )
                    }
                    HorizontalDivider(color = BorderSubtle.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
