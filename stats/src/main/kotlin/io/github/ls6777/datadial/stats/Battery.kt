/**
 * @file Battery.kt
 * The battery's time left: the level is read whenever the app wakes, each
 * percent it loses teaches the drain model (see BatteryModel), and the
 * model predicts the time left.
 */
package io.github.ls6777.datadial.stats

import android.content.Context
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import java.time.ZoneId
import kotlin.math.roundToInt

/** Battery readings and the time-left estimate. Not instantiable. */
object Battery {
    /** The battery's level in percent, and whether it is charging. */
    fun read(context: Context): Pair<Int, Boolean> {
        val manager = context.getSystemService(BatteryManager::class.java)
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to manager.isCharging
    }

    /**
     * How many times the watch has started: a change between readings
     * means it was off or restarted in between.
     */
    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    /**
     * Read the battery and give the reading to the drain model, which learns
     * from each percent lost. Synchronized: the data source and the Health
     * Services wake-ups can sample at the same time, and each updates the
     * stored model.
     */
    @Synchronized
    fun sample(context: Context, now: Long = System.currentTimeMillis()) {
        val (level, charging) = read(context)
        val model = Store.batteryModel(context)
        model.observe(now, level, charging, bootCount(context), ZoneId.systemDefault())?.let { seen ->
            val expected = seen.expected?.let { " (model expected %.2f%%)".format(it) }.orEmpty()
            Log.i(Sources.TAG, "Battery: %d%% in %.0f min%s, learned from %.1f h"
                .format(seen.percent, seen.hours * 60, expected, model.learnedHours()))
        }
        Store.setBatteryModel(context, model)
    }

    /**
     * Hours of battery left as the drain model predicts them, or null while
     * charging or before it has learned anything.
     */
    fun hoursLeft(context: Context, now: Long = System.currentTimeMillis()): Int? {
        val (level, charging) = read(context)
        if (charging) return null
        return Store.batteryModel(context).hoursLeft(now, level, ZoneId.systemDefault())?.roundToInt()
    }

    /** Hours as DataDial Stats sends them: "23h". */
    fun format(hours: Int) = "${hours}h"
}
