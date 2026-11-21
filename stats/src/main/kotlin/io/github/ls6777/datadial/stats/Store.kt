/**
 * @file Store.kt
 * The values the data sources show, kept in shared preferences so they
 * survive between the short-lived services that produce and read them.
 */
package io.github.ls6777.datadial.stats

import android.content.Context
import android.content.SharedPreferences
import io.github.ls6777.datadial.shared.Totals
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A total for the current day. */
enum class Daily(internal val key: String) {
    /** Steps today. */
    STEPS("steps"),

    /** Calories burned today, resting plus active, in kcal. */
    CALORIES("calories"),

    /** Floors climbed today. */
    FLOORS("floors"),
    ;

    /** This total in Google Health's totals. */
    fun of(totals: Totals): Double = when (this) {
        STEPS -> totals.steps.toDouble()
        CALORIES -> totals.calories
        FLOORS -> totals.floors
    }
}

/** Shared-preferences storage for the stats. Not instantiable. */
object Store {
    private const val PREFS = "stats"
    private const val DAY_SUFFIX = "_day"
    private const val CARRY_SUFFIX = "_carry"
    private const val SAMPLES_SUFFIX = "_samples"
    private const val PHONE = "phone_"
    private const val PHONE_DAY = "phone_day"
    private const val PHONE_SYNCED_TO = "phone_syncedTo"
    private const val PHONE_RECEIVED = "phone_received"
    private const val REFRESH_ASKED = "refreshAsked"
    private const val SEA_LEVEL_PRESSURE = "seaLevelPressure"
    private const val CALIBRATED_AT = "calibratedAt"
    private const val TEST_VALUES = "testValues"

    /** How long the watch's own samples are kept: well past Google Health's sync delay. */
    private const val SAMPLE_HOURS = 6L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun today(): Long = LocalDate.now().toEpochDay()

    /**
     * The watch's own total for today (Health Services), as last delivered.
     * It starts again from 0 when the watch restarts; see [carry].
     * @return The total, 0 if the stored one is from an earlier day, or null
     *         if Health Services has never sent one
     */
    fun daily(context: Context, daily: Daily): Double? {
        val p = prefs(context)
        if (!p.contains(daily.key)) return null
        if (p.getLong(daily.key + DAY_SUFFIX, 0) != today()) return 0.0
        return p.getFloat(daily.key, 0f).toDouble()
    }

    /**
     * What the watch counted today before it last restarted. Health Services'
     * daily totals start again from 0 after a restart, so this is added to them.
     */
    fun carry(context: Context, daily: Daily): Double {
        val p = prefs(context)
        if (p.getLong(daily.key + DAY_SUFFIX, 0) != today()) return 0.0
        return p.getFloat(daily.key + CARRY_SUFFIX, 0f).toDouble()
    }

    /**
     * Store a total Health Services delivered. A total lower than the last
     * one on the same day means the watch restarted: the last one is carried
     * over (see [carry]).
     * @param at The time the total is for
     * @return true if the carry changed
     */
    fun addDaily(context: Context, daily: Daily, value: Double, at: Instant): Boolean {
        val p = prefs(context)
        val day = at.atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        val storedDay = p.getLong(daily.key + DAY_SUFFIX, 0)
        if (day < storedDay) return false // late delivery for an earlier day
        val sameDay = p.contains(daily.key) && day == storedDay
        val last = if (sameDay) p.getFloat(daily.key, 0f).toDouble() else 0.0
        var carry = if (sameDay) p.getFloat(daily.key + CARRY_SUFFIX, 0f).toDouble() else 0.0
        val restarted = value < last - 0.5
        if (restarted) carry += last
        val samples = (if (sameDay) samples(p, daily) else emptyList()) + Sample(at.toEpochMilli(), value + carry)
        val since = at.toEpochMilli() - SAMPLE_HOURS * 3_600_000
        p.edit()
            .putFloat(daily.key, value.toFloat())
            .putLong(daily.key + DAY_SUFFIX, day)
            .putFloat(daily.key + CARRY_SUFFIX, carry.toFloat())
            .putString(daily.key + SAMPLES_SUFFIX, samples.filter { it.at >= since }.joinToString(",") { "${it.at}:${it.value}" })
            .apply()
        return restarted
    }

    /** A total including the carry, at a time. */
    private data class Sample(val at: Long, val value: Double)

    private fun samples(p: SharedPreferences, daily: Daily): List<Sample> =
        p.getString(daily.key + SAMPLES_SUFFIX, "").orEmpty().split(",").mapNotNull { entry ->
            val (at, value) = entry.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            Sample(at.toLongOrNull() ?: return@mapNotNull null, value.toDoubleOrNull() ?: return@mapNotNull null)
        }

    /**
     * What the watch had counted today (including the carry) at a time: the
     * newest sample at or before it, or the oldest one kept if all are later.
     * @return The total, or null if there are no samples today
     */
    fun countedAt(context: Context, daily: Daily, at: Instant): Double? {
        val p = prefs(context)
        if (p.getLong(daily.key + DAY_SUFFIX, 0) != today()) return null
        val samples = samples(p, daily)
        return (samples.filter { it.at <= at.toEpochMilli() }.maxByOrNull { it.at } ?: samples.minByOrNull { it.at })?.value
    }

    /** Store Google Health's totals from the phone. */
    fun setPhone(context: Context, totals: Totals) {
        prefs(context).edit()
            .putLong(PHONE_DAY, totals.day.toEpochDay())
            .putLong(PHONE_SYNCED_TO, totals.syncedTo.toEpochMilli())
            .putLong(PHONE_RECEIVED, System.currentTimeMillis())
            .apply {
                for (daily in Daily.entries) putFloat(PHONE + daily.key, daily.of(totals).toFloat())
            }
            .apply()
    }

    /** Google Health's totals for today from the phone, or null if none came today. */
    fun phone(context: Context): Totals? {
        val p = prefs(context)
        if (!p.contains(PHONE_DAY) || p.getLong(PHONE_DAY, 0) != today()) return null
        return Totals(
            day = LocalDate.ofEpochDay(p.getLong(PHONE_DAY, 0)),
            syncedTo = Instant.ofEpochMilli(p.getLong(PHONE_SYNCED_TO, 0)),
            steps = p.getFloat(PHONE + Daily.STEPS.key, 0f).toLong(),
            calories = p.getFloat(PHONE + Daily.CALORIES.key, 0f).toDouble(),
            floors = p.getFloat(PHONE + Daily.FLOORS.key, 0f).toDouble(),
        )
    }

    /** When the last totals came from the phone, or null if none ever did. */
    fun phoneReceived(context: Context): Instant? =
        prefs(context).getLong(PHONE_RECEIVED, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    /** When the watch last asked the phone for new totals, epoch milliseconds (0 if never). */
    fun refreshAsked(context: Context): Long = prefs(context).getLong(REFRESH_ASKED, 0)

    /** Record that the watch asked the phone for new totals. */
    fun setRefreshAsked(context: Context, at: Long) {
        prefs(context).edit().putLong(REFRESH_ASKED, at).apply()
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
