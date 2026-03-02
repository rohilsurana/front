package com.rohilsurana.front

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Alarm(
    val id: String,
    val label: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val days: List<String>,          // "mon","tue","wed","thu","fri","sat","sun"
    val textPath: String,            // path appended to base URL at fire time
    val fallback: String
) {
    fun timeLabel(): String = "%02d:%02d".format(hour, minute)

    fun daysLabel(): String {
        val all = listOf("mon","tue","wed","thu","fri","sat","sun")
        val weekdays = listOf("mon","tue","wed","thu","fri")
        val weekend = listOf("sat","sun")
        return when {
            days.containsAll(all)      -> "Every day"
            days.containsAll(weekdays) && days.none { it in weekend } -> "Weekdays"
            days.containsAll(weekend)  && days.none { it in weekdays } -> "Weekends"
            else -> days.joinToString(", ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        }
    }

    /** Stable non-negative Int for use as PendingIntent request code. */
    fun requestCode(): Int = id.hashCode().and(0x7FFFFFFF)

    /** Next Calendar this alarm should fire, accounting for days-of-week. */
    fun nextFireTime(): Calendar? {
        if (days.isEmpty()) return null
        val dayMap = mapOf(
            "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
            "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY,
            "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY,
            "sun" to Calendar.SUNDAY
        )
        val targetDays = days.mapNotNull { dayMap[it] }.toSet()
        val now = System.currentTimeMillis()

        // Check today through next 7 days
        for (daysAhead in 0..6) {
            val candidate = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, daysAhead)
            }
            if (candidate.get(Calendar.DAY_OF_WEEK) in targetDays
                && candidate.timeInMillis > now) {
                return candidate
            }
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("hour", hour)
        put("minute", minute)
        put("enabled", enabled)
        put("days", JSONArray(days))
        put("text_path", textPath)
        put("fallback", fallback)
    }

    companion object {
        fun fromJson(json: JSONObject): Alarm {
            val daysArr = json.optJSONArray("days")
            val days = if (daysArr != null) {
                (0 until daysArr.length()).map { daysArr.getString(it) }
            } else {
                listOf("mon","tue","wed","thu","fri","sat","sun")
            }
            return Alarm(
                id       = json.getString("id"),
                label    = json.optString("label", "Alarm"),
                hour     = json.getInt("hour"),
                minute   = json.getInt("minute"),
                enabled  = json.optBoolean("enabled", true),
                days     = days,
                textPath = json.optString("text_path", ""),
                fallback = json.optString("fallback", "Wake up! Good morning!")
            )
        }
    }
}
