package com.rohilsurana.front

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Alarms"
            setDisplayHomeAsUpEnabled(true)
        }

        adapter = AlarmAdapter(alarms = AlarmStore.getAll(this).toMutableList())
        binding.rvAlarms.layoutManager = LinearLayoutManager(this)
        binding.rvAlarms.adapter = adapter

        updateSyncLabel()
        showEmpty()

        binding.btnSync.setOnClickListener { sync() }

        // Auto-sync on open if no cached data
        if (AlarmStore.getAll(this).isEmpty()) sync()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun sync() {
        setSyncing(true)

        executor.execute {
            val result = AlarmStore.syncFromApi(this)

            runOnUiThread {
                setSyncing(false)
                result.fold(
                    onSuccess = { alarms ->
                        adapter.setAlarms(alarms)
                        showEmpty()
                        updateSyncLabel()
                        binding.tvSyncError.visibility = View.GONE
                        // Immediately fetch fresh text for the updated schedule
                        TextSyncWorker.syncNow(this)
                    },
                    onFailure = { err ->
                        binding.tvSyncError.text = "⚠️ Sync failed: ${err.message}"
                        binding.tvSyncError.visibility = View.VISIBLE
                        // Still refresh list from cache
                        adapter.setAlarms(AlarmStore.getAll(this))
                        showEmpty()
                    }
                )
            }
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
