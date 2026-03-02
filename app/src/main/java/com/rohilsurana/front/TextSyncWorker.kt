package com.rohilsurana.front

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class TextSyncWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "TextSyncWorker"
        private const val WORK_NAME_PERIODIC = "text_sync_periodic"
        private const val WORK_NAME_IMMEDIATE = "text_sync_immediate"
        private const val SYNC_INTERVAL_MINUTES = 20L

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Schedule the 20-min periodic sync. Safe to call multiple times — uses KEEP policy. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<TextSyncWorker>(
                SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Periodic text sync scheduled (every ${SYNC_INTERVAL_MINUTES}min)")
        }

        /** Trigger an immediate one-shot sync (e.g. right after alarm schedule sync). */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<TextSyncWorker>()
                .setConstraints(constraints())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "Immediate text sync enqueued")
        }
    }

    override fun doWork(): Result {
        Log.d(TAG, "Running text sync…")
        val success = AlarmStore.syncTextsFromApi(context)
        return if (success) {
            Log.d(TAG, "Text sync complete")
            Result.success()
        } else {
            // Retry later — WorkManager backs off automatically
            Log.w(TAG, "Text sync had failures, will retry")
            Result.retry()
        }
    }
}
