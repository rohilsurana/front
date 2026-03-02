package com.rohilsurana.front

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rohilsurana.front.databinding.ActivityMainBinding
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etServerUrl.setText(AlarmStore.getServerUrl(this))

        binding.btnTestUrl.setOnClickListener { testUrl() }
        binding.btnSaveUrl.setOnClickListener { saveUrl() }

        // Ensure 20-min text sync is always scheduled
        TextSyncWorker.schedulePeriodic(this)

        binding.tileAlarms.setOnClickListener {
            startActivity(Intent(this, AlarmsActivity::class.java))
        }
        binding.tileMetrics.setOnClickListener {
            startActivity(Intent(this, MetricsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-load URL in case it was changed elsewhere
        binding.etServerUrl.setText(AlarmStore.getServerUrl(this))
    }

    private fun saveUrl() {
        val url = binding.etServerUrl.text.toString().trim()
        AlarmStore.saveServerUrl(this, url)
        Toast.makeText(this, "Endpoint saved", Toast.LENGTH_SHORT).show()
    }

    private fun testUrl() {
        val url = binding.etServerUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter a URL to test", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTestUrl.isEnabled = false
        binding.tvTestResult.visibility = View.VISIBLE
        binding.tvTestResult.setTextColor(0xFF888888.toInt())
        binding.tvTestResult.text = "⏳ Connecting…"

        executor.execute {
            val (success, message) = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Accept", "text/plain")
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().readText().trim()
                    if (body.isNotEmpty()) true to "✅ Got response:\n\"$body\""
                    else false to "⚠️ Server replied 200 but body was empty"
                } else {
                    false to "❌ Server returned HTTP $code"
                }
            } catch (e: IOException) {
                false to "❌ Connection failed: ${e.message}"
            } catch (e: Exception) {
                false to "❌ Error: ${e.message}"
            }

            runOnUiThread {
                binding.btnTestUrl.isEnabled = true
                binding.tvTestResult.visibility = View.VISIBLE
                binding.tvTestResult.setTextColor(
                    if (success) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
                )
                binding.tvTestResult.text = message
            }
        }
    }
}
