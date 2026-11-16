package com.aura.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val Ink = Color(0xFF050608)
private val Panel = Color(0xFF17191D)
private val Muted = Color(0xFF9B9DA3)
private val Accent = Color(0xFFFFD36A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AuraCameraScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuraCameraScreen() {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    var captureAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var cameraSettings by remember { mutableStateOf<((Boolean, Float) -> Unit)?>(null) }
    var labels by remember { mutableStateOf("AURA CAMERA") }
    var histogram by remember { mutableStateOf(List(64) { 0f }) }
    var brightness by remember { mutableIntStateOf(0) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var enhanced by remember { mutableStateOf<Bitmap?>(null) }
    var report by remember { mutableStateOf("Your photo, naturally enhanced.") }
    var busy by remember { mutableStateOf(false) }
    var showEnhanced by remember { mutableStateOf(true) }
    var flashOn by remember { mutableStateOf(false) }
    var timer by remember { mutableIntStateOf(0) }
    var ratio by remember { mutableStateOf("4:3") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var mode by remember { mutableStateOf("PHOTO") }
    var showOptions by remember { mutableStateOf(false) }
    var selectedLook by remember { mutableStateOf("Natural") }
    val worker = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(flashOn, zoom) { cameraSettings?.invoke(flashOn, zoom) }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            worker.execute {
                try {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) {
                        val result = PhotoEnhancer.enhance(bitmap)
                        ContextCompat.getMainExecutor(context).execute {
                            original = bitmap; enhanced = result.bitmap
                            report = "AURA enhanced your photo · natural light and detail"
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

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel)) {
        if (original == null) {
            Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Minimal iPhone-inspired top camera bar.
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("AURA", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                    Spacer(Modifier.weight(1f))
                    RoundControl(if (flashOn) "⚡" else "ϟ", flashOn) { flashOn = !flashOn }
                    Spacer(Modifier.width(10.dp))
                    RoundControl("⌁", false) { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }
                    Spacer(Modifier.width(10.dp))
                    RoundControl("⋯", false) { showOptions = true }
                }

                if (hasPermission) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
                        CameraPreview(
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp)),
                            onCaptureReady = { captureAction = it },
                            onCameraReady = { action -> cameraSettings = action },
                            onAnalysis = { bins, mean -> histogram = bins; brightness = mean },
                            onLabels = { labels = it },
                            onPhoto = { file ->
                                busy = true
                                worker.execute {
                                    try {
                                        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: throw IllegalStateException("Photo could not be decoded")
                                        val result = PhotoEnhancer.enhance(bitmap)
                                        ContextCompat.getMainExecutor(context).execute {
                                            original = bitmap; enhanced = result.bitmap
                                            report = "AURA optimized · exposure ${result.report.appliedExposure}× · natural detail"
                                            busy = false
                                        }
                                    } catch (e: Exception) {
                                        ContextCompat.getMainExecutor(context).execute { busy = false; report = "Could not enhance photo: ${e.message}" }
                                    }
                                }
                            }
                        )
                        // Camera information is deliberately subtle, overlaid on the preview.
                        Surface(Modifier.align(Alignment.TopStart).padding(12.dp), color = Color(0x99000000), shape = RoundedCornerShape(18.dp)) {
                            Text(if (busy) "  ENHANCING…  " else "  ◉ LIVE  ", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(vertical = 7.dp, horizontal = 3.dp))
                        }
                        Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), color = Color(0x99000000), shape = RoundedCornerShape(18.dp)) {
                            Text(labels, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), maxLines = 1)
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        listOf(1f, 2f, 4f).forEach { factor ->
                            val active = zoom == factor
                            Text(if (factor == 1f) "1×" else "${factor.toInt()}×",
                                color = if (active) Accent else Color.White,
                                fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clip(CircleShape).background(if (active) Color(0xFF35302A) else Color.Transparent)
                                    .clickable { zoom = factor }.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("HISTOGRAM", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                        Text("LIGHT  $brightness/255", color = Muted, fontSize = 9.sp)
                    }
                    Histogram(histogram, Modifier.fillMaxWidth().height(25.dp))
                    Spacer(Modifier.height(6.dp))
                    ModeSelector(mode) { mode = it }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { galleryPicker.launch("image/*") }) {
                            Text("▧", color = Color.White, fontSize = 25.sp)
                            Text("Gallery", color = Muted, fontSize = 10.sp)
                        }
                        Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White).padding(5.dp).clip(CircleShape)
                            .background(Accent).clickable(enabled = captureAction != null && !busy) { captureAction?.invoke() },
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(58.dp).clip(CircleShape).background(Color.White))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { showOptions = true }) {
                            Text("◉", color = Color.White, fontSize = 25.sp)
                            Text("Options", color = Muted, fontSize = 10.sp)
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera access") }
                    }
                }
                Text("AURA AI  ·  NATURAL PHOTO ENHANCEMENT", color = Color(0xFF777A80), fontSize = 9.sp, letterSpacing = 1.2.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 5.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().background(Ink).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AURA", color = Color.White, fontSize = 22.sp, letterSpacing = 3.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { original = null; enhanced = null; showEnhanced = true }) { Text("Retake", color = Accent) }
                }
                Text("YOUR PHOTO", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    val shown = if (showEnhanced) enhanced else original
                    shown?.let { Image(it.asImageBitmap(), contentDescription = "Photo preview", modifier = Modifier.fillMaxWidth()) }
                }
                Text(report, color = Muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showEnhanced, onClick = { showEnhanced = false }, label = { Text("Original") })
                    FilterChip(selected = showEnhanced, onClick = { showEnhanced = true }, label = { Text("Enhanced") })
                }
                Button(onClick = {
                    val src = original; val edit = enhanced
                    if (src != null && edit != null) {
                        val a = saveBitmap(context, src, "AURA_Original")
                        val b = saveBitmap(context, edit, "AURA_Enhanced")
                        report = if (a && b) "Both versions saved to Pictures/AURA" else "Save failed. Check storage space."
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("Save original + enhanced") }
            }
        }

        if (showOptions) {
            ModalBottomSheet(onDismissRequest = { showOptions = false }, containerColor = Panel, contentColor = Color.White) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
                    Text("Camera options", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("Customize how AURA captures your photos", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                    Text("FLASH", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                    OptionRow(listOf("Off", "On")) { flashOn = it == "On" }
                    Spacer(Modifier.height(14.dp))
                    Text("TIMER", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                    OptionRow(listOf("Off", "3 sec", "10 sec")) { timer = when (it) { "3 sec" -> 3; "10 sec" -> 10; else -> 0 } }
                    Spacer(Modifier.height(14.dp))
                    Text("ASPECT RATIO", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                    OptionRow(listOf("4:3", "16:9", "1:1")) { ratio = it }
                    Spacer(Modifier.height(14.dp))
                    Text("PHOTO LOOK", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                    OptionRow(listOf("Natural", "Warm", "Cool", "Vivid")) { selectedLook = it }
                    Spacer(Modifier.height(12.dp))
                    Text("Selected: $ratio · $selectedLook · Timer ${if (timer == 0) "off" else "$timer sec"}", color = Muted, fontSize = 11.sp)
                    Text("Portrait and Night modes are currently interface presets; advanced depth and multi-frame night processing are not yet enabled.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun RoundControl(symbol: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(if (active) Color(0xFF3C3425) else Panel).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(symbol, color = if (active) Accent else Color.White, fontSize = 18.sp)
    }
}

@Composable
private fun ModeSelector(mode: String, onMode: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        listOf("NIGHT", "PORTRAIT", "PHOTO", "VIDEO").forEach { item ->
            Text(item, color = if (mode == item) Accent else Muted, fontSize = 11.sp, fontWeight = if (mode == item) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.clickable { onMode(item) }.padding(horizontal = 13.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun OptionRow(items: List<String>, onSelect: (String) -> Unit) {
    var selected by remember(items) { mutableStateOf(items.first()) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF25272C)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            val active = selected == item
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (active) Color(0xFF51452D) else Color.Transparent)
                .clickable { selected = item; onSelect(item) }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                Text(item, color = if (active) Accent else Color.White, fontSize = 12.sp)
            }
        }
    }
}

private fun saveBitmap(context: android.content.Context, bitmap: Bitmap, prefix: String): Boolean {
    return try {
        val name = "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AURA")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val stream = context.contentResolver.openOutputStream(uri) ?: return false
        stream.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }
    } catch (_: Exception) { false }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    onCaptureReady: (() -> Unit) -> Unit,
    onCameraReady: ((Boolean, Float) -> Unit) -> Unit,
    onAnalysis: (List<Float>, Int) -> Unit,
    onLabels: (String) -> Unit,
    onPhoto: (File) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(modifier = modifier, factory = { ctx ->
        val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(98).build()
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
            val analysis = ImageAnalysis.Builder().setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val image = proxy.image
                if (image == null) proxy.close() else {
                    val (bins, mean) = calculateHistogram(proxy)
                    ContextCompat.getMainExecutor(ctx).execute { onAnalysis(bins, mean) }
                    labeler.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { found ->
                            val top = found.sortedByDescending { it.confidence }.take(2)
                            ContextCompat.getMainExecutor(ctx).execute { onLabels(if (top.isEmpty()) "AURA CAMERA" else top.joinToString(" · ") { it.text }) }
                        }.addOnCompleteListener { proxy.close() }
                }
            }
            onCameraReady { flash, ratio ->
                capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                cameraRef?.cameraControl?.setZoomRatio(ratio)
            }
            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                cameraRef = camera
                onCaptureReady {
                    val file = File(ctx.cacheDir, "aura_${System.currentTimeMillis()}.jpg")
                    capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(ctx),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) { onPhoto(file) }
                            override fun onError(exception: ImageCaptureException) { onLabels("Capture failed: ${exception.message}") }
                        })
                }
            } catch (e: Exception) { onLabels("Camera unavailable: ${e.message}") }
        }, ContextCompat.getMainExecutor(ctx))
        view
    })
}

private var cameraRef: Camera? = null

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
            bins[value * 64 / 256]++; sum += value; count++
        }
    }
    val maxBin = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
    return bins.map { it / maxBin } to if (count == 0) 0 else (sum / count).toInt()
}

@Composable
private fun Histogram(bins: List<Float>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.background(Color(0xFF202126), RoundedCornerShape(8.dp)).padding(5.dp)) {
        if (bins.isEmpty()) return@Canvas
        val step = size.width / bins.size
        bins.forEachIndexed { index, value ->
            drawLine(Color(0xFFFFD36A), androidx.compose.ui.geometry.Offset(index * step, size.height),
                androidx.compose.ui.geometry.Offset(index * step, size.height - value * size.height), strokeWidth = maxOf(1f, step * .7f))
        }
    }
}
