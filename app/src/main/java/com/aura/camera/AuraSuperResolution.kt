package com.aura.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Optional 4x AI super-resolution using Qualcomm's mobile Real-ESRGAN TFLite
 * export. The ~4.88 MB model is downloaded once, SHA-256 checked, then cached
 * privately on the phone. Inference runs locally/offline after download.
 *
 * The model reconstructs plausible detail; it cannot guarantee recovery of
 * the exact original detail lost in a low-resolution source.
 */
object AuraSuperResolution {
    private const val MODEL_NAME = "aura_realesrgan_x4v3.tflite"
    private const val MODEL_URL =
        "https://huggingface.co/qualcomm/Real-ESRGAN-General-x4v3/resolve/cd6c94a652f125e9693a38d656b4d0e1176a4103/Real-ESRGAN-General-x4v3.tflite?download=true"
    private const val MODEL_SHA256 =
        "8a284b295049651c67ffdf35d4f365c1d702c13286836de2a33de76d5324146d"
    private const val TILE = 128
    private const val SCALE = 4

    @Synchronized
    fun upscaleTo4k(context: Context, source: Bitmap): Bitmap {
        require(source.width > 0 && source.height > 0)
        val longest = maxOf(source.width, source.height)
        if (longest >= 3840) return source.copy(Bitmap.Config.ARGB_8888, false)

        val modelFile = obtainModel(context)
        val options = Interpreter.Options().setNumThreads(4)
        Interpreter(modelFile, options).use { interpreter ->
            val inputShape = interpreter.getInputTensor(0).shape()
            val outputShape = interpreter.getOutputTensor(0).shape()
            require(inputShape.contentEquals(intArrayOf(1, TILE, TILE, 3))) {
                "Unsupported AI model input: ${inputShape.contentToString()}"
            }
            require(outputShape.size == 4 && outputShape[1] == TILE * SCALE &&
                outputShape[2] == TILE * SCALE && outputShape[3] == 3) {
                "Unsupported AI model output: ${outputShape.contentToString()}"
            }

            val input = ByteBuffer.allocateDirect(TILE * TILE * 3 * 4).order(ByteOrder.nativeOrder())
            val output = ByteBuffer.allocateDirect(TILE * SCALE * TILE * SCALE * 3 * 4)
                .order(ByteOrder.nativeOrder())

            // 4x ESRGAN output is reduced to a maximum 3840-pixel long edge.
            // A 1280x720 source therefore becomes exactly 3840x2160.
            val scale = min(SCALE.toFloat(), 3840f / longest)
            val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
            val resultPixels = IntArray(targetWidth * targetHeight)

            var tileY = 0
            while (tileY < source.height) {
                var tileX = 0
                while (tileX < source.width) {
                    input.clear()
                    for (y in 0 until TILE) {
                        val sy = (tileY + y).coerceAtMost(source.height - 1)
                        for (x in 0 until TILE) {
                            val sx = (tileX + x).coerceAtMost(source.width - 1)
                            val pixel = source.getPixel(sx, sy)
                            input.putFloat(Color.red(pixel) / 255f)
                            input.putFloat(Color.green(pixel) / 255f)
                            input.putFloat(Color.blue(pixel) / 255f)
                        }
                    }
                    input.rewind()
                    output.clear()
                    interpreter.run(input, output)
                    output.rewind()

                    val validWidth = min(TILE, source.width - tileX)
                    val validHeight = min(TILE, source.height - tileY)
                    val tilePixels = IntArray(TILE * SCALE * TILE * SCALE)
                    for (i in tilePixels.indices) {
                        val red = (output.getFloat().coerceIn(0f, 1f) * 255f).roundToInt()
                        val green = (output.getFloat().coerceIn(0f, 1f) * 255f).roundToInt()
                        val blue = (output.getFloat().coerceIn(0f, 1f) * 255f).roundToInt()
                        tilePixels[i] = Color.rgb(red, green, blue)
                    }
                    val tileBitmap = Bitmap.createBitmap(tilePixels, TILE * SCALE, TILE * SCALE, Bitmap.Config.ARGB_8888)
                    val crop = Bitmap.createBitmap(tileBitmap, 0, 0, validWidth * SCALE, validHeight * SCALE)
                    val destWidth = min(targetWidth - (tileX * scale).roundToInt(), (validWidth * scale).roundToInt())
                    val destHeight = min(targetHeight - (tileY * scale).roundToInt(), (validHeight * scale).roundToInt())
                    if (destWidth > 0 && destHeight > 0) {
                        val scaled = Bitmap.createScaledBitmap(crop, destWidth, destHeight, true)
                        val tileOut = IntArray(destWidth * destHeight)
                        scaled.getPixels(tileOut, 0, destWidth, 0, 0, destWidth, destHeight)
                        val startX = (tileX * scale).roundToInt()
                        val startY = (tileY * scale).roundToInt()
                        for (row in 0 until destHeight) {
                            tileOut.copyInto(resultPixels, (startY + row) * targetWidth + startX, row * destWidth, (row + 1) * destWidth)
                        }
                        if (scaled !== crop) scaled.recycle()
                    }
                    if (crop !== tileBitmap) crop.recycle()
                    tileBitmap.recycle()
                    tileX += TILE
                }
                tileY += TILE
            }
            return Bitmap.createBitmap(resultPixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        }
    }

    private fun obtainModel(context: Context): File {
        val model = File(context.filesDir, MODEL_NAME)
        if (model.exists() && sha256(model) == MODEL_SHA256) return model
        if (model.exists()) model.delete()
        val temporary = File(context.filesDir, "$MODEL_NAME.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 90000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            connection.connect()
            require(connection.responseCode in 200..299) { "AI model download failed: HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            require(sha256(temporary) == MODEL_SHA256) { "AI model verification failed. Please retry." }
            check(temporary.renameTo(model)) { "Could not install the AI model." }
            return model
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
