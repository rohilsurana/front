package com.rohilsurana.front

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AlarmStore {

    private const val TAG = "AlarmStore"
    const val PREFS_NAME        = "FrontAlarmPrefs"
    const val KEY_SERVER_URL    = "server_url"
    private const val KEY_ALARMS         = "alarms_json"
    private const val KEY_LAST_SYNC      = "last_sync_ms"
    private const val KEY_TEXT_PREFIX    = "alarm_text_"       // + alarm id
    private const val KEY_TEXT_SYNC_MS   = "alarm_text_sync_ms" // last text sync timestamp

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

            // Always persist — scheduling is best-effort (may lack permission)
            saveAll(context, alarms)
            prefs(context).edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            rescheduleAll(context, alarms)

            Log.d(TAG, "Synced ${alarms.size} alarms from API")
            Result.success(alarms)

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ── Text cache ────────────────────────────────────────────────────────────

    /** Returns the last successfully fetched TTS text for this alarm, or null if never synced. */
    fun getCachedText(context: Context, alarmId: String): String? =
        prefs(context).getString(KEY_TEXT_PREFIX + alarmId, null)

    fun cacheText(context: Context, alarmId: String, text: String) =
        prefs(context).edit().putString(KEY_TEXT_PREFIX + alarmId, text).apply()

    fun lastTextSyncMs(context: Context): Long =
        prefs(context).getLong(KEY_TEXT_SYNC_MS, 0L)

    /**
     * Fetches the TTS text for every enabled alarm and caches it.
     * Failures are silently skipped — stale cache is preserved.
     * Must be called from a background thread.
     * Returns true if at least one text was successfully fetched.
     */
    fun syncTextsFromApi(context: Context): Boolean {
        val baseUrl = getServerUrl(context).trimEnd('/')
        if (baseUrl.isEmpty()) return false

        var anySuccess = false
        getAll(context)
            .filter { it.enabled && it.textPath.isNotBlank() }
            .forEach { alarm ->
                try {
                    val conn = (URL("$baseUrl${alarm.textPath}").openConnection()
                            as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 5000
                        readTimeout = 5000
                    }
                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        val text = conn.inputStream.bufferedReader().readText().trim()
                        if (text.isNotEmpty()) {
                            cacheText(context, alarm.id, text)
                            anySuccess = true
                            Log.d(TAG, "Cached text for '${alarm.label}'")
                        }
                    } else {
                        Log.w(TAG, "Text fetch for '${alarm.id}' returned ${conn.responseCode} — keeping stale cache")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Text fetch for '${alarm.id}' failed: ${e.message} — keeping stale cache")
                }
            }

        if (anySuccess) {
            prefs(context).edit().putLong(KEY_TEXT_SYNC_MS, System.currentTimeMillis()).apply()
        }
        return anySuccess
    }

    // ── Scheduling ────────────────────────────────────────────────────────────

    fun canScheduleExact(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager(context).canScheduleExactAlarms()
        } else true
    }

    fun schedule(context: Context, alarm: Alarm) {
        if (!alarm.enabled) return
        if (!canScheduleExact(context)) {
            Log.w(TAG, "Missing SCHEDULE_EXACT_ALARM permission — skipping schedule for '${alarm.label}'")
            return
        }
        val fireTime = alarm.nextFireTime() ?: return
        try {
            alarmManager(context).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                fireTime.timeInMillis,
                pendingIntent(context, alarm)
            )
            Log.d(TAG, "Scheduled '${alarm.label}' for ${alarm.timeLabel()} on ${alarm.daysLabel()}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException scheduling '${alarm.label}': ${e.message}")
        }
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
