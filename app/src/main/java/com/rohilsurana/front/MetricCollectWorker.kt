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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import android.net.wifi.WifiManager
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

        // Reschedule FIRST — chain must survive regardless of what happens below
        enqueue(applicationContext, metricName)

        // GPS needs foreground service on Android 14+ to access location from background
        if (metricName == MetricsStore.NAME_GPS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                setForegroundAsync(buildForegroundInfo()).get()
            } catch (e: Exception) {
                Log.w(TAG, "setForegroundAsync failed (non-fatal): ${e.message}")
                // Continue anyway — worst case GPS fix fails, chain already rescheduled
            }
        }

        try {
            val point = when (metricName) {
                MetricsStore.NAME_GPS        -> collectGps()
                MetricsStore.NAME_BATTERY    -> collectBattery()
                MetricsStore.NAME_STEPS      -> collectSteps()
                MetricsStore.NAME_ACTIVITY   -> collectActivity()
                MetricsStore.NAME_WIFI       -> collectWifi()
                MetricsStore.NAME_SCREEN     -> collectScreen()
                MetricsStore.NAME_CONNECTION -> collectConnection()
                else -> { Log.w(TAG, "Unknown metric: $metricName"); null }
            }

            if (point != null) {
                MetricsStore.appendPoint(applicationContext, point)
                Log.d(TAG, "Collected $metricName: ${point.fields}")
            } else {
                Log.w(TAG, "$metricName: no point this interval")
            }
        } catch (e: Exception) {
            // Collection error must never break the chain — already rescheduled above
            val msg = "FAIL: unhandled ${e.javaClass.simpleName} — ${e.message}"
            Log.e(TAG, "$metricName: $msg")
            if (metricName == MetricsStore.NAME_GPS) MetricsStore.setGpsStatus(applicationContext, msg)
        }

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
            locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
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

    // ── Steps ─────────────────────────────────────────────────────────────────

    private fun collectSteps(): MetricPoint? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Steps: missing ACTIVITY_RECOGNITION permission")
            return null
        }
        val sm = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: run {
            Log.w(TAG, "Steps: no step counter sensor")
            return null
        }
        val latch = CountDownLatch(1)
        var total = -1L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                total = e.values[0].toLong(); latch.countDown(); sm.unregisterListener(this)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        latch.await(2000, TimeUnit.MILLISECONDS)
        sm.unregisterListener(listener)

        if (total < 0) total = MetricsStore.getLastStepCount(applicationContext)
        if (total < 0) return null

        val last = MetricsStore.getLastStepCount(applicationContext)
        val delta = if (last >= 0) (total - last).coerceAtLeast(0) else 0
        MetricsStore.setLastStepCount(applicationContext, total)

        return MetricPoint(MetricsStore.TYPE_COUNT, MetricsStore.NAME_STEPS,
            System.currentTimeMillis(), mapOf("value" to total, "delta" to delta))
    }

    // ── Activity (inferred from step rate) ────────────────────────────────────

    private fun collectActivity(): MetricPoint? {
        val sm = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null

        val latch = CountDownLatch(1)
        var total = -1L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                total = e.values[0].toLong(); latch.countDown(); sm.unregisterListener(this)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        latch.await(2000, TimeUnit.MILLISECONDS)
        sm.unregisterListener(listener)

        if (total < 0) total = MetricsStore.getLastActivityStepCount(applicationContext)
        if (total < 0) return null

        val intervalMin = MetricsStore.getInterval(applicationContext, MetricsStore.NAME_ACTIVITY).coerceAtLeast(1).toLong()
        val last = MetricsStore.getLastActivityStepCount(applicationContext)
        val delta = if (last >= 0) (total - last).coerceAtLeast(0) else 0
        MetricsStore.setLastActivityStepCount(applicationContext, total)

        val stepsPerMin = delta.toFloat() / intervalMin
        val activity = when {
            stepsPerMin >= 100 -> "running"
            stepsPerMin >= 10  -> "walking"
            else               -> "stationary"
        }
        return MetricPoint(MetricsStore.TYPE_ACTIVITY, MetricsStore.NAME_ACTIVITY,
            System.currentTimeMillis(), mapOf("activity" to activity, "step_rate" to stepsPerMin.toInt()))
    }

    // ── WiFi ──────────────────────────────────────────────────────────────────

    @Suppress("MissingPermission")
    private fun collectWifi(): MetricPoint? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val connected = info != null && info.networkId != -1
            val ssid = info?.ssid?.trim('"') ?: ""
            val rssi = info?.rssi ?: -999
            MetricPoint(MetricsStore.TYPE_NETWORK, MetricsStore.NAME_WIFI,
                System.currentTimeMillis(),
                mapOf("connected" to connected, "ssid" to (if (connected) ssid else ""), "rssi" to rssi))
        } catch (e: Exception) {
            Log.e(TAG, "WiFi collection failed: ${e.message}")
            null
        }
    }

    // ── Screen ────────────────────────────────────────────────────────────────

    private fun collectScreen(): MetricPoint? {
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return MetricPoint(MetricsStore.TYPE_EVENT, MetricsStore.NAME_SCREEN,
            System.currentTimeMillis(), mapOf("on" to pm.isInteractive))
    }

    // ── Network connection type ───────────────────────────────────────────────

    @Suppress("MissingPermission")
    private fun collectConnection(): MetricPoint? {
        return try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val type = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    try {
                        val tm = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        when (tm.dataNetworkType) {
                            TelephonyManager.NETWORK_TYPE_NR    -> "5g"
                            TelephonyManager.NETWORK_TYPE_LTE   -> "4g"
                            TelephonyManager.NETWORK_TYPE_HSPAP,
                            TelephonyManager.NETWORK_TYPE_HSPA  -> "3g"
                            TelephonyManager.NETWORK_TYPE_EDGE,
                            TelephonyManager.NETWORK_TYPE_GPRS  -> "2g"
                            else -> "cellular"
                        }
                    } catch (_: Exception) { "cellular" }
                }
                else -> "other"
            }
            MetricPoint(MetricsStore.TYPE_NETWORK, MetricsStore.NAME_CONNECTION,
                System.currentTimeMillis(), mapOf("type" to type))
        } catch (e: Exception) {
            Log.e(TAG, "Connection collection failed: ${e.message}")
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
