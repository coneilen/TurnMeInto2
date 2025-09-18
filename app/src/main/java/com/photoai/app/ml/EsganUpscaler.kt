package com.photoai.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

/**
 * Real-ESRGAN 4x TFLite based upscaler with:
 *  - Lazy interpreter init (GPU -> NNAPI -> CPU fallback)
 *  - Tiled inference w/ overlap + seam blending
 *  - Multi-pass scaling (SCALE_FACTOR^n) + fractional resize
 *  - Swirl artifact suppression post-process
 *
 * Assumptions (adjust if your model differs):
 *  - Model input/output: float32 RGB, range [0,1]
 *  - Scale factor: 4x (per pass)
 */
data class EsganOptions(
    val tileSize: Int = 224,
    val tileOverlap: Int = 16,
    val swirlSuppression: Float = 0.4f, // 0..1
    val enableArtifactDenoise: Boolean = true,
    val minEsrganScaleThreshold: Float = 1.15f, // skip ESRGAN if scale smaller
    val maxRetries: Int = 3
)

object EsganUpscaler {

    private const val TAG = "EsganUpscaler"
    private const val MODEL_PATH = "models/Real-ESRGAN-General-x4v3.tflite" // New 4x Real-ESRGAN model filename
    private const val SCALE_FACTOR = 4 // per pass
    private const val SOBEL_K = 10f
    private const val LOW_GRAD_THRESHOLD = 4f

    private var interpreter: Interpreter? = null
    private var backend: Backend = Backend.NONE
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

    private enum class Backend { GPU, NNAPI, CPU, NONE }

    private val lock = Any()
    private var initTried = false

    suspend fun ensureReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (interpreter != null) return@synchronized true
            if (initTried) return@synchronized interpreter != null
            initTried = true
            initInterpreterCascade(context)
            interpreter != null
        }
    }

    private fun initInterpreterCascade(context: Context) {
        val options = Interpreter.Options()

        // Try GPU
        try {
            gpuDelegate = GpuDelegate()
            options.addDelegate(gpuDelegate)
            interpreter = Interpreter(loadModelBuffer(context), options)
            backend = Backend.GPU
            Log.i(TAG, "Initialized ESRGAN with GPU delegate")
            return
        } catch (e: Throwable) {
            Log.w(TAG, "GPU delegate init failed: ${e.message}")
            safeClose(gpuDelegate)
            gpuDelegate = null
            interpreter = null
        }

        // Try NNAPI (API >= 27)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val nnOptions = Interpreter.Options()
                nnApiDelegate = NnApiDelegate()
                nnOptions.addDelegate(nnApiDelegate)
                interpreter = Interpreter(loadModelBuffer(context), nnOptions)
                backend = Backend.NNAPI
                Log.i(TAG, "Initialized ESRGAN with NNAPI delegate")
                return
            }
        } catch (e: Throwable) {
            Log.w(TAG, "NNAPI delegate init failed: ${e.message}")
            safeClose(nnApiDelegate)
            nnApiDelegate = null
            interpreter = null
        }

        // CPU fallback
        try {
            val cpuOptions = Interpreter.Options()
            interpreter = Interpreter(loadModelBuffer(context), cpuOptions)
            backend = Backend.CPU
            Log.i(TAG, "Initialized ESRGAN with CPU backend")
        } catch (e: Throwable) {
            Log.e(TAG, "CPU interpreter init failed: ${e.message}")
            interpreter = null
            backend = Backend.NONE
        }
    }

    private fun safeClose(delegate: Delegate?) {
        try {
            delegate?.close()
        } catch (_: Throwable) {
        }
    }

    private fun loadModelBuffer(context: Context): ByteBuffer {
        val asset = context.assets.open(MODEL_PATH)
        val bytes = asset.readBytes()
        asset.close()
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }

    /**
     * Public upscale entrypoint to match or exceed target dims (while preserving aspect).
     */
    suspend fun upscaleToMatch(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        options: EsganOptions = EsganOptions()
    ): Bitmap = withContext(Dispatchers.Default) {

        require(targetW > 0 && targetH > 0) { "Target dimensions must be positive" }

        val startW = src.width
        val startH = src.height

        val requiredScale = max(
            targetW.toFloat() / startW.toFloat(),
            targetH.toFloat() / startH.toFloat()
        )

        android.util.Log.d(
            "ImagePipeline",
            "phase=esrgan_entry src=${startW}x${startH} target=${targetW}x${targetH} requiredScale=${String.format("%.3f", requiredScale)} threshold=${String.format("%.3f", options.minEsrganScaleThreshold)}"
        )

        if (startW >= targetW && startH >= targetH) {
            android.util.Log.d(
                "ImagePipeline",
                "phase=esrgan_skip reason=alreadyLarge src=${startW}x${startH} target=${targetW}x${targetH}"
            )
            return@withContext src
        }

        if (requiredScale < options.minEsrganScaleThreshold) {
            android.util.Log.d(
                "ImagePipeline",
                "phase=esrgan_skip reason=belowThreshold scale=${String.format("%.3f", requiredScale)} threshold=${String.format("%.3f", options.minEsrganScaleThreshold)}"
            )
            return@withContext bicubicFinal(src, targetW, targetH, sharpen = true)
        }

        if (!ensureReady(context)) {
            android.util.Log.d(
                "ImagePipeline",
                "phase=esrgan_fail reason=interpreterNotReady fallback=bicubic src=${startW}x${startH} target=${targetW}x${targetH}"
            )
            return@withContext bicubicFinal(src, targetW, targetH, sharpen = true)
        }

        var current = src
        val passes = floor(kotlin.math.ln(requiredScale.toDouble()) / kotlin.math.ln(SCALE_FACTOR.toDouble())).toInt().coerceAtLeast(1)
        val maxUsefulPasses = passes.coerceAtMost(5) // Safety
        android.util.Log.d(
            "ImagePipeline",
            "phase=esrgan_backend backend=$backend requiredScale=${String.format("%.3f", requiredScale)} passes=$passes clamped=$maxUsefulPasses"
        )
        var fractionalApplied = false
        var artifactApplied = false

        val totalMs = measureTimeMillis {
            repeat(maxUsefulPasses) { passIdx ->
                if (current.width >= targetW || current.height >= targetH) return@repeat
                val passMs = measureTimeMillis {
                    current = runSinglePass(current, options)
                }
                android.util.Log.d(
                    "ImagePipeline",
                    "phase=esrgan_pass idx=${passIdx + 1}/$maxUsefulPasses out=${current.width}x${current.height} ms=${passMs}"
                )
            }

            // Fractional residual scale if still below target
            if (current.width < targetW || current.height < targetH) {
                android.util.Log.d(
                    "ImagePipeline",
                    "phase=esrgan_fractional start=${current.width}x${current.height} target=${targetW}x${targetH}"
                )
                val fracMs = measureTimeMillis {
                    current = bicubicFinal(current, targetW, targetH, sharpen = false)
                }
                fractionalApplied = true
                android.util.Log.d(
                    "ImagePipeline",
                    "phase=esrgan_fractional_done out=${current.width}x${current.height} ms=${fracMs}"
                )
            }

            // Swirl suppression / artifact reduction
            if (options.swirlSuppression > 0f || options.enableArtifactDenoise) {
                android.util.Log.d(
                    "ImagePipeline",
                    "phase=esrgan_artifact start=${current.width}x${current.height} swirl=${options.swirlSuppression} denoise=${options.enableArtifactDenoise}"
                )
                val artMs = measureTimeMillis {
                    current = swirlSuppress(
                        originalSmall = src,
                        upscaled = current,
                        strength = options.swirlSuppression,
                        applyLowGradMedian = options.enableArtifactDenoise
                    )
                }
                artifactApplied = true
                android.util.Log.d(
                    "ImagePipeline",
                    "phase=esrgan_artifact_done out=${current.width}x${current.height} ms=${artMs}"
                )
            }
        }

        android.util.Log.d(
            "ImagePipeline",
            "phase=esrgan_complete final=${current.width}x${current.height} totalMs=${totalMs} passes=$passes clamped=$maxUsefulPasses fracApplied=${fractionalApplied} artifactApplied=${artifactApplied} backend=$backend"
        )
        current
    }

    /**
     * Single 4x ESRGAN tiled inference pass.
     */
    private fun runSinglePass(
        input: Bitmap,
        options: EsganOptions
    ): Bitmap {
        val interp = interpreter ?: return input
        val scale = SCALE_FACTOR
        var tileSize = options.tileSize
        val overlap = options.tileOverlap

        val inW = input.width
        val inH = input.height
        val outW = inW * scale
        val outH = inH * scale

        var outBitmap: Bitmap? = null
        var attempt = 0
        var success = false
        var lastError: Throwable? = null

        while (attempt < options.maxRetries && !success) {
            try {
                outBitmap?.recycle()
                outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

                val accumulator = IntArray(outW * outH)
                val weightMap = FloatArray(outW * outH)

                val tilesX = ceil(inW / tileSize.toFloat()).toInt()
                val tilesY = ceil(inH / tileSize.toFloat()).toInt()

                var tileCount = 0
                val tileMsTotal = measureTimeMillis {
                    for (ty in 0 until tilesY) {
                        for (tx in 0 until tilesX) {
                            val x0 = tx * tileSize
                            val y0 = ty * tileSize
                            val tileW = min(tileSize, inW - x0)
                            val tileH = min(tileSize, inH - y0)

                            val padLeft = if (x0 == 0) 0 else overlap
                            val padTop = if (y0 == 0) 0 else overlap
                            val padRight = if (x0 + tileW >= inW) 0 else overlap
                            val padBottom = if (y0 + tileH >= inH) 0 else overlap

                            val cropX = x0 - padLeft
                            val cropY = y0 - padTop
                            val cropW = tileW + padLeft + padRight
                            val cropH = tileH + padTop + padBottom

                            val safeCropX = max(0, cropX)
                            val safeCropY = max(0, cropY)
                            val safeCropW = min(inW - safeCropX, cropW - (safeCropX - cropX))
                            val safeCropH = min(inH - safeCropY, cropH - (safeCropY - cropY))

                            val tileBitmap = Bitmap.createBitmap(
                                input,
                                safeCropX,
                                safeCropY,
                                safeCropW,
                                safeCropH
                            )

                            val srTile = runModel(interp, tileBitmap)
                            tileBitmap.recycle()

                            val innerX0 = (safeCropX + padLeft).coerceAtMost(inW)
                            val innerY0 = (safeCropY + padTop).coerceAtMost(inH)
                            val innerW = tileW
                            val innerH = tileH

                            val outInnerX0 = innerX0 * scale
                            val outInnerY0 = innerY0 * scale
                            val outInnerW = innerW * scale
                            val outInnerH = innerH * scale

                            blendTile(
                                srTile,
                                outInnerX0,
                                outInnerY0,
                                outInnerW,
                                outInnerH,
                                overlap * scale,
                                accumulator,
                                weightMap,
                                outW,
                                outH
                            )
                            srTile.recycle()
                            tileCount++
                        }
                    }
                }
                Log.d(TAG, "Tiled pass tiles=$tileCount in ${tileMsTotal}ms (tileSize=$tileSize overlap=$overlap)")

                // Normalize accumulated pixels by weights
                val outPixels = IntArray(outW * outH)
                for (i in outPixels.indices) {
                    val w = weightMap[i]
                    if (w > 0f) {
                        val c = accumulator[i]
                        val a = (c ushr 24) and 0xFF
                        val r = (c ushr 16) and 0xFF
                        val g = (c ushr 8) and 0xFF
                        val b = c and 0xFF
                        val nr = (r / w).roundToInt().coerceIn(0, 255)
                        val ng = (g / w).roundToInt().coerceIn(0, 255)
                        val nb = (b / w).roundToInt().coerceIn(0, 255)
                        outPixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
                    } else {
                        outPixels[i] = 0xFF000000.toInt()
                    }
                }
                outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
                success = true
            } catch (oom: OutOfMemoryError) {
                lastError = oom
                tileSize = when {
                    tileSize > 192 -> 192
                    tileSize > 160 -> 160
                    tileSize > 128 -> 128
                    else -> tileSize / 2
                }.coerceAtLeast(64)
                Log.w(TAG, "OOM on pass tileSize try $attempt; reducing tileSize -> $tileSize")
                System.gc()
            } catch (e: Throwable) {
                lastError = e
                Log.e(TAG, "Tile inference error: ${e.message}")
                break
            }
            attempt++
        }

        if (!success) {
            Log.e(TAG, "Failed ESRGAN pass, returning original (error=${lastError?.message})")
            outBitmap?.recycle()
            return input
        }

        return outBitmap!!
    }

    /**
     * Run full model on single tile.
     */
    private fun runModel(interp: Interpreter, tile: Bitmap): Bitmap {
        val inW = tile.width
        val inH = tile.height
        val outW = inW * SCALE_FACTOR
        val outH = inH * SCALE_FACTOR

        val inputTensor = interp.getInputTensor(0)
        val inputShape = inputTensor.shape()
        val expectedH = inputShape.getOrNull(1) ?: inH
        val expectedW = inputShape.getOrNull(2) ?: inW
        // If model is fully dynamic dims we proceed; else ensure match or resize
        val modelDynamic = (expectedH == -1 || expectedW == -1)

        val workBitmap = if (!modelDynamic && (expectedW != inW || expectedH != inH)) {
            Bitmap.createScaledBitmap(tile, expectedW, expectedH, true)
        } else {
            tile
        }

        val w = workBitmap.width
        val h = workBitmap.height

        val inputBuffer =
            ByteBuffer.allocateDirect(4 * w * h * 3).order(ByteOrder.nativeOrder())
        val intPixels = IntArray(w * h)
        workBitmap.getPixels(intPixels, 0, w, 0, 0, w, h)
        // Normalize to [0,1]
        var idx = 0
        for (py in 0 until h) {
            for (px in 0 until w) {
                val c = intPixels[idx++]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                inputBuffer.putFloat(r / 255f)
                inputBuffer.putFloat(g / 255f)
                inputBuffer.putFloat(b / 255f)
            }
        }
        inputBuffer.rewind()

        val outputBuffer =
            ByteBuffer.allocateDirect(4 * outW * outH * 3).order(ByteOrder.nativeOrder())
        outputBuffer.rewind()

        // If dynamic, try resizing
        try {
            if (modelDynamic) {
                interp.resizeInput(0, intArrayOf(1, h, w, 3))
                interp.allocateTensors()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ResizeInput failed (maybe static shape); proceeding: ${e.message}")
        }

        interp.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val outPixels = IntArray(outW * outH)
        idx = 0
        for (py in 0 until outH) {
            for (px in 0 until outW) {
                val rf = outputBuffer.getFloat().coerceIn(0f, 1f)
                val gf = outputBuffer.getFloat().coerceIn(0f, 1f)
                val bf = outputBuffer.getFloat().coerceIn(0f, 1f)
                val r = (rf * 255f + 0.5f).toInt().coerceIn(0, 255)
                val g = (gf * 255f + 0.5f).toInt().coerceIn(0, 255)
                val b = (bf * 255f + 0.5f).toInt().coerceIn(0, 255)
                outPixels[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        if (workBitmap != tile) workBitmap.recycle()

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        return out
    }

    /**
     * Blend tile into accumulator with linear feather edges over overlap distance.
     */
    private fun blendTile(
        tile: Bitmap,
        outX: Int,
        outY: Int,
        innerW: Int,
        innerH: Int,
        overlapPx: Int,
        acc: IntArray,
        weight: FloatArray,
        fullW: Int,
        fullH: Int
    ) {
        val tW = tile.width
        val tH = tile.height
        val pixels = IntArray(tW * tH)
        tile.getPixels(pixels, 0, tW, 0, 0, tW, tH)

        // region inside tile to copy (corresponding to inner region)
        val startX = (tW - innerW) / 2
        val startY = (tH - innerH) / 2

        for (y in 0 until innerH) {
            val gy = outY + y
            if (gy !in 0 until fullH) continue
            val ty = startY + y
            for (x in 0 until innerW) {
                val gx = outX + x
                if (gx !in 0 until fullW) continue
                val tx = startX + x
                val c = pixels[ty * tW + tx]

                // Weight based on distance to inner edge
                val distX = min(x, innerW - 1 - x)
                val distY = min(y, innerH - 1 - y)
                val dist = min(distX, distY)
                val wEdge = if (overlapPx > 0) {
                    min(1f, dist / overlapPx.toFloat())
                } else 1f
                val w = wEdge

                val idx = gy * fullW + gx
                val existing = acc[idx]
                val wr = weight[idx]

                val a = (c ushr 24) and 0xFF
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF

                if (wr == 0f) {
                    acc[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    val er = (existing shr 16) and 0xFF
                    val eg = (existing shr 8) and 0xFF
                    val eb = existing and 0xFF
                    val nr = er + r * w
                    val ng = eg + g * w
                    val nb = eb + b * w
                    acc[idx] = (0xFF shl 24) or (nr.toInt() shl 16) or (ng.toInt() shl 8) or nb.toInt()
                }
                weight[idx] = wr + w
            }
        }
    }

    private fun swirlSuppress(
        originalSmall: Bitmap,
        upscaled: Bitmap,
        strength: Float,
        applyLowGradMedian: Boolean
    ): Bitmap {
        if (strength <= 0f) return upscaled
        val outW = upscaled.width
        val outH = upscaled.height

        // Bicubic upscale original for guidance
        val guide = Bitmap.createScaledBitmap(originalSmall, outW, outH, true)

        val esPixels = IntArray(outW * outH)
        val guidePixels = IntArray(outW * outH)
        upscaled.getPixels(esPixels, 0, outW, 0, 0, outW, outH)
        guide.getPixels(guidePixels, 0, outW, 0, 0, outW, outH)

        val result = IntArray(outW * outH)

        // Precompute blurred variant (simple 3x3 box as approximation)
        val blurred = boxBlur3x3(esPixels, outW, outH)
        // Mix with guide 30%
        for (i in esPixels.indices) {
            val e = esPixels[i]
            val g = guidePixels[i]
            val b = blurred[i]
            val er = (e shr 16) and 0xFF
            val eg = (e shr 8) and 0xFF
            val eb = e and 0xFF
            val gr = (g shr 16) and 0xFF
            val gg = (g shr 8) and 0xFF
            val gb = g and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            val mr = (br * 0.7f + gr * 0.3f)
            val mg = (bg * 0.7f + gg * 0.3f)
            val mb = (bb * 0.7f + gb * 0.3f)
            blurred[i] = (0xFF shl 24) or (mr.toInt() shl 16) or (mg.toInt() shl 8) or mb.toInt()
        }

        // Sobel gradients on upscaled
        val grad = sobelGradientMagnitude(esPixels, outW, outH)

        val k = SOBEL_K
        for (i in esPixels.indices) {
            val mag = grad[i]
            val mask = (mag / (mag + k)).coerceIn(0f, 1f)
            val blendMask = (mask * (1f - strength) + mask * strength) // keep strong edges, reduce low freq
            val e = esPixels[i]
            val s = blurred[i]
            val er = (e shr 16) and 0xFF
            val eg = (e shr 8) and 0xFF
            val eb = e and 0xFF
            val sr = (s shr 16) and 0xFF
            val sg = (s shr 8) and 0xFF
            val sb = s and 0xFF
            val nr = (blendMask * er + (1f - blendMask) * sr).toInt().coerceIn(0, 255)
            val ng = (blendMask * eg + (1f - blendMask) * sg).toInt().coerceIn(0, 255)
            val nb = (blendMask * eb + (1f - blendMask) * sb).toInt().coerceIn(0, 255)
            result[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        if (applyLowGradMedian) {
            medianLowGradient(result, grad, outW, outH)
        }

        guide.recycle()
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, outW, 0, 0, outW, outH)
        return out
    }

    private fun boxBlur3x3(pixels: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(pixels.size)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var r = 0; var g = 0; var b = 0
                for (dy in -1..1) {
                    val row = (y + dy) * w
                    for (dx in -1..1) {
                        val c = pixels[row + (x + dx)]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                val idx = y * w + x
                out[idx] = (0xFF shl 24) or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
            }
        }
        return out
    }

    private fun sobelGradientMagnitude(pixels: IntArray, w: Int, h: Int): FloatArray {
        val grad = FloatArray(pixels.size)
        fun l(idx: Int): Float {
            val c = pixels[idx]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return (0.299f * r + 0.587f * g + 0.114f * b)
        }
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val tl = l((y - 1) * w + (x - 1))
                val tc = l((y - 1) * w + x)
                val tr = l((y - 1) * w + (x + 1))
                val ml = l(y * w + (x - 1))
                val mr = l(y * w + (x + 1))
                val bl = l((y + 1) * w + (x - 1))
                val bc = l((y + 1) * w + x)
                val br = l((y + 1) * w + (x + 1))
                val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
                val gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr)
                grad[i] = kotlin.math.sqrt(gx * gx + gy * gy)
            }
        }
        return grad
    }

    private fun medianLowGradient(pixels: IntArray, grad: FloatArray, w: Int, h: Int) {
        val window = IntArray(9)
        fun median9(arr: IntArray): Int {
            for (i in 1 until 9) {
                val v = arr[i]
                var j = i - 1
                while (j >= 0 && arr[j] > v) {
                    arr[j + 1] = arr[j]
                    j--
                }
                arr[j + 1] = v
            }
            return arr[4]
        }
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                if (grad[idx] >= LOW_GRAD_THRESHOLD) continue
                var k = 0
                var rr = 0; var gg = 0; var bb = 0
                val rArr = IntArray(9)
                val gArr = IntArray(9)
                val bArr = IntArray(9)
                for (dy in -1..1) {
                    val row = (y + dy) * w
                    for (dx in -1..1) {
                        val c = pixels[row + (x + dx)]
                        rArr[k] = (c shr 16) and 0xFF
                        gArr[k] = (c shr 8) and 0xFF
                        bArr[k] = c and 0xFF
                        k++
                    }
                }
                val mr = median9(rArr)
                val mg = median9(gArr)
                val mb = median9(bArr)
                pixels[idx] = (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
            }
        }
    }

    private fun bicubicFinal(src: Bitmap, targetW: Int, targetH: Int, sharpen: Boolean): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        if (!sharpen) return scaled
        // light unsharp
        val out = scaled.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val orig = IntArray(w * h)
        val blur = IntArray(w * h)
        out.getPixels(orig, 0, w, 0, 0, w, h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var r = 0; var g = 0; var b = 0
                for (dy in -1..1) {
                    val row = (y + dy) * w
                    for (dx in -1..1) {
                        val c = orig[row + (x + dx)]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                val idx = y * w + x
                blur[idx] = (0xFF shl 24) or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
            }
        }
        for (i in orig.indices) {
            val o = orig[i]
            val bpx = blur[i]
            val orr = (o shr 16) and 0xFF
            val org = (o shr 8) and 0xFF
            val orb = o and 0xFF
            val br = (bpx shr 16) and 0xFF
            val bg = (bpx shr 8) and 0xFF
            val bb = bpx and 0xFF
            val amount = 0.25f
            val nr = (orr * (1 + amount) - br * amount).coerceIn(0f, 255f).toInt()
            val ng = (org * (1 + amount) - bg * amount).coerceIn(0f, 255f).toInt()
            val nb = (orb * (1 + amount) - bb * amount).coerceIn(0f, 255f).toInt()
            orig[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        out.setPixels(orig, 0, w, 0, 0, w, h)
        return out
    }

    private fun log2(value: Float): Float = (kotlin.math.ln(value.toDouble()) / kotlin.math.ln(2.0)).toFloat()

    

    fun release() {
        synchronized(lock) {
            interpreter?.close()
            interpreter = null
            safeClose(gpuDelegate)
            safeClose(nnApiDelegate)
            gpuDelegate = null
            nnApiDelegate = null
            backend = Backend.NONE
            initTried = false
        }
    }
}
