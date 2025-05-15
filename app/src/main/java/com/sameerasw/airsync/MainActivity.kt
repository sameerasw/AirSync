package com.sameerasw.airsync

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.sameerasw.airsync.ui.theme.AirSyncTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AirSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotificationSenderScreen()
                }
            }
        }
    }
}

@Composable
fun NotificationSenderScreen() {
    val context = LocalContext.current
    var isServiceRunning by remember { mutableStateOf(NotificationForwardingService.isServiceRunning()) }
    var hasNotificationPermission by remember { mutableStateOf(checkNotificationListenerPermission(context)) }
    var hasPostNotificationPermission by remember { mutableStateOf(checkPostNotificationPermission(context)) } // For foreground service notification

    val localIpAddress by remember { mutableStateOf(NotificationForwardingService.getLocalIpAddress() ?: "N/A (Enable Wi-Fi)") }

    // Launcher for Notification Listener Permission
    val notificationListenerPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Result isn't directly useful here, we need to re-check the setting
        hasNotificationPermission = checkNotificationListenerPermission(context)
        if (hasNotificationPermission && isServiceRunning) {
            // If permission was just granted and service should be running, ensure it tries to start listener
            ContextCompat.startForegroundService(context, Intent(context, NotificationForwardingService::class.java).setAction(NotificationForwardingService.ACTION_START_SERVICE))
        }
    }

    // Launcher for Android 13+ Post Notification Permission (for the foreground service's own notification)
    val postNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasPostNotificationPermission = isGranted
        if (!isGranted) {
            // Handle denial if necessary (e.g., show a message)
        }
    }

    LaunchedEffect(Unit) { // Request Post Notification permission on launch if on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission) {
            postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Periodically update service running state (e.g., when composable recomposes after activity resume)
    // A more robust way would be a Flow or LiveData from the service.
    LaunchedEffect(key1 = Unit) { // Re-check on initial composition
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
            text = "Android Notification Sender",
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
                    Text(
                        "The app needs permission to show a persistent notification while the service is running.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }) {
                        Text("Grant 'Post Notifications' Permission")
                    }
                }
            }
        }


        if (!hasNotificationPermission) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "This app requires Notification Access to read notifications from other apps.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        notificationListenerPermissionLauncher.launch(intent)
                    }) {
                        Text("Grant Notification Access")
                    }
                    Text(
                        "Enable '${context.getString(R.string.notification_listener_service_label)}' in the list.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            Text(
                "Notification Access: Granted",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("Forwarding Service:", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = isServiceRunning,
                onCheckedChange = { shouldRun ->
                    if (shouldRun) {
                        if (!hasNotificationPermission) {
                            // Optionally show a snackbar or alert
                            // Toast.makeText(context, "Please grant Notification Access first.", Toast.LENGTH_LONG).show()
                            return@Switch
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission) {
                            // Toast.makeText(context, "Please grant Post Notification permission first.", Toast.LENGTH_LONG).show()
                            postNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) // Re-ask if not granted
                            return@Switch
                        }
                        val serviceIntent = Intent(context, NotificationForwardingService::class.java)
                        serviceIntent.action = NotificationForwardingService.ACTION_START_SERVICE
                        ContextCompat.startForegroundService(context, serviceIntent)
                        isServiceRunning = true
                    } else {
                        val serviceIntent = Intent(context, NotificationForwardingService::class.java)
                        serviceIntent.action = NotificationForwardingService.ACTION_STOP_SERVICE
                        context.startService(serviceIntent) // Can also use stopService directly
                        isServiceRunning = false
                    }
                },
                enabled = hasNotificationPermission && (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) hasPostNotificationPermission else true)
            )
        }
        Text(
            if (isServiceRunning) "Service is RUNNING" else "Service is STOPPED",
            color = if (isServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = {
            // Action to open app settings to manually kill/restart if needed for testing
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = Uri.fromParts("package", context.packageName, null)
            intent.data = uri
            context.startActivity(intent)
        }) {
            Text("App Info (for testing)")
        }
    }
}

fun checkNotificationListenerPermission(context: Context): Boolean {
    val componentName = ComponentName(context, MyNotificationListener::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat?.contains(componentName.flattenToString()) == true
}

fun checkPostNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true // Not needed before Android 13
    }
}