/**
 * @file Battery.kt
 * The battery's time left, estimated from how fast it has been draining:
 * its level is sampled whenever the app wakes, and the drain since the
 * last unplug gives the rate.
 */
package io.github.ls6777.datadial.stats

import android.content.Context
import android.os.BatteryManager
import kotlin.math.roundToInt

/** Battery sampling and the time-left estimate. Not instantiable. */
object Battery {
    /** Samples older than this are dropped: the rate follows the recent drain. */
    private const val WINDOW_MS = 12 * 60 * 60 * 1000L

    /** The fewest percent the battery must drop before the rate is trusted. */
    private const val MIN_DROP = 2

    /** Longest estimate, in hours (a nearly idle watch could claim weeks). */
    private const val MAX_HOURS = 999

    /** One battery reading. */
    data class Sample(val at: Long, val level: Int)

    /** The battery's level in percent, and whether it is charging. */
    fun read(context: Context): Pair<Int, Boolean> {
        val manager = context.getSystemService(BatteryManager::class.java)
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to manager.isCharging
    }

    /**
     * Record the battery's level now. Charging, or a level higher than the
     * last sample (charged in between), starts the history over.
     */
    fun sample(context: Context, now: Long = System.currentTimeMillis()) {
        val (level, charging) = read(context)
        val samples = Store.batterySamples(context)
        if (charging) {
            Store.setBatterySamples(context, emptyList())
            return
        }
        val kept = if (samples.isNotEmpty() && level > samples.last().level) {
            emptyList()
        } else {
            samples.filter { now - it.at <= WINDOW_MS }
        }
        // One sample per level is enough: keep the first time each level was seen.
        val updated = if (kept.isNotEmpty() && kept.last().level == level) kept else kept + Sample(now, level)
        Store.setBatterySamples(context, updated)
    }

    /**
     * The drain rate in percent per hour from samples, or null if the
     * battery hasn't dropped [MIN_DROP] percent since the oldest of them.
     */
    fun rate(samples: List<Sample>, now: Long): Double? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val drop = first.level - last.level
        if (drop < MIN_DROP) return null
        // Up to now: the level has stayed at the last value since it was seen.
        val hours = (now - first.at) / 3_600_000.0
        return if (hours > 0) drop / hours else null
    }

    /**
     * Hours of battery left at the measured drain rate (or the last one
     * measured, until the battery has dropped enough since the last unplug),
     * or null while charging or before any rate is known.
     */
    fun hoursLeft(context: Context, now: Long = System.currentTimeMillis()): Int? {
        val (level, charging) = read(context)
        if (charging) return null
        val measured = rate(Store.batterySamples(context), now)
        if (measured != null) Store.setBatteryRate(context, measured.toFloat())
        val perHour = measured ?: Store.batteryRate(context)?.toDouble() ?: return null
        return (level / perHour).roundToInt().coerceIn(0, MAX_HOURS)
    }

    /** Hours as DataDial Stats sends them: "23h". */
    fun format(hours: Int) = "${hours}h"
}
