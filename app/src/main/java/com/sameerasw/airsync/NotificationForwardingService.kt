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
import android.widget.Toast
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
import kotlin.coroutines.coroutineContext

class NotificationForwardingService : Service() {

    // New data class for clipboard items
    data class ClipboardPushData(val text: String, val type: String = "clipboard")

    companion object {
        const val TAG = "NotificationFwdSvc"
        private const val NOTIFICATION_CHANNEL_ID = "NotificationForwardingChannel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START_SERVICE = "com.sameerasw.airsync.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.sameerasw.airsync.ACTION_STOP_SERVICE"
        const val SERVER_PORT = 12345

        private val notificationQueue = LinkedBlockingQueue<NotificationData>()
        private val clipboardQueue = LinkedBlockingQueue<ClipboardPushData>() // New queue for clipboard
        private var instance: NotificationForwardingService? = null

        fun isServiceRunning(): Boolean = instance != null

        fun queueNotificationData(appName: String, title: String, text: String, packageName: String, iconBase64: String? = null) {
            if (!isServiceRunning()) {
                Log.w(TAG, "Service not running, cannot queue notification data.")
                return
            }
            try {
                notificationQueue.put(NotificationData(appName, title, text, packageName, iconBase64))
                Log.d(TAG, "Notification data queued for ${appName}.")
            } catch (e: InterruptedException) {
                Log.e(TAG, "Failed to queue notification data", e)
                Thread.currentThread().interrupt()
            }
        }

        // New method to queue clipboard data
        fun queueClipboardData(text: String) {
            if (!isServiceRunning()) {
                Log.w(TAG, "Service not running, cannot queue clipboard data.")
                // Optionally, you could consider starting the service here if it's not running,
                // but that might be unexpected user behavior. For now, just log and return.
                return
            }
            try {
                clipboardQueue.put(ClipboardPushData(text))
                Log.i(TAG, "Clipboard data queued: ${text.take(50)}...")
            } catch (e: InterruptedException) {
                Log.e(TAG, "Failed to queue clipboard data", e)
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

    // In NotificationForwardingService.kt
    data class NotificationData(
        val appName: String,
        val title: String,
        val text: String,
        val packageName: String,
        val iconBase64: String? = null // Add this line
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
                serviceScope.launch { processClipboardQueue() }
            }
            ACTION_STOP_SERVICE -> {
                Log.i(TAG, "Stopping service...")
                stopSelf()
            }
        }
        return START_STICKY // Restart service if killed
    }

    // Method to process the clipboard queue
    private suspend fun processClipboardQueue() {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Clipboard processing queue started.")
            try {
                while (coroutineContext.isActive) { // Fixed usage of isActive
                    val data = clipboardQueue.take()
                    Log.d(TAG, "Processing clipboard data from queue: ${data.text.take(50)}...")
                    sendClipboardDataToAllClients(data)
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "Clipboard processing queue interrupted.")
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Exception in clipboard processing queue", e)
            } finally {
                Log.i(TAG, "Clipboard processing queue stopped.")
            }
        }
    }

    // Method to send clipboard data to all clients
    private fun sendClipboardDataToAllClients(data: ClipboardPushData) {
        if (clientSockets.isEmpty()) {
            Log.d(TAG, "No clients connected, not sending clipboard data.")
            return
        }

        val json = JSONObject()
        json.put("type", data.type) // Will be "clipboard"
        json.put("text", data.text)
        val jsonString = json.toString()

        Log.d(TAG, "Sending clipboard to ${clientSockets.size} client(s): $jsonString")
        val clientsToRemove = mutableListOf<Socket>()
        synchronized(clientSockets) {
            clientSockets.forEach { (socket, writer) ->
                try {
                    writer.println(jsonString) // println adds a newline
                    if (writer.checkError()) { // Check for errors after write
                        Log.w(TAG, "Error sending clipboard to client ${socket.inetAddress}. Marking for removal.")
                        clientsToRemove.add(socket)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception sending clipboard to client ${socket.inetAddress}", e)
                    clientsToRemove.add(socket)
                }
            }

            clientsToRemove.forEach { socket ->
                clientSockets.remove(socket)?.close() // Close writer
                try { socket.close() } catch (e: IOException) { /* ignore */ }
                Log.i(TAG, "Removed disconnected client after clipboard send attempt: ${socket.inetAddress}")
            }
        }
        // No need to update the persistent notification for clipboard sends usually
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
                updateNotificationMessage("Listening on IP: $ipAddress Port: $SERVER_PORT. Clients: ${clientSockets.size}")

                while (isActive) {
                    try {
                        val clientSocket = serverSocket!!.accept() // Blocking call
                        Log.i(TAG, "Client connected: ${clientSocket.inetAddress.hostAddress}")

                        // Setup writer for this client (for sending notifications/clipboard from Android to Mac)
                        val writer = PrintWriter(BufferedWriter(OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8")), true)
                        synchronized(clientSockets) { // Synchronize access to clientSockets map
                            clientSockets[clientSocket] = writer
                        }
                        updateNotificationMessage("Clients: ${clientSockets.size}. Listening on IP: $ipAddress Port: $SERVER_PORT")


                        // Send a welcome/status message to the connected client
                        writer.println(JSONObject().apply {
                            put("type", "status") // Differentiate this message
                            put("message", "Connected to AndroidNotificationSender")
                            put("android_version", Build.VERSION.RELEASE)
                        }.toString()) // .toString() is important

                        // Launch a new coroutine to handle reading from this client
                        serviceScope.launch {
                            handleClientReads(clientSocket, clientSocket.inetAddress.hostAddress)
                        }

                    } catch (e: SocketException) {
                        if (!isActive || serverSocket?.isClosed == true) {
                            Log.i(TAG, "Server socket closed or no longer active, exiting accept loop.")
                            break
                        }
                        Log.e(TAG, "SocketException in server accept loop: ${e.message}")
                    } catch (e: IOException) {
                        Log.e(TAG, "IOException in server accept loop", e)
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server could not start or encountered a fatal error.", e)
                updateNotificationMessage("Error: ${e.message ?: "Unknown error"}")
            } finally {
                Log.i(TAG, "Server loop ending.")
                stopServerCleanup() // Renamed for clarity
            }
        }
    }

    // New function to handle reads from a specific client
    private suspend fun handleClientReads(clientSocket: Socket, clientAddress: String) {
        Log.i(TAG, "Started reader for client: $clientAddress")
        try {
            // It's crucial to use a BufferedReader for readLine()
            val reader = clientSocket.getInputStream().bufferedReader(Charsets.UTF_8)
            while (coroutineContext.isActive && clientSocket.isConnected && !clientSocket.isClosed) {
                val line = reader.readLine() // Reads a line of text, expecting JSON per line from Python
                if (line == null) {
                    Log.i(TAG, "Client $clientAddress disconnected (readLine returned null).")
                    break // End of stream
                }
                Log.d(TAG, "Android: Received from $clientAddress: $line")
                try {
                    val jsonData = JSONObject(line)
                    when (jsonData.optString("type")) {
                        "clipboard_push_to_android" -> {
                            val textToCopy = jsonData.optString("text")
                            if (textToCopy != null) {
                                Log.i(TAG, "Android: Received clipboard text from $clientAddress: '$textToCopy'")
                                copyTextToAndroidClipboard(textToCopy)
                            } else {
                                Log.w(TAG, "Android: clipboard_push_to_android type received but 'text' is null or missing.")
                            }
                        }
                        // You can add other types here if Python sends other commands
                        else -> {
                            Log.w(TAG, "Android: Received unknown JSON type from $clientAddress: ${jsonData.optString("type")}")
                        }
                    }
                } catch (e: org.json.JSONException) {
                    Log.e(TAG, "Android: JSONException parsing data from $clientAddress: ${e.message}. Data: $line")
                } catch (e: Exception) {
                    Log.e(TAG, "Android: Error processing data from $clientAddress: ${e.message}")
                }
            }
        } catch (e: IOException) {
            // This can happen if the client disconnects abruptly or network issue
            Log.i(TAG, "Android: IOException for client $clientAddress (likely disconnected): ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Android: Unexpected error in handleClientReads for $clientAddress: ${e.message}", e)
        } finally {
            Log.i(TAG, "Android: Reader for client $clientAddress ending.")
            // Cleanup for this specific client
            synchronized(clientSockets) {
                clientSockets.remove(clientSocket)
            }
            try {
                clientSocket.close()
            } catch (e: IOException) {
                Log.w(TAG, "Android: Error closing client socket for $clientAddress in finally block: ${e.message}")
            }
            val ipAddress = getLocalIpAddress() ?: "N/A"
            updateNotificationMessage("Clients: ${clientSockets.size}. Listening on IP: $ipAddress Port: $SERVER_PORT")
        }
    }

    // New function to copy text to Android's clipboard
    private fun copyTextToAndroidClipboard(text: String) {
        // Clipboard operations must happen on the main thread if they involve UI (like Toasts)
        // The actual copying can be off-thread, but Toasts must be on main.
        MainScope().launch { // Use MainScope for UI operations from a service/background thread
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Copied from Mac", text)
                clipboard.setPrimaryClip(clip)
                Log.i(TAG, "Text copied to Android clipboard: '${text.take(70)}...'")
                Toast.makeText(applicationContext, "Text copied from Mac!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error copying text to Android clipboard", e)
                Toast.makeText(applicationContext, "Error copying from Mac", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Renamed stopServer to stopServerCleanup to avoid confusion if called from multiple places
    private fun stopServerCleanup() {
        Log.i(TAG, "Stopping server and closing all client connections.")
        try {
            serverSocket?.close() // This will interrupt the accept() call
            serverSocket = null
            synchronized(clientSockets) {
                clientSockets.forEach { (socket, writer) ->
                    try {
                        writer.close() // Close the PrintWriter
                        socket.close() // Close the socket
                    } catch (e: IOException) {
                        Log.e(TAG, "Error closing a client socket during server stop: ${socket.inetAddress}", e)
                    }
                }
                clientSockets.clear()
            }
            Log.i(TAG, "All client connections closed and server socket stopped.")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing main server socket", e)
        }
        updateNotificationMessage("Service stopped.")
    }


    private suspend fun processNotificationQueue() {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Notification processing queue started.")
            try {
                while (coroutineContext.isActive) { // Fixed usage of isActive
                    val data = notificationQueue.take()
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


    // In NotificationForwardingService.kt
    private fun sendNotificationToAllClients(data: NotificationData) {
        if (clientSockets.isEmpty()) {
            return
        }

        val json = JSONObject()
        json.put("app", data.appName)
        json.put("title", data.title)
        json.put("text", data.text)
        json.put("packageName", data.packageName)
        data.iconBase64?.let {  // Only add if icon data exists
            json.put("icon_base64", it)
        }
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
        stopServerCleanup() // Call the cleanup method
        serviceJob.cancel()
        notificationQueue.clear()
        clipboardQueue.clear()
        instance = null
        isListenerBound = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        // We don't provide binding, so return null
        return null
    }
}