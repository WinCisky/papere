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

        val prefs = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)

        binding.requireWifiSwitch.isChecked = prefs.getBoolean("require_wifi", true)
        binding.requireWifiSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("require_wifi", isChecked).apply()
        }

        binding.requireBatterySwitch.isChecked = prefs.getBoolean("require_battery", true)
        binding.requireBatterySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("require_battery", isChecked).apply()
        }

        binding.checkboxNature.isChecked = prefs.getBoolean("checkbox_nature", true)
        binding.checkboxNature.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("checkbox_nature", isChecked).apply()
        }

        binding.checkboxLandscapes.isChecked = prefs.getBoolean("checkbox_landscapes", true)
        binding.checkboxLandscapes.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("checkbox_landscapes", isChecked).apply()
        }

        binding.checkboxSunsets.isChecked = prefs.getBoolean("checkbox_sunsets", true)
        binding.checkboxSunsets.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("checkbox_sunsets", isChecked).apply()
        }

        binding.checkboxAnimals.isChecked = prefs.getBoolean("checkbox_animals", true)
        binding.checkboxAnimals.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("checkbox_animals", isChecked).apply()
        }

        binding.checkboxFlora.isChecked = prefs.getBoolean("checkbox_flora", true)
        binding.checkboxFlora.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("checkbox_flora", isChecked).apply()
        }

        binding.viewLogsButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.backHomeButton.setOnClickListener {
            finish()
        }
    }
}
