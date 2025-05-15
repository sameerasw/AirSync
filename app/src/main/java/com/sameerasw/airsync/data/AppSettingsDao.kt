package com.sameerasw.airsync.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings ORDER BY appName ASC")
    fun getAllApps(): Flow<List<AppSettings>>

    @Query("SELECT * FROM app_settings WHERE isEnabled = 1")
    fun getEnabledApps(): Flow<List<AppSettings>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: AppSettings)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllApps(apps: List<AppSettings>)

    @Update
    suspend fun updateApp(app: AppSettings)

    @Query("SELECT * FROM app_settings WHERE packageName = :packageName")
    suspend fun getAppByPackageName(packageName: String): AppSettings?
}