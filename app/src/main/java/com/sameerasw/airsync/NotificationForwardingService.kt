package com.sameerasw.airsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

class NotificationForwardingService : Service() {

    companion object {
        const val TAG = "NotificationFwdSvc"
        private const val NOTIFICATION_CHANNEL_ID = "NotificationForwardingChannel"
        private const val NOTIFICATION_ID = 1
        // In NotificationForwardingService.kt
        const val ACTION_START_SERVICE = "com.sameerasw.airsync.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.sameerasw.airsync.ACTION_STOP_SERVICE"
        const val SERVER_PORT = 12345 // Ensure this matches your Python client

        // Static queue for notifications from MyNotificationListener
        private val notificationQueue = LinkedBlockingQueue<NotificationData>()
        private var instance: NotificationForwardingService? = null // For simple access

        fun isServiceRunning(): Boolean = instance != null

        fun queueNotificationData(appName: String, title: String, text: String, packageName: String) {
            if (!isServiceRunning()) {
                Log.w(TAG, "Service not running, cannot queue notification data.")
                return
            }
            try {
                notificationQueue.put(NotificationData(appName, title, text, packageName))
                Log.d(TAG, "Notification data queued for ${appName}.")
            } catch (e: InterruptedException) {
                Log.e(TAG, "Failed to queue notification data", e)
                Thread.currentThread().interrupt()
            }
        }

        fun getLocalIpAddress(): String? {
            try {
                val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs: List<InetAddress> = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) { // Check for IPv4
                            return addr.hostAddress
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Get IP error", ex)
            }
            return null
        }
    }

    data class NotificationData(
        val appName: String,
        val title: String,
        val text: String,
        val packageName: String
    )

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var serverSocket: ServerSocket? = null
    private val clientSockets = ConcurrentHashMap<Socket, PrintWriter>() // Thread-safe map for clients

    private var isListenerBound = false


    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Service Created. IP: ${getLocalIpAddress()}")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createServiceNotification("Starting up..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand received: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startForeground(NOTIFICATION_ID, createServiceNotification("Listening for notifications... IP: ${getLocalIpAddress() ?: "N/A"}"))
                ensureNotificationListenerEnabled() // Attempt to enable/check listener
                serviceScope.launch { startServer() }
                serviceScope.launch { processNotificationQueue() }
            }
            ACTION_STOP_SERVICE -> {
                Log.i(TAG, "Stopping service...")
                stopSelf()
            }
        }
        return START_STICKY // Restart service if killed
    }

    private fun createServiceNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Notification Sync Active")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher_round) // Replace with a proper notification icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Don't make sound/vibrate on updates
            .build()
    }

    private fun updateNotificationMessage(message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createServiceNotification(message))
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Notification Forwarding Service Channel",
                NotificationManager.IMPORTANCE_LOW // Use LOW to avoid sound for ongoing
            ).apply {
                description = "Channel for the notification forwarding service's persistent notification."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun ensureNotificationListenerEnabled() {
        val componentName = ComponentName(this, MyNotificationListener::class.java)
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val isEnabled = enabledListeners?.contains(componentName.flattenToString()) == true

        if (isEnabled) {
            Log.i(TAG, "Notification listener is enabled.")
            if (!isListenerBound) {
                // Ensure the listener service is actually started by the system
                // This usually happens automatically if permission is granted.
                // We can also try to bind/start it if it's not.
                try {
                    packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.i(TAG, "Requested to enable NotificationListener component state.")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException: Not allowed to enable NotificationListener component. Needs user permission first.", e)
                }
                isListenerBound = true // Assume it will bind if permission is granted
            }
        } else {
            Log.w(TAG, "Notification listener is NOT enabled. User needs to grant permission.")
            // The UI should guide the user to grant permission.
            // We cannot programmatically grant it here.
            isListenerBound = false
        }
    }


    private suspend fun startServer() {
        withContext(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                val ipAddress = getLocalIpAddress() ?: "N/A"
                Log.i(TAG, "Server started on port $SERVER_PORT. IP: $ipAddress")
                updateNotificationMessage("Listening on IP: $ipAddress Port: $SERVER_PORT")

                while (isActive) { // isActive is from CoroutineScope
                    try {
                        val clientSocket = serverSocket!!.accept() // Blocking call
                        Log.i(TAG, "Client connected: ${clientSocket.inetAddress.hostAddress}")
                        val writer = PrintWriter(BufferedWriter(OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8")), true)
                        clientSockets[clientSocket] = writer
                        // Send a welcome message or status
                        writer.println(JSONObject().apply {
                            put("type", "status")
                            put("message", "Connected to AndroidNotificationSender")
                            put("android_version", Build.VERSION.RELEASE)
                        }.toString())

                        // Optional: Start a new coroutine to handle reads from this client if needed
                        // For now, we only write to clients.
                    } catch (e: SocketException) {
                        if (!isActive || serverSocket?.isClosed == true) {
                            Log.i(TAG, "Server socket closed, exiting accept loop.")
                            break
                        }
                        Log.e(TAG, "SocketException in accept loop (client might have disconnected abruptly or socket closed): ${e.message}")
                    } catch (e: IOException) {
                        Log.e(TAG, "IOException in server accept loop", e)
                        delay(1000) // Wait a bit before retrying
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server could not start or encountered a fatal error.", e)
                updateNotificationMessage("Error: ${e.message}")
            } finally {
                Log.i(TAG, "Server loop ending.")
                stopServer()
            }
        }
    }

    private fun stopServer() {
        Log.i(TAG, "Stopping server and closing client connections.")
        try {
            serverSocket?.close()
            serverSocket = null
            synchronized(clientSockets) {
                clientSockets.forEach { (socket, writer) ->
                    try {
                        writer.close()
                        socket.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "Error closing client socket: ${socket.inetAddress}", e)
                    }
                }
                clientSockets.clear()
            }
            Log.i(TAG, "All client connections closed and server socket stopped.")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
    }

    private suspend fun processNotificationQueue() {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Notification processing queue started.")
            try {
                while (isActive) {
                    val data = notificationQueue.take() // Blocks until an item is available
                    Log.d(TAG, "Processing notification for ${data.appName} from queue.")
                    sendNotificationToAllClients(data)
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "Notification processing queue interrupted.")
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Exception in notification processing queue", e)
            } finally {
                Log.i(TAG, "Notification processing queue stopped.")
            }
        }
    }


    private fun sendNotificationToAllClients(data: NotificationData) {
        if (clientSockets.isEmpty()) {
            //Log.d(TAG, "No clients connected, not sending notification: ${data.title}")
            return
        }

        val json = JSONObject()
        json.put("app", data.appName)
        json.put("title", data.title)
        json.put("text", data.text)
        json.put("packageName", data.packageName) // Optional: for more advanced filtering on client
        val jsonString = json.toString()

        Log.d(TAG, "Sending to ${clientSockets.size} client(s): $jsonString")
        val clientsToRemove = mutableListOf<Socket>()
        synchronized(clientSockets) {
            clientSockets.forEach { (socket, writer) ->
                try {
                    // Run send on a separate IO dispatcher to avoid blocking the queue processor
                    // for too long if one client is slow or has issues.
                    // However, for simplicity here, direct send. If issues, wrap in another launch.
                    writer.println(jsonString) // println adds a newline, important for client
                    if (writer.checkError()) { // Check for errors after write
                        Log.w(TAG, "Error sending to client ${socket.inetAddress}. Marking for removal.")
                        clientsToRemove.add(socket)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception sending to client ${socket.inetAddress}", e)
                    clientsToRemove.add(socket)
                }
            }

            clientsToRemove.forEach { socket ->
                clientSockets.remove(socket)?.close() // Close writer
                try {
                    socket.close()
                } catch (e: IOException) { /* ignore */
                }
                Log.i(TAG, "Removed disconnected client: ${socket.inetAddress}")
            }
        }
        if (clientsToRemove.isNotEmpty()) {
            updateNotificationMessage("Clients: ${clientSockets.size}. Listening on IP: ${getLocalIpAddress() ?: "N/A"}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service Destroyed")
        stopServer()
        serviceJob.cancel() // Cancel all coroutines
        notificationQueue.clear()
        instance = null
        // Unbind or disable listener if desired, though system manages it based on permission.
        // If you programmatically enabled it:
        // val componentName = ComponentName(this, MyNotificationListener::class.java)
        // packageManager.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        // Log.i(TAG, "NotificationListener component state reset to default.")
        isListenerBound = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        // We don't provide binding, so return null
        return null
    }
}