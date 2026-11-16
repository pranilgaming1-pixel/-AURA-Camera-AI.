package com.aura.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AuraCameraScreen() }
    }
}

@Composable
private fun AuraCameraScreen() {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    var labels by remember { mutableStateOf("AI scene analyzer ready") }
    var histogram by remember { mutableStateOf(List(64) { 0f }) }
    var brightness by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF72E6F2),
        background = Color(0xFF080D12),
        surface = Color(0xFF111A22)
    )) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF080D12)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AURA", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                    Text("COMPUTATIONAL CAMERA", color = Color(0xFF72E6F2), style = MaterialTheme.typography.labelSmall)
                }
                Surface(color = Color(0xFF17313A), shape = RoundedCornerShape(20.dp)) {
                    Text("● LIVE AI", color = Color(0xFF72E6F2), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            if (hasPermission) {
                CameraPreview(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onAnalysis = { bins, mean -> histogram = bins; brightness = mean },
                    onLabels = { labels = it }
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Camera permission is required", color = Color.White)
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera access") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(labels, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Luminance · average $brightness / 255", color = Color(0xFF9CB0BC), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Histogram(bins = histogram, modifier = Modifier.fillMaxWidth().height(76.dp))
            Spacer(Modifier.height(12.dp))
            Text("AURA foundation · CameraX + on-device ML Kit", color = Color(0xFF78909C), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    onAnalysis: (List<Float>, Int) -> Unit,
    onLabels: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val cameraExecutor = Executors.newSingleThreadExecutor()
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    val frame = proxy.image
                    if (frame == null) {
                        proxy.close()
                    } else {
                        val (bins, mean) = calculateHistogram(proxy)
                        ContextCompat.getMainExecutor(ctx).execute { onAnalysis(bins, mean) }
                        val input = InputImage.fromMediaImage(frame, proxy.imageInfo.rotationDegrees)
                        labeler.process(input)
                            .addOnSuccessListener { results ->
                                val top = results.sortedByDescending { it.confidence }.take(3)
                                val description = if (top.isEmpty()) "Scene: analyzing…" else
                                    "Scene: " + top.joinToString(" · ") { it.text }
                                ContextCompat.getMainExecutor(ctx).execute { onLabels(description) }
                            }
                            .addOnFailureListener {
                                ContextCompat.getMainExecutor(ctx).execute { onLabels("Scene: automatic analysis available") }
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    onLabels("Camera unavailable · check device compatibility")
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

private fun calculateHistogram(proxy: ImageProxy): Pair<List<Float>, Int> {
    val plane = proxy.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = proxy.width
    val height = proxy.height
    val bins = IntArray(64)
    var sum = 0L
    var count = 0
    val stepX = (width / 160).coerceAtLeast(1)
    val stepY = (height / 120).coerceAtLeast(1)
    for (y in 0 until height step stepY) {
        for (x in 0 until width step stepX) {
            val index = y * rowStride + x * pixelStride
            if (index < buffer.limit()) {
                val value = buffer.get(index).toInt() and 0xFF
                bins[value * 64 / 256]++
                sum += value
                count++
            }
        }
    }
    val maxBin = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
    return bins.map { it / maxBin } to if (count == 0) 0 else (sum / count).toInt()
}

@Composable
private fun Histogram(bins: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF111A22), RoundedCornerShape(12.dp)).padding(8.dp)) {
        if (bins.isEmpty()) return@Canvas
        val path = Path()
        val step = size.width / bins.size
        bins.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height - value * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.lineTo(size.width, size.height)
        path.lineTo(0f, size.height)
        path.close()
        drawPath(path, Color(0x6672E6F2))
    }
}