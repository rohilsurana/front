package com.rohilsurana.front

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AlarmAdapter(
    private val alarms: MutableList<Alarm>,
    private val onToggle: (Alarm, Boolean) -> Unit,
    private val onDelete: (Alarm) -> Unit,
    private val onEdit:   (Alarm) -> Unit
) : RecyclerView.Adapter<AlarmAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime:    TextView    = view.findViewById(R.id.tvAlarmTime)
        val tvLabel:   TextView    = view.findViewById(R.id.tvAlarmLabel)
        val swEnabled: Switch      = view.findViewById(R.id.swAlarmEnabled)
        val btnDelete: ImageButton = view.findViewById(R.id.btnAlarmDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alarm, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val alarm = alarms[position]

        holder.tvTime.text  = alarm.timeLabel()
        holder.tvLabel.text = alarm.label.ifEmpty { "Alarm" }
        holder.swEnabled.isChecked = alarm.enabled

        holder.swEnabled.setOnCheckedChangeListener(null) // avoid stale listeners
        holder.swEnabled.setOnCheckedChangeListener { _, checked -> onToggle(alarm, checked) }

        holder.btnDelete.setOnClickListener { onDelete(alarm) }
        holder.itemView.setOnClickListener  { onEdit(alarm) }
    }

    override fun getItemCount() = alarms.size

    fun setAlarms(newAlarms: List<Alarm>) {
        alarms.clear()
        alarms.addAll(newAlarms)
        notifyDataSetChanged()
    }
}
