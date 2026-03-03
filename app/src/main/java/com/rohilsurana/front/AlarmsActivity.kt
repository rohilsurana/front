package com.rohilsurana.front

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.rohilsurana.front.databinding.ActivityAlarmsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class AlarmsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmsBinding
    private lateinit var adapter: AlarmAdapter
    private val executor = Executors.newSingleThreadExecutor()

    private val autoSyncHandler = Handler(Looper.getMainLooper())
    private val autoSyncRunnable = object : Runnable {
        override fun run() {
            if (!isSyncing) sync()
            autoSyncHandler.postDelayed(this, AUTO_SYNC_INTERVAL_MS)
        }
    }
    private var isSyncing = false

    companion object {
        private const val AUTO_SYNC_INTERVAL_MS = 10_000L
    }

    /** Kick off a sync immediately then schedule repeating every 10s. */
    private fun startAutoSync() {
        autoSyncHandler.removeCallbacks(autoSyncRunnable)   // avoid duplicate schedules
        if (!isSyncing) sync()                               // immediate sync on open
        autoSyncHandler.postDelayed(autoSyncRunnable, AUTO_SYNC_INTERVAL_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Alarms"
            setDisplayHomeAsUpEnabled(true)
        }

        adapter = AlarmAdapter(
            alarms = AlarmStore.getAll(this).toMutableList(),
            onToggle = { alarm, enabled -> handleToggle(alarm, enabled) }
        )
        binding.rvAlarms.layoutManager = LinearLayoutManager(this)
        binding.rvAlarms.adapter = adapter

        updateSyncLabel()
        showEmpty()
        updatePermissionBanner()

        binding.btnSync.setOnClickListener { sync() }
        binding.btnGrantPermission.setOnClickListener { requestExactAlarmPermission() }
    }

    override fun onResume() {
        super.onResume()
        // Re-check permission in case user just came back from settings
        updatePermissionBanner()
        if (AlarmStore.canScheduleExact(this)) {
            // Permission just granted — reschedule any cached alarms
            AlarmStore.scheduleAll(this)
        }
        // Sync immediately on open, then every 10s while screen is visible
        startAutoSync()
    }

    override fun onPause() {
        super.onPause()
        // Stop auto-sync when screen is not visible
        autoSyncHandler.removeCallbacks(autoSyncRunnable)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun handleToggle(alarm: Alarm, enabled: Boolean) {
        // Update locally immediately
        AlarmStore.setEnabled(this, alarm.id, enabled)

        // Persist to server in background (best-effort — local state already updated)
        executor.execute {
            try {
                val baseUrl = AlarmStore.getServerUrl(this).trimEnd('/')
                val url = java.net.URL("$baseUrl/alarms/${alarm.id}")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "PATCH"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val body = """{"enabled":$enabled}""".toByteArray()
                conn.outputStream.use { it.write(body) }
                conn.responseCode // trigger request
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("AlarmsActivity", "Toggle server sync failed: ${e.message}")
            }
        }
    }

    private fun sync() {
        if (isSyncing) return
        isSyncing = true
        setSyncing(true)

        executor.execute {
            val result = AlarmStore.syncFromApi(this)

            runOnUiThread {
                isSyncing = false
                setSyncing(false)
                result.fold(
                    onSuccess = { alarms ->
                        adapter.setAlarms(alarms)
                        showEmpty()
                        updateSyncLabel()
                        updatePermissionBanner()
                        binding.tvSyncError.visibility = View.GONE
                        TextSyncWorker.syncNow(this)
                    },
                    onFailure = { err ->
                        binding.tvSyncError.text = "⚠️ Sync failed: ${err.message}"
                        binding.tvSyncError.visibility = View.VISIBLE
                        adapter.setAlarms(AlarmStore.getAll(this))
                        showEmpty()
                    }
                )
            }
        }
    }

    private fun updatePermissionBanner() {
        val hasPermission = AlarmStore.canScheduleExact(this)
        binding.layoutPermissionBanner.visibility =
            if (!hasPermission) View.VISIBLE else View.GONE
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        }
    }

    private fun setSyncing(syncing: Boolean) {
        binding.btnSync.isEnabled = !syncing
        binding.progressSync.visibility = if (syncing) View.VISIBLE else View.GONE
    }

    private fun updateSyncLabel() {
        val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        val scheduleMs = AlarmStore.lastSyncMs(this)
        val textMs = AlarmStore.lastTextSyncMs(this)
        binding.tvLastSync.text = buildString {
            append(if (scheduleMs == 0L) "Schedule: never synced"
                   else "Schedule: ${fmt.format(Date(scheduleMs))}")
            append("\n")
            append(if (textMs == 0L) "Text: never synced"
                   else "Text: ${fmt.format(Date(textMs))}")
        }
    }

    private fun showEmpty() {
        binding.tvEmpty.visibility =
            if (AlarmStore.getAll(this).isEmpty()) View.VISIBLE else View.GONE
    }
}
