package com.example.timetablescraper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.timetablescraper.CrashHandler
import com.example.timetablescraper.api.cache.TimetableDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
/**
 * Full-screen fatal error recovery composable.
 *
 * Displayed by [MainActivity] when a crash from a previous session is
 * detected via [CrashHandler.hasCrashOccurred], OR in the current session
 * when a recoverable fatal error is caught by the root error boundary.
 *
 * ## Recovery actions
 * - **Try Again** — clears the crash flag and resumes normal UI.
 * - **Clear Cache & Restart** — wipes Room + SharedPreferences and
 *   restarts the activity (safe-mode recovery).
 */
@Composable
fun FatalErrorScreen(
    crashInfo: CrashHandler.CrashInfo? = null,
    onClearAndRestart: () -> Unit = {},
    onTryAgain: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isClearing by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error
                )

                Text(
                    "Something went wrong",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    "The app encountered an unexpected error and needs to recover. " +
                            "Your cached timetables may need to be refreshed.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Expandable error details
                if (crashInfo != null) {
                    var showDetails by remember { mutableStateOf(false) }
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "Hide details" else "Show error details")
                    }
                    if (showDetails) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = buildString {
                                    append(crashInfo.message ?: "Unknown error")
                                    if (crashInfo.stacktrace != null) {
                                        appendLine()
                                        appendLine()
                                        append(crashInfo.stacktrace)
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(12.dp),
                                maxLines = 30
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Try Again — non-destructive
                OutlinedButton(
                    onClick = {
                        CrashHandler.clearCrashFlag(context)
                        onTryAgain()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isClearing
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try Again")
                }

                // Clear Cache & Restart — destructive recovery
                Button(
                    onClick = {
                        isClearing = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val db = TimetableDatabase.getInstance(context)
                                db.clearAllTables()
                                context.getSharedPreferences("timetable_sync_prefs", Context.MODE_PRIVATE)
                                    .edit().clear().apply()
                                CrashHandler.clearCrashFlag(context)
                            }
                            onClearAndRestart()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    enabled = !isClearing
                ) {
                    if (isClearing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Clear Cache & Restart")
                }
            }
        }
    }
}
