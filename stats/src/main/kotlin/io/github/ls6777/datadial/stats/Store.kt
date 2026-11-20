/**
 * @file Store.kt
 * The values the data sources show, kept in shared preferences so they
 * survive between the short-lived services that produce and read them.
 */
package io.github.ls6777.datadial.stats

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** A total for the current day, from Health Services. */
enum class Daily(internal val key: String) {
    /** Calories burned today, resting plus active, in kcal. */
    CALORIES("calories"),

    /** Floors climbed today. */
    FLOORS("floors"),
}

/** Shared-preferences storage for the stats. Not instantiable. */
object Store {
    private const val PREFS = "stats"
    private const val DAY_SUFFIX = "_day"
    private const val SEA_LEVEL_PRESSURE = "seaLevelPressure"
    private const val CALIBRATED_AT = "calibratedAt"
    private const val TEST_VALUES = "testValues"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Today's total.
     * @return The total, 0 if the stored one is from an earlier day, or null
     *         if Health Services has never sent one
     */
    fun daily(context: Context, daily: Daily): Double? {
        val p = prefs(context)
        if (!p.contains(daily.key)) return null
        if (p.getLong(daily.key + DAY_SUFFIX, 0) != LocalDate.now().toEpochDay()) return 0.0
        return p.getFloat(daily.key, 0f).toDouble()
    }

    /**
     * Store a day's total.
     * @param day The day the total is for
     * @return true if the value shown for today changed
     */
    fun setDaily(context: Context, daily: Daily, value: Double, day: LocalDate): Boolean {
        val before = daily(context, daily)
        prefs(context).edit()
            .putFloat(daily.key, value.toFloat())
            .putLong(daily.key + DAY_SUFFIX, day.toEpochDay())
            .apply()
        return daily(context, daily) != before
    }

    /** Sea-level pressure in hPa from the last elevation calibration, or null if never calibrated. */
    fun seaLevelPressure(context: Context): Float? =
        prefs(context).getFloat(SEA_LEVEL_PRESSURE, 0f).takeIf { it > 0f }

    /** Time of the last elevation calibration in epoch milliseconds, or 0 if never calibrated. */
    fun calibratedAt(context: Context): Long = prefs(context).getLong(CALIBRATED_AT, 0)

    /**
     * Store an elevation calibration.
     * @param hPa Sea-level pressure that makes the barometer read the known altitude
     * @param at Time of the altitude fix, epoch milliseconds
     */
    fun setCalibration(context: Context, hPa: Float, at: Long) {
        prefs(context).edit().putFloat(SEA_LEVEL_PRESSURE, hPa).putLong(CALIBRATED_AT, at).apply()
    }

    /**
     * Whether the data sources show fixed test values instead of real ones.
     * Only the debug build can turn this on (TestValuesReceiver).
     */
    fun testValues(context: Context): Boolean = prefs(context).getBoolean(TEST_VALUES, false)

    /** Turn the fixed test values on or off. */
    fun setTestValues(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(TEST_VALUES, on).apply()
    }
}
