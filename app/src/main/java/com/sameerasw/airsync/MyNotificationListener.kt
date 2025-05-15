package com.sameerasw.airsync


import android.app.Notification
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MyNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "MyNotificationListener"
        // Action to send notification data to our own app/service
        const val ACTION_SEND_NOTIFICATION_DATA = "com.yourname.notificationsender.SEND_NOTIFICATION_DATA"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Ignore ongoing notifications (like downloads, music players unless you want them)
        if (sbn.isOngoing) {
            // Log.d(TAG, "Ignoring ongoing notification from ${sbn.packageName}")
            // You might want to make this configurable later
            // For now, let's allow some ongoing like music for testing, but not foreground service notifs
            if (sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) {
                Log.d(TAG, "Ignoring foreground service notification from ${sbn.packageName}")
                return
            }
        }

        // Ignore our own app's notifications (e.g., the foreground service notification)
        if (sbn.packageName == applicationContext.packageName) {
            Log.d(TAG, "Ignoring notification from own app: ${sbn.packageName}")
            return
        }


        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        // Sometimes EXTRA_BIG_TEXT contains more details
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        val appName = try {
            val pm = applicationContext.packageManager
            val applicationInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't get app name for $packageName", e)
            packageName // Fallback to package name
        }

        // We only care about notifications that have some text content
        if (title.isBlank() && text.isBlank() && (bigText == null || bigText.isBlank())) {
            Log.d(TAG, "Ignoring notification with no visible text content from $appName ($packageName)")
            return
        }

        val bestText = bigText ?: text

        Log.i(TAG, "Notification Posted:")
        Log.i(TAG, "  App: $appName ($packageName)")
        Log.i(TAG, "  Title: $title")
        Log.i(TAG, "  Text: $bestText")
        // Log.i(TAG, "  ID: ${sbn.id}")
        // Log.i(TAG, "  Key: ${sbn.key}")
        // Log.i(TAG, "  Tag: ${sbn.tag}")
        // Log.i(TAG, "  Post Time: ${sbn.postTime}")

        // --- TODO: Send this data to our NetworkServer ---
        // For now, we'll use a LocalBroadcastManager or an Intent to send it to our Foreground Service
        // which will then handle the network communication.
        NotificationForwardingService.queueNotificationData(
            appName = appName,
            title = title,
            text = bestText ?: "",
            packageName = packageName
        )

        // Using a more specific local broadcast or direct service call is better than global broadcast.
        // For simplicity, let's assume a service will pick this up.
        // LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        // Or, if NetworkService is running, you could try to bind to it or send a command.
        // For now, we will have the NetworkService start this listener if enabled,
        // and the listener will directly call a method in the NetworkService.

        // This is a placeholder for direct communication. This service (MyNotificationListener)
        // will be started and managed by NotificationForwardingService.
        // NotificationForwardingService.sendNotificationToClients(this, appName, title, bestText, packageName)
        // The above line would create a tight coupling. Instead, use an event bus or broadcast.
        // For now, we'll make NotificationForwardingService a singleton or provide a static method to queue data.
        NotificationForwardingService.queueNotificationData(
            appName = appName,
            title = title,
            text = bestText,
            packageName = packageName
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // sbn?.notification?.extras?.getString(Notification.EXTRA_TITLE)?.let { title ->
        //     Log.d(TAG, "Notification Removed: $title from ${sbn.packageName}")
        // }
        // You might want to send removal events to the client later.
    }
}