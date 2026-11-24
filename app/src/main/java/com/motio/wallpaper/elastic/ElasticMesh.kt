package com.motio.wallpaper.elastic
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.motio.wallpaper.data.BrushRegion
import kotlin.math.exp
import kotlin.math.sqrt
class ElasticMesh(private val columns: Int = 32, private val rows: Int = 48) {
    private var current = FloatArray((columns + 1) * (rows + 1) * 2)
    private var initialized = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    fun draw(canvas: Canvas, bitmap: Bitmap, width: Int, height: Int, tiltX: Float, tiltY: Float, brushes: List<BrushRegion>, globalStrength: Float) {
        val count = (columns + 1) * (rows + 1); val target = FloatArray(count * 2)
        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawW = bitmap.width * scale; val drawH = bitmap.height * scale
        val left = (width - drawW) / 2f; val top = (height - drawH) / 2f
        var index = 0
        for (gy in 0..rows) for (gx in 0..columns) {
            val px = left + gx.toFloat() / columns * drawW
            val py = top + gy.toFloat() / rows * drawH
            var dx = 0f; var dy = 0f
            brushes.forEach { b ->
                val bx = b.x * width; val by = b.y * height
                val radius = (b.radius * minOf(width, height)).coerceAtLeast(1f)
                val dist = sqrt((px - bx) * (px - bx) + (py - by) * (py - by))
                val falloff = exp(-((dist / radius) * (dist / radius)) * 2.2f)
                val amount = falloff * b.strength * globalStrength * 22f
                dx += tiltX * amount; dy += tiltY * amount
            }
            target[index * 2] = px + dx; target[index * 2 + 1] = py + dy; index++
        }
        if (!initialized) { current = target.copyOf(); initialized = true }
        for (i in current.indices) current[i] += (target[i] - current[i]) * .16f
        canvas.drawBitmapMesh(bitmap, columns, rows, current, 0, null, 0, paint)
    }
}