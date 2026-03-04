package com.rohilsurana.front

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import com.rohilsurana.front.databinding.ActivityMetricsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MetricsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMetricsBinding

    // Interval row views — resolved from included layouts
    private lateinit var gpsMinus:    Button
    private lateinit var gpsPlus:     Button
    private lateinit var gpsValue:    TextView
    private lateinit var battMinus:   Button
    private lateinit var battPlus:    Button
    private lateinit var battValue:   TextView
    private lateinit var uploadMinus: Button
    private lateinit var uploadPlus:  Button
    private lateinit var uploadValue: TextView

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMetricsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Metrics"
            setDisplayHomeAsUpEnabled(true)
        }

        // Resolve included layout views
        val gpsRow    = binding.intervalGps.root
        val battRow   = binding.intervalBattery.root
        val uploadRow = binding.intervalUpload.root
        gpsMinus    = gpsRow.findViewById(R.id.btnMinus)
        gpsPlus     = gpsRow.findViewById(R.id.btnPlus)
        gpsValue    = gpsRow.findViewById(R.id.tvValue)
        battMinus   = battRow.findViewById(R.id.btnMinus)
        battPlus    = battRow.findViewById(R.id.btnPlus)
        battValue   = battRow.findViewById(R.id.tvValue)
        uploadMinus = uploadRow.findViewById(R.id.btnMinus)
        uploadPlus  = uploadRow.findViewById(R.id.btnPlus)
        uploadValue = uploadRow.findViewById(R.id.tvValue)

        setupGps()
        setupBattery()
        setupUploadInterval()
        binding.btnGrantPermission.setOnClickListener { openAppSettings() }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        updateIntervalDisplay(MetricsStore.NAME_GPS, gpsValue)
        updateIntervalDisplay(MetricsStore.NAME_BATTERY, battValue)
        updateUploadIntervalDisplay()
        updateStatusCard()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── GPS ───────────────────────────────────────────────────────────────────

    private fun setupGps() {
        val enabled = MetricsStore.isEnabled(this, MetricsStore.NAME_GPS)
        binding.switchGpsEnabled.isChecked = enabled
        setIntervalVisible(binding.intervalGps.root, enabled)

        binding.switchGpsEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasLocationPermission()) {
                binding.switchGpsEnabled.isChecked = false
                requestLocationPermission()
                return@setOnCheckedChangeListener
            }
            onMetricToggled(MetricsStore.NAME_GPS, checked)
            setIntervalVisible(binding.intervalGps.root, checked)
        }

        setupIntervalButtons(MetricsStore.NAME_GPS, gpsMinus, gpsPlus, gpsValue)
    }

    // ── Battery ───────────────────────────────────────────────────────────────

    private fun setupBattery() {
        val enabled = MetricsStore.isEnabled(this, MetricsStore.NAME_BATTERY)
        binding.switchBatteryEnabled.isChecked = enabled
        setIntervalVisible(binding.intervalBattery.root, enabled)

        binding.switchBatteryEnabled.setOnCheckedChangeListener { _, checked ->
            onMetricToggled(MetricsStore.NAME_BATTERY, checked)
            setIntervalVisible(binding.intervalBattery.root, checked)
        }

        setupIntervalButtons(MetricsStore.NAME_BATTERY, battMinus, battPlus, battValue)
    }

    // ── Shared metric helpers ─────────────────────────────────────────────────

    private fun onMetricToggled(name: String, enabled: Boolean) {
        MetricsStore.setEnabled(this, name, enabled)
        if (enabled) {
            MetricCollectWorker.enqueue(this, name)
            MetricsUploadWorker.enqueue(this)   // idempotent — KEEP policy
        } else {
            MetricCollectWorker.cancel(this, name)
            // Upload worker stays alive — still uploads buffered points
        }
        updateStatusCard()
    }

    private fun setupIntervalButtons(
        name: String,
        minus: Button,
        plus: Button,
        valueLabel: TextView
    ) {
        minus.setOnClickListener {
            val cur = MetricsStore.getInterval(this, name)
            if (cur > 1) {
                MetricsStore.setInterval(this, name, cur - 1)
                updateIntervalDisplay(name, valueLabel)
                if (MetricsStore.isEnabled(this, name)) MetricCollectWorker.enqueue(this, name)
            }
        }
        plus.setOnClickListener {
            val cur = MetricsStore.getInterval(this, name)
            if (cur < 60) {
                MetricsStore.setInterval(this, name, cur + 1)
                updateIntervalDisplay(name, valueLabel)
                if (MetricsStore.isEnabled(this, name)) MetricCollectWorker.enqueue(this, name)
            }
        }
    }

    private fun updateIntervalDisplay(name: String, label: TextView) {
        label.text = "${MetricsStore.getInterval(this, name)} min"
    }

    // ── Upload interval ───────────────────────────────────────────────────────

    private fun setupUploadInterval() {
        updateUploadIntervalDisplay()
        uploadMinus.setOnClickListener {
            val cur = MetricsStore.getUploadInterval(this)
            if (cur > 5) {
                MetricsStore.setUploadInterval(this, cur - 5)
                updateUploadIntervalDisplay()
                if (MetricsStore.anyEnabled(this)) MetricsUploadWorker.enqueue(this)
            }
        }
        uploadPlus.setOnClickListener {
            val cur = MetricsStore.getUploadInterval(this)
            if (cur < 120) {
                MetricsStore.setUploadInterval(this, cur + 5)
                updateUploadIntervalDisplay()
                if (MetricsStore.anyEnabled(this)) MetricsUploadWorker.enqueue(this)
            }
        }
    }

    private fun updateUploadIntervalDisplay() {
        uploadValue.text = "${MetricsStore.getUploadInterval(this)} min"
    }

    // ── Status card ───────────────────────────────────────────────────────────

    private fun updateStatusCard() {
        val anyEnabled = MetricsStore.anyEnabled(this)
        binding.cardStatus.visibility = if (anyEnabled) View.VISIBLE else View.GONE

        val buffered = MetricsStore.getBufferSize(this)
        binding.tvBufferStatus.text = when (buffered) {
            0    -> "No points buffered"
            1    -> "1 point buffered"
            else -> "$buffered points buffered"
        }

        val lastMs = MetricsStore.getLastUploadMs(this)
        binding.tvLastUpload.text = if (lastMs == 0L) "Never uploaded"
        else "Last upload: ${SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(lastMs))}"
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun updatePermissionBanner() {
        binding.layoutPermissionBanner.visibility =
            if (!hasLocationPermission()) View.VISIBLE else View.GONE
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQUEST_LOCATION_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            updatePermissionBanner()
            binding.switchGpsEnabled.isChecked = true
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    // ── Visibility helpers ────────────────────────────────────────────────────

    private fun setIntervalVisible(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
