/**
 * @file Sources.kt
 * The complication data sources: steps and floors (with their goals),
 * calories and elevation, drawn by DataDial like its own stats.
 */
package io.github.ls6777.datadial.stats

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicFloat
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInt32
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

    /**
     * Daily steps goal for other faces. Data sources can't read the watch's
     * step goal; DataDial draws the steps gauge against it itself.
     */
    const val STEPS_GOAL = 10000

    /** Every data source in the app. */
    val ALL = listOf(
        StepsSource::class.java,
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

    /** [update] for the daily totals (steps, calories and floors). */
    fun updateDaily(context: Context) {
        update(context, StepsSource::class.java)
        update(context, CaloriesSource::class.java)
        update(context, FloorsSource::class.java)
    }

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
 * A daily total (steps, calories or floors): Google Health's, kept current by
 * the watch between syncs (see [Live]). A live value: the system keeps it
 * current on the face, so the app only pushes updates when the plan changes
 * (new totals from the phone, a restart).
 * @param daily The total
 * @param goal The goal for the gauge, or null for a plain value
 * @param nameResource The source's name in the editor
 * @param descriptionResource Screen reader text: "%1$s" is the value, "%2$d" the goal
 * @param iconResource The icon
 * @param testValue Fixed value for the test build
 * @param previewValue Value in the editor's preview
 */
abstract class DailySource(
    private val daily: Daily,
    private val goal: Int?,
    private val nameResource: Int,
    private val descriptionResource: Int,
    private val iconResource: Int,
    private val testValue: Int,
    private val previewValue: Int,
) : SuspendingComplicationDataSourceService() {
    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) =
        Health.registerAsync(this)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        if (Store.testValues(this)) return data(request.complicationType, testValue)
        Sources.logRequest(daily.name.lowercase(), Live.describe(this, daily))
        val value = Live.value(this, daily)
        val text = Sources.liveText(value)
        val name = Sources.text(getString(nameResource))
        val icon = Sources.icon(this, iconResource)
        if (goal == null || request.complicationType != ComplicationType.GOAL_PROGRESS) {
            return ShortTextComplicationData.Builder(text, name)
                .setMonochromaticImage(icon)
                .setDynamicValueInvalidationFallback(shortText(null))
                .build()
        }
        return GoalProgressComplicationData.Builder(value, goal.toFloat(), name)
            .setText(text)
            .setMonochromaticImage(icon)
            .setDynamicValueInvalidationFallback(goalProgress(null))
            .build()
    }

    override fun getPreviewData(type: ComplicationType) = data(type, previewValue)

    /** Fixed data: the preview, the test value, or "--" when the live value can't be evaluated. */
    private fun data(type: ComplicationType, value: Int?): ComplicationData =
        if (goal != null && type == ComplicationType.GOAL_PROGRESS) goalProgress(value) else shortText(value)

    private fun description(value: String) = getString(descriptionResource, value, goal ?: 0)

    private fun shortText(value: Int?): ShortTextComplicationData {
        val text = value?.toString() ?: Sources.NO_VALUE
        return Sources.shortText(this, text, description(text), iconResource)
    }

    private fun goalProgress(value: Int?): GoalProgressComplicationData {
        val text = value?.toString() ?: Sources.NO_VALUE
        val target = (goal ?: 0).toFloat()
        return GoalProgressComplicationData.Builder((value ?: 0).toFloat(), target, Sources.text(description(text)))
            .setText(Sources.text(text))
            .setMonochromaticImage(Sources.icon(this, iconResource))
            .build()
    }
}

/**
 * Steps today, with progress toward [Sources.STEPS_GOAL] (DataDial's 4:30
 * stat; DataDial draws its gauge against the watch's own step goal).
 */
class StepsSource : DailySource(
    Daily.STEPS, Sources.STEPS_GOAL, R.string.steps, R.string.steps_value, R.drawable.ic_steps,
    testValue = 25000, previewValue = 6500,
)

/** Calories burned today, resting plus active (DataDial's 6 o'clock stat). */
class CaloriesSource : DailySource(
    Daily.CALORIES, null, R.string.calories, R.string.calories_value, R.drawable.ic_calories,
    testValue = 3500, previewValue = 2100,
)

/** Floors climbed today, with progress toward [Sources.FLOORS_GOAL] (DataDial's 7:30 stat). */
class FloorsSource : DailySource(
    Daily.FLOORS, Sources.FLOORS_GOAL, R.string.floors, R.string.floors_value, R.drawable.ic_floors,
    testValue = 110, previewValue = 6,
)

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
