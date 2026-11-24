/**
 * @file Store.kt
 * The elevation calibration, the battery drain model, and the
 * test switch, kept in shared preferences so they survive between the
 * short-lived services that use them.
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
    private const val BATTERY_MODEL = "batteryModel"
    /** The earlier versions' battery samples, removed with the first model stored. */
    private const val OLD_BATTERY_SAMPLES = "batterySamples"
    /** The earlier versions' drain rate, the model's starting point. */
    private const val OLD_BATTERY_RATE = "batteryRate"

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

    /**
     * The battery drain model. Before there is one, it starts from the
     * drain rate the earlier versions measured, if any, so the time left
     * keeps showing after an update.
     */
    fun batteryModel(context: Context): BatteryModel {
        val prefs = prefs(context)
        prefs.getString(BATTERY_MODEL, null)?.let { return BatteryModel.decode(it) }
        val rate = prefs.getFloat(OLD_BATTERY_RATE, 0f)
        return if (rate > 0f) BatteryModel.seeded(rate.toDouble()) else BatteryModel.empty()
    }

    /** Store the battery drain model, dropping the earlier versions' samples and rate. */
    fun setBatteryModel(context: Context, model: BatteryModel) {
        prefs(context).edit()
            .putString(BATTERY_MODEL, model.encode())
            .remove(OLD_BATTERY_SAMPLES)
            .remove(OLD_BATTERY_RATE)
            .apply()
    }
}
