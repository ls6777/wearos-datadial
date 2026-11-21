/**
 * @file Sources.kt
 * The complication data source for elevation, drawn by DataDial like its own
 * stats.
 */
package io.github.ls6777.datadial.stats

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Helpers for the data sources. Not instantiable. */
object Sources {
    /** Log tag for the whole app: `adb logcat -s DataDialStats` (or `./build.sh statslog`). */
    const val TAG = "DataDialStats"

    /** Shown for a value that isn't available, as in DataDial. */
    const val NO_VALUE = "--"

    /** Every data source in the app. */
    val ALL = listOf(ElevationSource::class.java)

    /** Ask the system to request new data from every complication using a source. */
    fun update(context: Context, source: Class<out ComplicationDataSourceService>) {
        ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, source)).requestUpdateAll()
    }

    /** [update] for every source in the app. */
    fun updateAll(context: Context) = ALL.forEach { update(context, it) }

    /**
     * The sources that read a sensor (elevation). The system asks for them
     * rarely (it doesn't keep to their update period), so they are also
     * refreshed whenever Health Services wakes the app with new data.
     */
    fun updateSensors(context: Context) {
        update(context, ElevationSource::class.java)
    }

    /** Log that the system asked a source for its value, and what it got. */
    internal fun logRequest(source: String, value: String) {
        Log.i(TAG, "$source requested: $value")
    }

    internal fun text(text: CharSequence) = PlainComplicationText.Builder(text).build()

    internal fun icon(context: Context, resource: Int) =
        MonochromaticImage.Builder(Icon.createWithResource(context, resource)).build()

    /** A short text value with an optional icon. */
    internal fun shortText(context: Context, value: String, description: String, iconResource: Int?) =
        ShortTextComplicationData.Builder(text(value), text(description)).apply {
            if (iconResource != null) setMonochromaticImage(icon(context, iconResource))
        }.build()
}

/** Elevation from the barometer (DataDial's 9 o'clock stat). */
class ElevationSource : SuspendingComplicationDataSourceService() {
    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) =
        Health.registerAsync(this)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val value = if (Store.testValues(this)) {
            "13000ft"
        } else {
            Elevation.calibrate(this, Elevation.lastLocation(this))
            Elevation.meters(this)?.let(Elevation::format) ?: Sources.NO_VALUE
        }
        Sources.logRequest("Elevation", value)
        return data(value)
    }

    override fun getPreviewData(type: ComplicationType) = data(Elevation.format(376.0))

    private fun data(value: String) =
        Sources.shortText(this, value, getString(R.string.elevation_value, value), R.drawable.ic_elevation)
}
