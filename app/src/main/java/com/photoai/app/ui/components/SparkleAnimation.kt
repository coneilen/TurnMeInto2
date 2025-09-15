package com.photoai.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.random.Random

private data class Star(
    val x: Float,
    val y: Float,
    val baseSize: Float,
    val rotationOffset: Float,
    val phaseOffset: Float,
    val color: Color,
    val layer: Int, // 0 back, 1 front
    val points: Int, // number of star points (5 or 8)
    val innerRatio: Float, // inner radius ratio (controls spike sharpness)
    val speed: Float // individual lifecycle speed factor
)

private data class NebulaCloud(
    val x: Float,
    val y: Float,
    val radius: Float,
    val colors: List<Color>,
    val alpha: Float,
    val driftX: Float,
    val driftY: Float
)

private fun drawStar(
    size: Float,
    color: Color,
    alpha: Float,
    points: Int,
    innerRatio: Float,
    drawScope: androidx.compose.ui.graphics.drawscope.DrawScope
) {
    val path = Path()
    val outerRadius = size / 2f
    val innerRadius = outerRadius * innerRatio
    val angleStep = Math.PI / points

    // First outer point at top
    path.moveTo(
        (outerRadius * kotlin.math.cos(-Math.PI / 2)).toFloat(),
        (outerRadius * kotlin.math.sin(-Math.PI / 2)).toFloat()
    )

    for (i in 1 until points * 2) {
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        val angle = -Math.PI / 2 + i * angleStep
        path.lineTo(
            (radius * kotlin.math.cos(angle)).toFloat(),
            (radius * kotlin.math.sin(angle)).toFloat()
        )
    }


    path.close()

    drawScope.drawPath(
        path = path,
        color = color.copy(alpha = alpha),
        style = androidx.compose.ui.graphics.drawscope.Fill
    )
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
fun SparkleAnimation(
    modifier: Modifier = Modifier,
    starCount: Int = 156,  // per layer (total 312)
    colors: List<Color> = listOf(
        Color(0xFFFFD700),  // Gold
        Color(0xFFFF8CFF),  // Pink-Magenta
        Color(0xFF80D8FF),  // Light Cyan
        Color(0xFFC5A3FF),  // Soft Lavender
        Color(0xFF9DFFB0),  // Mint
        Color(0xFFFFD1A3)   // Peach
    )
) {
    // Nebula clouds (soft radial gradients drifting slowly)
    val nebulas = remember {
        List(5) {
            NebulaCloud(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                radius = Random.nextFloat() * 600f + 400f,
                colors = listOf(
                    Color.White.copy(alpha = 0f),
                    colors[Random.nextInt(colors.size)].copy(alpha = 0.15f),
                    colors[Random.nextInt(colors.size)].copy(alpha = 0.35f),
                    Color.White.copy(alpha = 0.05f)
                ),
                alpha = Random.nextFloat() * 0.4f + 0.3f,
                driftX = (Random.nextFloat() - 0.5f) * 40f,
                driftY = (Random.nextFloat() - 0.5f) * 20f
            )
        }
    }

    val stars = remember {
        (0..1).flatMap { layer ->
            List(starCount) {
                val layerScale = if (layer == 0) 0.6f else 1f
val generatedPoints = if (Random.nextFloat() < 0.45f) 8 else 5
Star(
    x = Random.nextFloat(),
    y = Random.nextFloat(),
    baseSize = (Random.nextFloat() * 28f + 12f) * 1.25f * layerScale, // 25% larger
    rotationOffset = Random.nextFloat() * 360f,
    phaseOffset = Random.nextFloat(),
    color = colors[Random.nextInt(colors.size)],
    layer = layer,
    points = generatedPoints,
    innerRatio = if (generatedPoints >= 8) (Random.nextFloat() * 0.10f + 0.22f) else (Random.nextFloat() * 0.12f + 0.28f),
    speed = if (layer == 0) (Random.nextFloat() * 0.4f + 0.4f) else (Random.nextFloat() * 0.6f + 0.9f)
)
            }
        }
    }

    // Continuous, non-resetting time (seconds)
    var baseTime by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        withFrameNanos { last = it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                // Avoid Float overflow for very long sessions (wrap but smooth)
                baseTime = (baseTime + dt) % 10_000f
            }
        }
    }

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Draw nebulas first (soft clouds)
        nebulas.forEachIndexed { index, n ->
            // Very slow 30s drift cycle
            val driftPhase = ((baseTime / 30f) + index * 0.1f) % 1f
            val dx = n.driftX * (driftPhase - 0.5f)
            val dy = n.driftY * (driftPhase - 0.5f)
            val pulsePhase = (baseTime / 4.083f) % 1f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = n.colors,
                    center = Offset(
                        x = n.x * canvasWidth + dx,
                        y = n.y * canvasHeight + dy
                    ),
                    radius = n.radius
                ),
                center = Offset(
                    x = n.x * canvasWidth + dx,
                    y = n.y * canvasHeight + dy
                ),
                radius = n.radius,
                alpha = n.alpha * (0.8f + 0.2f * kotlin.math.sin(pulsePhase * 2 * Math.PI).toFloat())
            )
        }

        // Sort stars so back layer draws first
        stars.forEach { star ->
            val progress = ((baseTime * star.speed) + star.phaseOffset) % 1f

            // Smooth lifecycle (fade in / hold / fade out)
            val fade = smoothstep(0f, 0.12f, progress) * (1f - smoothstep(0.70f, 1f, progress))
            val growth = smoothstep(0f, 0.55f, progress)
            val shrinkMod = 1f - smoothstep(0.65f, 1f, progress) * 0.3f
            val sizeFactor = 0.4f + growth * 1.6f * shrinkMod

            val layerAlpha = if (star.layer == 0) 0.25f else 0.55f
            val twinkle = 0.85f + 0.15f * kotlin.math.sin((baseTime * 0.8f + star.phaseOffset) * 2 * Math.PI).toFloat()
            val alpha = fade * layerAlpha * twinkle

            // Continuous parallax drift (px/s approximated from previous cycle-based speeds)
            val parallaxX = if (star.layer == 0) {
                (baseTime * 5.36f) % canvasWidth
            } else {
                (-baseTime * 8.57f) % canvasWidth
            }
            val shimmerY = kotlin.math.sin((baseTime * 0.215f + star.phaseOffset) * 6 * Math.PI).toFloat() * (if (star.layer == 0) 4f else 7f)

            val centerX = (star.x * canvasWidth + parallaxX + canvasWidth) % canvasWidth
            val centerY = (star.y * canvasHeight + shimmerY + canvasHeight) % canvasHeight

            withTransform({
                translate(centerX, centerY)
                rotate(star.rotationOffset + progress * (if (star.layer == 0) 15f else -30f))
            }) {
                val currentSize = star.baseSize * sizeFactor

                // Glow halo (reduced for more transparency)
                drawCircle(
                    color = star.color.copy(alpha = alpha * 0.12f),
                    radius = currentSize * 0.9f
                )
                drawCircle(
                    color = star.color.copy(alpha = alpha * 0.04f),
                    radius = currentSize * 1.5f
                )

                // Core star
drawStar(
    size = currentSize,
    color = star.color,
    alpha = alpha,
    points = star.points,
    innerRatio = star.innerRatio,
    drawScope = this
)
            }
        }
    }
}

@Composable
fun SparkleProcessingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),  // Softer to show nebula
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Full screen sparkle animation
            SparkleAnimation(
                modifier = Modifier.fillMaxSize()
            )
            
            // Centered text with shadow for better visibility
            Text(
                text = "Applying Magic",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(16.dp)
            )
        }
    }
}
