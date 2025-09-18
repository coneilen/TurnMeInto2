package com.photoai.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

/**
 * Real-ESRGAN 4x TFLite based upscaler (memory-optimized).
 *
 * Key improvements over previous version:
 *  - Optional forced XNNPACK (CPU) backend (skips GPU/NNAPI) for stability.
 *  - Pre-downscale strategy when required scale < 4× to avoid producing an oversized
 *    intermediate (Option B: match width after ESRGAN, shrink height afterwards).
 *  - Memory guard estimating peak buffers before running a pass; falls back early if unsafe.
 *  - Short-based weight accumulation (quantized) instead of large FloatArray for blending.
 *  - Reusable direct ByteBuffers for per-tile model I/O to reduce GC churn.
 *  - Adaptive initial tile size with pre-check instead of repeated OOM retries.
 *  - Configurable headroom limit and output memory cap.
 *
 * Assumptions (adjust if your model differs):
 *  - Model input/output: float32 RGB [0,1]
 *  - Scale factor per pass: 4x
 *
 * Usage:
 *  val bitmap2 = EsganUpscaler.upscaleToMatch(context, srcBitmap, targetW, targetH,
 *      EsganOptions(tileSize=224, enableArtifactDenoise=false))
 *
 * Tuning:
 *  - Reduce maxOutputMegabytes on low-memory devices (e.g. 96).
 *  - Increase minEsrganScaleThreshold to skip ESRGAN for modest enlargements.
 *  - Adjust tileSize / overlap for seam vs speed trade-off.
 *  - Set forceCpuXnnpack=false to allow GPU/NNAPI (may increase memory variance).
 *
 * Diagnostics (logcat filters):
 *  phase=esrgan_entry, phase=memory_estimate, phase=tiled_pass, phase=esrgan_complete.
 */
data class EsganOptions(
    val tileSize: Int = 224,
    val tileOverlap: Int = 16,
    val swirlSuppression: Float = 0.4f,
    val enableArtifactDenoise: Boolean = false,
    val minEsrganScaleThreshold: Float = 1.15f,
    val maxRetries: Int = 1,                 // We now size tiles up-front; limit retries.
    val forceCpuXnnpack: Boolean = true,
    val cpuMaxThreads: Int = 4,
    val preScaleHeadroom: Float = 1.15f,      // Allow at most +15% overshoot vs target on any axis.
    val maxOutputMegabytes: Int = 120,        // Soft memory cap for output frame related arrays.
    val adaptiveMemoryGuard: Boolean = true,
    val initialTileSize: Int = 224,
    val minTileSize: Int = 128
)

object EsganUpscaler {

    private const val TAG = "EsganUpscaler"
    private const val MODEL_PATH = "models/Real-ESRGAN-General-x4v3.tflite"
    private const val SCALE_FACTOR = 4
    private const val SOBEL_K = 10f
    private const val LOW_GRAD_THRESHOLD = 4f
    private const val WEIGHT_SCALE = 256      // Quantization scale for weight accumulation

    private var interpreter: Interpreter? = null
    private var backend: Backend = Backend.NONE

    private enum class Backend { CPU_XNNPACK, GPU, NNAPI, CPU, NONE }

    private val lock = Any()
    private var initTried = false

    // Reusable model buffers
    private var inputBuffer: ByteBuffer? = null
    private var inputCap = 0
    private var outputBuffer: ByteBuffer? = null
    private var outputCap = 0

    suspend fun ensureReady(context: Context, options: EsganOptions): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (interpreter != null) return@synchronized true
            if (initTried) return@synchronized interpreter != null
            initTried = true
            initInterpreter(context, options)
            interpreter != null
        }
    }

    private fun initInterpreter(context: Context, options: EsganOptions) {
        // Forced CPU/XNNPACK path (preferred for memory determinism)
        if (options.forceCpuXnnpack) {
            try {
                val cpuOpts = Interpreter.Options().apply {
                    // XNNPACK is enabled by default in recent TFLite when threads >1
                    setNumThreads(min(options.cpuMaxThreads, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)))
                }
                interpreter = Interpreter(loadModelBuffer(context), cpuOpts)
                backend = Backend.CPU_XNNPACK
                Log.i(TAG, "Initialized ESRGAN with forced XNNPACK backend (threads=${cpuOpts.numThreads})")
                return
            } catch (e: Throwable) {
                Log.e(TAG, "Forced CPU/XNNPACK init failed: ${e.message}")
                interpreter = null
            }
        }

        // Fallback cascade (kept for completeness — normally not reached when forceCpuXnnpack=true)
        try {
            // GPU (may fail due to missing delegate classes)
            val gpuClass = Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
            val gpuDelegate = gpuClass.getDeclaredConstructor().newInstance()
            val opts = Interpreter.Options().apply { addDelegate(gpuDelegate as org.tensorflow.lite.Delegate) }
            interpreter = Interpreter(loadModelBuffer(context), opts)
            backend = Backend.GPU
            Log.i(TAG, "Initialized ESRGAN with GPU delegate")
            return
        } catch (t: Throwable) {
            Log.w(TAG, "GPU delegate unavailable: ${t.message}")
            interpreter = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                val nnApiDelegateClass = Class.forName("org.tensorflow.lite.nnapi.NnApiDelegate")
                val nn = nnApiDelegateClass.getDeclaredConstructor().newInstance()
                val nnOpts = Interpreter.Options().apply { addDelegate(nn as org.tensorflow.lite.Delegate) }
                interpreter = Interpreter(loadModelBuffer(context), nnOpts)
                backend = Backend.NNAPI
                Log.i(TAG, "Initialized ESRGAN with NNAPI delegate")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI delegate init failed: ${t.message}")
                interpreter = null
            }
        }

        try {
            val cpuOptions = Interpreter.Options().apply {
                setNumThreads(min(options.cpuMaxThreads, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)))
            }
            interpreter = Interpreter(loadModelBuffer(context), cpuOptions)
            backend = Backend.CPU
            Log.i(TAG, "Initialized ESRGAN with generic CPU backend")
        } catch (e: Throwable) {
            Log.e(TAG, "CPU interpreter init failed: ${e.message}")
            interpreter = null
            backend = Backend.NONE
        }
    }

    private fun loadModelBuffer(context: Context): ByteBuffer {
        context.assets.open(MODEL_PATH).use { asset ->
            val bytes = asset.readBytes()
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
    }

    /**
     * Public upscale entrypoint to match or exceed target dims (aspect preserved).
     * Incorporates pre-downscaling strategy when required scale < 4.
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

        Log.d(TAG, "phase=esrgan_entry src=${startW}x${startH} target=${targetW}x${targetH} requiredScale=${fmt(requiredScale)} threshold=${fmt(options.minEsrganScaleThreshold)}")

        if (startW >= targetW && startH >= targetH) {
            Log.d(TAG, "phase=esrgan_skip reason=alreadyLarge")
            return@withContext src
        }

        if (requiredScale < options.minEsrganScaleThreshold) {
            Log.d(TAG, "phase=esrgan_skip reason=belowThreshold scale=${fmt(requiredScale)}")
            return@withContext bicubicFinal(src, targetW, targetH, sharpen = true)
        }

        if (!ensureReady(context, options)) {
            Log.d(TAG, "phase=esrgan_fail reason=interpreterNotReady fallback=bicubic")
            return@withContext bicubicFinal(src, targetW, targetH, sharpen = true)
        }

        // Pre-downscale (Option B) if requiredScale < 4 (single ESRGAN pass enough)
        var work = src
        var usedPreScale = false
        if (requiredScale < SCALE_FACTOR) {
            val widthMatchFactor = targetW / (startW.toFloat() * SCALE_FACTOR)
            // Factor < 1 since requiredScale <4
            var f = widthMatchFactor
            // Enforce headroom against height overshoot
            val projectedH = startH * f * SCALE_FACTOR
            val overshoot = projectedH / targetH.toFloat()
            if (overshoot > options.preScaleHeadroom) {
                f /= overshoot / options.preScaleHeadroom
            }
            if (f < 0.999f) {
                val newW = (startW * f).roundToInt().coerceAtLeast(8)
                val newH = (startH * f).roundToInt().coerceAtLeast(8)
                work = Bitmap.createScaledBitmap(src, newW, newH, true)
                usedPreScale = true
                Log.d(TAG, "phase=prescale applied factor=${fmt(f)} prescaled=${newW}x${newH}")
            } else {
                Log.d(TAG, "phase=prescale skipped factor≈1")
            }
        }

        // Compute passes (rare multi-pass path kept simple)
        val passes = if (requiredScale >= 3.2f) {
            (ln(requiredScale.toDouble()) / ln(SCALE_FACTOR.toDouble())).toInt().coerceAtLeast(1)
        } else 1
        val clampedPasses = passes.coerceAtMost(5)

        Log.d(TAG, "phase=esrgan_backend backend=$backend requiredScale=${fmt(requiredScale)} passes=$passes clamped=$clampedPasses prescale=$usedPreScale")

        var current = work
        var fractionalApplied = false
        var artifactApplied = false

        val totalMs = measureTimeMillis {
            repeat(clampedPasses) { idx ->
                if (current.width >= targetW || current.height >= targetH) return@repeat
                // Memory guard for this pass
                val projectedOutW = current.width * SCALE_FACTOR
                val projectedOutH = current.height * SCALE_FACTOR
                if (!memorySafe(projectedOutW, projectedOutH, options)) {
                    Log.w(TAG, "phase=memory_guard triggered out=${projectedOutW}x${projectedOutH} skip_esrgan")
                    return@repeat
                }
                val passMs = measureTimeMillis {
                    current = runSinglePass(current, options)
                }
                Log.d(TAG, "phase=esrgan_pass idx=${idx + 1}/$clampedPasses out=${current.width}x${current.height} ms=$passMs")
            }

            // Final upscale / downscale to target if needed
            if (current.width < targetW || current.height < targetH ||
                current.width > targetW || current.height > targetH
            ) {
                val fracMs = measureTimeMillis {
                    current = bicubicFinal(current, targetW, targetH, sharpen = (current.width > targetW * 1.05f || current.height > targetH * 1.05f))
                }
                fractionalApplied = true
                Log.d(TAG, "phase=esrgan_fractional_done out=${current.width}x${current.height} ms=$fracMs")
            }

            // Post-process (artifact suppression)
            if (options.swirlSuppression > 0f || options.enableArtifactDenoise) {
                val artMs = measureTimeMillis {
                    current = swirlSuppress(
                        originalSmall = src,
                        upscaled = current,
                        strength = options.swirlSuppression,
                        applyLowGradMedian = options.enableArtifactDenoise
                    )
                }
                artifactApplied = true
                Log.d(TAG, "phase=esrgan_artifact_done out=${current.width}x${current.height} ms=$artMs")
            }
        }

        Log.d(TAG, "phase=esrgan_complete final=${current.width}x${current.height} totalMs=$totalMs passes=$passes clamped=$clampedPasses fracApplied=$fractionalApplied artifactApplied=$artifactApplied backend=$backend")
        if (usedPreScale && current !== src && src !== current && !src.isRecycled) {
            // Keep original for potential reuse elsewhere; do not recycle here unless safe.
        }
        current
    }

    private fun memorySafe(outW: Int, outH: Int, options: EsganOptions): Boolean {
        if (!options.adaptiveMemoryGuard) return true
        val pixels = outW.toLong() * outH.toLong()
        // Approx bytes: rAcc+gAcc+bAcc (12) + weight (2) + output bitmap (4) = 18 bytes/px
        val bytes = pixels * 18L
        val mb = bytes / (1024.0 * 1024.0)
        val safe = mb <= options.maxOutputMegabytes
        Log.d(TAG, "phase=memory_estimate out=${outW}x${outH} estMB=${fmt(mb.toFloat())} limit=${options.maxOutputMegabytes} safe=$safe")
        return safe
    }

    /**
     * Single ESRGAN 4x tiled pass with quantized weight accumulation.
     */
    private fun runSinglePass(input: Bitmap, options: EsganOptions): Bitmap {
        val interp = interpreter ?: return input
        val scale = SCALE_FACTOR
        val overlap = options.tileOverlap
        var tileSize = options.initialTileSize.coerceAtMost(options.tileSize)

        // Pre-adjust tile size if estimated per-tile memory is too high
        tileSize = adjustInitialTileSize(tileSize, overlap, options)

        val inW = input.width
        val inH = input.height
        val outW = inW * scale
        val outH = inH * scale

        // Accumulators
        val rAcc = IntArray(outW * outH)
        val gAcc = IntArray(outW * outH)
        val bAcc = IntArray(outW * outH)
        val weight = ShortArray(outW * outH)

        var attempt = 0
        var success = false
        var lastError: Throwable? = null
        var outBitmap: Bitmap? = null

        while (attempt < options.maxRetries && !success) {
            try {
                outBitmap?.recycle()
                outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

                var tilesX = ceil(inW / tileSize.toFloat()).toInt()
                var tilesY = ceil(inH / tileSize.toFloat()).toInt()
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

                            blendTileQuantized(
                                srTile,
                                outInnerX0,
                                outInnerY0,
                                outInnerW,
                                outInnerH,
                                overlap * scale,
                                padLeft * scale,
                                padTop * scale,
                                rAcc,
                                gAcc,
                                bAcc,
                                weight,
                                outW,
                                outH
                            )
                            srTile.recycle()
                            tileCount++
                        }
                    }
                }
                Log.d(TAG, "phase=tiled_pass tiles=$tileCount ms=$tileMsTotal tileSize=$tileSize overlap=$overlap")

                // Compose final pixels
                val outPixels = IntArray(outW * outH)
                for (i in outPixels.indices) {
                    val wq = weight[i].toInt()
                    if (wq > 0) {
                        val wr = rAcc[i] / wq
                        val wg = gAcc[i] / wq
                        val wb = bAcc[i] / wq
                        outPixels[i] = (0xFF shl 24) or
                                (wr.coerceIn(0, 255) shl 16) or
                                (wg.coerceIn(0, 255) shl 8) or
                                wb.coerceIn(0, 255)
                    } else {
                        outPixels[i] = 0xFF000000.toInt()
                    }
                }
                outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
                success = true
            } catch (oom: OutOfMemoryError) {
                lastError = oom
                tileSize = reduceTileSize(tileSize, options)
                Log.w(TAG, "OOM tileSize attempt=$attempt newTileSize=$tileSize")
                System.gc()
            } catch (t: Throwable) {
                lastError = t
                Log.e(TAG, "Tile inference error: ${t.message}")
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

    private fun adjustInitialTileSize(tile: Int, overlap: Int, options: EsganOptions): Int {
        var t = tile
        while (t >= options.minTileSize) {
            val crop = t + 2 * overlap
            val inFloats = crop * crop * 3
            val outFloats = (crop * SCALE_FACTOR) * (crop * SCALE_FACTOR) * 3
            val bytes = 4L * (inFloats + outFloats)
            if (bytes / (1024 * 1024) < 32) break
            t -= 32
        }
        return t
    }

    private fun reduceTileSize(current: Int, options: EsganOptions): Int {
        return when {
            current > 192 -> 192
            current > 160 -> 160
            current > options.minTileSize -> max(options.minTileSize, 128)
            else -> options.minTileSize
        }
    }

    /**
     * Model invocation on a single tile.
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
        val dynamic = (expectedH == -1 || expectedW == -1)

        val workBitmap = if (!dynamic && (expectedW != inW || expectedH != inH)) {
            Bitmap.createScaledBitmap(tile, expectedW, expectedH, true)
        } else tile

        val w = workBitmap.width
        val h = workBitmap.height

        val neededInputBytes = 4 * w * h * 3
        if (neededInputBytes > inputCap) {
            inputBuffer = ByteBuffer.allocateDirect(neededInputBytes).order(ByteOrder.nativeOrder())
            inputCap = neededInputBytes
        }
        val inBuf = inputBuffer!!
        inBuf.rewind()

        val intPixels = IntArray(w * h)
        workBitmap.getPixels(intPixels, 0, w, 0, 0, w, h)
        var idx = 0
        for (py in 0 until h) {
            for (px in 0 until w) {
                val c = intPixels[idx++]
                inBuf.putFloat(((c shr 16) and 0xFF) / 255f)
                inBuf.putFloat(((c shr 8) and 0xFF) / 255f)
                inBuf.putFloat((c and 0xFF) / 255f)
            }
        }
        inBuf.rewind()

        val neededOutBytes = 4 * outW * outH * 3
        if (neededOutBytes > outputCap) {
            outputBuffer = ByteBuffer.allocateDirect(neededOutBytes).order(ByteOrder.nativeOrder())
            outputCap = neededOutBytes
        }
        val outBuf = outputBuffer!!
        outBuf.rewind()

        if (dynamic) {
            try {
                interp.resizeInput(0, intArrayOf(1, h, w, 3))
                interp.allocateTensors()
            } catch (t: Throwable) {
                Log.w(TAG, "resizeInput failed: ${t.message}")
            }
        }

        interp.run(inBuf, outBuf)
        outBuf.rewind()

        val outPixels = IntArray(outW * outH)
        idx = 0
        for (py in 0 until outH) {
            for (px in 0 until outW) {
                val rf = outBuf.getFloat().coerceIn(0f, 1f)
                val gf = outBuf.getFloat().coerceIn(0f, 1f)
                val bf = outBuf.getFloat().coerceIn(0f, 1f)
                val r = (rf * 255f + 0.5f).toInt().coerceIn(0, 255)
                val g = (gf * 255f + 0.5f).toInt().coerceIn(0, 255)
                val b = (bf * 255f + 0.5f).toInt().coerceIn(0, 255)
                outPixels[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        if (workBitmap !== tile) workBitmap.recycle()

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        return out
    }

    /**
     * Quantized blend with Short weight map.
     */
    private fun blendTileQuantized(
        tile: Bitmap,
        outX: Int,
        outY: Int,
        innerW: Int,
        innerH: Int,
        overlapPx: Int,
        padLeftPx: Int,
        padTopPx: Int,
        rAcc: IntArray,
        gAcc: IntArray,
        bAcc: IntArray,
        weight: ShortArray,
        fullW: Int,
        fullH: Int
    ) {
        val tW = tile.width
        val tH = tile.height
        val pixels = IntArray(tW * tH)
        tile.getPixels(pixels, 0, tW, 0, 0, tW, tH)

        // Corrected start offsets: use actual padded margins (scaled) instead of centering heuristic
        val startX = padLeftPx.coerceIn(0, tW - 1)
        val startY = padTopPx.coerceIn(0, tH - 1)

        for (y in 0 until innerH) {
            val gy = outY + y
            if (gy !in 0 until fullH) continue
            val ty = startY + y
            val rowOffset = gy * fullW
            for (x in 0 until innerW) {
                val gx = outX + x
                if (gx !in 0 until fullW) continue
                val tx = startX + x
                val c = pixels[ty * tW + tx]

                val distX = min(x, innerW - 1 - x)
                val distY = min(y, innerH - 1 - y)
                val dist = min(distX, distY)
                val wEdge = if (overlapPx > 0) min(1f, dist / overlapPx.toFloat()) else 1f
                val wInc = (wEdge * WEIGHT_SCALE).toInt().coerceAtLeast(1)
                val idx = rowOffset + gx

                val existingW = weight[idx].toInt()
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                if (existingW == 0) {
                    rAcc[idx] = r * wInc
                    gAcc[idx] = g * wInc
                    bAcc[idx] = b * wInc
                    weight[idx] = (wInc.coerceAtMost(Short.MAX_VALUE.toInt())).toShort()
                } else {
                    val newW = (existingW + wInc).coerceAtMost(Short.MAX_VALUE.toInt())
                    rAcc[idx] += r * wInc
                    gAcc[idx] += g * wInc
                    bAcc[idx] += b * wInc
                    weight[idx] = newW.toShort()
                }
            }
        }
    }

    // --- Artifact suppression (unchanged from original except for minor style tweaks) ---

    private fun swirlSuppress(
        originalSmall: Bitmap,
        upscaled: Bitmap,
        strength: Float,
        applyLowGradMedian: Boolean
    ): Bitmap {
        if (strength <= 0f) return upscaled
        val outW = upscaled.width
        val outH = upscaled.height

        val guide = Bitmap.createScaledBitmap(originalSmall, outW, outH, true)

        val esPixels = IntArray(outW * outH)
        val guidePixels = IntArray(outW * outH)
        upscaled.getPixels(esPixels, 0, outW, 0, 0, outW, outH)
        guide.getPixels(guidePixels, 0, outW, 0, 0, outW, outH)

        val result = IntArray(outW * outH)
        val blurred = boxBlur3x3(esPixels, outW, outH)

        for (i in esPixels.indices) {
            val g = guidePixels[i]
            val b = blurred[i]
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

        val grad = sobelGradientMagnitude(esPixels, outW, outH)
        for (i in esPixels.indices) {
            val mag = grad[i]
            val mask = (mag / (mag + SOBEL_K)).coerceIn(0f, 1f)
            val blendMask = mask * (1f - strength) + mask * strength
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
        // Copy edges directly to avoid uninitialized (black) borders
        if (w > 1 && h > 1) {
            for (x in 0 until w) {
                out[x] = pixels[x]
                out[(h - 1) * w + x] = pixels[(h - 1) * w + x]
            }
            for (y in 0 until h) {
                out[y * w] = pixels[y * w]
                out[y * w + (w - 1)] = pixels[y * w + (w - 1)]
            }
        } else {
            // Degenerate small image
            return pixels.copyOf()
        }
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

    private fun fmt(v: Float): String = String.format("%.3f", v)

    fun release() {
        synchronized(lock) {
            interpreter?.close()
            interpreter = null
            backend = Backend.NONE
            initTried = false
            inputBuffer = null
            outputBuffer = null
            inputCap = 0
            outputCap = 0
        }
    }
}
