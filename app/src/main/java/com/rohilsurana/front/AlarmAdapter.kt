package com.rohilsurana.front

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView

class AlarmAdapter(
    private val alarms: MutableList<Alarm>,
    private val onToggle: (alarm: Alarm, enabled: Boolean) -> Unit
) : RecyclerView.Adapter<AlarmAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime:    TextView    = view.findViewById(R.id.tvAlarmTime)
        val tvLabel:   TextView    = view.findViewById(R.id.tvAlarmLabel)
        val tvDays:    TextView    = view.findViewById(R.id.tvAlarmDays)
        val swEnabled: SwitchCompat = view.findViewById(R.id.switchAlarmEnabled)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alarm, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val alarm = alarms[position]
        holder.tvTime.text  = alarm.timeLabel()
        holder.tvLabel.text = alarm.label
        holder.tvDays.text  = alarm.daysLabel()

        // Set without triggering listener
        holder.swEnabled.setOnCheckedChangeListener(null)
        holder.swEnabled.isChecked = alarm.enabled

        holder.swEnabled.setOnCheckedChangeListener { _, checked ->
            // Update in-memory list so rapid toggling stays consistent
            alarms[holder.adapterPosition] = alarm.copy(enabled = checked)
            onToggle(alarm.copy(enabled = checked), checked)
        }
    }

    override fun getItemCount() = alarms.size

    fun setAlarms(newAlarms: List<Alarm>) {
        alarms.clear()
        alarms.addAll(newAlarms)
        notifyDataSetChanged()
    }
}
