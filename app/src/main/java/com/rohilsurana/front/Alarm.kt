package com.rohilsurana.front

import org.json.JSONObject

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val label: String = "",
    val fallback: String = "Wake up! Good morning!"
) {
    fun timeLabel(): String = "%02d:%02d".format(hour, minute)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("enabled", enabled)
        put("label", label)
        put("fallback", fallback)
    }

    companion object {
        fun fromJson(json: JSONObject) = Alarm(
            id       = json.getInt("id"),
            hour     = json.getInt("hour"),
            minute   = json.getInt("minute"),
            enabled  = json.getBoolean("enabled"),
            label    = json.optString("label", ""),
            fallback = json.optString("fallback", "Wake up! Good morning!")
        )
    }
}
