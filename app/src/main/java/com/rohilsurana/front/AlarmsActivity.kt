package com.rohilsurana.front

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TimePicker
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.rohilsurana.front.databinding.ActivityAlarmsBinding

class AlarmsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmsBinding
    private lateinit var adapter: AlarmAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Alarms"
            setDisplayHomeAsUpEnabled(true)
        }

        checkExactAlarmPermission()

        adapter = AlarmAdapter(
            alarms   = AlarmStore.getAll(this).toMutableList(),
            onToggle = ::handleToggle,
            onDelete = ::handleDelete,
            onEdit   = ::showEditDialog
        )

        binding.rvAlarms.layoutManager = LinearLayoutManager(this)
        binding.rvAlarms.adapter = adapter

        binding.fabAddAlarm.setOnClickListener { showEditDialog(null) }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() = adapter.setAlarms(AlarmStore.getAll(this))

    private fun handleToggle(alarm: Alarm, enabled: Boolean) {
        val updated = alarm.copy(enabled = enabled)
        AlarmStore.update(this, updated)
        if (enabled) AlarmStore.schedule(this, updated)
        else AlarmStore.cancel(this, alarm.id)
    }

    private fun handleDelete(alarm: Alarm) {
        AlertDialog.Builder(this)
            .setTitle("Delete alarm?")
            .setMessage("\"${alarm.label.ifEmpty { alarm.timeLabel() }}\" will be removed.")
            .setPositiveButton("Delete") { _, _ ->
                AlarmStore.cancel(this, alarm.id)
                AlarmStore.delete(this, alarm.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Opens the add/edit dialog. Pass null to create a new alarm. */
    private fun showEditDialog(existing: Alarm?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_alarm_edit, null)
        val timePicker: TimePicker = dialogView.findViewById(R.id.dialogTimePicker)
        val etLabel:    EditText   = dialogView.findViewById(R.id.dialogEtLabel)
        val etFallback: EditText   = dialogView.findViewById(R.id.dialogEtFallback)

        timePicker.setIs24HourView(true)

        // Pre-fill for edit
        existing?.let {
            timePicker.hour   = it.hour
            timePicker.minute = it.minute
            etLabel.setText(it.label)
            etFallback.setText(it.fallback)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add alarm" else "Edit alarm")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val alarm = Alarm(
                    id       = existing?.id ?: 0,
                    hour     = timePicker.hour,
                    minute   = timePicker.minute,
                    enabled  = existing?.enabled ?: true,
                    label    = etLabel.text.toString().trim(),
                    fallback = etFallback.text.toString().trim()
                        .ifEmpty { "Wake up! Good morning!" }
                )
                if (existing == null) {
                    val added = AlarmStore.add(this, alarm)
                    if (added.enabled) AlarmStore.schedule(this, added)
                    Toast.makeText(this, "Alarm set for ${added.timeLabel()}", Toast.LENGTH_SHORT).show()
                } else {
                    AlarmStore.update(this, alarm)
                    AlarmStore.cancel(this, alarm.id)
                    if (alarm.enabled) AlarmStore.schedule(this, alarm)
                    Toast.makeText(this, "Alarm updated", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                Toast.makeText(this, "Please grant exact alarm permission", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }
    }
}
