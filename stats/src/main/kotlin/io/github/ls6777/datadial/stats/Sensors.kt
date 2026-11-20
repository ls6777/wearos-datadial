/**
 * @file Sensors.kt
 * One-shot readings from the watch's environment sensors.
 */
package io.github.ls6777.datadial.stats

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Sensor access. Not instantiable. */
object Sensors {
    /** How long to wait for a reading, in milliseconds. */
    private const val TIMEOUT = 5_000L

    /** Whether the watch has a sensor of this type (a Sensor.TYPE_ constant). */
    fun available(context: Context, type: Int): Boolean =
        context.getSystemService(SensorManager::class.java).getDefaultSensor(type) != null

    /**
     * One reading: the sensor's first value, or null if the watch has no
     * such sensor or it doesn't answer within [TIMEOUT]. On-change sensors
     * report their current value on registration.
     */
    suspend fun read(context: Context, type: Int): Float? = withTimeoutOrNull(TIMEOUT) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(type) ?: return@withTimeoutOrNull null
        suspendCancellableCoroutine { cont ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    manager.unregisterListener(this)
                    if (cont.isActive) cont.resume(event.values[0])
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            cont.invokeOnCancellation { manager.unregisterListener(listener) }
        }
    }
}
