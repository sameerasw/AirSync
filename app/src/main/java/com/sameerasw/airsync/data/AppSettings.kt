package com.sameerasw.airsync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    var isEnabled: Boolean = true
)