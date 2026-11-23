/**
 * @file Health.kt
 * Health Services' passive monitoring, used to wake the app regularly so the
 * elevation stays current and the battery is sampled for its drain rate.
 */
package io.github.ls6777.datadial.stats

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

/**
 * Registration with Health Services. Not instantiable.
 *
 * The system doesn't keep to the elevation source's update period, but
 * Health Services delivers new activity data about once a minute, which
 * wakes the app to refresh the elevation.
 */
object Health {
    /**
     * The data type whose deliveries wake the app: daily calories, which go
     * up every minute even at rest (steps only change while walking).
     */
    private val TYPE = DataType.CALORIES_DAILY

    /** Whether the app may read activity data, which Health Services needs. */
    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Ask Health Services to wake [PassiveDataService] with new data.
     * Registering again replaces the earlier registration.
     * @return A short status for the app's screen
     */
    suspend fun register(context: Context): String {
        if (!permitted(context)) return "no permission"
        return try {
            val client = HealthServices.getClient(context).passiveMonitoringClient
            val supported = client.getCapabilitiesAsync().await().supportedDataTypesPassiveMonitoring
            if (TYPE !in supported) return "not supported on this watch"
            val config = PassiveListenerConfig.builder().setDataTypes(setOf(TYPE)).build()
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
}

/** Woken by Health Services with new activity data: samples the battery and refreshes the sources. */
class PassiveDataService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        Log.i(Sources.TAG, "Health Services delivery")
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
