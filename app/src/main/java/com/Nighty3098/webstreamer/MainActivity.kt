package com.Nighty3098.webstreamer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.ScaleGestureDetector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var isStreamingActive = false
    private var useFrontCamera = false
    private var scaleDetector: ScaleGestureDetector? = null
    private var currentZoomRatio = 1f
    private var currentResolution = Size(1280, 720)
    private var availableResolutions = emptyList<Size>()
    private var onResolutionsChanged: ((List<Size>, Size) -> Unit)? = null
    private var imageRotation = 0
    private val reusableVBytes = ByteArray(1920 * 1080 / 4)
    private val reusableUBytes = ByteArray(1920 * 1080 / 4)

    companion object {
        private const val TAG = "WebStreamer"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startStreamingService(lastPort)
        }
    }

    private var lastPort = 8080

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            scaleDetector = ScaleGestureDetector(this@MainActivity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val zoomState = cam.cameraInfo.zoomState.value ?: return false
                    val ratio = (zoomState.zoomRatio * detector.scaleFactor).coerceIn(
                        zoomState.minZoomRatio, zoomState.maxZoomRatio
                    )
                    cam.cameraControl.setZoomRatio(ratio)
                    currentZoomRatio = ratio
                    return true
                }
            })
            setOnTouchListener { v, event ->
                scaleDetector?.onTouchEvent(event)
                v.performClick()
                true
            }
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = dynamicDarkColorScheme(this@MainActivity)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var isStreaming by remember { mutableStateOf(false) }
                    var port by remember { mutableStateOf("8080") }
                    val ip = remember { NetworkUtils.getLocalIpAddress() }
                    var frontCamera by remember { mutableStateOf(false) }
                    var resolutions by remember { mutableStateOf(emptyList<Size>()) }
                    var selectedRes by remember { mutableStateOf<Size?>(null) }
                    var resMenuOpen by remember { mutableStateOf(false) }
                    var rotation by remember { mutableIntStateOf(0) }

                    if (isStreaming) {
                        DisposableEffect(Unit) {
                            onResolutionsChanged = { res, sel ->
                                resolutions = res
                                selectedRes = sel
                            }
                            onDispose { onResolutionsChanged = null }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                factory = { previewView!! },
                                modifier = Modifier.fillMaxSize()
                            )

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "http://$ip:$port/video",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    "${currentResolution.width}x${currentResolution.height} | Zoom: ${"%.1f".format(currentZoomRatio)}x | ${rotation}°",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Box {
                                    OutlinedButton(onClick = { resMenuOpen = true }) {
                                        Text(
                                            "Resolution: ${currentResolution.width}x${currentResolution.height}",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = resMenuOpen,
                                        onDismissRequest = { resMenuOpen = false }
                                    ) {
                                        resolutions.forEach { size ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "${size.width}x${size.height}" +
                                                            if (size == currentResolution) " ✓" else ""
                                                    )
                                                },
                                                onClick = {
                                                    currentResolution = size
                                                    selectedRes = size
                                                    resMenuOpen = false
                                                    if (isStreamingActive) {
                                                        cameraProvider?.unbindAll()
                                                        previewView?.post { initCamera() }
                                                    }
                                                }
                                            )
                                        }
                                        if (resolutions.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text("No resolutions available") },
                                                onClick = { resMenuOpen = false }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = {
                                            isStreaming = false
                                            useFrontCamera = false
                                            rotation = 0
                                            imageRotation = 0
                                            stopStreaming()
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text("Stop")
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            rotation = (rotation + 90) % 360
                                            imageRotation = rotation
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("${rotation}°")
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            frontCamera = !frontCamera
                                            useFrontCamera = frontCamera
                                            switchCamera()
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (frontCamera) "Back" else "Front")
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            OutlinedTextField(
                                value = port,
                                onValueChange = { if (it.all { c -> c.isDigit() }) port = it },
                                label = { Text("Port") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            Button(
                                onClick = {
                                    val p = port.toIntOrNull() ?: 8080
                                    lastPort = p
                                    isStreaming = true
                                    checkPermissionsAndStart(p)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Start")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isStreamingActive) initCamera()
    }

    private fun checkPermissionsAndStart(port: Int) {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (needed.isEmpty()) startStreamingService(port) else requestPermissionLauncher.launch(needed)
    }

    private fun startStreamingService(port: Int) {
        isStreamingActive = true
        Log.d(TAG, "Starting stream on ${NetworkUtils.getLocalIpAddress()}:$port")
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(this, intent)
        initCamera()
    }

    private fun stopStreaming() {
        isStreamingActive = false
        stopCamera()
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_STOP
        }
        startService(intent)
    }

    private fun queryAvailableResolutions(): List<Size> {
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = if (useFrontCamera) {
                val ids = cameraManager.cameraIdList
                ids.firstOrNull { id ->
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                } ?: ids.firstOrNull()
            } else {
                val ids = cameraManager.cameraIdList
                ids.firstOrNull { id ->
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: ids.firstOrNull()
            } ?: return emptyList()

            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
            val sizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?: map.getOutputSizes(android.graphics.ImageFormat.JPEG)
                ?: return emptyList()
            return sizes.distinct().sortedByDescending { it.width * it.height }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query resolutions", e)
            return emptyList()
        }
    }

    private fun initCamera() {
        val pv = previewView ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                availableResolutions = queryAvailableResolutions()
                runOnUiThread {
                    onResolutionsChanged?.invoke(availableResolutions, currentResolution)
                }
                bindCamera(pv)
            } catch (e: Exception) {
                Log.e(TAG, "CameraProvider failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(pv: PreviewView) {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = pv.surfaceProvider
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(
                currentResolution,
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
            ))
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
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
            camera = provider.bindToLifecycle(this, selector, preview, analysis)
            currentZoomRatio = 1f
            Log.d(TAG, "Camera bound (${if (useFrontCamera) "front" else "back"})")
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
        }
    }

    private fun processFrame(imageProxy: androidx.camera.core.ImageProxy) {
        if (!isStreamingActive) {
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

    private fun yuvToNv21(imageProxy: androidx.camera.core.ImageProxy): ByteArray {
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
        val yuvImage = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21, w, h, null
        )
        val out = java.io.ByteArrayOutputStream(w * h / 4)
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }

    private fun switchCamera() {
        if (isStreamingActive) {
            cameraProvider?.unbindAll()
            previewView?.post { initCamera() }
        }
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    override fun onDestroy() {
        stopCamera()
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
