package com.rohilsurana.front

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rohilsurana.front.databinding.ActivityMainBinding
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        const val PREFS_NAME = "FrontAlarmPrefs"
        const val KEY_URL = "alarm_url"
        const val KEY_FALLBACK = "alarm_fallback"
        const val KEY_HOUR = "alarm_hour"
        const val KEY_MINUTE = "alarm_minute"
        const val KEY_ACTIVE = "alarm_active"
        const val ALARM_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        restoreSavedState()

        binding.btnSetAlarm.setOnClickListener { scheduleAlarm() }
        binding.btnCancelAlarm.setOnClickListener { cancelAlarm() }
        binding.btnTestUrl.setOnClickListener { testUrl() }

        // On Android 12+ we need exact alarm permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this, "Please grant exact alarm permission", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }
    }

    private fun restoreSavedState() {
        binding.timePicker.setIs24HourView(true)
        val hour = prefs.getInt(KEY_HOUR, 7)
        val minute = prefs.getInt(KEY_MINUTE, 0)
        binding.timePicker.hour = hour
        binding.timePicker.minute = minute
        binding.etUrl.setText(prefs.getString(KEY_URL, ""))
        binding.etFallback.setText(prefs.getString(KEY_FALLBACK, "Wake up! Good morning!"))

        val active = prefs.getBoolean(KEY_ACTIVE, false)
        binding.tvStatus.text = if (active) "Alarm is SET ✅" else "No alarm set"
    }

    private fun testUrl() {
        val url = binding.etUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a URL to test", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTestUrl.isEnabled = false
        binding.tvTestResult.visibility = View.VISIBLE
        binding.tvTestResult.setTextColor(0xFF888888.toInt())
        binding.tvTestResult.text = "⏳ Connecting…"

        executor.execute {
            val (success, message) = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Accept", "text/plain")
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().readText().trim()
                    if (body.isNotEmpty())
                        true to "✅ Got response:\n\"$body\""
                    else
                        false to "⚠️ Server replied 200 but body was empty"
                } else {
                    false to "❌ Server returned HTTP $code"
                }
            } catch (e: IOException) {
                false to "❌ Connection failed: ${e.message}"
            } catch (e: Exception) {
                false to "❌ Error: ${e.message}"
            }

            runOnUiThread {
                binding.btnTestUrl.isEnabled = true
                binding.tvTestResult.visibility = View.VISIBLE
                binding.tvTestResult.setTextColor(
                    if (success) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
                )
                binding.tvTestResult.text = message
            }
        }
    }

    private fun scheduleAlarm() {
        val hour = binding.timePicker.hour
        val minute = binding.timePicker.minute
        val url = binding.etUrl.text.toString().trim()
        val fallback = binding.etFallback.text.toString().trim().ifEmpty { "Wake up! Good morning!" }

        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a server URL (or leave fallback only)", Toast.LENGTH_SHORT).show()
            // Allow empty URL — will just use fallback
        }

        // Save settings
        prefs.edit()
            .putInt(KEY_HOUR, hour)
            .putInt(KEY_MINUTE, minute)
            .putString(KEY_URL, url)
            .putString(KEY_FALLBACK, fallback)
            .putBoolean(KEY_ACTIVE, true)
            .apply()

        // Calculate next trigger time
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If time already passed today, schedule for tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )

        val timeStr = "%02d:%02d".format(hour, minute)
        binding.tvStatus.text = "Alarm set for $timeStr ✅"
        Toast.makeText(this, "Alarm set for $timeStr", Toast.LENGTH_SHORT).show()
    }

    private fun cancelAlarm() {
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)

        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        binding.tvStatus.text = "Alarm cancelled ❌"
        Toast.makeText(this, "Alarm cancelled", Toast.LENGTH_SHORT).show()
    }
}
