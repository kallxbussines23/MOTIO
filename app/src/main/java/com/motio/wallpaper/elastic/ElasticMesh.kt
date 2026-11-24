package com.motio.wallpaper.elastic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.motio.wallpaper.data.BrushRegion
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Smooth spring-based deformation. Brushed regions follow the phone's tilt
 * direction while the brush boundary stays anchored, creating a soft elastic pull.
 */
class ElasticMesh(
    private val columns: Int = 40,
    private val rows: Int = 60,
    private val density: Float = 1f
) {
    private val vertexCount = (columns + 1) * (rows + 1)
    private val rest = FloatArray(vertexCount * 2)
    private val vertices = FloatArray(vertexCount * 2)
    private val displacementX = FloatArray(vertexCount)
    private val displacementY = FloatArray(vertexCount)
    private val velocityX = FloatArray(vertexCount)
    private val velocityY = FloatArray(vertexCount)
    private var initialized = false
    private var lastFrameNanos = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    fun reset() {
        displacementX.fill(0f)
        displacementY.fill(0f)
        velocityX.fill(0f)
        velocityY.fill(0f)
        lastFrameNanos = 0L
        initialized = false
    }

    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        width: Int,
        height: Int,
        tiltX: Float,
        tiltY: Float,
        brushes: List<BrushRegion>,
        globalStrength: Float
    ) {
        if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return

        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val left = (width - drawW) * 0.5f
        val top = (height - drawH) * 0.5f

        var i = 0
        for (gy in 0..rows) {
            val ny = gy.toFloat() / rows
            for (gx in 0..columns) {
                val nx = gx.toFloat() / columns
                rest[i * 2] = left + nx * drawW
                rest[i * 2 + 1] = top + ny * drawH
                i++
            }
        }

        if (!initialized) {
            displacementX.fill(0f)
            displacementY.fill(0f)
            velocityX.fill(0f)
            velocityY.fill(0f)
            initialized = true
        }

        val maxTravel = minOf(width, height) * 0.045f * globalStrength.coerceIn(0.1f, 1.5f)
        val safeTiltX = tiltX.coerceIn(-1f, 1f)
        val safeTiltY = tiltY.coerceIn(-1f, 1f)

        // Use real frame time with small substeps so motion stays consistent
        // on 60/90/120 Hz screens and after occasional dropped frames.
        val now = System.nanoTime()
        val frameDt = if (lastFrameNanos == 0L) 1f / 60f
            else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(1f / 240f, 0.05f)
        lastFrameNanos = now
        val steps = ceil(frameDt / (1f / 120f)).toInt().coerceIn(1, 6)
        val dt = frameDt / steps
        val stiffness = 58f
        val damping = 15f
        val minDimension = minOf(width, height).toFloat()

        var vertex = 0
        for (gy in 0..rows) {
            for (gx in 0..columns) {
                val px = rest[vertex * 2]
                val py = rest[vertex * 2 + 1]
                var combinedInfluence = 0f

                for (brush in brushes) {
                    val radius = (brush.radius * minDimension).coerceAtLeast(1f)
                    val dx = px - brush.x * width
                    val dy = py - brush.y * height
                    val normalized = sqrt(dx * dx + dy * dy) / radius
                    if (normalized < 2.2f) {
                        val falloff = exp(-normalized * normalized * 1.65f)
                        val weighted = (falloff * brush.strength).coerceIn(0f, 1f)
                        combinedInfluence = 1f - (1f - combinedInfluence) * (1f - weighted)
                    }
                }

                // The brush center follows the tilt; its outer edge remains anchored.
                val targetX = safeTiltX * maxTravel * combinedInfluence
                val targetY = safeTiltY * maxTravel * combinedInfluence

                repeat(steps) {
                    val forceX = (targetX - displacementX[vertex]) * stiffness -
                        velocityX[vertex] * damping
                    val forceY = (targetY - displacementY[vertex]) * stiffness -
                        velocityY[vertex] * damping
                    velocityX[vertex] += forceX * dt
                    velocityY[vertex] += forceY * dt
                    displacementX[vertex] += velocityX[vertex] * dt
                    displacementY[vertex] += velocityY[vertex] * dt
                }

                vertices[vertex * 2] = px + displacementX[vertex]
                vertices[vertex * 2 + 1] = py + displacementY[vertex]
                vertex++
            }
        }

        canvas.drawBitmapMesh(bitmap, columns, rows, vertices, 0, null, 0, paint)
    }
}
