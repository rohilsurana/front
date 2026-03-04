package com.rohilsurana.front

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.rohilsurana.front.databinding.ActivityMetricsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MetricsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMetricsBinding

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

        setupToggle()
        setupIntervalButtons()
        binding.btnGrantPermission.setOnClickListener { openAppSettings() }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        updateIntervalDisplay()
        updateStatusCard()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Toggle ────────────────────────────────────────────────────────────────

    private fun setupToggle() {
        val enabled = MetricsStore.isGpsEnabled(this)
        binding.switchGpsEnabled.isChecked = enabled
        setIntervalVisible(enabled)
        setStatusVisible(enabled)

        binding.switchGpsEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasLocationPermission()) {
                // Ask for permission — revert toggle until granted
                binding.switchGpsEnabled.isChecked = false
                requestLocationPermission()
                return@setOnCheckedChangeListener
            }
            MetricsStore.setGpsEnabled(this, checked)
            if (checked) {
                GpsWorker.enqueue(this)
                GpsUploadWorker.enqueue(this)
            } else {
                GpsWorker.cancel(this)
                // GpsUploadWorker keeps running — it checks the flag and skips if disabled
            }
            setIntervalVisible(checked)
            setStatusVisible(checked)
            updateStatusCard()
        }
    }

    // ── Interval buttons ──────────────────────────────────────────────────────

    private fun setupIntervalButtons() {
        binding.btnIntervalMinus.setOnClickListener {
            val current = MetricsStore.getIntervalMinutes(this)
            if (current > 1) {
                MetricsStore.setIntervalMinutes(this, current - 1)
                updateIntervalDisplay()
                // Re-enqueue with new interval if enabled
                if (MetricsStore.isGpsEnabled(this)) GpsWorker.enqueue(this)
            }
        }
        binding.btnIntervalPlus.setOnClickListener {
            val current = MetricsStore.getIntervalMinutes(this)
            if (current < 60) {
                MetricsStore.setIntervalMinutes(this, current + 1)
                updateIntervalDisplay()
                if (MetricsStore.isGpsEnabled(this)) GpsWorker.enqueue(this)
            }
        }
    }

    private fun updateIntervalDisplay() {
        val min = MetricsStore.getIntervalMinutes(this)
        binding.tvIntervalValue.text = "$min min"
    }

    // ── Status card ───────────────────────────────────────────────────────────

    private fun updateStatusCard() {
        val buffered = MetricsStore.getBufferSize(this)
        binding.tvBufferStatus.text = when (buffered) {
            0 -> "No points buffered"
            1 -> "1 point buffered"
            else -> "$buffered points buffered"
        }

        val lastUploadMs = MetricsStore.getLastUploadMs(this)
        binding.tvLastUpload.text = if (lastUploadMs == 0L) {
            "Never uploaded"
        } else {
            val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            "Last upload: ${fmt.format(Date(lastUploadMs))}"
        }
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
            // Re-enable toggle now that permission is granted
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

    private fun setIntervalVisible(visible: Boolean) {
        binding.layoutInterval.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setStatusVisible(visible: Boolean) {
        binding.cardStatus.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
