/**
 * @file Health.kt
 * Daily calories and floors from Health Services' passive monitoring.
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
import java.time.ZoneId

/** Registration with Health Services. Not instantiable. */
object Health {
    /** Minimum time between flushes, in milliseconds. */
    private const val FLUSH_INTERVAL = 60_000L
    private var lastFlush = 0L

    /** The Health Services data type for each daily total. */
    internal val TYPES = mapOf(
        Daily.CALORIES to DataType.CALORIES_DAILY,
        Daily.FLOORS to DataType.FLOORS_DAILY,
    )

    /** Whether the app may read activity data (calories and floors). */
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
            val types = TYPES.values.filter { it in supported }.toSet()
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

/** Receives the daily totals from Health Services and updates the data sources. */
class PassiveDataService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val received = mutableListOf<String>()
        for ((daily, type) in Health.TYPES) {
            // Each point is the day's total so far: keep the newest.
            val point = dataPoints.getData(type).maxByOrNull { it.endDurationFromBoot } ?: continue
            val day = point.getEndInstant(boot).atZone(ZoneId.systemDefault()).toLocalDate()
            received += "${daily.name.lowercase()} ${point.value.toInt()}"
            // For the app's screen: the face gets live values (see CaloriesSource).
            Store.setDaily(this, daily, point.value, day)
        }
        Log.i(Sources.TAG, "Health Services delivery: ${received.joinToString().ifEmpty { "nothing new" }}")
        // The app is awake anyway: refresh the sensor stats too.
        Sources.updateSensors(this)
    }
}

/** Registers with Health Services again after a reboot, which clears the registration. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Health.registerAsync(context)
        }
    }
}
