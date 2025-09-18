package com.photoai.app.utils

import android.graphics.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Metadata describing how an input bitmap was embedded in a padded target canvas.
 * Includes:
 * - targetW / targetH: final padded canvas size
 * - baseW / baseH: the chosen aspect class base dimensions (one of 1024x1024, 1536x1024, 1024x1536)
 * - contentRect: location of the original (unscaled) bitmap inside the padded canvas
 * - originalW / originalH: original source dimensions
 */
data class PreparedImage(
    val targetW: Int,
    val targetH: Int,
    val contentRect: Rect,      // Location of the drawn (possibly downscaled) bitmap inside the padded canvas
    val originalW: Int,
    val originalH: Int,
    val baseW: Int,
    val baseH: Int,
    val drawnW: Int,            // Width actually drawn into the padded canvas (after optional downscale)
    val drawnH: Int,            // Height actually drawn
    val downscale: Float        // Downscale factor applied to original (1f if none)
)

/**
 * Utility for preparing images for edit endpoints by:
 * 1. Classifying the source aspect into one of three aspect classes:
 *    - Square (1024x1024 base)
 *    - Landscape (1536x1024 base) ratio 1.5
 *    - Portrait  (1024x1536 base) ratio ~0.6667
 *    with a bias window for near‑square images.
 * 2. Creating the *smallest* canvas that:
 *    - Preserves the chosen aspect class EXACTLY (same ratio as its base dimensions)
 *    - Fully contains the original image WITHOUT DOWNSCALING (no loss of detail)
 *      (Original is drawn 1:1 — only transparent padding is added.)
 * 3. Centering the original bitmap inside the padded transparent canvas.
 *
 * Restoration simply crops the edited padded result back to the original content rectangle.
 *
 * Previous behavior (scaling to fit inside fixed allowed sizes) has been replaced:
 * We now always upscale the canvas (never shrink the original) so the original fits completely
 * within a canvas whose aspect matches the chosen class.
 */
object ImagePrep {

    private const val SQUARE_LOW = 0.79f   // Square bias window lower bound
    private const val SQUARE_HIGH = 1.31f  // Square bias window upper bound

    private enum class Aspect(val baseW: Int, val baseH: Int, val ratio: Float) {
        SQUARE(1024, 1024, 1.0f),
        LANDSCAPE(1536, 1024, 1536f / 1024f),
        PORTRAIT(1024, 1536, 1024f / 1536f)
    }

    /**
     * Prepare the bitmap for upload: create an aspect-class canvas large enough to contain
     * the original without scaling it, preserving original detail.
     *
     * The canvas dimensions are:
     *   s = max(originalW / baseW, originalH / baseH, 1)
     *   targetW = ceil(baseW * s)
     *   targetH = ceil(baseH * s)
     * guaranteeing targetW:targetH == baseW:baseH.
     */
    fun prepare(src: Bitmap): Pair<Bitmap, PreparedImage> {
        val originalW = src.width
        val originalH = src.height
        require(originalW > 0 && originalH > 0) { "Source bitmap has invalid dimensions." }

        val aspect = originalW.toFloat() / originalH.toFloat()
        val chosen = chooseAspect(aspect)

        val baseW = chosen.baseW
        val baseH = chosen.baseH

        // BUG FIX (progressive top cropping):
        // Previously we upscaled the canvas (targetW/targetH) beyond the allowed OpenAI size (baseW/baseH)
        // while still sending only baseW x baseH in the 'size' parameter. The API returned a base-sized
        // edited image which we then treated as a downscaled version of a larger meta.target canvas.
        // On restore we proportionally mapped contentRect -> mismatch + rounding -> cumulative drift (cropping),
        // often visible at the top after multiple iterative edits.
        //
        // New strategy:
        // - The padded canvas ALWAYS matches exactly one of the allowed sizes (baseW x baseH).
        // - If the source is larger than the base, we downscale it uniformly to fit inside the base
        //   (preserving aspect). If smaller, we center it with transparent padding (no scaling up).
        // - meta.targetW / targetH == dimensions actually sent & received, eliminating drift.
        //
        // This guarantees deterministic restoration (restore is now a simple crop without scale mismatch).
        val needsDownscale = originalW > baseW || originalH > baseH
        val downscale = if (needsDownscale) {
            min(baseW.toFloat() / originalW.toFloat(), baseH.toFloat() / originalH.toFloat())
        } else 1f

        val drawBitmap: Bitmap
        val drawW: Int
        val drawH: Int
        if (downscale < 0.999f) {
            drawW = (originalW * downscale).roundToInt().coerceAtLeast(1)
            drawH = (originalH * downscale).roundToInt().coerceAtLeast(1)
            drawBitmap = Bitmap.createScaledBitmap(src, drawW, drawH, true)
        } else {
            drawBitmap = src
            drawW = originalW
            drawH = originalH
        }

        val targetW = baseW
        val targetH = baseH

        // Deterministic centering (floor) to remove rounding bias that could shift content upward.
        val left = (targetW - drawW) / 2
        val top = (targetH - drawH) / 2
        val rightPad = targetW - drawW - left
        val bottomPad = targetH - drawH - top
        android.util.Log.d(
            "ImagePipeline",
            "phase=prepare_center draw=${drawW}x${drawH} padL=$left padT=$top padR=$rightPad padB=$bottomPad"
        )

        val padded = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded) // starts transparent
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val srcRect = Rect(0, 0, drawW, drawH)
        val dstRect = Rect(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(drawBitmap, srcRect, dstRect, paint)

        // Recycle temporary scaled copy if created
        if (drawBitmap !== src) {
            try { drawBitmap.recycle() } catch (_: Exception) {}
        }

        val meta = PreparedImage(
            targetW = targetW,
            targetH = targetH,
            contentRect = dstRect,
            originalW = originalW,
            originalH = originalH,
            baseW = baseW,
            baseH = baseH,
            drawnW = drawW,
            drawnH = drawH,
            downscale = downscale
        )

        run {
            val formattedAspect = String.format("%.3f", aspect)
            val formattedDown = String.format("%.3f", downscale)
            android.util.Log.d(
                "ImagePipeline",
                "phase=prepare orig=${originalW}x${originalH} aspect=${formattedAspect} base=${baseW}x${baseH} downscale=${formattedDown} target=${targetW}x${targetH} drawn=${drawW}x${drawH} contentRect=${dstRect.left},${dstRect.top},${dstRect.right},${dstRect.bottom}"
            )
        }
        return padded to meta
    }

    /**
     * Restore edited padded image back to original content by cropping the original bounds.
     * The parameter scaleToOriginal is now effectively a no-op because we never scale
     * the source during preparation; kept for backward compatibility.
     */
    fun restore(
        edited: Bitmap,
        meta: PreparedImage,
        scaleToOriginal: Boolean = false
    ): Bitmap {
        val (mappedRect, _) = mapRectIfSizeMismatch(edited, meta)

        val safeRect = intersectSafe(mappedRect, 0, 0, edited.width, edited.height)
        if (safeRect.width() <= 0 || safeRect.height() <= 0) {
            return edited
        }

        val cropped = Bitmap.createBitmap(
            edited,
            safeRect.left,
            safeRect.top,
            safeRect.width(),
            safeRect.height()
        )

        // Optional upscale back to original resolution if we had to downscale during prepare
        if (scaleToOriginal &&
            (meta.downscale < 0.999f) &&
            (meta.drawnW != meta.originalW || meta.drawnH != meta.originalH)
        ) {
            val upscaled = try {
                Bitmap.createScaledBitmap(
                    cropped,
                    meta.originalW,
                    meta.originalH,
                    true
                )
            } catch (e: Exception) {
                android.util.Log.e("ImagePipeline", "phase=restore upscale_failed err=${e.message}")
                return cropped
            }
            if (upscaled !== cropped) {
                try { cropped.recycle() } catch (_: Exception) {}
            }
            android.util.Log.d(
                "ImagePipeline",
                "phase=restore upscaleApplied original=${meta.originalW}x${meta.originalH} drawn=${meta.drawnW}x${meta.drawnH} factor=${String.format("%.3f", 1f / meta.downscale)}"
            )
            return upscaled
        }

        return cropped
    }

    /**
     * Decide aspect class according to aspect ratio with square bias window.
     */
    private fun chooseAspect(r: Float): Aspect {
        return if (r in SQUARE_LOW..SQUARE_HIGH) {
            Aspect.SQUARE
        } else {
            // Distance to landscape or portrait reference ratios
            val candidates = listOf(Aspect.LANDSCAPE, Aspect.PORTRAIT)
            candidates.minBy { abs(r - it.ratio) }
        }
    }

    /**
     * If the provider changed the canvas size, proportionally map original rect.
     */
    private fun mapRectIfSizeMismatch(edited: Bitmap, meta: PreparedImage): Pair<Rect, Boolean> {
        if (edited.width == meta.targetW && edited.height == meta.targetH) {
            return meta.contentRect to false
        }
        val scaleX = edited.width.toFloat() / meta.targetW.toFloat()
        val scaleY = edited.height.toFloat() / meta.targetH.toFloat()
        val r = meta.contentRect
        val mapped = Rect(
            (r.left * scaleX).roundToInt(),
            (r.top * scaleY).roundToInt(),
            (r.right * scaleX).roundToInt(),
            (r.bottom * scaleY).roundToInt()
        )
        return mapped to false
    }

    private fun intersectSafe(r: Rect, minX: Int, minY: Int, maxX: Int, maxY: Int): Rect {
        val left = r.left.coerceIn(minX, maxX)
        val top = r.top.coerceIn(minY, maxY)
        val right = r.right.coerceIn(left, maxX)
        val bottom = r.bottom.coerceIn(top, maxY)
        return Rect(left, top, right, bottom)
    }
}
