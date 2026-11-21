/**
 * @file RefreshService.kt
 * Answers the watch's requests for new totals.
 */
package io.github.ls6777.datadial.phone

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.ls6777.datadial.shared.Totals

/** Started by Google Play services when the watch asks for new totals. */
class RefreshService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Totals.REFRESH_PATH) return
        Log.i(GoogleHealth.TAG, "Watch asked for new totals")
        SyncWorker.now(this)
    }
}
