package com.sameerasw.airsync

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sameerasw.airsync.data.AppDatabase
import com.sameerasw.airsync.data.AppRepository
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import kotlinx.coroutines.launch

// New imports for About Dialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called. Intent: ${intent?.action}")
        handleShareIntent(intent) // Handle intent if app was launched via share

        // Initialize app database
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = AppRepository(database.appSettingsDao())

        lifecycleScope.launch {
            repository.loadAllInstalledApps(applicationContext)
        }

        setContent {
            AirSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotificationSenderScreen(
                        onOpenAppList = {
                            startActivity(Intent(this, AppListActivity::class.java))
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "onNewIntent called. Intent: ${intent.action}")
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return

        if (Intent.ACTION_SEND == intent.action && "text/plain" == intent.type) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                Log.i("MainActivity", "Shared text received: $sharedText")
                if (NotificationForwardingService.isServiceRunning()) {
                    NotificationForwardingService.queueClipboardData(sharedText)
                    Toast.makeText(this, "Text sent to Mac", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Service not running. Start service first.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
fun NotificationSenderScreen(onOpenAppList: () -> Unit) {
    val context = LocalContext.current
    var isServiceRunning by remember { mutableStateOf(NotificationForwardingService.isServiceRunning()) }
    var hasNotificationPermission by remember { mutableStateOf(checkNotificationListenerPermission(context)) }
    var hasPostNotificationPermission by remember { mutableStateOf(checkPostNotificationPermission(context)) }
    val localIpAddress by remember { mutableStateOf(NotificationForwardingService.getLocalIpAddress() ?: "N/A (Enable Wi-Fi)") }

    val notificationListenerPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasNotificationPermission = checkNotificationListenerPermission(context)
    }

    val postNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPostNotificationPermission = isGranted
    }

    LaunchedEffect(Unit) {
        hasNotificationPermission = checkNotificationListenerPermission(context)
        hasPostNotificationPermission = checkPostNotificationPermission(context)
    }

    // Poll periodically to update service status and permissions
    LaunchedEffect(key1 = Unit) {
        kotlinx.coroutines.delay(1000) // Consider making this a constant or configurable
        isServiceRunning = NotificationForwardingService.isServiceRunning()
        hasNotificationPermission = checkNotificationListenerPermission(context)
        hasPostNotificationPermission = checkPostNotificationPermission(context)
    }

    MainScreen(
        isServiceRunning = isServiceRunning,
        hasNotificationPermission = hasNotificationPermission,
        hasPostNotificationPermission = hasPostNotificationPermission,
        localIpAddress = localIpAddress,
        onServiceToggle = { shouldRun ->
            if (shouldRun) {
                // Try to start the service
                if (!hasNotificationPermission) {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    notificationListenerPermissionLauncher.launch(intent)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission) {
                    postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPostNotificationPermission)
                    && hasNotificationPermission) {
                    val serviceIntent = Intent(context, NotificationForwardingService::class.java).apply {
                        action = NotificationForwardingService.ACTION_START_SERVICE
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                    isServiceRunning = true
                } else {
                    // Display a toast if permissions are still missing before attempting to start
                    if (!hasNotificationPermission) {
                        Toast.makeText(context, "Notification Access permission is required.", Toast.LENGTH_LONG).show()
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission) {
                        Toast.makeText(context, "Post Notifications permission is required.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                // Stop the service
                val serviceIntent = Intent(context, NotificationForwardingService::class.java).apply {
                    action = NotificationForwardingService.ACTION_STOP_SERVICE
                }
                context.startService(serviceIntent)
                isServiceRunning = false
            }
        },
        onOpenAppList = onOpenAppList
    )
}

@Composable
fun MainScreen(
    isServiceRunning: Boolean,
    hasNotificationPermission: Boolean,
    hasPostNotificationPermission: Boolean,
    localIpAddress: String,
    onServiceToggle: (Boolean) -> Unit,
    onOpenAppList: () -> Unit
) {
    var showAboutDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) { // Wrap in Box for dialog overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "AirSync",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Permission status
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Notification Access",
                            modifier = Modifier.weight(1f)
                        )
                        if (hasNotificationPermission) {
                            Text(
                                text = "Granted",
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            val context = LocalContext.current
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    context.startActivity(intent)
                                }
                            ) {
                                Text("Grant")
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Post Notifications",
                                modifier = Modifier.weight(1f)
                            )
                            if (hasPostNotificationPermission) {
                                Text(
                                    text = "Granted",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                val context = LocalContext.current
                                // Re-using parent's launcher, ensure it's correctly triggered
                                // The button here currently re-declares a launcher, which is not ideal.
                                // For simplicity, we assume the parent's launcher mechanism is the primary way.
                                // This button here is more of a visual cue if permission is denied.
                                // The actual request logic is in NotificationSenderScreen.
                                val postNotificationPermissionLauncher = rememberLauncherForActivityResult(
                                    ActivityResultContracts.RequestPermission()
                                ) { /* This result should ideally update the state in NotificationSenderScreen */ }

                                Button(
                                    onClick = {
                                        postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                ) {
                                    Text("Grant")
                                }
                            }
                        }
                    }
                }
            }

            // Service status and control
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Service Status",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isServiceRunning) "Running" else "Stopped",
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isServiceRunning,
                            onCheckedChange = { onServiceToggle(it) },
                            enabled = hasNotificationPermission &&
                                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPostNotificationPermission)
                        )
                    }

                    if (isServiceRunning) {
                        Text(
                            text = "Your device IP: $localIpAddress",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Port: ${NotificationForwardingService.SERVER_PORT}",
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // App list button
            Button(
                onClick = onOpenAppList,
                modifier = Modifier.padding(top = 16.dp),
                enabled = true
            ) {
                Text("Manage App Notifications")
            }

            // Instructions
            if (isServiceRunning) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "How to Use",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "1. Make sure both devices are on the same WiFi network\n" +
                                    "2. Note your IP address: $localIpAddress\n" +
                                    "3. On your Mac, enter this address and port ${NotificationForwardingService.SERVER_PORT}\n" +
                                    "4. You can also share text to this app to send to your Mac",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp)) // Space before About button

            // About Button
            OutlinedButton(
                onClick = { showAboutDialog = true }
            ) {
                Text("About AirSync")
            }
        } // End of Column

        // About Dialog - displayed on top of other content when showAboutDialog is true
        if (showAboutDialog) {
            AboutDialog(
                onDismissRequest = { showAboutDialog = false },
                appName = "AirSync",
                developerName = "Sameera Wijerathna", // Replace with your name if different
                description = "AirSync forwards Android notifications and shared text to your Mac over your local Wi-Fi network.",
                githubUsername = "sameerasw" // Replace with your GitHub username if different
            )
        }
    } // End of Box
}

fun checkNotificationListenerPermission(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat?.contains(ComponentName(context, MyNotificationListener::class.java).flattenToString()) == true
}

fun checkPostNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true
}

// AboutDialog Composable (adapted from Moview app)
@Composable
fun AboutDialog(
    onDismissRequest: () -> Unit,
    appName: String,
    developerName: String,
    description: String,
    githubUsername: String? = null
) {
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("About $appName") },
        text = {
            Column {
                Text("Developed by: $developerName", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(description, style = MaterialTheme.typography.bodySmall)
                githubUsername?.let {
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = {
                        uriHandler.openUri("https://github.com/$it")
                    }) {
                        Text("GitHub: @$it", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismissRequest) {
                Text("Close")
            }
        }
    )
}