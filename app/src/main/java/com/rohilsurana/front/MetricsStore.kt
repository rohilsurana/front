package com.rohilsurana.front

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class MetricPoint(
    val type: String,   // "location", "percentage", etc.
    val name: String,   // "gps", "battery", etc.
    val ts: Long,
    val fields: Map<String, Any>  // type-specific fields, merged flat into JSON
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("name", name)
        put("ts", ts)
        fields.forEach { (k, v) -> put(k, v) }
    }

    companion object {
        fun fromJson(o: JSONObject): MetricPoint {
            val type = o.getString("type")
            val name = o.getString("name")
            val ts   = o.getLong("ts")
            val fields = mutableMapOf<String, Any>()
            o.keys().forEach { key ->
                if (key != "type" && key != "name" && key != "ts") {
                    fields[key] = o.get(key)
                }
            }
            return MetricPoint(type, name, ts, fields)
        }
    }
}

object MetricsStore {

    private const val TAG = "MetricsStore"
    private const val PREFS_NAME = "FrontMetricsPrefs"

    // Per-metric keys (appended with metric name)
    private const val KEY_ENABLED_PREFIX  = "enabled_"   // + name
    private const val KEY_INTERVAL_PREFIX = "interval_"  // + name

    // Shared
    private const val KEY_BUFFER          = "metrics_buffer"
    private const val KEY_LAST_UPLOAD     = "last_upload_ms"
    private const val KEY_UPLOAD_INTERVAL = "upload_interval_min"

    const val DEFAULT_INTERVAL_MIN        = 5
    const val DEFAULT_UPLOAD_INTERVAL_MIN = 20

    // Metric definitions
    const val NAME_GPS     = "gps"
    const val NAME_BATTERY = "battery"
    const val TYPE_LOCATION   = "location"
    const val TYPE_PERCENTAGE = "percentage"

    // ── Per-metric toggle ─────────────────────────────────────────────────────

    fun isEnabled(context: Context, name: String): Boolean =
        prefs(context).getBoolean(KEY_ENABLED_PREFIX + name, false)

    fun setEnabled(context: Context, name: String, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED_PREFIX + name, enabled).apply()

    fun anyEnabled(context: Context): Boolean =
        listOf(NAME_GPS, NAME_BATTERY).any { isEnabled(context, it) }

    // ── Per-metric interval ───────────────────────────────────────────────────

    fun getInterval(context: Context, name: String): Int =
        prefs(context).getInt(KEY_INTERVAL_PREFIX + name, DEFAULT_INTERVAL_MIN)

    fun setInterval(context: Context, name: String, minutes: Int) =
        prefs(context).edit()
            .putInt(KEY_INTERVAL_PREFIX + name, minutes.coerceIn(1, 60))
            .apply()

    // ── Unified buffer ────────────────────────────────────────────────────────

    fun getBufferedPoints(context: Context): List<MetricPoint> {
        val json = prefs(context).getString(KEY_BUFFER, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { MetricPoint.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse metrics buffer: ${e.message}")
            emptyList()
        }
    }

    fun appendPoint(context: Context, point: MetricPoint) {
        val points = getBufferedPoints(context).toMutableList()
        points.add(point)
        val arr = JSONArray().also { a -> points.forEach { a.put(it.toJson()) } }
        prefs(context).edit().putString(KEY_BUFFER, arr.toString()).apply()
        Log.d(TAG, "Buffered ${point.name} point — total ${points.size}")
    }

    fun clearBuffer(context: Context) =
        prefs(context).edit().putString(KEY_BUFFER, "[]").apply()

    fun getBufferSize(context: Context): Int = getBufferedPoints(context).size

    // ── Upload interval ───────────────────────────────────────────────────────

    fun getUploadInterval(context: Context): Int =
        prefs(context).getInt(KEY_UPLOAD_INTERVAL, DEFAULT_UPLOAD_INTERVAL_MIN)

    fun setUploadInterval(context: Context, minutes: Int) =
        prefs(context).edit()
            .putInt(KEY_UPLOAD_INTERVAL, minutes.coerceIn(5, 120))
            .apply()

    // ── Last upload ───────────────────────────────────────────────────────────

    fun getLastUploadMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_UPLOAD, 0L)

    fun setLastUploadMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_LAST_UPLOAD, ms).apply()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
