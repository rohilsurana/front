package com.rohilsurana.front

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.work.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GpsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "GpsWorker"
        const val WORK_NAME = "gps_record"
        private const val GPS_TIMEOUT_MS = 10_000L

        fun enqueue(context: Context) {
            val intervalMin = MetricsStore.getIntervalMinutes(context).toLong()
            val request = OneTimeWorkRequestBuilder<GpsWorker>()
                .setInitialDelay(intervalMin, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            Log.d(TAG, "Next GPS record in ${intervalMin}m")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "GPS recording cancelled")
        }
    }

    override fun doWork(): Result {
        if (!MetricsStore.isGpsEnabled(applicationContext)) {
            Log.d(TAG, "GPS disabled — stopping chain")
            return Result.success()
        }

        val location = getLocation()
        if (location != null) {
            val point = GpsPoint(
                ts = System.currentTimeMillis(),
                lat = location.latitude,
                lng = location.longitude,
                accuracy = location.accuracy
            )
            MetricsStore.appendPoint(applicationContext, point)
        } else {
            Log.w(TAG, "Could not get GPS fix — skipping this interval")
        }

        // Schedule next run regardless of whether we got a fix
        enqueue(applicationContext)
        return Result.success()
    }

    @Suppress("MissingPermission")
    private fun getLocation(): Location? {
        val latch = CountDownLatch(1)
        var result: Location? = null

        val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE)
            as LocationManager

        // Try GPS first, fall back to network
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

        // Check for cached location first (< 5 min old)
        try {
            val last = locationManager.getLastKnownLocation(provider)
            if (last != null && System.currentTimeMillis() - last.time < 5 * 60 * 1000) {
                Log.d(TAG, "Using cached location from $provider")
                return last
            }
        } catch (e: Exception) {
            Log.w(TAG, "getLastKnownLocation failed: ${e.message}")
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                result = location
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
            latch.await(GPS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            result
        } catch (e: Exception) {
            Log.e(TAG, "Location request failed: ${e.message}")
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            null
        }
    }
}
