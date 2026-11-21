/**
 * @file GoogleHealth.kt
 * Google Health's daily totals, read from Health Connect.
 */
package io.github.ls6777.datadial.phone

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.ls6777.datadial.shared.Totals
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/** Reads Google Health's records from Health Connect. Not instantiable. */
object GoogleHealth {
    /** Log tag, the same as DataDial Stats on the watch. */
    const val TAG = "DataDialStats"

    /**
     * Google Health's package, which Health Connect records as the origin of
     * its data. Only its records are counted, so the totals match the Google
     * Health app (Health Connect also holds steps the phone counts itself).
     */
    private const val PACKAGE = "com.fitbit.FitbitMobile"

    /** The record types read. */
    private val TYPES = listOf(StepsRecord::class, TotalCaloriesBurnedRecord::class, FloorsClimbedRecord::class)

    /** Health Connect permissions the app needs. */
    val PERMISSIONS = TYPES.map { HealthPermission.getReadPermission(it) }.toSet() +
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** Whether Health Connect is on this phone. */
    fun available(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** Whether the app has all of [PERMISSIONS]. */
    suspend fun permitted(context: Context): Boolean =
        available(context) &&
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /**
     * Today's totals from Google Health's records.
     * @return The totals. With no records yet today, they are 0 as of midnight.
     */
    suspend fun read(context: Context): Totals {
        val client = HealthConnectClient.getOrCreate(context)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val midnight = today.atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(midnight, Instant.now())
        val origin = setOf(DataOrigin(PACKAGE))
        val totals = client.aggregate(
            AggregateRequest(
                setOf(StepsRecord.COUNT_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL),
                range,
                origin,
            ),
        )
        // Google Health writes calories for every minute, so the newest
        // record shows how far its data reaches.
        val syncedTo = TYPES.mapNotNull { newestEnd(client, it, range, origin) }.maxOrNull() ?: midnight
        return Totals(
            day = today,
            syncedTo = syncedTo,
            steps = totals[StepsRecord.COUNT_TOTAL] ?: 0L,
            calories = totals[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0,
            floors = totals[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL] ?: 0.0,
        )
    }

    /** End time of the newest record of a type in the range, or null if there is none. */
    private suspend fun <T : Record> newestEnd(
        client: HealthConnectClient,
        type: KClass<T>,
        range: TimeRangeFilter,
        origin: Set<DataOrigin>,
    ): Instant? {
        val request = ReadRecordsRequest(type, range, origin, ascendingOrder = false, pageSize = 1)
        return client.readRecords(request).records.mapNotNull(::endTime).maxOrNull()
    }

    /** End time of one of the records read. */
    private fun endTime(record: Record): Instant? = when (record) {
        is StepsRecord -> record.endTime
        is TotalCaloriesBurnedRecord -> record.endTime
        is FloorsClimbedRecord -> record.endTime
        else -> null
    }
}
