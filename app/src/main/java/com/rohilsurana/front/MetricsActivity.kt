package com.rohilsurana.front

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rohilsurana.front.databinding.ActivityMetricsBinding

class MetricsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMetricsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMetricsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Metrics"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
