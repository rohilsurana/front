package com.rohilsurana.front

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
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
        private const val NOTIF_CHANNEL_ID = "metrics_collect"
        private const val NOTIF_ID = 9001

        fun workName(metricName: String) = "collect_$metricName"

        /** Schedule next collection after intervalMin delay (used for self-rescheduling). */
        fun enqueue(context: Context, metricName: String) {
            val intervalMin = MetricsStore.getInterval(context, metricName).toLong()
            enqueueWithDelay(context, metricName, intervalMin, TimeUnit.MINUTES)
        }

        /** Fire immediately — use when first enabling a metric. */
        fun enqueueNow(context: Context, metricName: String) {
            enqueueWithDelay(context, metricName, 0L, TimeUnit.SECONDS)
        }

        private fun enqueueWithDelay(
            context: Context,
            metricName: String,
            delay: Long,
            unit: TimeUnit
        ) {
            val request = OneTimeWorkRequestBuilder<MetricCollectWorker>()
                .setInputData(workDataOf(KEY_METRIC_NAME to metricName))
                .setInitialDelay(delay, unit)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(metricName), ExistingWorkPolicy.REPLACE, request)
            Log.d(TAG, "Scheduled $metricName collection in $delay ${unit.name.lowercase()}")
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

        // GPS needs foreground service on Android 14+ to access location from background
        if (metricName == MetricsStore.NAME_GPS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setForegroundAsync(buildForegroundInfo()).get()
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
            Log.d(TAG, "Collected $metricName point: ${point.fields}")
        } else {
            Log.w(TAG, "No point collected for $metricName — skipping this interval")
        }

        // Reschedule at full interval delay
        enqueue(applicationContext, metricName)
        return Result.success()
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    private fun collectGps(): MetricPoint? {
        // Explicit runtime permission check
        val hasFine = ActivityCompat.checkSelfPermission(
            applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ActivityCompat.checkSelfPermission(
            applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            val msg = "FAIL: no location permission (fine=$hasFine coarse=$hasCoarse)"
            Log.e(TAG, "GPS: $msg")
            MetricsStore.setGpsStatus(applicationContext, msg)
            return null
        }

        val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE)
            as LocationManager

        val gpsEnabled  = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netEnabled  = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val provider = when {
            gpsEnabled  -> LocationManager.GPS_PROVIDER
            netEnabled  -> LocationManager.NETWORK_PROVIDER
            else -> {
                val msg = "FAIL: no provider enabled (gps=$gpsEnabled network=$netEnabled)"
                Log.w(TAG, "GPS: $msg")
                MetricsStore.setGpsStatus(applicationContext, msg)
                return null
            }
        }

        Log.d(TAG, "GPS: using provider=$provider")

        // Use cached location if fresh (< 5 min)
        try {
            @Suppress("MissingPermission")
            val last = locationManager.getLastKnownLocation(provider)
            if (last != null && System.currentTimeMillis() - last.time < 5 * 60 * 1000) {
                val msg = "OK (cached): ${last.latitude},${last.longitude} acc=${last.accuracy}m"
                Log.d(TAG, "GPS: $msg")
                MetricsStore.setGpsStatus(applicationContext, msg)
                return gpsPoint(last)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GPS: getLastKnownLocation failed: ${e.message}")
        }

        // Request a fresh fix
        val latch = CountDownLatch(1)
        var location: Location? = null

        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                location = loc
                latch.countDown()
                try { locationManager.removeUpdates(this) } catch (_: Exception) {}
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {
                Log.w(TAG, "GPS: provider disabled mid-request")
                latch.countDown()
            }
        }

        return try {
            @Suppress("MissingPermission")
            locationManager.requestLocationUpdates(provider, 0L, 0f, listener)
            MetricsStore.setGpsStatus(applicationContext, "Waiting for fix via $provider (30s)...")
            Log.d(TAG, "GPS: waiting for fix (30s timeout)...")
            val gotFix = latch.await(30_000L, TimeUnit.MILLISECONDS)
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            if (gotFix && location != null) {
                val msg = "OK: ${location!!.latitude},${location!!.longitude} acc=${location!!.accuracy}m via $provider"
                Log.d(TAG, "GPS: $msg")
                MetricsStore.setGpsStatus(applicationContext, msg)
            } else {
                val msg = "FAIL: timed out after 30s via $provider"
                Log.w(TAG, "GPS: $msg")
                MetricsStore.setGpsStatus(applicationContext, msg)
            }
            location?.let { gpsPoint(it) }
        } catch (e: SecurityException) {
            val msg = "FAIL: SecurityException — ${e.message}"
            Log.e(TAG, "GPS: $msg")
            MetricsStore.setGpsStatus(applicationContext, msg)
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            null
        } catch (e: Exception) {
            val msg = "FAIL: ${e.javaClass.simpleName} — ${e.message}"
            Log.e(TAG, "GPS: $msg")
            MetricsStore.setGpsStatus(applicationContext, msg)
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
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: run {
                Log.w(TAG, "Battery: null intent from registerReceiver")
                return null
            }

            val level    = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale    = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status   = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL

            if (level < 0 || scale <= 0) {
                Log.w(TAG, "Battery: invalid level=$level scale=$scale")
                return null
            }
            val pct = (level * 100) / scale
            Log.d(TAG, "Battery: $pct% charging=$charging")

            MetricPoint(
                type   = MetricsStore.TYPE_PERCENTAGE,
                name   = MetricsStore.NAME_BATTERY,
                ts     = System.currentTimeMillis(),
                fields = mapOf("value" to pct, "charging" to charging)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Battery collection failed: ${e.message}")
            null
        }
    }

    // ── Foreground service (Android 14+ location access) ─────────────────────

    private fun buildForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "Metrics collection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
        val notif: Notification = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL_ID)
            .setContentTitle("Recording location")
            .setContentText("Front is collecting a GPS fix")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }
}
