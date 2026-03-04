package com.rohilsurana.front

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class GpsPoint(val ts: Long, val lat: Double, val lng: Double, val accuracy: Float) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", ts)
        put("lat", lat)
        put("lng", lng)
        put("accuracy", accuracy.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject) = GpsPoint(
            ts = o.getLong("ts"),
            lat = o.getDouble("lat"),
            lng = o.getDouble("lng"),
            accuracy = o.getDouble("accuracy").toFloat()
        )
    }
}

object MetricsStore {

    private const val TAG = "MetricsStore"
    private const val PREFS_NAME = "FrontMetricsPrefs"

    private const val KEY_GPS_ENABLED       = "gps_enabled"
    private const val KEY_GPS_INTERVAL_MIN  = "gps_interval_min"
    private const val KEY_GPS_BUFFER        = "gps_buffer"
    private const val KEY_GPS_LAST_UPLOAD   = "gps_last_upload_ms"

    private const val DEFAULT_INTERVAL_MIN = 5

    // ── GPS enabled toggle ────────────────────────────────────────────────────

    fun isGpsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GPS_ENABLED, false)

    fun setGpsEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_GPS_ENABLED, enabled).apply()

    // ── Interval ──────────────────────────────────────────────────────────────

    fun getIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_GPS_INTERVAL_MIN, DEFAULT_INTERVAL_MIN)

    fun setIntervalMinutes(context: Context, minutes: Int) =
        prefs(context).edit().putInt(KEY_GPS_INTERVAL_MIN, minutes.coerceIn(1, 60)).apply()

    // ── Point buffer ──────────────────────────────────────────────────────────

    fun getBufferedPoints(context: Context): List<GpsPoint> {
        val json = prefs(context).getString(KEY_GPS_BUFFER, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { GpsPoint.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GPS buffer: ${e.message}")
            emptyList()
        }
    }

    fun appendPoint(context: Context, point: GpsPoint) {
        val points = getBufferedPoints(context).toMutableList()
        points.add(point)
        val arr = JSONArray().also { a -> points.forEach { a.put(it.toJson()) } }
        prefs(context).edit().putString(KEY_GPS_BUFFER, arr.toString()).apply()
        Log.d(TAG, "Buffered GPS point: ${point.lat},${point.lng} — total ${points.size}")
    }

    fun clearBuffer(context: Context) =
        prefs(context).edit().putString(KEY_GPS_BUFFER, "[]").apply()

    fun getBufferSize(context: Context): Int = getBufferedPoints(context).size

    // ── Last upload timestamp ─────────────────────────────────────────────────

    fun getLastUploadMs(context: Context): Long =
        prefs(context).getLong(KEY_GPS_LAST_UPLOAD, 0L)

    fun setLastUploadMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_GPS_LAST_UPLOAD, ms).apply()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
