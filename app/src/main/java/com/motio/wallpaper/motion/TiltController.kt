package com.motio.wallpaper.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Reads orientation for tilt and linear acceleration for quick motion impulses.
 * Both streams are filtered independently to keep shake responsive without jitter.
 */
class TiltController(
    context: Context,
    private val onMotion: (Float, Float, Float, Float) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linearAcceleration = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val matrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var baselineRoll = 0f
    private var baselinePitch = 0f
    private var hasBaseline = false
    private var smoothX = 0f
    private var smoothY = 0f
    private var shakeX = 0f
    private var shakeY = 0f
    private var lastTimestamp = 0L
    private var lastShakeTimestamp = 0L

    fun start() {
        hasBaseline = false
        smoothX = 0f
        smoothY = 0f
        shakeX = 0f
        shakeY = 0f
        lastTimestamp = 0L
        lastShakeTimestamp = 0L

        val orientationSensor = rotation ?: accelerometer
        if (orientationSensor != null) {
            manager.registerListener(this, orientationSensor, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAcceleration?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        emit()
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            updateShake(event)
            return
        }

        val roll: Float
        val pitch: Float
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            SensorManager.getOrientation(matrix, orientation)
            pitch = orientation[1]
            roll = orientation[2]
        } else {
            val norm = sqrt(
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
        val targetY = -normalizeTilt(deltaPitch)

        val elapsed = if (lastTimestamp == 0L) 1f / 60f
            else ((event.timestamp - lastTimestamp) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastTimestamp = event.timestamp

        val alpha = (1f - exp(-elapsed * 13f)).coerceIn(0.02f, 0.48f)
        smoothX += (targetX - smoothX) * alpha
        smoothY += (targetY - smoothY) * alpha
        emit()
    }

    private fun updateShake(event: SensorEvent) {
        val elapsed = if (lastShakeTimestamp == 0L) 1f / 60f
            else ((event.timestamp - lastShakeTimestamp) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastShakeTimestamp = event.timestamp

        // Linear acceleration excludes gravity. A soft threshold ignores sensor noise,
        // while ordinary quick hand movement still produces a visible elastic response.
        val rawX = deadband(event.values[0], 0.55f) / 8.5f
        val rawY = -deadband(event.values[1], 0.55f) / 8.5f
        val alpha = (1f - exp(-elapsed * 22f)).coerceIn(0.04f, 0.72f)
        shakeX += (rawX.coerceIn(-1f, 1f) - shakeX) * alpha
        shakeY += (rawY.coerceIn(-1f, 1f) - shakeY) * alpha
        emit()
    }

    private fun deadband(value: Float, threshold: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= threshold) return 0f
        return (magnitude - threshold) * if (value < 0f) -1f else 1f
    }

    private fun emit() {
        onMotion(smoothX, smoothY, shakeX, shakeY)
    }

    private fun normalizeTilt(angle: Float): Float {
        val deadZone = 0.025f
        val magnitude = abs(angle)
        if (magnitude <= deadZone) return 0f
        val usable = (magnitude - deadZone) / (0.48f - deadZone)
        return usable.coerceIn(0f, 1f) * if (angle < 0f) -1f else 1f
    }

    private fun wrapAngle(value: Float): Float {
        var result = value
        while (result > Math.PI) result -= 2f * Math.PI.toFloat()
        while (result < -Math.PI) result += 2f * Math.PI.toFloat()
        return result
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
