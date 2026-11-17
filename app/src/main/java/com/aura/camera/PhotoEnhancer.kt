package com.aura.camera

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Adaptive, memory-conscious photo finishing. This is classical image processing;
 * AuraSuperResolution is the separate trained neural super-resolution engine.
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

        // First pass: histogram from row buffers only. No full-image IntArray.
        val histogram = IntArray(256)
        var sum = 0L
        var sumSquared = 0.0
        var samples = 0
        val sampleXStep = max(1, width / 120)
        val sampleYStep = max(1, height / 150)
        val sampleRow = IntArray(width)
        for (y in 0 until height step sampleYStep) {
            source.getPixels(sampleRow, 0, width, 0, y, width, 1)
            for (x in 0 until width step sampleXStep) {
                val value = luma(sampleRow[x])
                histogram[value]++
                sum += value
                sumSquared += value.toDouble() * value
                samples++
            }
        }
        val mean = sum.toFloat() / max(1, samples)
        val variance = (sumSquared / max(1, samples) - mean * mean).coerceAtLeast(0.0)
        val contrast = kotlin.math.sqrt(variance).toFloat()
        val low = percentile(histogram, samples, 0.025f)
        val high = percentile(histogram, samples, 0.985f)
        val range = (high - low).coerceAtLeast(80)

        val scene = sceneHint.lowercase()
        val portraitScene = scene.contains("person") || scene.contains("face") || scene.contains("people")
        val nightScene = scene.contains("night") || scene.contains("dark") || mean < 62f
        val landscapeScene = scene.contains("landscape") || scene.contains("mountain") || scene.contains("sky")
        val foodScene = scene.contains("food") || scene.contains("dish")
        val exposure = when {
            mean < 62f -> 1.16f
            mean < 88f -> 1.10f
            mean < 112f -> 1.055f
            mean > 205f -> 0.97f
            else -> 1.015f
        }
        val sceneExposure = when {
            nightScene -> 1.045f
            portraitScene -> 0.99f
            foodScene -> 1.015f
            else -> 1f
        }
        val contrastGain = when {
            contrast < 34f -> 1.12f
            contrast < 52f -> 1.075f
            else -> 1.035f
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

        // Precompute the bilateral range weights once. Calling exp() for every
        // neighbour of every pixel was a major CPU bottleneck on mid-range phones.
        val rangeWeights = FloatArray(256) { difference -> exp(-difference / 22f) }

        // Stream three source rows through the denoise/detail pass. This keeps
        // peak working memory low enough for high-megapixel photos on mid-range phones.
        val previous = IntArray(width)
        val current = IntArray(width)
        val next = IntArray(width)
        val outputRow = IntArray(width)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            source.getPixels(current, 0, width, 0, y, width, 1)
            source.getPixels(previous, 0, width, 0, max(0, y - 1), width, 1)
            source.getPixels(next, 0, width, 0, min(height - 1, y + 1), width, 1)

            for (x in 0 until width) {
                val center = current[x]
                val centerY = luma(center).toFloat()
                var redSum = 0f
                var greenSum = 0f
                var blueSum = 0f
                var weightSum = 0f
                var localLuma = 0f
                var localCount = 0

                for (dy in -1..1) {
                    val row = when (dy) { -1 -> previous; 0 -> current; else -> next }
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val neighbour = row[nx]
                        val difference = kotlin.math.abs(luma(neighbour) - centerY)
                        val spatial = if (dx == 0 && dy == 0) 1f else 0.72f
                        val weight = spatial * rangeWeights[difference.toInt().coerceIn(0, 255)]
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
                val denoiseBase = if (nightScene) 0.25f else 0.20f
                val denoiseMix = (denoiseBase - texture / 180f).coerceIn(0.04f, if (nightScene) 0.22f else 0.17f)
                var red = Color.red(center) * (1f - denoiseMix) + smoothR * denoiseMix
                var green = Color.green(center) * (1f - denoiseMix) + smoothG * denoiseMix
                var blue = Color.blue(center) * (1f - denoiseMix) + smoothB * denoiseMix

                val normalized = ((centerY - low) / range).coerceIn(0f, 1f)
                val shadowLift = (if (nightScene) 0.075f else 0.045f) *
                    (1f - normalized) * exp(-normalized * 3.2f)
                val highlightRollOff = (if (portraitScene) 0.055f else 0.035f) * normalized.pow(3)
                var mapped = (normalized + shadowLift - highlightRollOff)
                mapped = ((mapped - 0.5f) * contrastGain + 0.5f).coerceIn(0f, 1f)
                mapped = (mapped * exposure * sceneExposure).coerceIn(0f, 1f)
                val shoulder = if (mapped > 0.82f) 0.82f + (mapped - 0.82f) * 0.72f else mapped
                val targetY = (shoulder * 255f).coerceIn(0f, 255f)

                val detail = centerY - localLuma / localCount
                val detailGain = if (kotlin.math.abs(detail) < 2.5f) 0f else sharpen
                val sharpenedY = (targetY + detail * detailGain).coerceIn(0f, 255f)
                val scale = if (centerY > 2f) sharpenedY / centerY else 1f
                red = (red * scale).coerceIn(0f, 255f)
                green = (green * scale).coerceIn(0f, 255f)
                blue = (blue * scale).coerceIn(0f, 255f)
                outputRow[x] = Color.rgb(red.toInt(), green.toInt(), blue.toInt())
            }
            output.setPixels(outputRow, 0, width, 0, y, width, 1)

        }
        return Result(output, Report(mean, contrast, exposure * sceneExposure, contrastGain, sharpen))
    }

    private fun luma(color: Int): Int =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color))
            .toInt().coerceIn(0, 255)

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
