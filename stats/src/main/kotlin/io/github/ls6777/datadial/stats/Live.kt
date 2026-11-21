/**
 * @file Live.kt
 * The steps, calories and floors the face shows: Google Health's totals from
 * the phone, kept current between syncs with what the watch counts itself.
 */
package io.github.ls6777.datadial.stats

import androidx.wear.protolayout.expression.DynamicBuilders.DynamicFloat
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.expression.PlatformHealthSources
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds the live values. Not instantiable.
 *
 * The face shows Google Health's total as of its last sync plus what the
 * watch has counted since (Health Services' daily total, which the system
 * keeps current on the face). Each new sync puts the face back on Google
 * Health's number. Health Services' totals start again from 0 when the watch
 * restarts, so what the watch counted before is carried over. Without totals
 * from the phone today, the face shows the watch's own count.
 */
object Live {
    /**
     * What to add to the watch's daily total, and the lowest value to show.
     * @property offset Added to Health Services' daily total
     * @property floor Google Health's total: the face never shows less
     */
    private data class Plan(val offset: Double, val floor: Double)

    private fun plan(context: Context, daily: Daily): Plan {
        val carry = Store.carry(context, daily)
        val phone = Store.phone(context) ?: return Plan(carry, 0.0)
        val total = daily.of(phone)
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val counted = when {
            !phone.syncedTo.isAfter(midnight) -> 0.0
            else -> Store.countedAt(context, daily, phone.syncedTo) ?: ((Store.daily(context, daily) ?: 0.0) + carry)
        }
        return Plan(carry + total - counted, total)
    }

    /** Health Services' daily total, kept current by the system. */
    private fun watchTotal(daily: Daily): DynamicFloat = when (daily) {
        Daily.STEPS -> PlatformHealthSources.dailySteps().asFloat()
        Daily.CALORIES -> PlatformHealthSources.dailyCalories()
        Daily.FLOORS -> PlatformHealthSources.dailyFloors()
    }

    /**
     * The value for the face, kept current by the system. At midnight it
     * switches to the watch's own count until the phone sends the new day's
     * totals.
     */
    fun value(context: Context, daily: Daily): DynamicFloat {
        val plan = plan(context, daily)
        val watch = watchTotal(daily)
        val combined = watch.plus(plan.offset.toFloat())
        val atLeastFloor = DynamicFloat.onCondition(combined.lt(plan.floor.toFloat()))
            .use(plan.floor.toFloat())
            .elseUse(combined)
        val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val beforeMidnight = DynamicInstant.platformTimeWithSecondsPrecision()
            .durationUntil(DynamicInstant.withSecondsPrecision(midnight))
            .toIntSeconds()
            .gt(0)
        return DynamicFloat.onCondition(beforeMidnight).use(atLeastFloor).elseUse(watch)
    }

    /**
     * The value as of the watch's last delivered total, for logs and the
     * app's screen.
     * @return The value, or null without any total from the watch or phone
     */
    fun now(context: Context, daily: Daily): Double? {
        val watch = Store.daily(context, daily)
        if (watch == null && Store.phone(context) == null) return null
        val plan = plan(context, daily)
        return maxOf((watch ?: 0.0) + plan.offset, plan.floor)
    }

    /** How the value is made up, for the log. */
    fun describe(context: Context, daily: Daily): String {
        val plan = plan(context, daily)
        val phone = Store.phone(context)
        val source = if (phone == null) "no Google Health totals today" else "Google Health ${daily.of(phone).toLong()} up to ${phone.syncedTo}"
        return "watch total + ${plan.offset.toLong()} ($source, carry ${Store.carry(context, daily).toLong()})"
    }
}
