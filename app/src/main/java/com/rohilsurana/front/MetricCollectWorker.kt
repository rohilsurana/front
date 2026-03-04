package com.rohilsurana.front

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import androidx.work.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Parameterised collect worker — handles all metric types.
 * Pass KEY_METRIC_NAME as input data to specify which metric to collect.
 * Self-rescheduling via OneTimeWorkRequest chain (avoids WorkManager 15-min floor).
 *
 * Adding a new metric:
 *   1. Add constants to MetricsStore (name, type)
 *   2. Add a collect*() function here
 *   3. Add a when branch in doWork()
 *   4. Wire up toggle + interval in MetricsActivity
 */
class MetricCollectWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "MetricCollectWorker"
        const val KEY_METRIC_NAME = "metric_name"

        fun workName(metricName: String) = "collect_$metricName"

        fun enqueue(context: Context, metricName: String) {
            val intervalMin = MetricsStore.getInterval(context, metricName).toLong()
            val request = OneTimeWorkRequestBuilder<MetricCollectWorker>()
                .setInputData(workDataOf(KEY_METRIC_NAME to metricName))
                .setInitialDelay(intervalMin, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(metricName), ExistingWorkPolicy.REPLACE, request)
            Log.d(TAG, "Next $metricName collection in ${intervalMin}m")
        }

        fun cancel(context: Context, metricName: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(metricName))
            Log.d(TAG, "$metricName collection cancelled")
        }
    }

    override fun doWork(): Result {
        val metricName = inputData.getString(KEY_METRIC_NAME)
            ?: return Result.failure()

        if (!MetricsStore.isEnabled(applicationContext, metricName)) {
            Log.d(TAG, "$metricName disabled — stopping chain")
            return Result.success()
        }

        val point = when (metricName) {
            MetricsStore.NAME_GPS     -> collectGps()
            MetricsStore.NAME_BATTERY -> collectBattery()
            else -> {
                Log.w(TAG, "Unknown metric: $metricName")
                null
            }
        }

        if (point != null) {
            MetricsStore.appendPoint(applicationContext, point)
        } else {
            Log.w(TAG, "No point collected for $metricName — skipping this interval")
        }

        // Reschedule regardless of whether we got a reading
        enqueue(applicationContext, metricName)
        return Result.success()
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    @Suppress("MissingPermission")
    private fun collectGps(): MetricPoint? {
        val latch = CountDownLatch(1)
        var location: Location? = null

        val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE)
            as LocationManager

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> {
                Log.w(TAG, "No location provider available")
                return null
            }
        }

        // Use cached location if fresh (< 5 min)
        try {
            val last = locationManager.getLastKnownLocation(provider)
            if (last != null && System.currentTimeMillis() - last.time < 5 * 60 * 1000) {
                Log.d(TAG, "GPS: using cached location")
                return gpsPoint(last)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getLastKnownLocation failed: ${e.message}")
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                location = loc
                latch.countDown()
                try { locationManager.removeUpdates(this) } catch (_: Exception) {}
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) { latch.countDown() }
        }

        return try {
            locationManager.requestLocationUpdates(provider, 0L, 0f, listener)
            latch.await(10_000L, TimeUnit.MILLISECONDS)
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            location?.let { gpsPoint(it) }
        } catch (e: Exception) {
            Log.e(TAG, "GPS request failed: ${e.message}")
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            null
        }
    }

    private fun gpsPoint(loc: Location) = MetricPoint(
        type   = MetricsStore.TYPE_LOCATION,
        name   = MetricsStore.NAME_GPS,
        ts     = System.currentTimeMillis(),
        fields = mapOf(
            "lat"      to loc.latitude,
            "lng"      to loc.longitude,
            "accuracy" to loc.accuracy.toDouble()
        )
    )

    // ── Battery ───────────────────────────────────────────────────────────────

    private fun collectBattery(): MetricPoint? {
        return try {
            val intent = applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return null

            val level    = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale    = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status   = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL

            if (level < 0 || scale <= 0) return null
            val pct = (level * 100) / scale

            MetricPoint(
                type   = MetricsStore.TYPE_PERCENTAGE,
                name   = MetricsStore.NAME_BATTERY,
                ts     = System.currentTimeMillis(),
                fields = mapOf(
                    "value"    to pct,
                    "charging" to charging
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Battery collection failed: ${e.message}")
            null
        }
    }
}
