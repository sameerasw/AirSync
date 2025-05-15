package com.sameerasw.airsync.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sameerasw.airsync.data.AppDatabase
import com.sameerasw.airsync.data.AppRepository
import com.sameerasw.airsync.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: AppRepository

    private val _allApps = MutableStateFlow<List<AppSettings>>(emptyList())
    val allApps: StateFlow<List<AppSettings>> = _allApps.asStateFlow()

    private val _showSystemApps = MutableStateFlow(false)
    val showSystemApps: StateFlow<Boolean> = _showSystemApps.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        val dao = AppDatabase.getDatabase(application).appSettingsDao()
        repository = AppRepository(dao)

        viewModelScope.launch {
            repository.loadAllInstalledApps(application)

            repository.allApps.collect { apps ->
                _allApps.value = filterApps(apps)
            }
        }
    }

    fun setShowSystemApps(show: Boolean) {
        _showSystemApps.value = show
        updateFilteredApps()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        updateFilteredApps()
    }

    fun toggleAppEnabled(app: AppSettings) {
        viewModelScope.launch {
            val updatedApp = app.copy(isEnabled = !app.isEnabled)
            repository.updateApp(updatedApp)
        }
    }

    private fun updateFilteredApps() {
        viewModelScope.launch {
            repository.allApps.collect { apps ->
                _allApps.value = filterApps(apps)
            }
        }
    }

    private fun filterApps(apps: List<AppSettings>): List<AppSettings> {
        return apps.filter { app ->
            (showSystemApps.value || !app.isSystemApp) &&
                    (searchQuery.value.isEmpty() ||
                            app.appName.contains(searchQuery.value, ignoreCase = true))
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            repository.loadAllInstalledApps(getApplication())
        }
    }
}