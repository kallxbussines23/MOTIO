package com.motio.wallpaper.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.exp

/**
 * Converts device orientation into a neutral-relative, gently filtered tilt signal.
 * The first sample after start is neutral, so the wallpaper does not jump on entry.
 */
class TiltController(
    context: Context,
    private val onTilt: (Float, Float) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val matrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var baselineRoll = 0f
    private var baselinePitch = 0f
    private var hasBaseline = false
    private var smoothX = 0f
    private var smoothY = 0f
    private var lastTimestamp = 0L

    fun start() {
        hasBaseline = false
        smoothX = 0f
        smoothY = 0f
        lastTimestamp = 0L
        val sensor = rotation ?: accelerometer
        if (sensor != null) manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        onTilt(0f, 0f)
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val roll: Float
        val pitch: Float

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            SensorManager.getOrientation(matrix, orientation)
            pitch = orientation[1]
            roll = orientation[2]
        } else {
            val norm = kotlin.math.sqrt(
                event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
            ).coerceAtLeast(0.001f)
            roll = kotlin.math.atan2(event.values[0], event.values[2])
            pitch = kotlin.math.asin((event.values[1] / norm).coerceIn(-1f, 1f))
        }

        if (!hasBaseline) {
            baselineRoll = roll
            baselinePitch = pitch
            hasBaseline = true
            lastTimestamp = event.timestamp
            return
        }

        val deltaRoll = wrapAngle(roll - baselineRoll)
        val deltaPitch = wrapAngle(pitch - baselinePitch)

        val targetX = normalizeTilt(deltaRoll)
        // Android's pitch sign is opposite to the visual "top edge moves down"
        // direction expected by the wallpaper canvas. Invert it here.
        val targetY = -normalizeTilt(deltaPitch)

        val elapsed = if (lastTimestamp == 0L) 1f / 60f
            else ((event.timestamp - lastTimestamp) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastTimestamp = event.timestamp

        // A softer low-pass removes sensor jitter without making the wallpaper lag.
        val alpha = (1f - exp(-elapsed * 8f)).coerceIn(0.02f, 0.34f)
        smoothX += (targetX - smoothX) * alpha
        smoothY += (targetY - smoothY) * alpha
        onTilt(smoothX, smoothY)
    }

    private fun normalizeTilt(angle: Float): Float {
        val deadZone = 0.035f
        val magnitude = abs(angle)
        if (magnitude <= deadZone) return 0f
        val usable = (magnitude - deadZone) / (0.48f - deadZone)
        return usable.coerceIn(0f, 1f) * if (angle < 0f) -1f else 1f
    }

    private fun wrapAngle(value: Float): Float {
        var result = value
        while (result > Math.PI) result -= (2f * Math.PI.toFloat())
        while (result < -Math.PI) result += (2f * Math.PI.toFloat())
        return result
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
