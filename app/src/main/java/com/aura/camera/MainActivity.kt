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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private val Black = Color(0xFF050607)
private val Glass = Color(0xCC17191D)
private val White = Color(0xFFF7F7F5)
private val Soft = Color(0xFF9A9DA5)
private val Gold = Color(0xFFFFD36A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent { AuraCameraScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuraCameraScreen() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var capture by remember { mutableStateOf<(() -> Unit)?>(null) }
    var configure by remember { mutableStateOf<((String, Float, Float) -> Unit)?>(null) }
    var aiText by remember { mutableStateOf("AI CAMERA READY") }
    var sceneHint by remember { mutableStateOf("AUTO") }
    var brightness by remember { mutableIntStateOf(0) }
    var histogram by remember { mutableStateOf(List(48) { 0f }) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var enhanced by remember { mutableStateOf<Bitmap?>(null) }
    var resultText by remember { mutableStateOf("Natural detail enhancement") }
    var processing by remember { mutableStateOf(false) }
    var showEnhanced by remember { mutableStateOf(true) }
    var flash by remember { mutableStateOf("AUTO") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var timer by remember { mutableIntStateOf(0) }
    var grid by remember { mutableStateOf(false) }
    var ratio by remember { mutableStateOf("4:3") }
    var mode by remember { mutableStateOf("PHOTO") }
    var backCamera by remember { mutableStateOf(true) }
    var optionsOpen by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var autoSave by remember { mutableStateOf(true) }
    var auto4k by remember { mutableStateOf(false) }
    val worker = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) { if (!permission) permissionRequest.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(flash, zoom, exposure) { configure?.invoke(flash, zoom, exposure) }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            processing = true
            worker.execute {
                try {
                    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    if (bmp == null) throw IllegalStateException("Image could not be opened")
                    val edit = PhotoEnhancer.enhance(bmp, sceneHint)
                    ContextCompat.getMainExecutor(context).execute {
                        original = bmp
                        enhanced = edit.bitmap
                        resultText = "AURA · natural light and detail"
                        processing = false
                    }
                } catch (e: Exception) {
                    ContextCompat.getMainExecutor(context).execute { processing = false; resultText = e.message ?: "Enhancement failed" }
                }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Black, surface = Glass)) {
        if (original != null && enhanced != null) {
            ResultScreen(
                original = original!!,
                enhanced = enhanced!!,
                showEnhanced = showEnhanced,
                onToggle = { showEnhanced = it },
                message = resultText,
                isUpscaling = processing,
                onRetake = { if (!processing) { original = null; enhanced = null; showEnhanced = true } },
                onUpscale = {
                    if (!processing) {
                        val inputBitmap = enhanced!!
                        processing = true
                        resultText = "Preparing AURA AI super-resolution model..."
                        worker.execute {
                            try {
                                val upscaled = AuraSuperResolution.upscaleTo4k(context, inputBitmap)
                                ContextCompat.getMainExecutor(context).execute {
                                    enhanced = upscaled
                                    showEnhanced = true
                                    resultText = "AI super-resolution complete · ${upscaled.width} × ${upscaled.height}"
                                    processing = false
                                }
                            } catch (e: Exception) {
                                ContextCompat.getMainExecutor(context).execute {
                                    resultText = "AI upscale failed: ${e.message ?: "Please retry"}"
                                    processing = false
                                }
                            }
                        }
                    }
                },
                onSave = {
                    if (!processing) {
                        val first = saveBitmap(context, original!!, "AURA_Original")
                        val second = saveBitmap(context, enhanced!!, "AURA_AI_Enhanced")
                        resultText = if (first && second) "Saved original and AI-enhanced photos to Pictures/AURA" else "Could not save. Check available storage."
                    }
                }
            )
        } else {
            Column(Modifier.fillMaxSize().background(Black).padding(horizontal = 16.dp)) {
                // Premium control strip
                Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("AURA", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Text("COMPUTATIONAL CAMERA", color = Soft, fontSize = 8.sp, letterSpacing = 1.5.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TopChip(flash, flash != "OFF") {
                        flash = when (flash) { "AUTO" -> "ON"; "ON" -> "OFF"; else -> "AUTO" }
                    }
                    Spacer(Modifier.width(8.dp))
                    TopIcon("▦", grid) { grid = !grid }
                    Spacer(Modifier.width(8.dp))
                    TopIcon("⚙", false) { optionsOpen = true }
                }

                if (permission) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp, bottom = 10.dp).clip(RoundedCornerShape(30.dp))) {
                        key(backCamera) {
                            CameraPreview(
                                modifier = Modifier.fillMaxSize(),
                                backCamera = backCamera,
                                onCaptureReady = { capture = it },
                                onConfigure = { configure = it },
                                onAnalysis = { bins, mean -> histogram = bins; brightness = mean },
                                sceneHint = sceneHint,
                                onAi = { label ->
                                    aiText = label
                                    sceneHint = label.substringAfter("AI ·", "AUTO").trim().ifBlank { "AUTO" }
                                },
                                onPhoto = { file, detectedScene ->
                                    processing = true
                                    worker.execute {
                                        try {
                                            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: throw IllegalStateException("Photo decode failed")
                                            val edit = PhotoEnhancer.enhance(bmp, detectedScene)
                                            val finalBitmap = if (auto4k && maxOf(edit.bitmap.width, edit.bitmap.height) < 3840) AuraSuperResolution.upscaleTo4k(context, edit.bitmap) else edit.bitmap
                                            val savedOriginal = if (autoSave) saveBitmap(context, bmp, "AURA_Original") else false
                                            val savedEnhanced = if (autoSave) saveBitmap(context, finalBitmap, "AURA_AI_Enhanced") else false
                                            ContextCompat.getMainExecutor(context).execute {
                                                original = bmp; enhanced = finalBitmap
                                                resultText = buildString {
                                                    append("Auto-enhanced · ${edit.report.appliedExposure}× exposure")
                                                    if (auto4k && maxOf(finalBitmap.width, finalBitmap.height) >= 3840) append(" · AI 4K")
                                                    if (autoSave) append(if (savedOriginal && savedEnhanced) " · saved to Pictures/AURA" else " · auto-save failed")
                                                }
                                                processing = false
                                            }
                                        } catch (e: Exception) {
                                            ContextCompat.getMainExecutor(context).execute { processing = false; aiText = e.message ?: "Capture processing failed" }
                                        }
                                    }
                                }
                            )
                        }
                        if (grid) RuleOfThirds(Modifier.fillMaxSize())
                        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            GlassPill(if (processing) "PROCESSING PHOTO" else "● LIVE VIEW", if (processing) Gold else Color(0xFFB9F4D0))
                            GlassPill("SMART TONE", White)
                        }
                        GlassPill(aiText, Gold, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
                    }

                    // Exposure and live meter
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("−", color = White, fontSize = 17.sp)
                        Slider(
                            value = exposure, onValueChange = { exposure = it },
                            valueRange = -2f..2f, modifier = Modifier.weight(1f).height(30.dp),
                            colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color(0xFF42444A))
                        )
                        Text("+", color = White, fontSize = 17.sp)
                        Text(String.format(Locale.US, "  %.1f EV", exposure), color = Soft, fontSize = 10.sp)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("LIGHT", color = Soft, fontSize = 8.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.width(8.dp))
                        MiniHistogram(histogram, Modifier.weight(1f).height(20.dp))
                        Text("  $brightness", color = Soft, fontSize = 9.sp)
                    }
                    Spacer(Modifier.height(8.dp))

                    // Zoom selector
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        listOf(0.5f, 1f, 2f, 4f).forEach { factor ->
                            val label = if (factor == 0.5f) "0.5×" else "${factor.toInt()}×"
                            Box(Modifier.padding(horizontal = 5.dp).clip(CircleShape)
                                .background(if (zoom == factor) Color(0xFF3C3425) else Color(0xFF191B20))
                                .clickable { zoom = factor }.padding(horizontal = 15.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                                Text(label, color = if (zoom == factor) Gold else White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // Mode wheel
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        listOf("NIGHT", "PORTRAIT", "PHOTO", "VIDEO").forEach { item ->
                            Text(item, color = if (mode == item) White else Soft, fontSize = if (mode == item) 12.sp else 10.sp,
                                fontWeight = if (mode == item) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.clickable { mode = item }.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }

                    // Shutter dock
                    Row(Modifier.fillMaxWidth().height(94.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        DockButton("▧", "GALLERY") { galleryPicker.launch("image/*") }
                        Box(Modifier.size(78.dp).clip(CircleShape).background(White).padding(5.dp)
                            .clip(CircleShape).background(Black).padding(3.dp).clip(CircleShape)
                            .background(White).clickable(enabled = capture != null && !processing) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    if (timer > 0) {
                                        for (n in timer downTo 1) { countdown = n; delay(1000) }
                                        countdown = 0
                                    }
                                    capture?.invoke()
                                }
                            }, contentAlignment = Alignment.Center) {
                            if (countdown > 0) Text("$countdown", color = Black, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        }
                        DockButton("⇄", "FLIP") { backCamera = !backCamera }
                    }
                    Text("AURA IMAGE ENGINE", color = Color(0xFF656971), fontSize = 8.sp, letterSpacing = 2.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Button(onClick = { permissionRequest.launch(Manifest.permission.CAMERA) }) { Text("Allow camera access") }
                    }
                }
            }
        }

        if (optionsOpen) {
            ModalBottomSheet(onDismissRequest = { optionsOpen = false }, containerColor = Color(0xFF17191D), contentColor = White) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 32.dp)) {
                    Text("Camera settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Quick controls for your shot", color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
                    SettingLabel("FLASH")
                    ChoiceRow(listOf("AUTO", "ON", "OFF"), flash) { flash = it }
                    SettingLabel("TIMER")
                    ChoiceRow(listOf("OFF", "3 SEC", "10 SEC"), if (timer == 0) "OFF" else if (timer == 3) "3 SEC" else "10 SEC") {
                        timer = when (it) { "3 SEC" -> 3; "10 SEC" -> 10; else -> 0 }
                    }
                    SettingLabel("ASPECT RATIO")
                    ChoiceRow(listOf("4:3", "16:9", "1:1"), ratio) { ratio = it }
                    SettingLabel("COMPOSITION GRID")
                    ChoiceRow(listOf("OFF", "ON"), if (grid) "ON" else "OFF") { grid = it == "ON" }
                    SettingLabel("AUTOMATIC FEATURES")
                    Text("AUTO AI ENHANCEMENT · ALWAYS ON", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
                    SettingToggle("Auto-save photos", "Save original and enhanced copies", autoSave, { autoSave = it })
                    SettingToggle("Auto 4K AI upscale", "Upscale smaller photos after capture; uses more time and memory", auto4k, { auto4k = it })
                    Text("Selected ratio: $ratio", color = Soft, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp))
                    Text("The current camera sensor and Android driver determine available HDR, optical zoom and manual exposure capabilities.", color = Soft, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun ResultScreen(original: Bitmap, enhanced: Bitmap, showEnhanced: Boolean, onToggle: (Boolean) -> Unit, message: String, isUpscaling: Boolean, onRetake: () -> Unit, onUpscale: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Black).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AURA", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRetake) { Text("RETAKE", color = Gold) }
        }
        Text("YOUR PHOTO", color = Soft, fontSize = 10.sp, letterSpacing = 2.sp)
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Image((if (showEnhanced) enhanced else original).asImageBitmap(), contentDescription = "Photo preview", modifier = Modifier.fillMaxWidth())
        }
        Text(message, color = Soft, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showEnhanced, onClick = { onToggle(false) }, label = { Text("ORIGINAL") })
            FilterChip(selected = showEnhanced, onClick = { onToggle(true) }, label = { Text("AURA ENHANCED") })
        }
        OutlinedButton(
            onClick = onUpscale,
            enabled = !isUpscaling && maxOf(original.width, original.height) < 3840,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold)
        ) {
            Text(if (isUpscaling) "AI SUPER-RESOLUTION RUNNING..." else "✦ AI UPSCALE TO 4K", fontWeight = FontWeight.Bold)
        }
        Button(onClick = onSave, enabled = !isUpscaling, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = Black)) {
            Text("SAVE ORIGINAL + AI PHOTO", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TopChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(if (active) Color(0xFF383124) else Glass).clickable { onClick() }.padding(horizontal = 13.dp, vertical = 9.dp)) {
        Text(text, color = if (active) Gold else White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TopIcon(symbol: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(if (active) Color(0xFF383124) else Glass).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(symbol, color = if (active) Gold else White, fontSize = 17.sp)
    }
}

@Composable
private fun GlassPill(text: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color(0xCC111317), shape = CircleShape, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x22333333))) {
        Text(text, color = tint, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .7.sp,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp), maxLines = 1)
    }
}

@Composable
private fun DockButton(symbol: String, label: String, onClick: () -> Unit) {
    Column(Modifier.width(68.dp).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Glass), contentAlignment = Alignment.Center) {
            Text(symbol, color = White, fontSize = 22.sp)
        }
        Text(label, color = Soft, fontSize = 8.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, color = Soft, fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun SettingToggle(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Soft, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceRow(items: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color(0xFF25272D)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (selected == item) Color(0xFF51452D) else Color.Transparent)
                .clickable { onSelect(item) }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                Text(item, color = if (selected == item) Gold else White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RuleOfThirds(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val thirdW = size.width / 3f
        val thirdH = size.height / 3f
        for (i in 1..2) {
            drawLine(Color(0x88FFFFFF), Offset(thirdW * i, 0f), Offset(thirdW * i, size.height), 1f)
            drawLine(Color(0x88FFFFFF), Offset(0f, thirdH * i), Offset(size.width, thirdH * i), 1f)
        }
    }
}

@Composable
private fun MiniHistogram(values: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val step = size.width / values.size.coerceAtLeast(1)
        values.forEachIndexed { index, value ->
            drawLine(Gold, Offset(index * step, size.height), Offset(index * step, size.height - value * size.height), maxOf(1f, step * .7f))
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
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it) } ?: false
    } catch (_: Exception) { false }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    backCamera: Boolean,
    onCaptureReady: (() -> Unit) -> Unit,
    onConfigure: ((String, Float, Float) -> Unit) -> Unit,
    onAnalysis: (List<Float>, Int) -> Unit,
    sceneHint: String,
    onAi: (String) -> Unit,
    onPhoto: (File, String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSceneHint by rememberUpdatedState(sceneHint)
    val latestOnPhoto by rememberUpdatedState(onPhoto)
    AndroidView(modifier = modifier, factory = { ctx ->
        val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(98)
                .build()
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
            var lastSceneAnalysis = 0L
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) {
                    proxy.close()
                } else {
                    val stats = calculateHistogram(proxy)
                    ContextCompat.getMainExecutor(ctx).execute { onAnalysis(stats.first, stats.second) }
                    val now = System.currentTimeMillis()
                    if (now - lastSceneAnalysis >= 900L) {
                        lastSceneAnalysis = now
                        labeler.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { labels ->
                                val best = labels.maxByOrNull { it.confidence }
                                val text = best?.text?.uppercase(Locale.getDefault()) ?: "ANALYZING SCENE"
                                ContextCompat.getMainExecutor(ctx).execute { onAi("AI · $text") }
                            }
                            .addOnFailureListener {
                                ContextCompat.getMainExecutor(ctx).execute { onAi("AI · LIVE ANALYSIS") }
                            }
                            .addOnCompleteListener { proxy.close() }
                    } else {
                        proxy.close()
                    }
                }
            }
            try {
                provider.unbindAll()
                val camera: Camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, analysis)
                onConfigure { flashMode, zoomRatio, ev ->
                    imageCapture.flashMode = when (flashMode) {
                        "ON" -> ImageCapture.FLASH_MODE_ON
                        "AUTO" -> ImageCapture.FLASH_MODE_AUTO
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                    camera.cameraControl.setZoomRatio(zoomRatio.coerceIn(camera.cameraInfo.zoomState.value?.minZoomRatio ?: 1f, camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f))
                    val state = camera.cameraInfo.exposureState
                    val steps = (ev / (state.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: 1f)).toInt()
                    camera.cameraControl.setExposureCompensationIndex(steps.coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper))
                }
                onCaptureReady {
                    val output = File(ctx.cacheDir, "aura_${System.currentTimeMillis()}.jpg")
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(output).build(),
                        ContextCompat.getMainExecutor(ctx),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(results: ImageCapture.OutputFileResults) { latestOnPhoto(output, latestSceneHint) }
                            override fun onError(exception: ImageCaptureException) { onAi("CAPTURE ERROR · ${exception.message ?: "TRY AGAIN"}") }
                        }
                    )
                }
            } catch (e: Exception) {
                onAi("CAMERA ERROR · ${e.message ?: "UNAVAILABLE"}")
            }
        }, ContextCompat.getMainExecutor(ctx))
        previewView
    })
}

private fun calculateHistogram(proxy: androidx.camera.core.ImageProxy): Pair<List<Float>, Int> {
    val plane = proxy.planes[0]
    val buffer = plane.buffer
    val bins = IntArray(48)
    var sum = 0L
    var count = 0
    val stepX = (proxy.width / 120).coerceAtLeast(1)
    val stepY = (proxy.height / 90).coerceAtLeast(1)
    for (y in 0 until proxy.height step stepY) for (x in 0 until proxy.width step stepX) {
        val index = y * plane.rowStride + x * plane.pixelStride
        if (index < buffer.limit()) {
            val value = buffer.get(index).toInt() and 0xFF
            bins[value * bins.size / 256]++
            sum += value
            count++
        }
    }
    val peak = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
    return bins.map { it / peak } to if (count == 0) 0 else (sum / count).toInt()
}
