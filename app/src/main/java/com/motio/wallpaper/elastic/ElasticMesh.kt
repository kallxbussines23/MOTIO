package com.motio.wallpaper.elastic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.motio.wallpaper.data.BrushRegion
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Cached brush influence + softly underdamped spring mesh.
 * A controlled rebound gives the brushed image a lively elastic feel while
 * displacement limits and smooth falloff protect the original picture.
 */
class ElasticMesh(
    private val columns: Int = 40,
    private val rows: Int = 60,
    private val density: Float = 1f
) {
    private val vertexCount = (columns + 1) * (rows + 1)
    private val rest = FloatArray(vertexCount * 2)
    private val vertices = FloatArray(vertexCount * 2)
    private val influence = FloatArray(vertexCount)
    private val displacementX = FloatArray(vertexCount)
    private val displacementY = FloatArray(vertexCount)
    private val velocityX = FloatArray(vertexCount)
    private val velocityY = FloatArray(vertexCount)
    private var initialized = false
    private var lastFrameNanos = 0L
    private var cachedBrushHash = 0
    private var cachedWidth = -1
    private var cachedHeight = -1
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    fun reset() {
        displacementX.fill(0f)
        displacementY.fill(0f)
        velocityX.fill(0f)
        velocityY.fill(0f)
        influence.fill(0f)
        lastFrameNanos = 0L
        cachedBrushHash = 0
        cachedWidth = -1
        cachedHeight = -1
        initialized = false
    }

    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        width: Int,
        height: Int,
        tiltX: Float,
        tiltY: Float,
        shakeX: Float,
        shakeY: Float,
        brushes: List<BrushRegion>,
        globalStrength: Float
    ) {
        if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return

        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val left = (width - drawW) * 0.5f
        val top = (height - drawH) * 0.5f

        var index = 0
        for (gy in 0..rows) {
            val ny = gy.toFloat() / rows
            for (gx in 0..columns) {
                val nx = gx.toFloat() / columns
                rest[index * 2] = left + nx * drawW
                rest[index * 2 + 1] = top + ny * drawH
                index++
            }
        }

        if (!initialized) {
            displacementX.fill(0f)
            displacementY.fill(0f)
            velocityX.fill(0f)
            velocityY.fill(0f)
            initialized = true
        }

        val brushHash = brushes.hashCode()
        if (cachedWidth != width || cachedHeight != height || cachedBrushHash != brushHash) {
            rebuildInfluence(width, height, brushes)
            cachedWidth = width
            cachedHeight = height
            cachedBrushHash = brushHash
        }

        val maxTravel = minOf(width, height) * 0.038f * globalStrength.coerceIn(0.1f, 1.5f)
        val safeTiltX = tiltX.coerceIn(-1f, 1f)
        val safeTiltY = tiltY.coerceIn(-1f, 1f)
        val safeShakeX = shakeX.coerceIn(-1f, 1f)
        val safeShakeY = shakeY.coerceIn(-1f, 1f)

        val now = System.nanoTime()
        val frameDt = if (lastFrameNanos == 0L) 1f / 60f
            else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(1f / 240f, 0.05f)
        lastFrameNanos = now
        // Small fixed substeps avoid spring instability when a frame is delayed.
        val steps = ceil(frameDt / (1f / 180f)).toInt().coerceIn(1, 10)
        val dt = frameDt / steps

        // Slightly underdamped: visible rebound, but no long uncontrolled wobble.
        val stiffness = 78f
        val damping = 12.7f
        var vertex = 0
        while (vertex < vertexCount) {
            val amount = influence[vertex]
            val localLimit = maxTravel * amount * 1.22f
            val targetX = (safeTiltX + safeShakeX * 0.78f) * maxTravel * amount
            val targetY = (safeTiltY + safeShakeY * 0.78f) * maxTravel * amount

            repeat(steps) {
                val forceX = (targetX - displacementX[vertex]) * stiffness -
                    velocityX[vertex] * damping
                val forceY = (targetY - displacementY[vertex]) * stiffness -
                    velocityY[vertex] * damping
                velocityX[vertex] += forceX * dt
                velocityY[vertex] += forceY * dt
                displacementX[vertex] += velocityX[vertex] * dt
                displacementY[vertex] += velocityY[vertex] * dt

                // Limit overshoot to preserve the image and prevent mesh folding.
                displacementX[vertex] = displacementX[vertex].coerceIn(-localLimit, localLimit)
                displacementY[vertex] = displacementY[vertex].coerceIn(-localLimit, localLimit)
            }

            vertices[vertex * 2] = rest[vertex * 2] + displacementX[vertex]
            vertices[vertex * 2 + 1] = rest[vertex * 2 + 1] + displacementY[vertex]
            vertex++
        }

        canvas.drawBitmapMesh(bitmap, columns, rows, vertices, 0, null, 0, paint)
    }

    private fun rebuildInfluence(width: Int, height: Int, brushes: List<BrushRegion>) {
        val minDimension = minOf(width, height).toFloat()
        var vertex = 0
        while (vertex < vertexCount) {
            val px = rest[vertex * 2]
            val py = rest[vertex * 2 + 1]
            var combined = 0f

            for (brush in brushes) {
                val radius = (brush.radius * minDimension).coerceAtLeast(1f)
                val dx = px - brush.x * width
                val dy = py - brush.y * height
                val normalized = sqrt(dx * dx + dy * dy) / radius
                if (normalized < 2.2f) {
                    val falloff = exp(-normalized * normalized * 1.45f)
                    val weighted = (falloff * brush.strength).coerceIn(0f, 1f)
                    combined = 1f - (1f - combined) * (1f - weighted)
                }
            }
            influence[vertex] = combined
            vertex++
        }
    }
}
