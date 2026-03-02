package com.rohilsurana.front

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import java.util.Calendar

/**
 * Single source of truth for all alarm data and scheduling.
 * Persists to SharedPreferences as a JSON array.
 */
object AlarmStore {

    const val PREFS_NAME  = "FrontAlarmPrefs"
    const val KEY_SERVER_URL = "server_url"
    private const val KEY_ALARMS   = "alarms_json"
    private const val KEY_NEXT_ID  = "alarms_next_id"

    // ── CRUD ─────────────────────────────────────────────────────────────────

    fun getAll(context: Context): List<Alarm> {
        val json = prefs(context).getString(KEY_ALARMS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    fun add(context: Context, alarm: Alarm): Alarm {
        val withId = alarm.copy(id = nextId(context))
        val list = getAll(context).toMutableList().also { it.add(withId) }
        saveAll(context, list)
        return withId
    }

    fun update(context: Context, alarm: Alarm) {
        val list = getAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == alarm.id }
        if (idx >= 0) list[idx] = alarm
        saveAll(context, list)
    }

    fun delete(context: Context, id: Int) {
        saveAll(context, getAll(context).filter { it.id != id })
    }

    // ── Server URL ────────────────────────────────────────────────────────────

    fun getServerUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "") ?: ""

    fun saveServerUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_SERVER_URL, url).apply()

    // ── Scheduling ────────────────────────────────────────────────────────────

    fun schedule(context: Context, alarm: Alarm) {
        if (!alarm.enabled) return
        val pi = pendingIntent(context, alarm.id)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    fun cancel(context: Context, id: Int) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(pendingIntent(context, id))
    }

    fun scheduleAll(context: Context) =
        getAll(context).filter { it.enabled }.forEach { schedule(context, it) }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveAll(context: Context, alarms: List<Alarm>) {
        val arr = JSONArray().also { a -> alarms.forEach { a.put(it.toJson()) } }
        prefs(context).edit().putString(KEY_ALARMS, arr.toString()).apply()
    }

    private fun nextId(context: Context): Int {
        val p = prefs(context)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    fun pendingIntent(context: Context, alarmId: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra("alarm_id", alarmId)
        return PendingIntent.getBroadcast(
            context, alarmId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
