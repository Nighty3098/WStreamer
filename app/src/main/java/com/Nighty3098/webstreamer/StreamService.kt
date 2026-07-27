package com.Nighty3098.webstreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class StreamService : LifecycleService() {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var port = 8080
    private val clients = mutableListOf<Socket>()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private var currentResolution = Size(1280, 720)
    private var useFrontCamera = false
    private var imageRotation = 0
    private val reusableVBytes = ByteArray(1920 * 1080 / 4)
    private val reusableUBytes = ByteArray(1920 * 1080 / 4)
    private var wakeLock: PowerManager.WakeLock? = null

    var onZoomChanged: ((Float) -> Unit)? = null
    var onCameraReady: ((Boolean) -> Unit)? = null

    companion object {
        private const val TAG = "WebStreamer"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_PORT = "EXTRA_PORT"
        private const val CHANNEL_ID = "WebStreamerChannel"
        private const val NOTIFICATION_ID = 1

        var instance: StreamService? = null
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        instance = this
        when (intent?.action) {
            ACTION_START -> {
                port = intent.getIntExtra(EXTRA_PORT, 8080)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }
                acquireWakeLock()
                startServer()
                initCamera()
            }
            ACTION_STOP -> {
                stopCamera()
                stopServer()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                instance = null
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

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebStreamer::StreamWakeLock")
            wakeLock?.acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    fun setPreviewSurface(provider: Preview.SurfaceProvider?) {
        previewSurfaceProvider = provider
        Handler(Looper.getMainLooper()).post { bindCamera() }
    }

    fun switchCamera() {
        useFrontCamera = !useFrontCamera
        Handler(Looper.getMainLooper()).post { bindCamera() }
    }

    fun setResolution(size: Size) {
        currentResolution = size
        Handler(Looper.getMainLooper()).post { bindCamera() }
    }

    fun setRotation(degrees: Int) {
        imageRotation = degrees
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    private fun initCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "CameraProvider failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            currentResolution,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }
            }

        val selector = if (useFrontCamera)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()

            val preview = previewSurfaceProvider?.let { sp ->
                Preview.Builder().build().also {
                    it.surfaceProvider = sp
                }
            }

            camera = if (preview != null) {
                provider.bindToLifecycle(this, selector, preview, analysis)
            } else {
                provider.bindToLifecycle(this, selector, analysis)
            }

            camera?.cameraInfo?.zoomState?.observe(this) { state ->
                onZoomChanged?.invoke(state.zoomRatio)
            }

            onCameraReady?.invoke(true)
            Log.d(TAG, "Camera bound (${if (useFrontCamera) "front" else "back"})")
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
            onCameraReady?.invoke(false)
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (!isRunning) {
            imageProxy.close()
            return
        }
        try {
            val w = imageProxy.width
            val h = imageProxy.height
            val nv21 = yuvToNv21(imageProxy)
            imageProxy.close()
            val rotated = if (imageRotation != 0) rotateNv21(nv21, w, h, imageRotation) else nv21
            val outW = if (imageRotation == 90 || imageRotation == 270) h else w
            val outH = if (imageRotation == 90 || imageRotation == 270) w else h
            val jpeg = nv21ToJpeg(rotated, outW, outH, 70)
            StreamState.pushFrame(jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "Frame error", e)
            try { imageProxy.close() } catch (_: Exception) {}
        }
    }

    private fun rotateNv21(nv21: ByteArray, w: Int, h: Int, degrees: Int): ByteArray {
        val size = w * h
        val uvSize = size / 2
        val outY = ByteArray(size)
        val outUv = ByteArray(uvSize)
        val uvW = w / 2
        val uvH = h / 2

        when (degrees) {
            90 -> {
                var pos = 0
                for (j in 0 until w) {
                    for (i in h - 1 downTo 0) {
                        outY[pos++] = nv21[i * w + j]
                    }
                }
                var uvPos = 0
                for (outI in 0 until uvW) {
                    for (outJ in 0 until uvH) {
                        val srcI = uvH - 1 - outJ
                        val srcJ = outI
                        val srcIdx = (srcI * uvW + srcJ) * 2
                        outUv[uvPos++] = nv21[size + srcIdx]
                        outUv[uvPos++] = nv21[size + srcIdx + 1]
                    }
                }
            }
            180 -> {
                var pos = 0
                for (i in h - 1 downTo 0) {
                    for (j in w - 1 downTo 0) {
                        outY[pos++] = nv21[i * w + j]
                    }
                }
                var uvPos = 0
                for (outI in 0 until uvH) {
                    for (outJ in 0 until uvW) {
                        val srcI = uvH - 1 - outI
                        val srcJ = uvW - 1 - outJ
                        val srcIdx = (srcI * uvW + srcJ) * 2
                        outUv[uvPos++] = nv21[size + srcIdx]
                        outUv[uvPos++] = nv21[size + srcIdx + 1]
                    }
                }
            }
            270 -> {
                var pos = 0
                for (j in w - 1 downTo 0) {
                    for (i in 0 until h) {
                        outY[pos++] = nv21[i * w + j]
                    }
                }
                var uvPos = 0
                for (outI in 0 until uvW) {
                    for (outJ in 0 until uvH) {
                        val srcI = outJ
                        val srcJ = uvW - 1 - outI
                        val srcIdx = (srcI * uvW + srcJ) * 2
                        outUv[uvPos++] = nv21[size + srcIdx]
                        outUv[uvPos++] = nv21[size + srcIdx + 1]
                    }
                }
            }
            else -> return nv21
        }

        val result = ByteArray(size + uvSize)
        System.arraycopy(outY, 0, result, 0, size)
        System.arraycopy(outUv, 0, result, size, uvSize)
        return result
    }

    private fun yuvToNv21(imageProxy: ImageProxy): ByteArray {
        val w = imageProxy.width
        val h = imageProxy.height

        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride

        val nv21 = ByteArray(w * h * 3 / 2)

        val yBuf = yPlane.buffer
        var yPos = 0
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, yPos, w)
            yPos += w
        }

        val vBuf = vPlane.buffer
        val uBuf = uPlane.buffer
        vBuf.rewind()
        uBuf.rewind()
        val uvSize = vBuf.remaining()
        val vBytes = if (uvSize <= reusableVBytes.size) reusableVBytes else ByteArray(uvSize)
        val uBytes = if (uvSize <= reusableUBytes.size) reusableUBytes else ByteArray(uvSize)
        vBuf.get(vBytes, 0, uvSize)
        uBuf.get(uBytes, 0, uvSize)

        val uvH = h / 2
        val uvW = w / 2
        var pos = w * h
        for (row in 0 until uvH) {
            for (col in 0 until uvW) {
                val vi = (row * vRowStride + col * vPixStride).coerceIn(0, uvSize - 1)
                val ui = (row * uRowStride + col * uPixStride).coerceIn(0, uvSize - 1)
                nv21[pos++] = vBytes[vi]
                nv21[pos++] = uBytes[ui]
            }
        }

        return nv21
    }

    private fun nv21ToJpeg(nv21: ByteArray, w: Int, h: Int, quality: Int): ByteArray {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream(w * h / 4)
        yuvImage.compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }

    private fun stopCamera() {
        Handler(Looper.getMainLooper()).post {
            cameraProvider?.unbindAll()
            cameraProvider = null
            camera = null
        }
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
        stopCamera()
        stopServer()
        releaseWakeLock()
        instance = null
        super.onDestroy()
    }
}
