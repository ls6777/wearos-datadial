/**
 * @file Totals.kt
 * Google Health's daily totals as the phone reads them from Health Connect,
 * and how they travel to the watch over the Wearable Data Layer.
 */
package io.github.ls6777.datadial.shared

import com.google.android.gms.wearable.DataMap
import java.time.Instant
import java.time.LocalDate

/**
 * Google Health's totals for one day, as of [syncedTo].
 * @property day The day the totals are for (the phone's local date)
 * @property syncedTo End of the newest record Google Health had written to
 *           Health Connect: the totals include everything up to this time
 * @property steps Steps today
 * @property calories Calories burned today, resting plus active, in kcal
 * @property floors Floors climbed today
 */
data class Totals(
    val day: LocalDate,
    val syncedTo: Instant,
    val steps: Long,
    val calories: Double,
    val floors: Double,
) {
    /** Write the totals into a data item's map. */
    fun writeTo(map: DataMap) {
        map.putLong(KEY_DAY, day.toEpochDay())
        map.putLong(KEY_SYNCED_TO, syncedTo.toEpochMilli())
        map.putLong(KEY_STEPS, steps)
        map.putDouble(KEY_CALORIES, calories)
        map.putDouble(KEY_FLOORS, floors)
    }

    companion object {
        /** Data item path of the totals the phone sends. */
        const val PATH = "/datadial/totals"

        /** Message path the watch sends to ask the phone for new totals. */
        const val REFRESH_PATH = "/datadial/refresh"

        private const val KEY_DAY = "day"
        private const val KEY_SYNCED_TO = "syncedTo"
        private const val KEY_STEPS = "steps"
        private const val KEY_CALORIES = "calories"
        private const val KEY_FLOORS = "floors"

        /**
         * Read totals from a data item's map.
         * @return The totals, or null if the map doesn't hold them
         */
        fun from(map: DataMap): Totals? {
            if (!map.containsKey(KEY_DAY) || !map.containsKey(KEY_SYNCED_TO)) return null
            return Totals(
                day = LocalDate.ofEpochDay(map.getLong(KEY_DAY)),
                syncedTo = Instant.ofEpochMilli(map.getLong(KEY_SYNCED_TO)),
                steps = map.getLong(KEY_STEPS),
                calories = map.getDouble(KEY_CALORIES),
                floors = map.getDouble(KEY_FLOORS),
            )
        }
    }
}
