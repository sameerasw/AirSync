package com.sameerasw.airsync

import android.app.Notification
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.sameerasw.airsync.data.AppDatabase
import com.sameerasw.airsync.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class MyNotificationListener : NotificationListenerService() {

    private lateinit var repository: AppRepository
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val TAG = "MyNotificationListener"
    }

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.getDatabase(applicationContext)
        repository = AppRepository(database.appSettingsDao())
    }

    // Helper function to convert Drawable to Bitmap
    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        // For other drawables, attempt to draw to a new bitmap
        val intrinsicWidth = drawable.intrinsicWidth
        val intrinsicHeight = drawable.intrinsicHeight
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            return null // Cannot create bitmap with invalid dimensions
        }
        return try {
            val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error converting drawable to bitmap", e)
            null
        }
    }

    // Helper function to convert Bitmap to Base64 String
    private fun bitmapToBase64(bitmap: Bitmap?, quality: Int = 50): String? { // Lower quality for smaller string
        if (bitmap == null) return null
        return try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            // Compress to PNG or JPEG. PNG is lossless but can be larger. JPEG is lossy.
            // For icons, PNG is often preferred, but let's try JPEG for size.
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP) // NO_WRAP is important for network transmission
        } catch (e: Exception) {
            Log.e(TAG, "Error converting bitmap to Base64", e)
            null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        if (sbn.isOngoing) {
            if (sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) {
                Log.d(TAG, "Ignoring foreground service notification from ${sbn.packageName}")
                return
            }
        }

        if (sbn.packageName == applicationContext.packageName) {
            Log.d(TAG, "Ignoring notification from own app: ${sbn.packageName}")
            return
        }

        coroutineScope.launch {
            val appSettings = repository.getAppByPackageName(sbn.packageName)

            // If app not in database or disabled, don't forward notification
            if (appSettings == null) {
                // App not in database yet, likely a new app install
                // Default decision: forward notifications from non-system apps
                val pm = applicationContext.packageManager
                try {
                    val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (isSystemApp) {
                        Log.d(TAG, "Ignoring notification from new system app: ${sbn.packageName}")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking app info for ${sbn.packageName}", e)
                }
            } else if (!appSettings.isEnabled) {
                Log.d(TAG, "Ignoring notification from disabled app: ${sbn.packageName}")
                return@launch
            }

            val packageName = sbn.packageName
            val notification = sbn.notification
            val extras = notification.extras

            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

            var appName = packageName // Default to package name
            var appIconBase64: String? = null

            try {
                val pm = applicationContext.packageManager
                val applicationInfo: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
                appName = pm.getApplicationLabel(applicationInfo).toString()

                // Get App Icon
                val iconDrawable: Drawable? = pm.getApplicationIcon(applicationInfo)
                if (iconDrawable != null) {
                    val iconBitmap = drawableToBitmap(iconDrawable)
                    appIconBase64 = bitmapToBase64(iconBitmap)
                    // Log.d(TAG, "Icon Base64 length: ${appIconBase64?.length}")
                } else {
                    Log.w(TAG, "Could not get app icon drawable for $packageName")
                }

            } catch (e: Exception) {
                Log.w(TAG, "Couldn't get app info or icon for $packageName", e)
            }

            if (title.isBlank() && text.isBlank() && (bigText == null || bigText.isBlank())) {
                Log.d(
                    TAG,
                    "Ignoring notification with no visible text content from $appName ($packageName)"
                )
                return@launch
            }

            val bestText = bigText ?: text ?: ""

            Log.i(TAG, "Notification Posted:")
            Log.i(TAG, "  App: $appName ($packageName)")
            Log.i(TAG, "  Title: $title")
            Log.i(TAG, "  Text: $bestText")
            Log.i(TAG, "  Icon available: ${appIconBase64 != null}")

            // Replace this line in MyNotificationListener.kt:
            NotificationForwardingService.queueNotificationData(
                appName = appName,
                title = title,
                text = bestText,
                packageName = packageName,
                iconBase64 = appIconBase64 // Pass the icon data
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called")
        return super.onBind(intent)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification Listener connected.")
        // You could potentially query active notifications here if needed on connect
        // val activeNotifications = activeNotifications
        // Log.d(TAG, "Currently active notifications: ${activeNotifications.size}")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification Listener disconnected.")
    }


    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // sbn?.notification?.extras?.getString(Notification.EXTRA_TITLE)?.let { title ->
        //     Log.d(TAG, "Notification Removed: $title from ${sbn.packageName}")
        // }
        // You might want to send removal events to the client later.
    }
}