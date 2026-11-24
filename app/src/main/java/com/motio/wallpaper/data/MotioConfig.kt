package com.motio.wallpaper.data
data class BrushRegion(val x: Float, val y: Float, val radius: Float, val strength: Float)
data class MotioConfig(val imagePath: String?, val brushes: List<BrushRegion>, val brushSize: Float = 0.18f, val strength: Float = 0.8f)