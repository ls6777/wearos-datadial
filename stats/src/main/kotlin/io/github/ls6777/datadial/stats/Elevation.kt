/**
 * @file Elevation.kt
 * Elevation from the barometer, calibrated to the altitude of a location fix
 * when the app may read the location.
 */
package io.github.ls6777.datadial.stats

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.location.Location
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.math.roundToInt

/** Barometric elevation. Not instantiable. */
object Elevation {
    private const val FEET_PER_METER = 3.28084

    /** How long to wait for a fresh location fix, in milliseconds. */
    private const val LOCATION_TIMEOUT = 30_000L

    /** Location fixes older than this aren't used to calibrate, in milliseconds. */
    private const val MAX_FIX_AGE = 30 * 60_000L

    /** Fixes with a larger vertical error aren't used to calibrate, in meters. */
    private const val MAX_VERTICAL_ERROR = 30f

    /** Whether the watch has a barometer. */
    fun available(context: Context): Boolean = Sensors.available(context, Sensor.TYPE_PRESSURE)

    /** Whether the app may read the location (for calibration). */
    fun locationPermitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** One barometer reading in hPa, or null if there is no barometer or it doesn't answer. */
    suspend fun pressure(context: Context): Float? = Sensors.read(context, Sensor.TYPE_PRESSURE)

    /**
     * Current elevation above sea level. Uncalibrated, it assumes standard
     * sea-level pressure, so it is off by about 8 m per hPa of weather.
     * @return Meters, or null without a barometer
     */
    suspend fun meters(context: Context): Double? {
        val pressure = pressure(context) ?: return null
        val seaLevel = Store.seaLevelPressure(context) ?: SensorManager.PRESSURE_STANDARD_ATMOSPHERE
        return SensorManager.getAltitude(seaLevel, pressure).toDouble()
    }

    /** Elevation as DataDial shows it: whole feet ("1234ft") where the locale uses US units, else meters ("376m"). */
    fun format(meters: Double): String {
        val us = LocaleData.getMeasurementSystem(ULocale.getDefault()) == LocaleData.MeasurementSystem.US
        return if (us) "${(meters * FEET_PER_METER).roundToInt()}ft" else "${meters.roundToInt()}m"
    }

    /** The newest location the system already has, without asking for a fix. */
    fun lastLocation(context: Context): Location? {
        if (!locationPermitted(context)) return null
        val manager = context.getSystemService(LocationManager::class.java)
        return manager.allProviders
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** A fresh location fix, or null if none comes within [LOCATION_TIMEOUT]. */
    suspend fun currentLocation(context: Context): Location? {
        if (!locationPermitted(context)) return null
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) } ?: return null
        return withTimeoutOrNull(LOCATION_TIMEOUT) {
            suspendCancellableCoroutine { cont ->
                try {
                    manager.getCurrentLocation(provider, null, context.mainExecutor) { cont.resume(it) }
                } catch (e: SecurityException) {
                    cont.resume(null)
                }
            }
        }
    }

    /**
     * Calibrate the barometer to a location fix's altitude, if the fix is
     * recent, newer than the last calibration and has a good altitude.
     * @return true if calibrated
     */
    suspend fun calibrate(context: Context, location: Location?): Boolean {
        if (location == null || location.time <= Store.calibratedAt(context)) return false
        if (System.currentTimeMillis() - location.time > MAX_FIX_AGE) return false
        val msl = mslAltitude(context, location) ?: return false
        val pressure = pressure(context) ?: return false
        // Inverse of SensorManager.getAltitude: h = 44330 (1 - (p / p0)^(1 / 5.255)).
        val seaLevel = pressure / (1 - msl / 44330.0).pow(5.255)
        Store.setCalibration(context, seaLevel.toFloat(), location.time)
        Log.i(Sources.TAG, "Elevation calibrated to ${msl.roundToInt()} m, sea level $seaLevel hPa")
        return true
    }

    /** A fix's altitude above mean sea level (GPS reports it above the ellipsoid), or null if it has none. */
    private suspend fun mslAltitude(context: Context, location: Location): Double? = withContext(Dispatchers.IO) {
        if (!location.hasAltitude()) return@withContext null
        if (location.hasVerticalAccuracy() && location.verticalAccuracyMeters > MAX_VERTICAL_ERROR) {
            return@withContext null
        }
        if (!location.hasMslAltitude()) {
            try {
                AltitudeConverter().addMslAltitudeToLocation(context, location)
            } catch (e: IOException) {
                Log.w(Sources.TAG, "No sea-level altitude for the fix", e)
                return@withContext null
            }
        }
        location.mslAltitudeMeters
    }
}
