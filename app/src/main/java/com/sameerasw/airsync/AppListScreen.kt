package com.sameerasw.airsync

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sameerasw.airsync.data.AppDatabase
import com.sameerasw.airsync.data.AppRepository
import com.sameerasw.airsync.data.AppSettings
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }
    val repository = remember { AppRepository(database.appSettingsDao()) }

    var allApps by remember { mutableStateOf<List<AppSettings>>(emptyList()) }
    var showSystemApps by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    // Load apps when composable is first created
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            repository.loadAllInstalledApps(context)
            repository.allApps.collect { apps ->
                allApps = filterApps(apps, showSystemApps, searchQuery)
                isLoading = false
            }
        }
    }

    // Update filtered apps when filters change
    LaunchedEffect(showSystemApps, searchQuery) {
        coroutineScope.launch {
            repository.allApps.collect { apps ->
                allApps = filterApps(apps, showSystemApps, searchQuery)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Notifications") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                singleLine = true
            )

            // System apps toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Show system apps",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = showSystemApps,
                    onCheckedChange = { showSystemApps = it }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // List of apps
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Text(
                    text = "Toggle apps below to enable/disable notification forwarding:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn {
                    items(allApps) { app ->
                        AppItem(
                            app = app,
                            onToggle = {
                                coroutineScope.launch {
                                    val updatedApp = app.copy(isEnabled = !app.isEnabled)
                                    repository.updateApp(updatedApp)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun filterApps(
    apps: List<AppSettings>,
    showSystemApps: Boolean,
    searchQuery: String
): List<AppSettings> {
    return apps.filter { app ->
        (showSystemApps || !app.isSystemApp) &&
                (searchQuery.isEmpty() ||
                        app.appName.contains(searchQuery, ignoreCase = true))
    }
}

@Composable
fun AppItem(app: AppSettings, onToggle: () -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager

    val appIcon = remember(app.packageName) {
        try {
            packageManager.getApplicationIcon(app.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon
        if (appIcon != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(appIcon)
                    .build(),
                contentDescription = "App icon",
                modifier = Modifier.size(40.dp)
            )
        } else {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text("?")
            }
        }

        // App info
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Toggle switch
        Switch(
            checked = app.isEnabled,
            onCheckedChange = { onToggle() }
        )
    }

    Divider()
}