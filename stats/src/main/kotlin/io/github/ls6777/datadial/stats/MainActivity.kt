/**
 * @file MainActivity.kt
 * The app's one screen: asks for the permissions the elevation needs,
 * registers with Health Services (which wakes the app to refresh it),
 * calibrates the elevation and shows what it reads, and what the battery
 * drain model has learned.
 */
package io.github.ls6777.datadial.stats

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date
import kotlin.math.roundToInt

/** Permissions and status screen. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var status: TextView
    private lateinit var allow: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Round screen: keep the text inside the circle.
        val inset = (resources.displayMetrics.widthPixels * 0.15).toInt()
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
        }
        allow = Button(this).apply {
            text = getString(R.string.allow)
            setOnClickListener { askPermissions() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(inset, inset, inset, inset)
            addView(TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                text = getString(R.string.app_name)
            })
            addView(status)
            addView(allow)
        }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        scroll.requestFocus() // for the crown
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun askPermissions() {
        requestPermissions(
            arrayOf(
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            0,
        )
    }

    /** Register, show the elevation, then calibrate it (a location fix can take a while). */
    private fun refresh() = scope.launch {
        val health = Health.register(this@MainActivity)
        allow.visibility = if (Health.permitted(this@MainActivity) && Elevation.locationPermitted(this@MainActivity)) {
            View.GONE
        } else {
            View.VISIBLE
        }
        show(health, getString(R.string.calibrating))
        val calibrated = Elevation.calibrate(this@MainActivity, Elevation.currentLocation(this@MainActivity))
        Sources.updateAll(this@MainActivity)
        show(health, if (calibrated) getString(R.string.calibrated_now) else lastCalibration())
    }

    private fun lastCalibration(): String {
        val at = Store.calibratedAt(this)
        return when {
            !Elevation.available(this) -> getString(R.string.no_barometer)
            at == 0L && !Elevation.locationPermitted(this) -> getString(R.string.not_calibrated_no_location)
            at == 0L -> getString(R.string.not_calibrated)
            else -> getString(R.string.calibrated_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)))
        }
    }

    private suspend fun show(health: String, calibration: String) {
        val elevation = Elevation.meters(this)?.let(Elevation::format) ?: Sources.NO_VALUE
        status.text = getString(R.string.status, elevation, calibration, health, batteryStatus())
    }

    /** What the battery drain model has learned so far, and its estimate now. */
    private fun batteryStatus(): String {
        val now = System.currentTimeMillis()
        val (level, charging) = Battery.read(this)
        val model = Store.batteryModel(this)
        val learned = model.learnedHours().roundToInt()
        val rate = model.rateNow(now, level, ZoneId.systemDefault())
        val hours = Battery.hoursLeft(this, now)
        return when {
            rate == null -> getString(R.string.battery_learning)
            charging || hours == null -> getString(R.string.battery_charging, learned)
            else -> getString(R.string.battery_model, Battery.format(hours), rate, learned)
        }
    }
}
