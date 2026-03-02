package com.rohilsurana.front

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "FrontAlarmChannel"
        private const val NOTIFICATION_ID = 42
        private const val FETCH_TIMEOUT_MS = 5000
    }

    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Alarm firing…"))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Front::AlarmWakeLock")
            .also { it.acquire(60_000L) }

        val alarmId = intent?.getIntExtra("alarm_id", -1) ?: -1
        val alarm = AlarmStore.getAll(this).find { it.id == alarmId }
        val url      = AlarmStore.getServerUrl(this)
        val fallback = alarm?.fallback ?: "Wake up! Good morning!"

        executor.execute {
            val text = fetchTextOrFallback(url, fallback)
            Log.d(TAG, "Alarm [$alarmId] speaking: $text")

            // Re-schedule for tomorrow before TTS (ensures alarm persists even if TTS crashes)
            alarm?.let { scheduleForTomorrow(it) }

            runTts(text)
        }

        return START_NOT_STICKY
    }

    private fun fetchTextOrFallback(url: String, fallback: String): String {
        if (url.isBlank()) return fallback
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = FETCH_TIMEOUT_MS
                readTimeout = FETCH_TIMEOUT_MS
                setRequestProperty("Accept", "text/plain")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader().readText().trim()
                if (body.isNotEmpty()) body else fallback
            } else {
                Log.w(TAG, "HTTP ${conn.responseCode} — using fallback")
                fallback
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error: ${e.message} — using fallback")
            fallback
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected: ${e.message} — using fallback")
            fallback
        }
    }

    private fun scheduleForTomorrow(alarm: Alarm) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = AlarmStore.pendingIntent(this, alarm.id)
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    private fun runTts(text: String) {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = cleanup()
                    override fun onError(utteranceId: String?) = cleanup()
                    @Deprecated("Deprecated in newer API")
                    override fun onError(utteranceId: String?, errorCode: Int) = cleanup()
                })
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ALARM_UTTERANCE")
            } else {
                Log.e(TAG, "TTS init failed ($status)")
                cleanup()
            }
        }
    }

    private fun cleanup() {
        try { tts?.stop(); tts?.shutdown(); tts = null } catch (e: Exception) { /* ignore */ }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Front Alarm", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Front alarm firing notification" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Front")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .build()

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
