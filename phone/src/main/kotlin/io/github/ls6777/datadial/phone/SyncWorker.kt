/**
 * @file SyncWorker.kt
 * Reads Google Health's totals and sends them to the watch: every 15 minutes,
 * when the app opens and when the watch asks.
 */
package io.github.ls6777.datadial.phone

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.github.ls6777.datadial.shared.Totals
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** One read of Google Health's totals, sent to the watch. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!GoogleHealth.permitted(context)) {
            Status.setError(context, "no Health Connect access")
            return Result.success()
        }
        return try {
            val totals = GoogleHealth.read(context)
            val request = PutDataMapRequest.create(Totals.PATH).apply { totals.writeTo(dataMap) }
                .asPutDataRequest()
                .setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            Status.setSent(context, totals)
            Log.i(GoogleHealth.TAG, "Sent $totals")
            Result.success()
        } catch (e: Exception) {
            Log.w(GoogleHealth.TAG, "Sync failed", e)
            Status.setError(context, e.message ?: e.javaClass.simpleName)
            Result.retry()
        }
    }

    /** Scheduling. Not instantiable. */
    companion object {
        private const val PERIODIC = "sync"
        private const val NOW = "syncNow"

        /** Sync every 15 minutes (the shortest period Android allows). Survives reboots. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Sync as soon as possible. */
        fun now(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
