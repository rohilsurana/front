package com.rohilsurana.front

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AlarmAdapter(
    private val alarms: MutableList<Alarm>
) : RecyclerView.Adapter<AlarmAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime:    TextView = view.findViewById(R.id.tvAlarmTime)
        val tvLabel:   TextView = view.findViewById(R.id.tvAlarmLabel)
        val tvDays:    TextView = view.findViewById(R.id.tvAlarmDays)
        val tvEnabled: TextView = view.findViewById(R.id.tvAlarmEnabled)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alarm, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val alarm = alarms[position]
        holder.tvTime.text    = alarm.timeLabel()
        holder.tvLabel.text   = alarm.label
        holder.tvDays.text    = alarm.daysLabel()
        holder.tvEnabled.text = if (alarm.enabled) "ON" else "OFF"
        holder.tvEnabled.setTextColor(
            if (alarm.enabled) 0xFF2E7D32.toInt() else 0xFF9E9E9E.toInt()
        )
        holder.tvEnabled.setBackgroundResource(
            if (alarm.enabled) R.drawable.bg_badge_on else R.drawable.bg_badge_off
        )
    }

    override fun getItemCount() = alarms.size

    fun setAlarms(newAlarms: List<Alarm>) {
        alarms.clear()
        alarms.addAll(newAlarms)
        notifyDataSetChanged()
    }
}
