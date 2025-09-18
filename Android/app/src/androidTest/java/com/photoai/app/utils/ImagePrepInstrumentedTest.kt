package com.photoai.app.utils

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class ImagePrepInstrumentedTest {

    private fun makeBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private fun aspect(w: Int, h: Int): Float = w.toFloat() / h.toFloat()

    private fun almostEqual(a: Float, b: Float, eps: Float = 0.002f): Boolean =
        abs(a - b) <= eps

    /**
     * Square-biased near-square image should choose square aspect and produce
     * a square canvas large enough to contain the original without scaling it.
     */
    @Test
    fun testSquareBiasNoScaling() {
        val srcW = 1300
        val srcH = 1250
        val src = makeBitmap(srcW, srcH)
        val (padded, meta) = ImagePrep.prepare(src)
        // Base dimensions should be square base
        assertEquals(1024, meta.baseW)
        assertEquals(1024, meta.baseH)

        assertEquals("Square canvas expected", meta.targetW, meta.targetH)
        assertTrue(meta.targetW >= max(srcW, srcH))

        // ContentRect should match original dimensions (no scaling)
        assertEquals(srcW, meta.contentRect.width())
        assertEquals(srcH, meta.contentRect.height())

        // Centering (allow off-by-one due to rounding on odd diffs)
        val expectedLeftDiff = meta.targetW - srcW
        val expectedTopDiff = meta.targetH - srcH
        val leftOffset = meta.contentRect.left
        val topOffset = meta.contentRect.top
        assertTrue(abs(leftOffset * 2 - expectedLeftDiff) <= 1)
        assertTrue(abs(topOffset * 2 - expectedTopDiff) <= 1)

        padded.recycle()
        src.recycle()
    }

    /**
     * Perfect 1.5 aspect should map exactly to landscape base ratio canvas that
     * equals original size (no padding necessary).
     */
    @Test
    fun testLandscapeExactMatch() {
        val src = makeBitmap(3000, 2000) // 1.5 aspect exactly
        val (padded, meta) = ImagePrep.prepare(src)
        // Base landscape
        assertEquals(1536, meta.baseW)
        assertEquals(1024, meta.baseH)

        assertEquals(3000, meta.targetW)
        assertEquals(2000, meta.targetH)
        assertEquals(0, meta.contentRect.left)
        assertEquals(0, meta.contentRect.top)
        assertEquals(src.width, meta.contentRect.width())
        assertEquals(src.height, meta.contentRect.height())

        padded.recycle()
        src.recycle()
    }

    /**
     * Portrait image should choose portrait aspect; height matches original,
     * width expanded to preserve aspect class ratio; original centered horizontally only.
     */
    @Test
    fun testPortraitCanvasExpansion() {
        val srcW = 1500
        val srcH = 3000
        val src = makeBitmap(srcW, srcH) // aspect 0.5
        val (padded, meta) = ImagePrep.prepare(src)
        // Base portrait
        assertEquals(1024, meta.baseW)
        assertEquals(1536, meta.baseH)

        // Height should remain original (since scale driven by height), targetW widened.
        assertEquals(srcH, meta.targetH)
        assertTrue(meta.targetW >= srcW)

        // Aspect ratio of canvas close to portrait base ratio (1024/1536)
        val portraitRatio = 1024f / 1536f
        assertTrue(almostEqual(aspect(meta.targetW, meta.targetH), portraitRatio, 0.005f))

        // Original unscaled
        assertEquals(srcW, meta.contentRect.width())
        assertEquals(srcH, meta.contentRect.height())

        // Horizontal centering (vertical offset zero or near zero)
        val horizPaddingTotal = meta.targetW - srcW
        val leftOffset = meta.contentRect.left
        assertTrue(abs(leftOffset * 2 - horizPaddingTotal) <= 1)
        assertEquals(0, meta.contentRect.top)

        padded.recycle()
        src.recycle()
    }

    /**
     * Round trip restore should recover original dimensions exactly.
     */
    @Test
    fun testRestoreRoundTrip() {
        val src = makeBitmap(2200, 1400)
        val (padded, meta) = ImagePrep.prepare(src)
        // Base landscape
        assertEquals(1536, meta.baseW)
        assertEquals(1024, meta.baseH)

        val restored = ImagePrep.restore(padded, meta, scaleToOriginal = true)
        assertEquals(src.width, restored.width)
        assertEquals(src.height, restored.height)
        assertTrue(almostEqual(aspect(src.width, src.height), aspect(restored.width, restored.height), 0.0001f))

        restored.recycle()
        padded.recycle()
        src.recycle()
    }

    /**
     * Extreme panorama should choose landscape aspect; canvas height expands to maintain ratio;
     * original centered vertically with padding top/bottom.
     */
    @Test
    fun testExtremePanoramaLandscapePadding() {
        val srcW = 4000
        val srcH = 800 // aspect 5.0
        val src = makeBitmap(srcW, srcH)
        val (padded, meta) = ImagePrep.prepare(src)

        // Canvas must be at least as wide as original
        assertEquals(srcW, meta.targetW)
        assertTrue(meta.targetH >= srcH)

        // Canvas ratio close to landscape 1536/1024 = 1.5
        val landscapeRatio = 1536f / 1024f
        assertTrue(almostEqual(aspect(meta.targetW, meta.targetH), landscapeRatio, 0.002f))

        // Unscaled content
        assertEquals(srcW, meta.contentRect.width())
        assertEquals(srcH, meta.contentRect.height())

        // Vertical centering
        val verticalPaddingTotal = meta.targetH - srcH
        val topOffset = meta.contentRect.top
        assertTrue(abs(topOffset * 2 - verticalPaddingTotal) <= 1)
        assertEquals(0, meta.contentRect.left)

        padded.recycle()
        src.recycle()
    }

    /**
     * New test ensuring no scaling is ever applied (contentRect dimensions equal source).
     */
    @Test
    fun testNoScalingInvariant() {
        val cases = listOf(
            100 to 100,
            1024 to 600,
            600 to 1024,
            2500 to 900,
            900 to 2500,
            3333 to 1777
        )
        for ((w, h) in cases) {
            val src = makeBitmap(w, h)
            val (padded, meta) = ImagePrep.prepare(src)
            // Ensure base dims recorded
            assertTrue(
                (meta.baseW == 1024 && meta.baseH == 1024) ||
                        (meta.baseW == 1536 && meta.baseH == 1024) ||
                        (meta.baseW == 1024 && meta.baseH == 1536)
            )
            assertEquals(w, meta.contentRect.width())
            assertEquals(h, meta.contentRect.height())
            padded.recycle()
            src.recycle()
        }
    }
}
