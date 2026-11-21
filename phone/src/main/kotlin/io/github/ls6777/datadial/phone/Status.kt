/**
 * @file Status.kt
 * The outcome of the last sync, for the app's screen.
 */
package io.github.ls6777.datadial.phone

import android.content.Context
import android.content.SharedPreferences
import io.github.ls6777.datadial.shared.Totals
import java.time.Instant
import java.time.LocalDate

/** Shared-preferences storage for the last sync. Not instantiable. */
object Status {
    private const val PREFS = "status"
    private const val AT = "at"
    private const val ERROR = "error"
    private const val DAY = "day"
    private const val SYNCED_TO = "syncedTo"
    private const val STEPS = "steps"
    private const val CALORIES = "calories"
    private const val FLOORS = "floors"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Record totals sent to the watch. */
    fun setSent(context: Context, totals: Totals) {
        prefs(context).edit()
            .putLong(AT, System.currentTimeMillis())
            .remove(ERROR)
            .putLong(DAY, totals.day.toEpochDay())
            .putLong(SYNCED_TO, totals.syncedTo.toEpochMilli())
            .putLong(STEPS, totals.steps)
            .putFloat(CALORIES, totals.calories.toFloat())
            .putFloat(FLOORS, totals.floors.toFloat())
            .apply()
    }

    /** Record a failed sync. */
    fun setError(context: Context, error: String) {
        prefs(context).edit().putLong(AT, System.currentTimeMillis()).putString(ERROR, error).apply()
    }

    /** Time of the last sync attempt, or null if there was none. */
    fun at(context: Context): Instant? = prefs(context).getLong(AT, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    /** Why the last sync failed, or null if it worked. */
    fun error(context: Context): String? = prefs(context).getString(ERROR, null)

    /** The last totals sent, or null if none were. */
    fun sent(context: Context): Totals? {
        val p = prefs(context)
        if (!p.contains(DAY)) return null
        return Totals(
            day = LocalDate.ofEpochDay(p.getLong(DAY, 0)),
            syncedTo = Instant.ofEpochMilli(p.getLong(SYNCED_TO, 0)),
            steps = p.getLong(STEPS, 0),
            calories = p.getFloat(CALORIES, 0f).toDouble(),
            floors = p.getFloat(FLOORS, 0f).toDouble(),
        )
    }
}
