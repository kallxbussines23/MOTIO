package com.motio.wallpaper.elastic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.motio.wallpaper.data.BrushRegion
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Local elastic deformation field.
 *
 * The brush center follows device tilt most strongly; influence fades smoothly
 * toward the edge so the surrounding image remains anchored. Every mesh vertex
 * has its own spring velocity, avoiding the old "whole patch slides together" look.
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
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    fun reset() {
        displacementX.fill(0f)
        displacementY.fill(0f)
        velocityX.fill(0f)
        velocityY.fill(0f)
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
        if (width <= 0 || height <= 0) return

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
                val px = left + nx * drawW
                val py = top + ny * drawH
                rest[i * 2] = px
                rest[i * 2 + 1] = py
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

        val maxTravel = minOf(width, height) * 0.055f * globalStrength.coerceIn(0.1f, 1.5f)
        val safeTiltX = tiltX.coerceIn(-1f, 1f)
        val safeTiltY = tiltY.coerceIn(-1f, 1f)

        // Stable 30 Hz integration. The target is a local displacement field,
        // not a translated copy of the whole image.
        val dt = 1f / 30f
        val stiffness = 92f
        val damping = 19f
        var vertex = 0

        for (gy in 0..rows) {
            for (gx in 0..columns) {
                val px = rest[vertex * 2]
                val py = rest[vertex * 2 + 1]
                var combinedInfluence = 0f

                brushes.forEach { brush ->
                    val radius = (brush.radius * minOf(width, height)).coerceAtLeast(1f)
                    val bx = brush.x * width
                    val by = brush.y * height
                    val dx = px - bx
                    val dy = py - by
                    val distance = sqrt(dx * dx + dy * dy)
                    val normalized = distance / radius
                    if (normalized < 2.2f) {
                        val falloff = exp(-(normalized * normalized) * 2.0f)
                        val weighted = (falloff * brush.strength).coerceIn(0f, 1f)
                        // Union prevents overlapping brush stamps from multiplying force.
                        combinedInfluence = 1f - (1f - combinedInfluence) * (1f - weighted)
                    }
                }

                val targetX = safeTiltX * maxTravel * combinedInfluence
                val targetY = safeTiltY * maxTravel * combinedInfluence

                val forceX = (targetX - displacementX[vertex]) * stiffness - velocityX[vertex] * damping
                val forceY = (targetY - displacementY[vertex]) * stiffness - velocityY[vertex] * damping
                velocityX[vertex] += forceX * dt
                velocityY[vertex] += forceY * dt
                displacementX[vertex] += velocityX[vertex] * dt
                displacementY[vertex] += velocityY[vertex] * dt

                vertices[vertex * 2] = px + displacementX[vertex]
                vertices[vertex * 2 + 1] = py + displacementY[vertex]
                vertex++
            }
        }

        canvas.drawBitmapMesh(bitmap, columns, rows, vertices, 0, null, 0, paint)
    }
}