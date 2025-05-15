package com.sameerasw.airsync // Your package name

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log // Import Log
import android.widget.Toast // Import Toast
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
import com.sameerasw.airsync.ui.theme.AirSyncTheme // Ensure this theme exists

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called. Intent: ${intent?.action}")
        handleShareIntent(intent) // Handle intent if app was launched via share

        setContent {
            AirSyncTheme { // Or your theme name e.g. Theme.AirSync
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotificationSenderScreen()
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
                    NotificationForwardingService.queueClipboardData(sharedText) // New method to queue clipboard data
                    Toast.makeText(this, "Text sent to synced clipboard!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Sync service not running. Please start it first.", Toast.LENGTH_LONG).show()
                }

                // Optional: If the app was opened *only* for sharing, you might want to finish it
                // This prevents the main UI from staying open if the user just shared and expected to go back.
                // Be careful with this logic if users might also open the app to share *and* then interact with the UI.
                // if (isTaskRoot && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
                //    finish()
                // }
            }
        }
    }
}

// ... (Rest of your MainActivity: NotificationSenderScreen, permission checks, etc.)
// Make sure checkNotificationListenerPermission and checkPostNotificationPermission are still there.
// You'll need to copy the existing @Composable NotificationSenderScreen and its helper functions.
// The code you provided for MainActivity was cut off before these.
// For brevity, I'm not re-pasting the entire NotificationSenderScreen composable here,
// but it should remain as it was.

@Composable
fun NotificationSenderScreen() {
    val context = LocalContext.current
    var isServiceRunning by remember { mutableStateOf(NotificationForwardingService.isServiceRunning()) }
    var hasNotificationPermission by remember { mutableStateOf(checkNotificationListenerPermission(context)) }
    var hasPostNotificationPermission by remember { mutableStateOf(checkPostNotificationPermission(context)) }

    val localIpAddress by remember { mutableStateOf(NotificationForwardingService.getLocalIpAddress() ?: "N/A (Enable Wi-Fi)") }

    val notificationListenerPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasNotificationPermission = checkNotificationListenerPermission(context)
        if (hasNotificationPermission && isServiceRunning) {
            ContextCompat.startForegroundService(context, Intent(context, NotificationForwardingService::class.java).setAction(NotificationForwardingService.ACTION_START_SERVICE))
        }
    }

    val postNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasPostNotificationPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission) {
            postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(key1 = Unit) {
        isServiceRunning = NotificationForwardingService.isServiceRunning()
        hasNotificationPermission = checkNotificationListenerPermission(context)
        hasPostNotificationPermission = checkPostNotificationPermission(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "AirSync Client", // Or your app name
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Text(
            text = "Local IP: $localIpAddress",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (!hasPostNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("The app needs permission to show a persistent notification.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("Grant 'Post Notifications' Permission")
                    }
                }
            }
        }

        if (!hasNotificationPermission) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This app requires Notification Access to read notifications.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { notificationListenerPermissionLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                        Text("Grant Notification Access")
                    }
                    Text("Enable '${context.getString(R.string.notification_listener_service_label)}' in the list.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        } else {
            Text("Notification Access: Granted", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 16.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Text("Sync Service:", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = isServiceRunning,
                onCheckedChange = { shouldRun ->
                    if (shouldRun) {
                        if (!hasNotificationPermission) return@Switch
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission) {
                            postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@Switch
                        }
                        ContextCompat.startForegroundService(context, Intent(context, NotificationForwardingService::class.java).setAction(NotificationForwardingService.ACTION_START_SERVICE))
                        isServiceRunning = true
                    } else {
                        context.startService(Intent(context, NotificationForwardingService::class.java).setAction(NotificationForwardingService.ACTION_STOP_SERVICE))
                        isServiceRunning = false
                    }
                },
                enabled = hasNotificationPermission && (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) hasPostNotificationPermission else true)
            )
        }
        Text(if (isServiceRunning) "Service is RUNNING" else "Service is STOPPED", color = if (isServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)

        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", context.packageName, null)
            context.startActivity(intent)
        }) {
            Text("App Info (for testing)")
        }
    }
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