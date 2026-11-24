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
    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleStartedNanos = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var dragActive = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var releasedDragX = 0f
    private var releasedDragY = 0f
    private var dragReleaseNanos = 0L

    fun beginDrag(x: Float, y: Float) {
        dragStartX = x
        dragStartY = y
        dragOffsetX = 0f
        dragOffsetY = 0f
        releasedDragX = 0f
        releasedDragY = 0f
        dragReleaseNanos = 0L
        dragActive = true
    }

    fun moveDrag(x: Float, y: Float) {
        if (!dragActive) return
        dragOffsetX = x - dragStartX
        dragOffsetY = y - dragStartY
    }

    fun endDrag() {
        if (!dragActive) return
        releasedDragX = dragOffsetX
        releasedDragY = dragOffsetY
        dragReleaseNanos = System.nanoTime()
        dragActive = false
    }

    fun triggerRipple(x: Float, y: Float) {
        rippleX = x.coerceIn(0f, 1f)
        rippleY = y.coerceIn(0f, 1f)
        rippleStartedNanos = System.nanoTime()
    }

    fun reset() {
        dragActive = false
        dragOffsetX = 0f
        dragOffsetY = 0f
        releasedDragX = 0f
        releasedDragY = 0f
        dragReleaseNanos = 0L
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
            val localLimit = maxTravel * amount * 1.8f
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

            val dx = rest[vertex * 2] - dragStartX
            val dy = rest[vertex * 2 + 1] - dragStartY
            val dragRadius = minOf(width, height) * 0.24f
            val normalizedDragDistance = sqrt(dx * dx + dy * dy) / dragRadius
            val dragFalloff = exp(-normalizedDragDistance * normalizedDragDistance * 2.2f)
            val brushWeight = amount * dragFalloff
            val maxDragOffset = minOf(width, height) * 0.16f
            val directX: Float
            val directY: Float
            if (dragActive) {
                // Apply the pointer delta directly: no spring lag while the finger moves.
                directX = dragOffsetX.coerceIn(-maxDragOffset, maxDragOffset) * brushWeight
                directY = dragOffsetY.coerceIn(-maxDragOffset, maxDragOffset) * brushWeight
            } else if (dragReleaseNanos != 0L) {
                // On release, decay with a short damped oscillation for an elastic bounce.
                val age = (now - dragReleaseNanos) / 1_000_000_000f
                if (age < 0.72f) {
                    val decay = exp(-age * 6.2f)
                    val bounce = kotlin.math.cos(age * 19f) * decay
                    directX = releasedDragX * brushWeight * bounce
                    directY = releasedDragY * brushWeight * bounce
                } else {
                    directX = 0f
                    directY = 0f
                    dragReleaseNanos = 0L
                    releasedDragX = 0f
                    releasedDragY = 0f
                }
            } else {
                directX = 0f
                directY = 0f
            }
            vertices[vertex * 2] = rest[vertex * 2] + displacementX[vertex] + directX
            vertices[vertex * 2 + 1] = rest[vertex * 2 + 1] + displacementY[vertex] + directY
            vertex++
        }

        // A tap creates a traveling radial wave. It is a temporary vertex offset;
        // the source bitmap is never modified.
        val rippleAge = if (rippleStartedNanos == 0L) Float.POSITIVE_INFINITY
            else (now - rippleStartedNanos) / 1_000_000_000f
        if (rippleAge in 0f..1.25f) {
            val minDimension = minOf(width, height).toFloat()
            val waveRadius = rippleAge * minDimension * 1.35f
            val waveWidth = minDimension * 0.105f
            val amplitude = minDimension * 0.009f * exp(-rippleAge * 2.7f)
            var i = 0
            while (i < vertexCount) {
                val dx = rest[i * 2] - rippleX * width
                val dy = rest[i * 2 + 1] - rippleY * height
                val distance = sqrt(dx * dx + dy * dy)
                val band = (distance - waveRadius) / waveWidth
                val envelope = exp(-band * band * 1.8f)
                val wave = kotlin.math.sin((distance / minDimension * 42f) - rippleAge * 17f)
                // Restrict the ripple strictly to brushed vertices; outside the brush
                // influence the original image geometry remains untouched.
                val brushMask = influence[i]
                val offset = amplitude * envelope * wave * brushMask
                val safeDistance = distance.coerceAtLeast(1f)
                val limit = minDimension * 0.012f
                val ox = (dx / safeDistance * offset).coerceIn(-limit, limit)
                val oy = (dy / safeDistance * offset).coerceIn(-limit, limit)
                vertices[i * 2] += ox
                vertices[i * 2 + 1] += oy
                i++
            }
        } else if (rippleAge > 1.25f) {
            rippleStartedNanos = 0L
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
