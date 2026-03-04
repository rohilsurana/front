package com.rohilsurana.front

import android.content.Context
import android.util.Log
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class MetricsUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "MetricsUploadWorker"
        const val WORK_NAME = "metrics_upload"
        private const val UPLOAD_INTERVAL_MIN = 20L

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<MetricsUploadWorker>(
                UPLOAD_INTERVAL_MIN, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            Log.d(TAG, "Metrics upload worker enqueued (every ${UPLOAD_INTERVAL_MIN}m)")
        }
    }

    override fun doWork(): Result {
        val points = MetricsStore.getBufferedPoints(applicationContext)
        if (points.isEmpty()) {
            Log.d(TAG, "Nothing to upload")
            return Result.success()
        }

        val baseUrl = applicationContext.getSharedPreferences(
            AlarmStore.PREFS_NAME, Context.MODE_PRIVATE
        ).getString(AlarmStore.KEY_SERVER_URL, "") ?: ""

        if (baseUrl.isEmpty()) {
            Log.w(TAG, "No server URL — skipping upload")
            return Result.success()
        }

        return try {
            val body = JSONObject().apply {
                put("metrics", JSONArray().also { arr ->
                    points.forEach { arr.put(it.toJson()) }
                })
            }.toString().toByteArray()

            val conn = (URL("${baseUrl.trimEnd('/')}/metrics").openConnection()
                    as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Length", body.size.toString())
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()

            if (code == HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "Uploaded ${points.size} points — clearing buffer")
                MetricsStore.clearBuffer(applicationContext)
                MetricsStore.setLastUploadMs(applicationContext, System.currentTimeMillis())
                Result.success()
            } else {
                Log.w(TAG, "Upload returned HTTP $code — keeping buffer for retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed: ${e.message} — keeping buffer for retry")
            Result.retry()
        }
    }
}
