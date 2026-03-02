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
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Foreground service responsible for:
 * 1. Acquiring a WakeLock (keeps CPU alive during TTS)
 * 2. Fetching alarm text from the configured server URL
 * 3. Falling back to the saved fallback string on any error
 * 4. Speaking the text via Android TextToSpeech
 * 5. Stopping itself when TTS finishes
 */
class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "FrontAlarmChannel"
        private const val NOTIFICATION_ID = 42
        private const val FETCH_TIMEOUT_MS = 5000 // 5 seconds to fetch before fallback
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
        // Must call startForeground immediately to avoid ANR
        startForeground(NOTIFICATION_ID, buildNotification("Alarm firing…"))

        // Acquire WakeLock — released after TTS finishes
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Front::AlarmWakeLock"
        ).also { it.acquire(60_000L) } // max 60s safety timeout

        // Read prefs
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(MainActivity.KEY_URL, "") ?: ""
        val fallback = prefs.getString(MainActivity.KEY_FALLBACK, "Wake up! Good morning!")
            ?: "Wake up! Good morning!"

        // Fetch text on a background thread, then kick off TTS
        executor.execute {
            val alarmText = fetchTextOrFallback(url, fallback)
            Log.d(TAG, "Alarm text: $alarmText")
            runTts(alarmText)
        }

        return START_NOT_STICKY
    }

    /**
     * Tries to GET plain text from [url]. Returns [fallback] on any error
     * (no internet, timeout, non-200 response, empty body, blank URL).
     */
    private fun fetchTextOrFallback(url: String, fallback: String): String {
        if (url.isBlank()) {
            Log.d(TAG, "No URL configured — using fallback")
            return fallback
        }
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = FETCH_TIMEOUT_MS
                readTimeout = FETCH_TIMEOUT_MS
                setRequestProperty("Accept", "text/plain")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader().readText().trim()
                if (body.isNotEmpty()) body else fallback
            } else {
                Log.w(TAG, "Server returned HTTP $code — using fallback")
                fallback
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error: ${e.message} — using fallback")
            fallback
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message} — using fallback")
            fallback
        }
    }

    /**
     * Initialises TTS and speaks [text]. Stops the service when done.
     * Runs on whichever thread calls it; TTS callbacks come on the main thread.
     */
    private fun runTts(text: String) {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = cleanup()
                    override fun onError(utteranceId: String?) = cleanup()
                    // Required for older API levels
                    @Deprecated("Deprecated in newer API")
                    override fun onError(utteranceId: String?, errorCode: Int) = cleanup()
                })

                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ALARM_UTTERANCE")
            } else {
                Log.e(TAG, "TTS init failed with status $status")
                cleanup()
            }
        }
    }

    private fun cleanup() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "TTS cleanup error: ${e.message}")
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Front Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Front alarm firing notification"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Front")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
