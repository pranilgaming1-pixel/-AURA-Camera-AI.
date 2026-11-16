package com.aura.camera

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * AURA computational photo processing.
 *
 * This is adaptive on-device image processing, not a generative or trained
 * super-resolution model. It preserves the source bitmap and creates a new
 * result. Processing is deliberately conservative to protect skin tones.
 */
object PhotoEnhancer {
    data class Report(
        val brightnessBefore: Float,
        val contrastBefore: Float,
        val appliedExposure: Float,
        val appliedContrast: Float,
        val sharpening: Float
    )

    data class Result(val bitmap: Bitmap, val report: Report)

    fun enhance(source: Bitmap, sceneHint: String = "AUTO"): Result {
        require(source.width > 0 && source.height > 0)
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // Analyze a distributed sample so full-resolution photos stay responsive.
        val histogram = IntArray(256)
        var sum = 0L
        var sumSquared = 0.0
        var samples = 0
        val sampleStep = max(1, pixels.size / 18000)
        for (i in pixels.indices step sampleStep) {
            val c = pixels[i]
            val y = luma(c)
            histogram[y]++
            sum += y
            sumSquared += y.toDouble() * y
            samples++
        }
        val mean = sum.toFloat() / max(1, samples)
        val variance = (sumSquared / max(1, samples) - mean * mean).coerceAtLeast(0.0)
        val contrast = kotlin.math.sqrt(variance).toFloat()
        val low = percentile(histogram, samples, 0.025f)
        val high = percentile(histogram, samples, 0.985f)
        val range = (high - low).coerceAtLeast(80)

        val exposure = when {
            mean < 62f -> 1.16f
            mean < 88f -> 1.10f
            mean < 112f -> 1.055f
            mean > 205f -> 0.97f
            else -> 1.015f
        }
        val contrastGain = when {
            contrast < 34f -> 1.12f
            contrast < 52f -> 1.075f
            else -> 1.035f
        }
        val scene = sceneHint.lowercase()
        val portraitScene = scene.contains("person") || scene.contains("face") || scene.contains("people")
        val nightScene = scene.contains("night") || scene.contains("dark") || mean < 62f
        val landscapeScene = scene.contains("landscape") || scene.contains("mountain") || scene.contains("sky")
        val foodScene = scene.contains("food") || scene.contains("dish")
        val sceneExposure = when {
            nightScene -> 1.045f
            portraitScene -> 0.99f
            foodScene -> 1.015f
            else -> 1f
        }
        val sharpen = (when {
            contrast < 28f -> 0.22f
            contrast < 48f -> 0.17f
            else -> 0.12f
        } * when {
            portraitScene -> 0.72f
            landscapeScene -> 1.16f
            else -> 1f
        }).coerceIn(0.07f, 0.24f)

        // One output buffer only. A 12 MP image needs about 48 MB per IntArray.
        val outputPixels = IntArray(pixels.size)
        for (y in 0 until height) {
            val y0 = max(0, y - 1)
            val y2 = min(height - 1, y + 1)
            for (x in 0 until width) {
                val index = y * width + x
                val center = pixels[index]
                val centerY = luma(center).toFloat()

                // Edge-aware 3x3 chroma smoothing: strong in flat/noisy areas,
                // weak across edges so hair, text and fine texture remain intact.
                var redSum = 0f
                var greenSum = 0f
                var blueSum = 0f
                var weightSum = 0f
                var localLuma = 0f
                var localCount = 0
                for (ny in y0..y2) {
                    val row = ny * width
                    for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                        val neighbour = pixels[row + nx]
                        val difference = kotlin.math.abs(luma(neighbour) - centerY)
                        val spatial = if (nx == x && ny == y) 1f else 0.72f
                        val weight = spatial * exp(-difference / 22f)
                        redSum += Color.red(neighbour) * weight
                        greenSum += Color.green(neighbour) * weight
                        blueSum += Color.blue(neighbour) * weight
                        weightSum += weight
                        localLuma += luma(neighbour)
                        localCount++
                    }
                }
                val smoothR = redSum / weightSum.coerceAtLeast(0.001f)
                val smoothG = greenSum / weightSum.coerceAtLeast(0.001f)
                val smoothB = blueSum / weightSum.coerceAtLeast(0.001f)
                val texture = kotlin.math.abs(centerY - localLuma / localCount)
                val denoiseBase = if (nightScene) 0.25f else 0.20f\n                val denoiseMix = (denoiseBase - texture / 180f).coerceIn(0.04f, if (nightScene) 0.22f else 0.17f)

                var r = Color.red(center) * (1f - denoiseMix) + smoothR * denoiseMix
                var g = Color.green(center) * (1f - denoiseMix) + smoothG * denoiseMix
                var b = Color.blue(center) * (1f - denoiseMix) + smoothB * denoiseMix

                // Tone-map luminance, rather than independently pushing RGB
                // channels. This avoids the harsh, oversaturated filter look.
                val normalized = ((centerY - low) / range).coerceIn(0f, 1f)
                val shadowLift = 0.045f * (1f - normalized) * exp(-normalized * 3.2f)
                val highlightRollOff = 0.035f * normalized.pow(3)
                var mapped = (normalized + shadowLift - highlightRollOff)
                mapped = ((mapped - 0.5f) * contrastGain + 0.5f).coerceIn(0f, 1f)
                mapped = (mapped * exposure * sceneExposure).coerceIn(0f, 1f)
                // A gentle shoulder protects bright skies and faces.
                val shoulder = if (mapped > 0.82f) 0.82f + (mapped - 0.82f) * 0.72f else mapped
                val targetY = (shoulder * 255f).coerceIn(0f, 255f)

                // Edge-aware luminance sharpening, with no aggressive colour halos.
                val averageY = localLuma / localCount
                val detail = centerY - averageY
                val detailGain = if (kotlin.math.abs(detail) < 2.5f) 0f else sharpen
                val sharpenedY = (targetY + detail * detailGain).coerceIn(0f, 255f)
                val scale = if (centerY > 2f) sharpenedY / centerY else 1f
                r = (r * scale).coerceIn(0f, 255f)
                g = (g * scale).coerceIn(0f, 255f)
                b = (b * scale).coerceIn(0f, 255f)

                outputPixels[index] = Color.rgb(r.toInt(), g.toInt(), b.toInt())
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return Result(output, Report(mean, contrast, exposure, contrastGain, sharpen))
    }

    private fun luma(color: Int): Int =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)).toInt().coerceIn(0, 255)

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).toInt().coerceAtLeast(1)
        var accumulated = 0
        for (i in histogram.indices) {
            accumulated += histogram[i]
            if (accumulated >= target) return i
        }
        return 255
    }
}
