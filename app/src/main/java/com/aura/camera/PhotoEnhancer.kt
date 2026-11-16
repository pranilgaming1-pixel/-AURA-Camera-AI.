package com.aura.camera

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight on-device enhancement for mid-range phones.
 *
 * This is deterministic image processing, not a generative AI model. It keeps
 * the input Bitmap untouched and returns a separate enhanced Bitmap.
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

    fun enhance(source: Bitmap): Result {
        require(source.width > 0 && source.height > 0)
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)

        var luminanceSum = 0.0
        var luminanceSquared = 0.0
        val sampleStep = max(1, input.size / 12000)
        var samples = 0
        for (i in input.indices step sampleStep) {
            val c = input[i]
            val y = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)
            luminanceSum += y
            luminanceSquared += y * y
            samples++
        }
        val mean = (luminanceSum / max(1, samples)).toFloat()
        val variance = (luminanceSquared / max(1, samples) - mean * mean).coerceAtLeast(0.0)
        val contrast = kotlin.math.sqrt(variance).toFloat()

        // Gentle exposure and contrast correction; intentionally conservative
        // to avoid clipping highlights or changing skin tones aggressively.
        val exposure = when {
            mean < 82f -> 1.12f
            mean < 105f -> 1.07f
            mean > 190f -> 0.96f
            else -> 1.02f
        }
        val contrastGain = when {
            contrast < 42f -> 1.13f
            contrast < 58f -> 1.07f
            else -> 1.035f
        }
        val sharpening = if (contrast < 38f) 0.16f else 0.11f
        val adjusted = IntArray(input.size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val original = input[index]
                val left = input[y * width + max(0, x - 1)]
                val right = input[y * width + min(width - 1, x + 1)]
                val up = input[max(0, y - 1) * width + x]
                val down = input[min(height - 1, y + 1) * width + x]
                val r = channel(original, left, right, up, down, 16, exposure, contrastGain, sharpening)
                val g = channel(original, left, right, up, down, 8, exposure, contrastGain, sharpening)
                val b = channel(original, left, right, up, down, 0, exposure, contrastGain, sharpening)
                adjusted[index] = Color.rgb(r, g, b)
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(adjusted, 0, width, 0, 0, width, height)
        return Result(output, Report(mean, contrast, exposure, contrastGain, sharpening))
    }

    private fun channel(
        center: Int, left: Int, right: Int, up: Int, down: Int, shift: Int,
        exposure: Float, contrast: Float, sharpen: Float
    ): Int {
        val c = (center ushr shift) and 0xFF
        val neighbours = (((left ushr shift) and 0xFF) +
            ((right ushr shift) and 0xFF) +
            ((up ushr shift) and 0xFF) +
            ((down ushr shift) and 0xFF)) / 4f
        val detail = c - neighbours
        val corrected = ((c - 128f) * contrast + 128f) * exposure + detail * sharpen
        return corrected.toInt().coerceIn(0, 255)
    }
}
