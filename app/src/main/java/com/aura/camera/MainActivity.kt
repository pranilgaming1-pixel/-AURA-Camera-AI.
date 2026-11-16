package com.aura.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
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
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    var captureAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var labels by remember { mutableStateOf("AI camera ready") }
    var histogram by remember { mutableStateOf(List(64) { 0f }) }
    var brightness by remember { mutableIntStateOf(0) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var enhanced by remember { mutableStateOf<Bitmap?>(null) }
    var report by remember { mutableStateOf("Take a photo to begin") }
    var busy by remember { mutableStateOf(false) }
    var showEnhanced by remember { mutableStateOf(true) }
    val worker = remember { Executors.newSingleThreadExecutor() }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            worker.execute {
                try {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) {
                        val result = PhotoEnhancer.enhance(bitmap)
                        ContextCompat.getMainExecutor(context).execute {
                            original = bitmap
                            enhanced = result.bitmap
                            report = "Photo enhanced · adaptive light, contrast and detail"
                            busy = false
                        }
                    } else ContextCompat.getMainExecutor(context).execute { busy = false; report = "Could not open this image" }
                } catch (e: Exception) {
                    ContextCompat.getMainExecutor(context).execute { busy = false; report = "Enhancement failed: ${e.message ?: "unknown error"}" }
                }
            }
        }
    }

    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8DECF2), background = Color(0xFF080D12), surface = Color(0xFF111A22))) {
        Column(Modifier.fillMaxSize().background(Color(0xFF080D12)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AURA", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                    Text("AI PHOTO CAMERA", color = Color(0xFF8DECF2), style = MaterialTheme.typography.labelSmall)
                }
                Surface(color = Color(0xFF17313A), shape = RoundedCornerShape(20.dp)) {
                    Text(if (busy) "● ENHANCING" else "● LIVE AI", color = Color(0xFF8DECF2), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            if (original == null) {
                if (hasPermission) {
                    CameraPreview(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onCaptureReady = { captureAction = it },
                        onAnalysis = { bins, mean -> histogram = bins; brightness = mean },
                        onLabels = { labels = it },
                        onPhoto = { file ->
                            busy = true
                            worker.execute {
                                try {
                                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                                    if (bitmap == null) throw IllegalStateException("Photo could not be decoded")
                                    val result = PhotoEnhancer.enhance(bitmap)
                                    ContextCompat.getMainExecutor(context).execute {
                                        original = bitmap
                                        enhanced = result.bitmap
                                        report = "AURA optimized · exposure ${result.report.appliedExposure}× · detail sharpening"
                                        busy = false
                                    }
                                } catch (e: Exception) {
                                    ContextCompat.getMainExecutor(context).execute { busy = false; report = "Could not enhance photo: ${e.message}" }
                                }
                            }
                        }
                    )
                    Text(labels, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Text("Light meter · $brightness / 255", color = Color(0xFF9CB0BC), style = MaterialTheme.typography.labelSmall)
                    Histogram(bins = histogram, modifier = Modifier.fillMaxWidth().height(44.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { galleryPicker.launch("image/*") }) { Text("Gallery") }
                        Button(
                            onClick = { captureAction?.invoke() },
                            enabled = captureAction != null && !busy,
                            modifier = Modifier.size(76.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(4.dp)
                        ) { Text("●", style = MaterialTheme.typography.headlineLarge) }
                        TextButton(onClick = { report = "AURA uses natural, conservative enhancement" }) { Text("AI AUTO") }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera access") }
                    }
                }
            } else {
                Text("YOUR PHOTO", color = Color(0xFF8DECF2), style = MaterialTheme.typography.labelLarge)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val shown = if (showEnhanced) enhanced else original
                    shown?.let { Image(it.asImageBitmap(), contentDescription = "Photo preview", modifier = Modifier.fillMaxWidth()) }
                }
                Text(report, color = Color(0xFFB7C8D0), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showEnhanced = false }, modifier = Modifier.weight(1f)) { Text("Original") }
                    Button(onClick = { showEnhanced = true }, modifier = Modifier.weight(1f)) { Text("Enhanced") }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { original = null; enhanced = null; showEnhanced = true }, modifier = Modifier.weight(1f)) { Text("Retake") }
                    Button(onClick = {
                        val src = original
                        val edit = enhanced
                        if (src != null && edit != null) {
                            val a = saveBitmap(context, src, "AURA_Original")
                            val b = saveBitmap(context, edit, "AURA_Enhanced")
                            report = if (a && b) "Saved original and enhanced photos to Pictures/AURA" else "Save failed. Please check storage space."
                        }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Save both") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Text("Natural enhancement · original always preserved", color = Color(0xFF718995), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun saveBitmap(context: android.content.Context, bitmap: Bitmap, prefix: String): Boolean {
    return try {
        val name = "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AURA")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) } ?: return false
        true
    } catch (_: Exception) { false }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    onCaptureReady: (() -> Unit) -> Unit,
    onAnalysis: (List<Float>, Int) -> Unit,
    onLabels: (String) -> Unit,
    onPhoto: (File) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val executor: ExecutorService = Executors.newSingleThreadExecutor()
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(98)
                    .build()
                val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                val analysis = ImageAnalysis.Builder().setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val image = proxy.image
                    if (image == null) proxy.close() else {
                        val (bins, mean) = calculateHistogram(proxy)
                        ContextCompat.getMainExecutor(ctx).execute { onAnalysis(bins, mean) }
                        labeler.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { labels ->
                                val top = labels.sortedByDescending { it.confidence }.take(2)
                                ContextCompat.getMainExecutor(ctx).execute { onLabels(if (top.isEmpty()) "Scene analysis active" else top.joinToString(" · ") { it.text }) }
                            }.addOnCompleteListener { proxy.close() }
                    }
                }
                onCaptureReady {
                    val file = File(ctx.cacheDir, "aura_${System.currentTimeMillis()}.jpg")
                    val options = ImageCapture.OutputFileOptions.Builder(file).build()
                    capture.takePicture(options, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { onPhoto(file) }
                        override fun onError(exception: ImageCaptureException) { onLabels("Capture failed: ${exception.message}") }
                    })
                }
                try { provider.unbindAll(); provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis) }
                catch (e: Exception) { onLabels("Camera unavailable: ${e.message}") }
            }, ContextCompat.getMainExecutor(ctx))
            view
        }
    )
}

private fun calculateHistogram(proxy: ImageProxy): Pair<List<Float>, Int> {
    val plane = proxy.planes[0]
    val buffer = plane.buffer
    val bins = IntArray(64)
    var sum = 0L
    var count = 0
    val stepX = (proxy.width / 160).coerceAtLeast(1)
    val stepY = (proxy.height / 120).coerceAtLeast(1)
    for (y in 0 until proxy.height step stepY) for (x in 0 until proxy.width step stepX) {
        val index = y * plane.rowStride + x * plane.pixelStride
        if (index < buffer.limit()) {
            val value = buffer.get(index).toInt() and 0xFF
            bins[value * 64 / 256]++
            sum += value
            count++
        }
    }
    val maxBin = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
    return bins.map { it / maxBin } to if (count == 0) 0 else (sum / count).toInt()
}

@Composable
private fun Histogram(bins: List<Float>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.background(Color(0xFF111A22), RoundedCornerShape(10.dp)).padding(6.dp)) {
        if (bins.isEmpty()) return@Canvas
        val step = size.width / bins.size
        bins.forEachIndexed { index, value ->
            drawLine(Color(0xFF72DDE8), androidx.compose.ui.geometry.Offset(index * step, size.height),
                androidx.compose.ui.geometry.Offset(index * step, size.height - value * size.height), strokeWidth = maxOf(1f, step * .7f))
        }
    }
}
