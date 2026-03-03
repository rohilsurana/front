package com.rohilsurana.front

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.Executors

class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "FrontAlarmChannel"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.rohilsurana.front.ACTION_STOP_ALARM"
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
        // Handle Stop tap from notification action
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received — stopping alarm")
            cleanup()
            return START_NOT_STICKY
        }

        // Resolve alarm info synchronously (SharedPrefs — no I/O delay)
        val alarmId  = intent?.getStringExtra("alarm_id")
        val alarm    = AlarmStore.getAll(this).find { it.id == alarmId }
        val label    = alarm?.label ?: "Front Alarm"
        val fallback = alarm?.fallback ?: "Wake up! Good morning!"
        val text     = alarm?.let { AlarmStore.getCachedText(this, it.id) } ?: fallback

        // Start foreground with rich notification immediately
        // On API 29+ we can pass the foreground service type explicitly (required on API 34+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(label, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(label, text))
        }

        Log.d(TAG, "Alarm [$alarmId] firing — label='$label', cached=${alarm != null}")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Front::AlarmWakeLock")
            .also { it.acquire(60_000L) }

        executor.execute {
            alarm?.let { scheduleNext(it) }
            runTts(text)
        }

        return START_NOT_STICKY
    }

    private fun scheduleNext(alarm: Alarm) {
        val fireTime = alarm.nextFireTime() ?: return
        val pi = AlarmStore.pendingIntent(this, alarm)
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, fireTime.timeInMillis, pi)
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
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Front Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows while an alarm is speaking"
                setShowBadge(true)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(label: String, text: String): Notification {
        // Tap notification → open app
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action → re-deliver to this service with STOP action
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, AlarmService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(label)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)        // can't be swiped away while ringing
            .setAutoCancel(false)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_stop_circle, "Stop", stopPi)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
