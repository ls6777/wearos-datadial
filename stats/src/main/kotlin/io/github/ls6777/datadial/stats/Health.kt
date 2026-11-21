/**
 * @file Health.kt
 * The watch's own daily steps, calories and floors from Health Services'
 * passive monitoring.
 */
package io.github.ls6777.datadial.stats

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/** Registration with Health Services. Not instantiable. */
object Health {
    /** Minimum time between flushes, in milliseconds. */
    private const val FLUSH_INTERVAL = 60_000L
    private var lastFlush = 0L

    /** The Health Services data types for the daily totals. */
    private val TYPES = setOf(DataType.STEPS_DAILY, DataType.CALORIES_DAILY, DataType.FLOORS_DAILY)

    /** Whether the app may read activity data (steps, calories and floors). */
    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Ask Health Services to send the daily totals to [PassiveDataService].
     * Registering again replaces the earlier registration.
     * @return A short status for the app's screen
     */
    suspend fun register(context: Context): String {
        if (!permitted(context)) return "no permission"
        return try {
            val client = HealthServices.getClient(context).passiveMonitoringClient
            val supported = client.getCapabilitiesAsync().await().supportedDataTypesPassiveMonitoring
            val types = TYPES.filter { it in supported }.toSet()
            if (types.isEmpty()) return "not supported on this watch"
            val config = PassiveListenerConfig.builder().setDataTypes(types).build()
            client.setPassiveListenerServiceAsync(PassiveDataService::class.java, config).await()
            "on"
        } catch (e: Exception) {
            Log.w(Sources.TAG, "Health Services registration failed", e)
            "error: ${e.message}"
        }
    }

    /** [register] in the background, for callers that aren't coroutines. */
    fun registerAsync(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch { register(app) }
    }

    /**
     * Ask Health Services to deliver the data it has batched up, so the
     * totals are current. At most once a minute.
     */
    fun flush(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (!permitted(context) || now - lastFlush < FLUSH_INTERVAL) return
        lastFlush = now
        HealthServices.getClient(context).passiveMonitoringClient.flushAsync()
    }
}

/**
 * Receives the daily totals from Health Services. The face gets them live
 * (see [Live]); they are stored to carry them over a restart, to line them
 * up with Google Health's totals, and for the app's screen.
 */
class PassiveDataService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val points = mapOf(
            Daily.STEPS to dataPoints.getData(DataType.STEPS_DAILY).map { it.getEndInstant(boot) to it.value.toDouble() },
            Daily.CALORIES to dataPoints.getData(DataType.CALORIES_DAILY).map { it.getEndInstant(boot) to it.value },
            Daily.FLOORS to dataPoints.getData(DataType.FLOORS_DAILY).map { it.getEndInstant(boot) to it.value },
        )
        val received = mutableListOf<String>()
        var restarted = false
        for ((daily, list) in points) {
            // Each point is the day's total so far, oldest first.
            for ((at, value) in list.sortedBy { it.first }) {
                restarted = Store.addDaily(this, daily, value, at) || restarted
            }
            list.maxByOrNull { it.first }?.let { received += "${daily.name.lowercase()} ${it.second.toLong()}" }
        }
        Log.i(Sources.TAG, "Health Services delivery: ${received.joinToString().ifEmpty { "nothing new" }}")
        if (restarted) {
            // The watch restarted: the face needs the new carry.
            Log.i(Sources.TAG, "Watch restarted: carrying over today's totals")
            Sources.updateDaily(this)
        }
        // The app is awake anyway: refresh the sensor stats, and ask the phone
        // for Google Health's totals now and then.
        Sources.updateSensors(this)
        Phone.requestRefresh(this)
    }
}

/** Registers with Health Services again after a reboot, which clears the registration. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Health.registerAsync(context)
            Phone.requestRefresh(context, force = true)
        }
    }
}
