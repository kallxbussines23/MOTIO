package com.motio.wallpaper.data
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
object MotioStore {
    private const val PREFS = "motio_settings"
    private const val CONFIG = "config"
    fun get(context: Context): MotioConfig {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CONFIG, null) ?: return MotioConfig(null, emptyList())
        return try {
            val obj = JSONObject(raw); val arr = obj.optJSONArray("brushes") ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) { val b = arr.getJSONObject(i)
                    add(BrushRegion(b.getDouble("x").toFloat(), b.getDouble("y").toFloat(), b.getDouble("radius").toFloat(), b.getDouble("strength").toFloat()))
                }
            }
            MotioConfig(obj.optString("imagePath").takeIf { it.isNotBlank() }, list, obj.optDouble("brushSize", .18).toFloat(), obj.optDouble("strength", .8).toFloat())
        } catch (_: Exception) { MotioConfig(null, emptyList()) }
    }
    fun save(context: Context, config: MotioConfig) {
        val arr = JSONArray()
        config.brushes.forEach { b -> arr.put(JSONObject().put("x", b.x).put("y", b.y).put("radius", b.radius).put("strength", b.strength)) }
        val obj = JSONObject().put("imagePath", config.imagePath ?: "").put("brushes", arr).put("brushSize", config.brushSize).put("strength", config.strength)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CONFIG, obj.toString()).apply()
    }
    fun importImage(context: Context, uri: Uri): String {
        val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }; val out = File(dir, "current_wallpaper")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected image" }.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
        }
        return out.absolutePath
    }
}