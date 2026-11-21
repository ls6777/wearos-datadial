/**
 * @file Phone.kt
 * Google Health's totals from DataDial Stats on the phone, over the Wearable
 * Data Layer.
 */
package io.github.ls6777.datadial.stats

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.github.ls6777.datadial.shared.Totals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Talking to the phone app. Not instantiable. */
object Phone {
    /** Shortest time between requests for new totals, in milliseconds. */
    private const val REFRESH_INTERVAL = 5 * 60_000L

    /** Store totals from the phone and update the face. */
    fun received(context: Context, totals: Totals) {
        Store.setPhone(context, totals)
        Log.i(Sources.TAG, "From the phone: $totals")
        Sources.updateDaily(context)
    }

    /**
     * Ask the phone to read Google Health's totals and send them. At most
     * every [REFRESH_INTERVAL] unless forced.
     */
    fun requestRefresh(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - Store.refreshAsked(context) < REFRESH_INTERVAL) return
        Store.setRefreshAsked(context, now)
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = Wearable.getNodeClient(app).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(app).sendMessage(node.id, Totals.REFRESH_PATH, null).await()
                }
                Log.i(Sources.TAG, "Asked ${nodes.size} phone(s) for new totals")
            } catch (e: Exception) {
                Log.w(Sources.TAG, "Couldn't ask the phone for new totals", e)
            }
        }
    }

    /**
     * Read the totals the phone last sent, which the Data Layer keeps, in
     * case they arrived while the app couldn't receive them (e.g. before
     * an update).
     */
    suspend fun load(context: Context) {
        try {
            val uri = Uri.Builder().scheme("wear").path(Totals.PATH).build()
            val items = Wearable.getDataClient(context).getDataItems(uri, DataClient.FILTER_LITERAL).await()
            val newest = try {
                items.mapNotNull { Totals.from(DataMapItem.fromDataItem(it).dataMap) }.maxByOrNull { it.syncedTo }
            } finally {
                items.release()
            }
            val stored = Store.phone(context)
            if (newest != null && (stored == null || newest.syncedTo.isAfter(stored.syncedTo) || newest.day != stored.day)) {
                received(context, newest)
            }
        } catch (e: Exception) {
            Log.w(Sources.TAG, "Couldn't read the phone's totals", e)
        }
    }
}

/** Started by Google Play services when the phone sends new totals. */
class PhoneListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != Totals.PATH) continue
            Totals.from(DataMapItem.fromDataItem(event.dataItem).dataMap)?.let { Phone.received(this, it) }
        }
    }
}
