package com.ssimo.papere

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.ssimo.papere.databinding.ActivityLogsBinding

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private val handler = Handler(Looper.getMainLooper())
    private val logUpdater = object : Runnable {
        override fun run() {
            updateLogs()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener {
            finish()
        }

        updateLogs()
    }

    override fun onResume() {
        super.onResume()
        handler.post(logUpdater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(logUpdater)
    }

    private fun updateLogs() {
        val logs = Logger.getLogs(this).reversed()
        binding.logText.text = logs.joinToString("\n")
        
        // Scroll to bottom
        binding.logScrollView.post {
            binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
