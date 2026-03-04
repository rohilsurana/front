package com.rohilsurana.front

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
        private const val REQUEST_BACKGROUND_LOCATION = 1002
        private const val UI_REFRESH_MS               = 3_000L
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatusCard()
            refreshHandler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMetricsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestOptionalPermissions()

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
        setupMetricCard(MetricsStore.NAME_STEPS,      binding.switchStepsEnabled,      binding.intervalSteps.root,      R.drawable.ic_directions_walk)
        setupMetricCard(MetricsStore.NAME_ACTIVITY,   binding.switchActivityEnabled,   binding.intervalActivity.root,   R.drawable.ic_directions_run)
        setupMetricCard(MetricsStore.NAME_WIFI,       binding.switchWifiEnabled,       binding.intervalWifi.root,       R.drawable.ic_wifi)
        setupMetricCard(MetricsStore.NAME_SCREEN,     binding.switchScreenEnabled,     binding.intervalScreen.root,     R.drawable.ic_phone_android)
        setupMetricCard(MetricsStore.NAME_CONNECTION, binding.switchConnectionEnabled, binding.intervalConnection.root, R.drawable.ic_signal_cellular_alt)
        setupUploadInterval()
        binding.btnGrantPermission.setOnClickListener { openAppSettings() }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        updateIntervalDisplay(MetricsStore.NAME_GPS,        gpsValue)
        updateIntervalDisplay(MetricsStore.NAME_BATTERY,    battValue)
        updateIntervalDisplay(MetricsStore.NAME_STEPS,      binding.intervalSteps.root.findViewById(R.id.tvValue))
        updateIntervalDisplay(MetricsStore.NAME_ACTIVITY,   binding.intervalActivity.root.findViewById(R.id.tvValue))
        updateIntervalDisplay(MetricsStore.NAME_WIFI,       binding.intervalWifi.root.findViewById(R.id.tvValue))
        updateIntervalDisplay(MetricsStore.NAME_SCREEN,     binding.intervalScreen.root.findViewById(R.id.tvValue))
        updateIntervalDisplay(MetricsStore.NAME_CONNECTION, binding.intervalConnection.root.findViewById(R.id.tvValue))
        updateUploadIntervalDisplay()
        updateStatusCard()
        refreshHandler.postDelayed(refreshRunnable, UI_REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── GPS ───────────────────────────────────────────────────────────────────

    private fun setupGps() {
        val enabled = MetricsStore.isEnabled(this, MetricsStore.NAME_GPS)
        binding.switchGpsEnabled.isChecked = enabled
        setIntervalVisible(binding.intervalGps.root, enabled)

        setRowIcon(binding.intervalGps.root, R.drawable.ic_description)

        binding.switchGpsEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasFineLocationPermission()) {
                binding.switchGpsEnabled.isChecked = false
                requestLocationPermission()
                return@setOnCheckedChangeListener
            }
            if (checked && !hasBackgroundLocationPermission()) {
                // Fine location granted but not background — still allow toggle,
                // but prompt user to upgrade. Worker will fall back to foreground fixes.
                requestBackgroundLocationPermission()
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

        setRowIcon(binding.intervalBattery.root, R.drawable.ic_description)

        binding.switchBatteryEnabled.setOnCheckedChangeListener { _, checked ->
            onMetricToggled(MetricsStore.NAME_BATTERY, checked)
            setIntervalVisible(binding.intervalBattery.root, checked)
        }

        setupIntervalButtons(MetricsStore.NAME_BATTERY, battMinus, battPlus, battValue)
    }

    // ── Generic metric card setup ─────────────────────────────────────────────

    private fun setupMetricCard(name: String, switch: SwitchCompat, intervalRow: View, iconRes: Int) {
        setRowIcon(intervalRow, iconRes)
        val enabled = MetricsStore.isEnabled(this, name)
        switch.isChecked = enabled
        setIntervalVisible(intervalRow, enabled)
        switch.setOnCheckedChangeListener { _, checked ->
            onMetricToggled(name, checked)
            setIntervalVisible(intervalRow, checked)
        }
        setupIntervalButtons(name,
            intervalRow.findViewById(R.id.btnMinus),
            intervalRow.findViewById(R.id.btnPlus),
            intervalRow.findViewById(R.id.tvValue))
    }

    // ── Shared metric helpers ─────────────────────────────────────────────────

    private fun onMetricToggled(name: String, enabled: Boolean) {
        MetricsStore.setEnabled(this, name, enabled)
        if (enabled) {
            MetricCollectWorker.enqueueNow(this, name)  // immediate first collection
            MetricsUploadWorker.enqueue(this)
        } else {
            MetricCollectWorker.cancel(this, name)
            // Upload worker stays alive — still uploads buffered points
        }
        updateStatusCard()
    }

    private fun setRowIcon(row: View, iconRes: Int) {
        row.findViewById<android.widget.ImageView>(R.id.ivRowIcon).apply {
            setImageResource(iconRes)
            visibility = View.VISIBLE
        }
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
        binding.intervalUpload.root.apply {
            findViewById<TextView>(R.id.tvLabel).text = "Upload every"
            setRowIcon(this, R.drawable.ic_upload)
        }
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
            1    -> "1 point buffered · tap to inspect"
            else -> "$buffered points buffered · tap to inspect"
        }

        binding.tvBufferStatus.setOnClickListener { showBufferDebugDialog() }

        val lastMs = MetricsStore.getLastUploadMs(this)
        binding.tvLastUpload.text = if (lastMs == 0L) "Never uploaded"
        else "Last upload: ${SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(lastMs))}"
    }

    private fun showBufferDebugDialog() {
        val points = MetricsStore.getBufferedPoints(this)

        val gpsStatus    = MetricsStore.getGpsStatus(this)
        val uploadStatus = MetricsStore.getUploadStatus(this)
        val serverUrl    = this.getSharedPreferences(AlarmStore.PREFS_NAME, MODE_PRIVATE)
            .getString(AlarmStore.KEY_SERVER_URL, "(not set)") ?: "(not set)"

        val bufferText = if (points.isEmpty()) "Buffer is empty."
        else points.joinToString("\n\n") { p ->
            val ts = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date(p.ts))
            val fields = p.fields.entries.joinToString(", ") { "${it.key}=${it.value}" }
            "[${p.type}/${p.name}] $ts\n$fields"
        }

        val text = """
            |── GPS status ──
            |$gpsStatus
            |
            |── Upload status ──
            |$uploadStatus
            |Server: $serverUrl
            |
            |── Buffer (${points.size} pts) ──
            |$bufferText
        """.trimMargin()

        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(48, 32, 48, 32)
        }
        val scroll = ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle("Buffered points (${points.size})")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .setNeutralButton("Clear buffer") { _, _ ->
                MetricsStore.clearBuffer(this)
                updateStatusCard()
            }
            .setNegativeButton("Collect now") { _, _ ->
                // Trigger immediate collection for all enabled metrics — bypasses WorkManager scheduling
                if (MetricsStore.isEnabled(this, MetricsStore.NAME_GPS))
                    MetricCollectWorker.enqueueNow(this, MetricsStore.NAME_GPS)
                if (MetricsStore.isEnabled(this, MetricsStore.NAME_BATTERY))
                    MetricCollectWorker.enqueueNow(this, MetricsStore.NAME_BATTERY)
            }
            .show()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun hasFineLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true  // < Android 10 — background location bundled with fine

    private fun hasLocationPermission(): Boolean =
        hasFineLocationPermission() && hasBackgroundLocationPermission()

    private fun updatePermissionBanner() {
        binding.layoutPermissionBanner.visibility =
            if (!hasLocationPermission()) View.VISIBLE else View.GONE

        // Update banner text based on which permission is missing
        if (!hasFineLocationPermission()) {
            binding.layoutPermissionBanner.findViewById<TextView>(
                android.R.id.text1
            )  // falls back to default banner text — fine
        }
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

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("Background location needed")
                .setMessage(
                    "To record your GPS location while the app is in the background, " +
                    "please set location access to \"Allow all the time\" in settings."
                )
                .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_LOCATION_PERMISSION -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    updatePermissionBanner()
                    // Now ask for background location separately (Android 10+ requirement)
                    if (!hasBackgroundLocationPermission()) {
                        requestBackgroundLocationPermission()
                    } else {
                        binding.switchGpsEnabled.isChecked = true
                    }
                }
            }
        }
    }

    private fun requestOptionalPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.READ_PHONE_STATE)
        if (perms.isNotEmpty())
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1003)
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
