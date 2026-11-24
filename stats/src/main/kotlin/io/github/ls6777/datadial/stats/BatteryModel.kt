/**
 * @file BatteryModel.kt
 * A model of how fast this watch's battery drains, learned from its own use
 * and refined with every percent it loses, and the time left it predicts.
 */
package io.github.ls6777.datadial.stats

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * How fast the battery drains, learned from the watch's own use.
 *
 * The drain rate in percent per hour is the product of three parts:
 * - **The hour of the day:** the usual rate in each of the day's 24 hours
 *   (slow overnight, faster through the day). Each hour leans on the
 *   overall average until it has data of its own.
 * - **The battery level:** how much faster or slower than usual each tenth
 *   of the battery goes. The fuel gauge's percentages aren't all the same
 *   size; the top and the bottom often go faster or slower. 1 until learned.
 * - **What the watch is doing now:** how much faster or slower than the
 *   model the last hour or so has drained (a workout, say). In the
 *   forecast it fades back to 1 over the next couple of hours.
 *
 * Each percent the battery loses while DataDial Stats watches is one
 * observation, which updates all three. Older observations count for less
 * and less (half as much every [HALF_LIFE_DAYS] days), so the model follows
 * changes in use and the battery's aging. Until it has learned anything it
 * predicts from the rate an earlier version measured, if it has a plausible
 * one, or nothing; after its first hour or so it predicts from what it has
 * learned alone, and gets better from there.
 *
 * Pure Kotlin, no Android: [Battery] reads the battery and stores the
 * model's state ([encode], [decode]) between the app's short runs.
 */
class BatteryModel private constructor(
    /** Percent lost in each hour of the day (divided by the level factor at the time), decayed. */
    private val hourPercent: DoubleArray,
    /** Hours observed in each hour of the day, decayed. */
    private val hourHours: DoubleArray,
    /** Percent lost in each tenth of the battery, decayed. */
    private val bandObserved: DoubleArray,
    /** Percent the hour-of-day rates expected over the same observations, decayed. */
    private val bandExpected: DoubleArray,
    /** When the sums were last decayed, epoch milliseconds (0 before any observation). */
    private var decayedAt: Long,
    /** How much faster than the model the watch has been draining lately. */
    private var recent: Double,
    /** When the battery reached its current level, if that was seen closely enough. */
    private var anchor: Anchor?,
    /** The last reading, or null since charging or before any. */
    private var last: Reading?,
    /**
     * The drain rate an earlier version measured, in percent per hour, used
     * only until the model has learned from real use (0 if none). Kept
     * apart from the sums, so a poor one can't skew what's learned.
     */
    private val startRate: Double,
) {
    /** A battery reading: [level] percent at [at], epoch milliseconds, in the watch's [boot]th run. */
    private data class Reading(val at: Long, val level: Int, val boot: Int)

    /**
     * The battery's estimated arrival at [level], at [at] give or take
     * [uncertainty] (both epoch milliseconds): the midpoint between the
     * readings either side of the drop, and half the gap between them.
     */
    private data class Anchor(val at: Long, val level: Int, val uncertainty: Long)

    /**
     * One learned observation, for the log.
     * @property percent Percent lost
     * @property hours Over this many hours
     * @property expected Percent the model expected over that time, or null before it had learned anything
     */
    data class Observation(val percent: Int, val hours: Double, val expected: Double?)

    /**
     * Record a battery reading, and learn from it if it completes an
     * observation: the time from one closely seen drop in level to the next.
     * A drop is closely seen when the readings either side of it are close
     * together compared with the time since the last drop.
     *
     * Charging, a restart (the watch turned off, or restarted after an
     * update), a level that went up or a clock that went back ends the
     * observation in progress: the time the watch was off, say, isn't time
     * it took to use a percent. What the model has learned is kept.
     * @param now Time of the reading, epoch milliseconds
     * @param level Battery level in percent
     * @param charging Whether the watch is charging, which ends the observations until it's unplugged
     * @param boot Which run of the watch this is (Android's boot count): a change means it restarted
     * @param zone Time zone for the hour of the day
     * @return The observation learned from, or null if none
     */
    fun observe(now: Long, level: Int, charging: Boolean, boot: Int, zone: ZoneId): Observation? {
        val previous = last
        if (charging) {
            last = null
            anchor = null
            recent = 1.0
            return null
        }
        last = Reading(now, level, boot)
        if (previous == null) return null
        if (boot != previous.boot || now <= previous.at || level > previous.level) {
            // Restarted, the clock went back, or the battery was charged in between.
            anchor = null
            recent = 1.0
            return null
        }
        if (level == previous.level) return null
        // The level dropped somewhere between the previous reading and now.
        val gap = now - previous.at
        val start = anchor
        val end = Anchor(previous.at + gap / 2, level, gap / 2)
        anchor = end
        if (start == null) return null
        val duration = end.at - start.at
        if (duration <= 0 || start.uncertainty + end.uncertainty > duration * MAX_TIMING_ERROR) return null
        return learn(start.at, end.at, start.level - level, (start.level + level) / 2.0, zone)
    }

    /**
     * Hours until the battery runs out, from the model: the percent left
     * used up hour by hour at the rate for each hour of the day, the level
     * and (fading) the current use.
     *
     * Within a percent the time left keeps counting down: the part of the
     * current percent used since the battery reached it is taken off, as
     * far as the model expected to use it.
     * @param now Current time, epoch milliseconds
     * @param level Current battery level in percent
     * @param zone Time zone for the hour of the day
     * @return Hours left (at most [MAX_HOURS]); before the model has learned
     *   anything, at the earlier version's rate, or null without one
     */
    fun hoursLeft(now: Long, level: Int, zone: ZoneId): Double? {
        if (!trained()) {
            return startRate.takeIf { it > 0 }?.let { min((level - 0.5).coerceAtLeast(0.0) / it, MAX_HOURS) }
        }
        var remaining = level.toDouble()
        var current = recent
        val open = anchor?.takeIf { it.level == level && it.at < now }
        if (open != null) {
            val used = expectedPercent(open.at, now, level.toDouble(), zone)
            // Slower than expected: no drop yet where the model expected one.
            if (used > 1.0) current = min(current, 1.0 / used)
            remaining -= (used * current).coerceIn(0.0, MAX_USED_OF_PERCENT)
        } else {
            remaining -= 0.5
        }
        var t = now
        var hours = 0.0
        while (remaining > 0 && hours < MAX_HOURS) {
            val zoned = Instant.ofEpochMilli(t).atZone(zone)
            val nextHour = zoned.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val stepMs = min(STEP_MS, nextHour - t)
            val step = stepMs / MS_PER_HOUR
            val boost = 1.0 + (current - 1.0) * exp(-hours / RECENT_FADE_HOURS)
            val rate = hourRate(zoned.hour) * levelFactor(band(remaining)) * boost
            if (rate <= 0) return MAX_HOURS
            if (rate * step >= remaining) return hours + remaining / rate
            remaining -= rate * step
            hours += step
            t += stepMs
        }
        return min(hours, MAX_HOURS)
    }

    /** Hours of use the model has learned from (decayed: older hours count for less). */
    fun learnedHours(): Double = hourHours.sum()

    /**
     * The drain rate the model expects right now, in percent per hour;
     * before it has learned anything, the earlier version's rate, or null
     * without one.
     */
    fun rateNow(now: Long, level: Int, zone: ZoneId): Double? {
        if (!trained()) return startRate.takeIf { it > 0 }
        return hourRate(Instant.ofEpochMilli(now).atZone(zone).hour) * levelFactor(band(level.toDouble())) * recent
    }

    /** The model's state as text, for [decode]. */
    fun encode(): String = listOf(
        VERSION,
        hourPercent.joinToString(","),
        hourHours.joinToString(","),
        bandObserved.joinToString(","),
        bandExpected.joinToString(","),
        decayedAt.toString(),
        recent.toString(),
        anchor?.let { "${it.at},${it.level},${it.uncertainty}" }.orEmpty(),
        last?.let { "${it.at},${it.level},${it.boot}" }.orEmpty(),
        startRate.toString(),
    ).joinToString("|")

    /** Learn from [percent] lost between [start] and [end] around [level], updating all three parts. */
    private fun learn(start: Long, end: Long, percent: Int, level: Double, zone: ZoneId): Observation {
        decay(end)
        val wasTrained = trained()
        val segments = hourSegments(start, end, zone)
        val hours = (end - start) / MS_PER_HOUR
        val band = band(level)
        val factor = levelFactor(band)
        // What the hour-of-day rates alone expected, then with the level.
        val base = segments.sumOf { (hour, h) -> hourRate(hour) * h }
        val expected = base * factor
        if (wasTrained && expected > 0) {
            val weight = 1.0 - exp(-hours / RECENT_LEARN_HOURS)
            recent = (recent + weight * (percent / expected - recent)).coerceIn(RECENT_MIN, RECENT_MAX)
            bandObserved[band] += percent.toDouble()
            bandExpected[band] += base
        }
        for ((hour, h) in segments) {
            hourPercent[hour] += percent * (h / hours) / factor
            hourHours[hour] += h
        }
        return Observation(percent, hours, if (wasTrained) expected else null)
    }

    /** Whether the model has learned enough to predict. */
    private fun trained() = learnedHours() >= MIN_LEARNED_HOURS

    /** Make every sum count for less, by the time since they were last decayed. */
    private fun decay(now: Long) {
        if (decayedAt in 1 until now) {
            val k = 0.5.pow((now - decayedAt) / (HALF_LIFE_DAYS * 24 * MS_PER_HOUR))
            for (sums in listOf(hourPercent, hourHours, bandObserved, bandExpected)) {
                for (i in sums.indices) sums[i] *= k
            }
        }
        decayedAt = now
    }

    /** The average rate over everything learned, in percent per hour (level-adjusted). */
    private fun overallRate(): Double {
        val hours = hourHours.sum()
        return if (hours > 0) hourPercent.sum() / hours else 0.0
    }

    /** The usual rate in an hour of the day, leaning on the overall rate until it has its own data. */
    private fun hourRate(hour: Int): Double =
        (hourPercent[hour] + HOUR_PRIOR_HOURS * overallRate()) / (hourHours[hour] + HOUR_PRIOR_HOURS)

    /**
     * How much faster than the hour-of-day rates a tenth of the battery
     * drains, scaled so the factors average 1 over everything observed.
     *
     * Until a tenth has data of its own it leans on its neighbor toward
     * the middle of the battery (the middle two lean on 1): the bottom
     * tenth, which the watch is usually charged before reaching, drains
     * like the one above it rather than like an average one.
     */
    private fun levelFactor(band: Int): Double {
        val raw = DoubleArray(BANDS)
        fun estimate(b: Int, prior: Double) =
            (bandObserved[b] + BAND_PRIOR_PERCENT * prior) / (bandExpected[b] + BAND_PRIOR_PERCENT)
        val middle = BANDS / 2
        raw[middle - 1] = estimate(middle - 1, 1.0)
        raw[middle] = estimate(middle, 1.0)
        for (b in middle - 2 downTo 0) raw[b] = estimate(b, raw[b + 1])
        for (b in middle + 1 until BANDS) raw[b] = estimate(b, raw[b - 1])
        var weighted = 0.0
        var weights = 0.0
        for (b in 0 until BANDS) {
            val w = bandExpected[b] + BAND_PRIOR_PERCENT
            weighted += w * raw[b]
            weights += w
        }
        return raw[band] / (weighted / weights)
    }

    /** The model's expected percent lost from [start] to [end] at [level], without the current use. */
    private fun expectedPercent(start: Long, end: Long, level: Double, zone: ZoneId): Double =
        hourSegments(start, end, zone).sumOf { (hour, h) -> hourRate(hour) * h } * levelFactor(band(level))

    /** The time from [start] to [end] split at the hours of the day: (hour, hours in it). */
    private fun hourSegments(start: Long, end: Long, zone: ZoneId): List<Pair<Int, Double>> {
        val segments = mutableListOf<Pair<Int, Double>>()
        var t = start
        while (t < end) {
            val zoned = Instant.ofEpochMilli(t).atZone(zone)
            val next = min(end, zoned.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli())
            segments += zoned.hour to (next - t) / MS_PER_HOUR
            t = next
        }
        return segments
    }

    companion object {
        /** Format version of [encode]'s text. */
        private const val VERSION = "2"
        /** 1.1.0's format: no [startRate]; the earlier version's rate went into the sums. */
        private const val VERSION_1 = "1"
        /** An earlier version's rate is used only if it's in this range, in percent per hour. */
        private const val MIN_START_RATE = 0.1
        private const val MAX_START_RATE = 30.0
        private const val HOURS = 24
        /** The battery's tenths: 0-9%, 10-19%, ... 90-100%. */
        private const val BANDS = 10
        /** Observations lose half their weight every this many days. */
        const val HALF_LIFE_DAYS = 14.0
        /** An hour of the day counts as this many hours at the overall rate until it has data. */
        private const val HOUR_PRIOR_HOURS = 1.0
        /** A tenth of the battery counts as this many percent at factor 1 until it has data. */
        private const val BAND_PRIOR_PERCENT = 3.0
        /** The current use follows observations over about this many hours. */
        private const val RECENT_LEARN_HOURS = 1.0
        /** In the forecast, the current use fades to the usual over about this many hours. */
        private const val RECENT_FADE_HOURS = 2.0
        private const val RECENT_MIN = 0.2
        private const val RECENT_MAX = 5.0
        /**
         * An observation is used only if its two drops' timing is uncertain
         * by at most this fraction of its length (the readings either side
         * of each drop are close together).
         */
        private const val MAX_TIMING_ERROR = 0.25
        /** Hours of use to learn from before predicting. */
        private const val MIN_LEARNED_HOURS = 0.5
        /** At most this much of the current percent counts as used. */
        private const val MAX_USED_OF_PERCENT = 0.95
        /** Longest prediction, in hours (a nearly idle watch could claim weeks). */
        const val MAX_HOURS = 999.0
        /** The forecast's longest step: the current use's fade changes within an hour. */
        private const val STEP_MS = 15 * 60 * 1000L
        private const val MS_PER_HOUR = 3_600_000.0

        /** A model that hasn't learned anything. */
        fun empty() = starting(0.0)

        /**
         * A model that hasn't learned anything, predicting at an earlier
         * version's drain rate until it has. A rate no watch drains at
         * (outside [MIN_START_RATE] to [MAX_START_RATE], say from a battery
         * level set by hand on the emulator) is ignored.
         * @param perHour Drain rate in percent per hour
         */
        fun starting(perHour: Double) = BatteryModel(
            DoubleArray(HOURS), DoubleArray(HOURS), DoubleArray(BANDS), DoubleArray(BANDS),
            0, 1.0, null, null,
            perHour.takeIf { it in MIN_START_RATE..MAX_START_RATE } ?: 0.0,
        )

        /**
         * The model from [encode]'s text, or an empty one if there's none or
         * it can't be read. 1.1.0's text is read too: if it hadn't learned
         * anything yet, the earlier version's rate it was given moves out of
         * the sums to be the start rate (if plausible).
         */
        fun decode(text: String?): BatteryModel {
            val parts = text?.split("|") ?: return empty()
            return try {
                when {
                    parts.size == 10 && parts[0] == VERSION -> parse(parts, parts[9].toDouble())
                    parts.size == 9 && parts[0] == VERSION_1 -> fromVersion1(parse(parts, 0.0))
                    else -> empty()
                }
            } catch (e: RuntimeException) {
                empty()
            }
        }

        /** The model in [parts] of [encode]'s text, with [startRate]. */
        private fun parse(parts: List<String>, startRate: Double): BatteryModel {
            fun doubles(part: String, size: Int) =
                part.split(",").map(String::toDouble).toDoubleArray().also { require(it.size == size) }
            fun longs(part: String) = part.takeIf(String::isNotEmpty)?.split(",")?.map(String::toLong)
            return BatteryModel(
                doubles(parts[1], HOURS), doubles(parts[2], HOURS),
                doubles(parts[3], BANDS), doubles(parts[4], BANDS),
                parts[5].toLong(), parts[6].toDouble(),
                longs(parts[7])?.let { Anchor(it[0], it[1].toInt(), it[2]) },
                longs(parts[8])?.let { Reading(it[0], it[1].toInt(), it[2].toInt()) },
                startRate,
            )
        }

        /**
         * A 1.1.0 model. One that has only the earlier version's rate in its
         * sums (an hour spread evenly, nothing learned) starts over from
         * that rate; one that has learned is kept as it is.
         */
        private fun fromVersion1(model: BatteryModel): BatteryModel {
            val onlyStart = model.hourHours.all { abs(it - 1.0 / HOURS) < 1e-9 } &&
                model.bandExpected.all { it == 0.0 }
            if (!onlyStart) return model
            return starting(model.hourPercent.sum()).also {
                it.anchor = model.anchor
                it.last = model.last
            }
        }

        /** The tenth of the battery a level is in. */
        private fun band(level: Double) = (level / 10).toInt().coerceIn(0, BANDS - 1)
    }
}
