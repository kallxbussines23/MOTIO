package com.motio.wallpaper.wallpaper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.motio.wallpaper.data.MotioStore
import com.motio.wallpaper.elastic.ElasticMesh
import com.motio.wallpaper.motion.TiltController
class MotioWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = MotioEngine()
    inner class MotioEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false; private var tiltX = 0f; private var tiltY = 0f
        private val mesh = ElasticMesh(); private var bitmap: Bitmap? = null; private var tilt: TiltController? = null
        private val drawTask = object : Runnable { override fun run() { drawFrame(); if (visible) handler.postDelayed(this, 33L) } }
        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder); setTouchEventsEnabled(false); loadWallpaper()
            tilt = TiltController(this@MotioWallpaperService) { x, y -> tiltX = x; tiltY = y }
        }
        private fun loadWallpaper() {
            bitmap?.recycle(); val config = MotioStore.get(this@MotioWallpaperService)
            bitmap = config.imagePath?.let { BitmapFactory.decodeFile(it) }
        }
        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) { tilt?.start(); handler.removeCallbacks(drawTask); handler.post(drawTask) }
            else { handler.removeCallbacks(drawTask); tilt?.stop() }
        }
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { super.onSurfaceChanged(holder, format, width, height); drawFrame() }
        private fun drawFrame() {
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(android.graphics.Color.BLACK)
                    val image = bitmap
                    if (image != null && !image.isRecycled) {
                        val config = MotioStore.get(this@MotioWallpaperService)
                        mesh.draw(canvas, image, canvas.width, canvas.height, tiltX, tiltY, config.brushes, config.strength)
                    }
                }
            } catch (_: Exception) {
            } finally { if (canvas != null) try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Exception) {} }
        }
        override fun onDestroy() {
            visible = false; handler.removeCallbacks(drawTask); tilt?.stop(); bitmap?.recycle(); bitmap = null; super.onDestroy()
        }
    }
}