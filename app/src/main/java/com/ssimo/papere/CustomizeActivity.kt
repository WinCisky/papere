package com.ssimo.papere

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ssimo.papere.databinding.ActivityCustomizeBinding

class CustomizeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomizeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomizeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPreference(binding.requireWifiSwitch, "require_wifi")
        setupPreference(binding.requireBatterySwitch, "require_battery")
        setupPreference(binding.checkboxNature, "checkbox_nature")
        setupPreference(binding.checkboxLandscapes, "checkbox_landscapes")
        setupPreference(binding.checkboxSunsets, "checkbox_sunsets")
        setupPreference(binding.checkboxAnimals, "checkbox_animals")
        setupPreference(binding.checkboxFlora, "checkbox_flora")

        val prefs = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)
        val currentFreq = prefs.getLong("update_frequency_hours", 4L)
        when (currentFreq) {
            2L -> binding.radioFreqMore.isChecked = true
            8L -> binding.radioFreqLess.isChecked = true
            else -> binding.radioFreqDefault.isChecked = true
        }

        binding.updateFrequencyGroup.setOnCheckedChangeListener { _, checkedId ->
            val hours = when (checkedId) {
                R.id.radio_freq_more -> 2L
                R.id.radio_freq_less -> 8L
                else -> 4L
            }
            prefs.edit().putLong("update_frequency_hours", hours).apply()
        }

        binding.viewLogsButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.backHomeButton.setOnClickListener {
            finish()
        }
    }

    private fun setupPreference(compoundButton: android.widget.CompoundButton, key: String, defaultValue: Boolean = true) {
        val prefs = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)
        compoundButton.isChecked = prefs.getBoolean(key, defaultValue)
        compoundButton.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(key, isChecked).apply()
        }
    }
}
