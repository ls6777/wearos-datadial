/**
 * @file TestValuesReceiver.kt
 * Debug build only: switches the data sources to fixed test values and back.
 */
package io.github.ls6777.datadial.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Turns the fixed test values on (extra "on" true, the default) or off, as
 * `adb shell am broadcast -n io.github.ls6777.datadial.stats/.TestValuesReceiver --ez on true`.
 */
class TestValuesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.setTestValues(context, intent.getBooleanExtra("on", true))
        Sources.updateAll(context)
    }
}
