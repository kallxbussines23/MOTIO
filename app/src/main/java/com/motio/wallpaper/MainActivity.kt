package com.motio.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.motio.wallpaper.data.BrushRegion
import com.motio.wallpaper.data.MotioConfig
import com.motio.wallpaper.data.MotioStore
import com.motio.wallpaper.wallpaper.MotioWallpaperService
import kotlinx.coroutines.launch
import kotlin.math.hypot

class MainActivity : ComponentActivity() {
    companion object {
        private const val MAX_BRUSHES = 5_000
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MotioApp() }
    }

    @Composable
    private fun MotioApp() {
        val context = this
        val scope = rememberCoroutineScope()
        var imagePath by remember { mutableStateOf(MotioStore.get(context).imagePath) }
        var brushes by remember { mutableStateOf(MotioStore.get(context).brushes) }
        var brushSize by remember { mutableFloatStateOf(.18f) }
        var strength by remember { mutableFloatStateOf(.8f) }
        var status by remember { mutableStateOf("Import gambar untuk memulai.") }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) scope.launch {
                val saved = MotioStore.importImage(context, uri)
                imagePath = saved
                brushes = emptyList()
                MotioStore.save(context, MotioConfig(saved, brushes, brushSize, strength))
                status = "Gambar berhasil diimpor."
            }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Color(0xFF090B0F),
                surface = Color(0xFF151922),
                primary = Color(0xFFE8EDF5),
                secondary = Color(0xFF9BA8BC)
            )
        ) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF090B0F)) {
                Column(
                    Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("MOTIO", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                    Text("MULTI ELASTIC BRUSH", style = MaterialTheme.typography.labelMedium, color = Color(0xFF9BA8BC))
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .background(Color(0xFF151922), RoundedCornerShape(24.dp))
                    ) {
                        if (imagePath != null) {
                            BrushCanvas(
                                imagePath = imagePath!!,
                                brushes = brushes,
                                brushRadius = brushSize,
                                onAddBrush = { point ->
                                    if (brushes.size < MAX_BRUSHES) {
                                        brushes = brushes + BrushRegion(point.x, point.y, brushSize, strength)
                                        status = "${brushes.size}/${MAX_BRUSHES} area kuas aktif."
                                    } else {
                                        status = "Batas ${MAX_BRUSHES} brush tercapai."
                                    }
                                },
                                onStrokeFinished = {
                                    MotioStore.save(context, MotioConfig(imagePath, brushes, brushSize, strength))
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                Modifier.align(androidx.compose.ui.Alignment.Center),
                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                            ) {
                                Text("Belum ada wallpaper", color = Color.White)
                                Text("Pilih gambar untuk mulai mengedit", color = Color(0xFF9BA8BC))
                            }
                        }
                    }
                    Text("BRUSH SIZE  ${(brushSize * 100).toInt()}%", color = Color.White)
                    Slider(value = brushSize, onValueChange = { brushSize = it.coerceIn(.05f, .45f) })
                    Text("ELASTIC STRENGTH  ${(strength * 100).toInt()}%", color = Color.White)
                    Slider(value = strength, onValueChange = { strength = it.coerceIn(.1f, 1.5f) })
                    Text(status, color = Color(0xFF9BA8BC), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                            Text("Import image")
                        }
                        OutlinedButton(onClick = {
                            brushes = emptyList()
                            imagePath?.let { MotioStore.save(context, MotioConfig(it, brushes, brushSize, strength)) }
                            status = "Semua area kuas dihapus."
                        }, modifier = Modifier.weight(1f)) { Text("Clear brush") }
                    }
                    Button(
                        onClick = {
                            if (imagePath == null) status = "Import gambar terlebih dahulu."
                            else {
                                MotioStore.save(context, MotioConfig(imagePath, brushes, brushSize, strength))
                                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    ComponentName(context, MotioWallpaperService::class.java)
                                )
                                try { startActivity(intent) }
                                catch (_: Exception) { status = "Wallpaper picker tidak tersedia." }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = imagePath != null
                    ) { Text("Apply wallpaper") }
                }
            }
        }
    }

    @Composable
    private fun BrushCanvas(
        imagePath: String,
        brushes: List<BrushRegion>,
        brushRadius: Float,
        onAddBrush: (Offset) -> Unit,
        onStrokeFinished: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val bitmap = remember(imagePath) { android.graphics.BitmapFactory.decodeFile(imagePath) }
        val latestOnAddBrush by rememberUpdatedState(onAddBrush)
        val latestOnStrokeFinished by rememberUpdatedState(onStrokeFinished)
        Canvas(
            modifier.pointerInput(imagePath, brushRadius) {
                var lastPoint: Offset? = null
                detectDragGestures(
                    onDragStart = { point ->
                        lastPoint = point
                        latestOnAddBrush(Offset(point.x / size.width, point.y / size.height))
                    },
                    onDragEnd = {
                        lastPoint = null
                        latestOnStrokeFinished()
                    },
                    onDragCancel = {
                        lastPoint = null
                        latestOnStrokeFinished()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val point = change.position
                        val previous = lastPoint
                        val minSpacing = (brushRadius * minOf(size.width, size.height) * .42f).coerceAtLeast(8f)
                        if (previous == null || hypot(point.x - previous.x, point.y - previous.y) >= minSpacing) {
                            latestOnAddBrush(Offset(point.x / size.width, point.y / size.height))
                            lastPoint = point
                        }
                    }
                )
            }
        ) {
            bitmap?.let {
                val scale = maxOf(size.width / it.width, size.height / it.height)
                val w = it.width * scale
                val h = it.height * scale
                drawImage(
                    it.asImageBitmap(),
                    dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                    dstSize = IntSize(w.toInt(), h.toInt())
                )
            }
            brushes.forEach { brush ->
                drawCircle(
                    Color(0xFFB8D8FF).copy(alpha = .65f),
                    brush.radius * size.minDimension,
                    Offset(brush.x * size.width, brush.y * size.height),
                    style = Stroke(2.dp.toPx())
                )
            }
        }
    }
}