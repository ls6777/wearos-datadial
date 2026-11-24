/**
 * @file BatteryModelTest.kt
 * Unit tests for the battery drain model, against a simulated watch whose
 * true drain is known.
 */
package io.github.ls6777.datadial.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ceil

class BatteryModelTest {
    private companion object {
        val UTC: ZoneOffset = ZoneOffset.UTC
        const val MINUTE = 60_000L
        val START = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
    }

    /**
     * A watch whose battery drains at drain(hour of day, charge) percent per
     * hour, read every [every] minutes, charged to 100% (1.5 h on the
     * charger) when it gets down to [chargeBelow]. The level is the charge
     * rounded up, as a fuel gauge shows it.
     */
    private class Watch(
        val model: BatteryModel,
        val every: Int = 1,
        val drain: (hour: Int, charge: Double) -> Double,
    ) {
        var t = START
        var charge = 100.0
        var chargeBelow = 10.0
        var boot = 1
        private var minutes = 0L

        val level get() = ceil(charge).toInt().coerceIn(0, 100)

        fun hour(at: Long) = Instant.ofEpochMilli(at).atZone(UTC).hour

        /** Run for [count] minutes. */
        fun run(count: Long) {
            repeat(count.toInt()) {
                charge -= drain(hour(t), charge) / 60
                t += MINUTE
                minutes++
                if (charge <= chargeBelow) recharge()
                if (minutes % every == 0L) model.observe(t, level, false, boot, UTC)
            }
        }

        /** Run for [days] days. */
        fun runDays(days: Int) = run(days * 24 * 60L)

        /** Charge to 100% (1.5 h on the charger). */
        fun recharge() {
            model.observe(t, level, true, boot, UTC)
            t += 90 * MINUTE
            charge = 100.0
        }

        /** Turn the watch off for [count] minutes (the battery holds) and back on. */
        fun powerOff(count: Long) {
            t += count * MINUTE
            boot++
        }

        /** Run until the level is [target] or less, without charging. */
        fun runTo(target: Int) {
            chargeBelow = -1.0
            while (level > target) run(1)
        }

        /** Hours until the charge actually runs out from here. */
        fun truth(): Double {
            var c = charge
            var at = t
            var hours = 0.0
            while (c > 0) {
                c -= drain(hour(at), c) / 60
                at += MINUTE
                hours += 1.0 / 60
            }
            return hours
        }

        fun predict() = model.hoursLeft(t, level, UTC)
    }

    private fun assertClose(expected: Double, actual: Double?, fraction: Double) {
        assertNotNull(actual)
        assertTrue("expected $expected ± ${fraction * 100}%, got $actual", abs(actual!! - expected) <= expected * fraction)
    }

    @Test
    fun predictsNothingBeforeLearning() {
        assertNull(BatteryModel.empty().hoursLeft(START, 80, UTC))
    }

    @Test
    fun seededModelPredictsFromItsRate() {
        // 79.5% left (half the current percent assumed used) at 2%/h.
        assertClose(39.75, BatteryModel.seeded(2.0).hoursLeft(START, 80, UTC), 0.01)
    }

    @Test
    fun learnsSteadyDrain() {
        val watch = Watch(BatteryModel.empty()) { _, _ -> 2.0 }
        watch.runDays(3)
        watch.recharge()
        watch.runTo(50)
        assertClose(watch.truth(), watch.predict(), 0.05)
    }

    @Test
    fun learnsDayAndNight() {
        // 4%/h from 7:00 to 22:00, 1%/h overnight.
        val watch = Watch(BatteryModel.empty()) { hour, _ -> if (hour in 7..21) 4.0 else 1.0 }
        watch.runDays(21)
        // Check at every hour of a further day, recharging when low.
        val errors = (0 until 24).map {
            watch.run(60)
            abs(watch.predict()!! - watch.truth()) / watch.truth()
        }
        assertTrue("mean error ${errors.average()}", errors.average() < 0.08)
        assertTrue("worst error ${errors.max()}", errors.max() < 0.15)
    }

    @Test
    fun learnsUnevenLevels() {
        // The last 20% goes twice as fast. The watch is charged at 10%, so
        // the bottom tenth is never seen and has to lean on the one above.
        val watch = Watch(BatteryModel.empty()) { _, charge -> if (charge < 20) 4.0 else 2.0 }
        watch.runDays(21)
        watch.recharge()
        watch.runTo(40)
        assertClose(watch.truth(), watch.predict(), 0.07)
    }

    @Test
    fun followsCurrentUse() {
        var workout = false
        val watch = Watch(BatteryModel.empty()) { _, _ -> if (workout) 12.0 else 2.0 }
        watch.runDays(7)
        watch.recharge()
        watch.runTo(90)
        // An hour's workout: the time left drops more than the steady rate
        // alone would explain, without assuming the workout goes on.
        workout = true
        watch.run(60)
        workout = false
        val afterWorkout = watch.predict()!!
        val steady = watch.level / 2.0
        assertTrue("after workout $afterWorkout, steady $steady", afterWorkout < steady * 0.95)
        assertTrue("after workout $afterWorkout", afterWorkout > watch.level / 12.0 * 2)
        // A few quiet hours later it's back to the steady estimate.
        watch.run(4 * 60)
        assertClose(watch.truth(), watch.predict(), 0.07)
    }

    @Test
    fun countsDownWithinAPercent() {
        val watch = Watch(BatteryModel.empty()) { _, _ -> 1.0 }
        watch.runDays(5)
        watch.runTo(watch.level - 1) // just after a drop: the next is an hour away
        val predictions = (0 until 4).map {
            watch.predict()!!.also { watch.run(15) }
        }
        assertTrue("$predictions", predictions.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun ignoresTimeSwitchedOff() {
        val watch = Watch(BatteryModel.empty()) { _, _ -> 2.0 }
        watch.runDays(5)
        watch.recharge()
        watch.runTo(60)
        watch.run(10) // partway into a percent
        val before = watch.predict()!!
        // Off for a day: no percent lost, but that isn't a day of use. Back
        // on, the time left is what it was (less where in the percent it is,
        // which the restart forgets: at most half a percent, 15 minutes).
        watch.powerOff(24 * 60)
        watch.run(1)
        assertEquals(before, watch.predict()!!, 0.3)
        // Nor does the next drop teach the model a slow rate.
        watch.run(3 * 60)
        assertClose(watch.truth(), watch.predict(), 0.02)
    }

    @Test
    fun ignoresWidelySpacedReadings() {
        // Readings 3 hours apart can't time a 30-minute percent.
        val watch = Watch(BatteryModel.empty(), every = 180) { _, _ -> 2.0 }
        watch.runDays(3)
        assertNull(watch.predict())
    }

    @Test
    fun keepsLearningThroughText() {
        var model = BatteryModel.empty()
        val watch = Watch(model) { hour, _ -> if (hour in 7..21) 4.0 else 1.0 }
        watch.runDays(5)
        model = BatteryModel.decode(model.encode())
        assertEquals(watch.predict()!!, model.hoursLeft(watch.t, watch.level, UTC)!!, 1e-9)
        assertEquals(model.encode(), BatteryModel.decode(model.encode()).encode())
    }

    @Test
    fun unreadableTextGivesAnEmptyModel() {
        assertNull(BatteryModel.decode("garbage").hoursLeft(START, 80, UTC))
        assertNull(BatteryModel.decode(null).hoursLeft(START, 80, UTC))
    }
}
