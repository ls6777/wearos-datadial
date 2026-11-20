/**
 * @file Sources.kt
 * The complication data sources: calories, floors (with the goal) and
 * elevation, drawn by DataDial like its own stats.
 */
package io.github.ls6777.datadial.stats

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicFloat
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInt32
import androidx.wear.protolayout.expression.PlatformHealthSources
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.DynamicComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Helpers shared by the data sources. Not instantiable. */
object Sources {
    /** Log tag for the whole app: `adb logcat -s DataDialStats` (or `./build.sh statslog`). */
    const val TAG = "DataDialStats"

    /** Shown for a value that isn't available, as in DataDial. */
    const val NO_VALUE = "--"

    /** Daily floors goal. Health Services has no floors goal; this is Garmin's default. */
    const val FLOORS_GOAL = 10

    /** Every data source in the app. */
    val ALL = listOf(
        CaloriesSource::class.java,
        FloorsSource::class.java,
        ElevationSource::class.java,
    )

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

    /** A live number as whole units without grouping, e.g. "2100", kept current by the system. */
    internal fun liveText(value: DynamicFloat) = DynamicComplicationText(
        value.asInt().format(DynamicInt32.IntFormatter.Builder().setGroupingUsed(false).build()),
    )

    internal fun icon(context: Context, resource: Int) =
        MonochromaticImage.Builder(Icon.createWithResource(context, resource)).build()

    /** A short text value with an optional icon. */
    internal fun shortText(context: Context, value: String, description: String, iconResource: Int?) =
        ShortTextComplicationData.Builder(text(value), text(description)).apply {
            if (iconResource != null) setMonochromaticImage(icon(context, iconResource))
        }.build()
}

/**
 * Calories burned today, resting plus active (DataDial's 6 o'clock stat).
 * A live value: the system evaluates [PlatformHealthSources.dailyCalories]
 * and keeps it current, so the app doesn't push updates.
 */
class CaloriesSource : SuspendingComplicationDataSourceService() {
    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) =
        Health.registerAsync(this)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        if (Store.testValues(this)) return data("3500")
        Sources.logRequest("Calories", "live")
        val live = Sources.liveText(PlatformHealthSources.dailyCalories())
        return ShortTextComplicationData.Builder(live, Sources.text(getString(R.string.calories)))
            .setMonochromaticImage(Sources.icon(this, R.drawable.ic_calories))
            .setDynamicValueInvalidationFallback(data(Sources.NO_VALUE))
            .build()
    }

    override fun getPreviewData(type: ComplicationType) = data("2100")

    private fun data(value: String) =
        Sources.shortText(this, value, getString(R.string.calories_value, value), R.drawable.ic_calories)
}

/**
 * Floors climbed today, with progress toward [Sources.FLOORS_GOAL] (DataDial's
 * 7:30 stat). A live value, like [CaloriesSource].
 */
class FloorsSource : SuspendingComplicationDataSourceService() {
    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) =
        Health.registerAsync(this)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        if (Store.testValues(this)) return data(request.complicationType, 110)
        Sources.logRequest("Floors", "live")
        val floors = PlatformHealthSources.dailyFloors()
        val live = Sources.liveText(floors)
        val description = Sources.text(getString(R.string.floors))
        if (request.complicationType != ComplicationType.GOAL_PROGRESS) {
            return ShortTextComplicationData.Builder(live, description)
                .setMonochromaticImage(Sources.icon(this, R.drawable.ic_floors))
                .setDynamicValueInvalidationFallback(shortText(null))
                .build()
        }
        return GoalProgressComplicationData.Builder(floors, Sources.FLOORS_GOAL.toFloat(), description)
            .setText(live)
            .setMonochromaticImage(Sources.icon(this, R.drawable.ic_floors))
            .setDynamicValueInvalidationFallback(goalProgress(null))
            .build()
    }

    override fun getPreviewData(type: ComplicationType) = data(type, 6)

    /** Fixed data: the preview, the test value, or "--" when the live value can't be evaluated. */
    private fun data(type: ComplicationType, floors: Int?): ComplicationData =
        if (type == ComplicationType.GOAL_PROGRESS) goalProgress(floors) else shortText(floors)

    private fun description(value: String) = getString(R.string.floors_value, value, Sources.FLOORS_GOAL)

    private fun shortText(floors: Int?): ShortTextComplicationData {
        val value = floors?.toString() ?: Sources.NO_VALUE
        return Sources.shortText(this, value, description(value), R.drawable.ic_floors)
    }

    private fun goalProgress(floors: Int?): GoalProgressComplicationData {
        val value = floors?.toString() ?: Sources.NO_VALUE
        val goal = Sources.FLOORS_GOAL.toFloat()
        return GoalProgressComplicationData.Builder((floors ?: 0).toFloat(), goal, Sources.text(description(value)))
            .setText(Sources.text(value))
            .setMonochromaticImage(Sources.icon(this, R.drawable.ic_floors))
            .build()
    }
}

/** Elevation from the barometer (DataDial's 9 o'clock stat). */
class ElevationSource : SuspendingComplicationDataSourceService() {
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
