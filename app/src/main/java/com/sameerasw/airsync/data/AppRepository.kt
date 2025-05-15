package com.sameerasw.airsync.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.Flow

class AppRepository(private val appSettingsDao: AppSettingsDao) {

    val allApps: Flow<List<AppSettings>> = appSettingsDao.getAllApps()
    val enabledApps: Flow<List<AppSettings>> = appSettingsDao.getEnabledApps()

    suspend fun updateApp(app: AppSettings) {
        appSettingsDao.updateApp(app)
    }

    suspend fun getAppByPackageName(packageName: String): AppSettings? {
        return appSettingsDao.getAppByPackageName(packageName)
    }

    suspend fun loadAllInstalledApps(context: Context) {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val appSettingsList = installedApps.map { appInfo ->
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val appName = pm.getApplicationLabel(appInfo).toString()

            // Check if app already exists in database and preserve existing enabled state
            val existingApp = appSettingsDao.getAppByPackageName(appInfo.packageName)

            AppSettings(
                packageName = appInfo.packageName,
                appName = appName,
                isSystemApp = isSystemApp,
                isEnabled = existingApp?.isEnabled ?: !isSystemApp // Default: enable non-system apps
            )
        }

        appSettingsDao.insertAllApps(appSettingsList)
    }
}