package com.rohilsurana.front

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AlarmStore {

    private const val TAG = "AlarmStore"
    const val PREFS_NAME        = "FrontAlarmPrefs"
    const val KEY_SERVER_URL    = "server_url"
    private const val KEY_ALARMS        = "alarms_json"
    private const val KEY_LAST_SYNC     = "last_sync_ms"

    // ── CRUD ─────────────────────────────────────────────────────────────────

    fun getAll(context: Context): List<Alarm> {
        val json = prefs(context).getString(KEY_ALARMS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cached alarms: ${e.message}")
            emptyList()
        }
    }

    fun lastSyncMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC, 0L)

    // ── Server URL ────────────────────────────────────────────────────────────

    fun getServerUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "") ?: ""

    fun saveServerUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_SERVER_URL, url).apply()

    // ── API Sync ──────────────────────────────────────────────────────────────

    /**
     * Fetches GET {baseUrl}/alarms, persists the result, and re-schedules.
     * Must be called from a background thread.
     * Returns a Result with the alarm list or an error message.
     */
    fun syncFromApi(context: Context): Result<List<Alarm>> {
        val baseUrl = getServerUrl(context).trimEnd('/')
        if (baseUrl.isEmpty()) return Result.failure(Exception("No server URL configured"))

        return try {
            val conn = (URL("$baseUrl/alarms").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return Result.failure(Exception("Server returned HTTP ${conn.responseCode}"))
            }

            val body = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(body)
            val arr = root.getJSONArray("alarms")
            val alarms = (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }

            // Persist and reschedule
            saveAll(context, alarms)
            rescheduleAll(context, alarms)

            prefs(context).edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            Log.d(TAG, "Synced ${alarms.size} alarms from API")
            Result.success(alarms)

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    fun schedule(context: Context, alarm: Alarm) {
        if (!alarm.enabled) return
        val fireTime = alarm.nextFireTime() ?: return
        alarmManager(context).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            fireTime.timeInMillis,
            pendingIntent(context, alarm)
        )
        Log.d(TAG, "Scheduled '${alarm.label}' for ${alarm.timeLabel()} on ${alarm.daysLabel()}")
    }

    fun cancel(context: Context, alarm: Alarm) =
        alarmManager(context).cancel(pendingIntent(context, alarm))

    fun rescheduleAll(context: Context, alarms: List<Alarm>) {
        // Cancel all existing before rescheduling (handles removed/disabled alarms)
        getAll(context).forEach { cancel(context, it) }
        alarms.filter { it.enabled }.forEach { schedule(context, it) }
    }

    fun scheduleAll(context: Context) =
        getAll(context).filter { it.enabled }.forEach { schedule(context, it) }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun pendingIntent(context: Context, alarm: Alarm): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra("alarm_id", alarm.id)
        return PendingIntent.getBroadcast(
            context, alarm.requestCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun saveAll(context: Context, alarms: List<Alarm>) {
        val arr = JSONArray().also { a -> alarms.forEach { a.put(it.toJson()) } }
        prefs(context).edit().putString(KEY_ALARMS, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
}
