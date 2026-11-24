package com.motio.wallpaper.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.view.MotionEvent
import com.motio.wallpaper.data.MotioConfig
import com.motio.wallpaper.data.MotioStore
import com.motio.wallpaper.elastic.ElasticMesh
import com.motio.wallpaper.motion.TiltController

class MotioWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = MotioEngine()

    inner class MotioEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        @Volatile private var tiltX = 0f
        @Volatile private var tiltY = 0f
        @Volatile private var shakeX = 0f
        @Volatile private var shakeY = 0f
        private val mesh = ElasticMesh(density = resources.displayMetrics.density)
        private var bitmap: Bitmap? = null
        private var config: MotioConfig = MotioConfig(null, emptyList())
        private var tilt: TiltController? = null

        private val drawTask = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) handler.postDelayed(this, 8L)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            loadWallpaper()
            tilt = TiltController(this@MotioWallpaperService) { x, y, sx, sy ->
                tiltX = x
                tiltY = y
                shakeX = sx
                shakeY = sy
            }
        }

        private fun loadWallpaper() {
            bitmap?.recycle()
            config = MotioStore.get(this@MotioWallpaperService)
            bitmap = config.imagePath?.let { BitmapFactory.decodeFile(it) }
            mesh.reset()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                loadWallpaper()
                tilt?.start()
                handler.removeCallbacks(drawTask)
                handler.post(drawTask)
            } else {
                handler.removeCallbacks(drawTask)
                tilt?.stop()
                tiltX = 0f
                tiltY = 0f
                shakeX = 0f
                shakeY = 0f
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val width = surfaceHolder.surfaceFrame.width().coerceAtLeast(1)
                val height = surfaceHolder.surfaceFrame.height().coerceAtLeast(1)
                mesh.triggerRipple(event.x / width.toFloat(), event.y / height.toFloat())
                // Redraw immediately so the ripple begins at the touch point.
                if (visible) {
                    handler.removeCallbacks(drawTask)
                    handler.post(drawTask)
                }
            }
            super.onTouchEvent(event)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        private fun drawFrame() {
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(android.graphics.Color.BLACK)
                    val image = bitmap
                    if (image != null && !image.isRecycled) {
                        mesh.draw(
                            canvas, image, canvas.width, canvas.height,
                            tiltX, tiltY, shakeX, shakeY,
                            config.brushes, config.strength
                        )
                    }
                }
            } catch (_: Exception) {
                // Surface may be temporarily unavailable while wallpaper visibility changes.
            } finally {
                if (canvas != null) {
                    try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Exception) { }
                }
            }
        }

        override fun onDestroy() {
            visible = false
            handler.removeCallbacks(drawTask)
            tilt?.stop()
            bitmap?.recycle()
            bitmap = null
            super.onDestroy()
        }
    }
}
