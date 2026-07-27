package com.Nighty3098.webstreamer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.ScaleGestureDetector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var previewView: PreviewView? = null
    private var isStreamingActive = false
    private var isFrontCamera = false
    private var scaleDetector: ScaleGestureDetector? = null
    private var currentZoomRatio = 1f
    private var currentResolution = Size(1280, 720)
    private var onResolutionsChanged: ((List<Size>, Size) -> Unit)? = null

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
                    val newZoom = (currentZoomRatio * detector.scaleFactor).coerceIn(1f, 10f)
                    currentZoomRatio = newZoom
                    StreamService.instance?.setZoom(newZoom)
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
            MaterialTheme(colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(this@MainActivity) else darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var isStreaming by remember { mutableStateOf(false) }
                    var port by remember { mutableStateOf("8080") }
                    val ip = remember { NetworkUtils.getLocalIpAddress() }
                    var frontCamera by remember { mutableStateOf(false) }
                    var resolutions by remember { mutableStateOf(emptyList<Size>()) }
                    var selectedRes by remember { mutableStateOf<Size?>(null) }
                    var resMenuOpen by remember { mutableStateOf(false) }
                    var rotation by remember { mutableIntStateOf(0) }
                    var zoomDisplay by remember { mutableFloatStateOf(1f) }

                    if (isStreaming) {
                        DisposableEffect(Unit) {
                            val res = this@MainActivity.queryAvailableResolutions()
                            if (res.isNotEmpty()) {
                                currentResolution = res.first()
                            }
                            onResolutionsChanged = { r, sel ->
                                resolutions = r
                                selectedRes = sel
                            }
                            onResolutionsChanged?.invoke(res, currentResolution)
                            StreamService.instance?.onZoomChanged = { zoom ->
                                currentZoomRatio = zoom
                                zoomDisplay = zoom
                            }
                            StreamService.instance?.setResolution(currentResolution)
                            onDispose {
                                onResolutionsChanged = null
                                StreamService.instance?.onZoomChanged = null
                            }
                        }
                    }

                    if (isStreaming) {
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
                                    "${currentResolution.width}x${currentResolution.height} | Zoom: ${"%.1f".format(zoomDisplay)}x | ${rotation}°",
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
                                                    resMenuOpen = false
                                                    StreamService.instance?.setResolution(size)
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
                                            frontCamera = false
                                            rotation = 0
                                            currentZoomRatio = 1f
                                            zoomDisplay = 1f
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
                                            StreamService.instance?.setRotation(rotation)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("${rotation}°")
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            frontCamera = !frontCamera
                                            isFrontCamera = frontCamera
                                            StreamService.instance?.switchCamera()
                                            val res = queryAvailableResolutions()
                                            if (res.isNotEmpty()) {
                                                onResolutionsChanged?.invoke(res, res.first())
                                                currentResolution = res.first()
                                                StreamService.instance?.setResolution(currentResolution)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (frontCamera) "Back" else "Front")
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "WStream",
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(bottom = 48.dp)
                                )

                                OutlinedTextField(
                                    value = port,
                                    onValueChange = { if (it.all { c -> c.isDigit() }) port = it },
                                    modifier = Modifier.width(200.dp),
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Button(
                                    onClick = {
                                        val p = port.toIntOrNull() ?: 8080
                                        lastPort = p
                                        isStreaming = true
                                        checkPermissionsAndStart(p)
                                    },
                                    modifier = Modifier.width(200.dp)
                                ) {
                                    Text("Start")
                                }
                            }

                            Text(
                                "By Nighty3098",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isStreamingActive) {
            StreamService.instance?.setPreviewSurface(previewView?.surfaceProvider)
        }
    }

    override fun onPause() {
        super.onPause()
        StreamService.instance?.setPreviewSurface(null)
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

    private fun queryAvailableResolutions(): List<Size> {
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = if (isFrontCamera) {
                cameraManager.cameraIdList.firstOrNull { id ->
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                } ?: cameraManager.cameraIdList.firstOrNull()
            } else {
                cameraManager.cameraIdList.firstOrNull { id ->
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                } ?: cameraManager.cameraIdList.firstOrNull()
            } ?: return emptyList()

            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
            val sizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?: map.getOutputSizes(android.graphics.ImageFormat.JPEG)
                ?: return emptyList()
            return sizes.distinct().sortedByDescending { it.width * it.height }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query resolutions", e)
            return emptyList()
        }
    }

    private fun startStreamingService(port: Int) {
        isStreamingActive = true
        Log.d(TAG, "Starting stream on ${NetworkUtils.getLocalIpAddress()}:$port")
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(this, intent)
        previewView?.postDelayed({
            StreamService.instance?.setPreviewSurface(previewView?.surfaceProvider)
        }, 200)
    }

    private fun stopStreaming() {
        isStreamingActive = false
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onDestroy() {
        previewView = null
        super.onDestroy()
    }
}
