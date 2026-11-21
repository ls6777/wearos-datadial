/**
 * @file MainActivity.kt
 * The phone app's one screen: asks for Health Connect access, starts the
 * syncing and shows what was last sent to the watch.
 */
package io.github.ls6777.datadial.phone

import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

/** Permissions and status screen. */
class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var allow: Button

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 24).toInt()
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad, 0, pad)
        }
        allow = Button(this).apply {
            text = getString(R.string.allow)
            setOnClickListener { requestPermissions.launch(GoogleHealth.PERMISSIONS) }
        }
        val sync = Button(this).apply {
            text = getString(R.string.sync_now)
            setOnClickListener { SyncWorker.now(this@MainActivity) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                textSize = 24f
                text = getString(R.string.app_name)
            })
            addView(TextView(context).apply {
                textSize = 16f
                setPadding(0, pad / 2, 0, 0)
                text = getString(R.string.about)
            })
            addView(status)
            addView(allow)
            addView(sync)
        }
        val scroll = ScrollView(this).apply { addView(column) }
        // Edge to edge: keep the content clear of the status and navigation bars.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        // Show the result when a sync finishes.
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("syncNow").observe(this) { infos ->
            if (infos.any { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED }) refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        start()
    }

    /** Sync now (each time the app opens) and make sure the regular syncs are scheduled. */
    private fun start() = lifecycleScope.launch {
        if (GoogleHealth.permitted(this@MainActivity)) {
            SyncWorker.schedule(this@MainActivity)
            SyncWorker.now(this@MainActivity)
        }
        refresh()
    }

    /** Show the status. */
    private fun refresh() = lifecycleScope.launch {
        val available = GoogleHealth.available(this@MainActivity)
        val permitted = GoogleHealth.permitted(this@MainActivity)
        allow.visibility = if (available && !permitted) View.VISIBLE else View.GONE
        status.text = when {
            !available -> getString(R.string.no_health_connect)
            !permitted -> getString(R.string.needs_access)
            else -> lastSync()
        }
    }

    private fun lastSync(): String {
        val at = Status.at(this) ?: return getString(R.string.not_synced)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        Status.error(this)?.let { return getString(R.string.sync_failed, time.format(Date(at.toEpochMilli())), it) }
        val totals = Status.sent(this) ?: return getString(R.string.not_synced)
        return getString(
            R.string.synced,
            time.format(Date(at.toEpochMilli())),
            totals.steps,
            totals.calories.roundToLong(),
            totals.floors.roundToLong(),
            time.format(Date(totals.syncedTo.toEpochMilli())),
        )
    }
}
