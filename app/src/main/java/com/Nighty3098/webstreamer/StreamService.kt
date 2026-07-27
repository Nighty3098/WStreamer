package com.Nighty3098.webstreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket

class StreamService : LifecycleService() {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var port = 8080
    private val clients = mutableListOf<Socket>()

    companion object {
        private const val TAG = "WebStreamer"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_PORT = "EXTRA_PORT"
        private const val CHANNEL_ID = "WebStreamerChannel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                port = intent.getIntExtra(EXTRA_PORT, 8080)
                startForeground(NOTIFICATION_ID, createNotification())
                startServer()
            }
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "Stream", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Stream active")
            .setContentText("Port: $port")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun startServer() {
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                serverSocket?.reuseAddress = true
                Log.d(TAG, "Server listening on 0.0.0.0:$port")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    Log.d(TAG, "Client connected: ${client.inetAddress.hostAddress}")
                    client.tcpNoDelay = true
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Server error", e)
            }
        }.start()
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val request = reader.readLine() ?: return

            if (request.contains("GET /video")) {
                while (reader.readLine().isNotEmpty()) {}

                val out = BufferedOutputStream(client.getOutputStream(), 65536)
                val header = (
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray()
                out.write(header)
                out.flush()

                synchronized(clients) { clients.add(client) }

                var lastFrame: ByteArray? = null
                while (isRunning && !client.isClosed) {
                    val frame: ByteArray?
                    synchronized(StreamState.lock) {
                        while (StreamState.latestFrame === lastFrame && isRunning && !client.isClosed) {
                            (StreamState.lock as java.lang.Object).wait(500L)
                        }
                        frame = StreamState.latestFrame
                    }
                    if (frame != null && frame !== lastFrame) {
                        val part = (
                            "--frame\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${frame.size}\r\n\r\n"
                        ).toByteArray()
                        out.write(part)
                        out.write(frame)
                        out.write("\r\n".toByteArray())
                        out.flush()
                        lastFrame = frame
                    }
                }
            } else {
                val body = "WebStreamer MJPEG — use /video"
                val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body"
                client.getOutputStream().write(resp.toByteArray())
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
        } finally {
            synchronized(clients) { clients.remove(client) }
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun stopServer() {
        isRunning = false
        synchronized(StreamState.lock) { StreamState.lock.notifyAll() }
        synchronized(clients) {
            clients.forEach { try { it.close() } catch (_: Exception) {} }
            clients.clear()
        }
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
