/**
 * @file Store.kt
 * The elevation calibration and the test switch, kept in shared preferences
 * so they survive between the short-lived services that use them.
 */
package io.github.ls6777.datadial.stats

import android.content.Context
import android.content.SharedPreferences

/** Shared-preferences storage. Not instantiable. */
object Store {
    private const val PREFS = "stats"
    private const val SEA_LEVEL_PRESSURE = "seaLevelPressure"
    private const val CALIBRATED_AT = "calibratedAt"
    private const val TEST_VALUES = "testValues"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
     * Whether the elevation source shows a fixed test value instead of the
     * real one. Only the debug build can turn this on (TestValuesReceiver).
     */
    fun testValues(context: Context): Boolean = prefs(context).getBoolean(TEST_VALUES, false)

    /** Turn the fixed test value on or off. */
    fun setTestValues(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(TEST_VALUES, on).apply()
    }
}
