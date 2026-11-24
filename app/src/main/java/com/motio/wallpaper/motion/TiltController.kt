package com.motio.wallpaper.motion
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.max
import kotlin.math.min
class TiltController(context: Context, private val onTilt: (Float, Float) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val matrix = FloatArray(9); private val orientation = FloatArray(3)
    private var smoothX = 0f; private var smoothY = 0f
    fun start() { (rotation ?: accel)?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() = manager.unregisterListener(this)
    override fun onSensorChanged(event: SensorEvent) {
        val x: Float; val y: Float
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            SensorManager.getOrientation(matrix, orientation)
            x = orientation[2] / .65f; y = orientation[1] / .65f
        } else { x = event.values[0] / SensorManager.GRAVITY_EARTH; y = event.values[1] / SensorManager.GRAVITY_EARTH }
        smoothX += (min(1f, max(-1f, x)) - smoothX) * .18f
        smoothY += (min(1f, max(-1f, y)) - smoothY) * .18f
        onTilt(smoothX, smoothY)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}