package com.ssimo.papere

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ssimo.papere.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val requiredPermissions = arrayOf(Manifest.permission.SET_WALLPAPER)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateUI()
        loadCurrentWallpaper()

        binding.actionButton.setOnClickListener {
            Logger.log(this, "MainActivity", "Action button clicked")
            if (hasPermissions()) {
                toggleWallpaperWork()
            } else {
                Logger.log(this, "MainActivity", "Permissions not granted, requesting...")
                requestPermissions()
            }
         }

        binding.customizeButton.setOnClickListener {
            startActivity(Intent(this, CustomizeActivity::class.java))
        }

        checkWorkStatus()
    }

    override fun onResume() {
        super.onResume()
        loadCurrentWallpaper()
        updateAttribution()
        checkWorkStatus()
    }

    fun saveScreenSize(context: Context) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            Pair(metrics.bounds.width(), metrics.bounds.height())
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }

        context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).edit()
            .putInt("screen_width", width)
            .putInt("screen_height", height)
            .apply()
    }

    private fun updateAttribution() {
        val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val author = prefs.getString("attribution_author", null)
        val license = prefs.getString("attribution_license", null)
        val descriptionUrl = prefs.getString("attribution_description_url", null)

        if (author != null && license != null) {
            binding.attributionText.text = "© $author • $license"
            binding.attributionText.visibility = View.VISIBLE
            binding.attributionText.setOnClickListener {
                descriptionUrl?.let { url ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                }
            }
        } else {
            binding.attributionText.visibility = View.GONE
        }
    }

    private fun loadCurrentWallpaper() {
        val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val path = prefs.getString("current_wallpaper_path", null)
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                binding.backgroundImage.setImageBitmap(bitmap)
            }
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updateUI()
        }
    }

    private fun updateUI() {
        if (hasPermissions()) {
            binding.permissionWarning.visibility = View.GONE
        } else {
            binding.permissionWarning.visibility = View.VISIBLE
            binding.statusText.text = "Status: Permissions Required"
            binding.actionButton.text = "Grant Permissions"
        }
    }

    private fun checkWorkStatus() {
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(WallpaperWorker.UNIQUE_WORK_NAME)
            .observe(this, Observer { workInfos ->
                val isActive = workInfos?.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED } == true

                if (hasPermissions()) {
                    if (isActive) {
                        binding.statusText.text = "ACTIVE"
                        binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
                        binding.actionButton.text = "Deactivate"
                        updateNextChangeTime()
                    } else {
                        binding.statusText.text = "INACTIVE"
                        binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.red))
                        binding.actionButton.text = "Activate"
                        binding.nextChangeText.visibility = View.GONE
                    }
                }
                loadCurrentWallpaper()
            })
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun updateNextChangeTime() {
        val prefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val nextTime = prefs.getLong("next_execution_time", 0L)

        val requireWifiPreference = prefs.getBoolean("require_wifi", true)
        val requireBatteryPreference = prefs.getBoolean("require_battery", true)

        if (requireWifiPreference && !isWifiConnected()) {
            binding.nextChangeText.visibility = View.VISIBLE
            binding.nextChangeText.text = "Waiting for Wifi"
        } else if (requireBatteryPreference && getBatteryLevel() < 50) {
            binding.nextChangeText.visibility = View.VISIBLE
            binding.nextChangeText.text = "Charge your phone"
        } else if (nextTime > System.currentTimeMillis()) {
            val remainingMillis = nextTime - System.currentTimeMillis()
            val minutes = (remainingMillis / 1000 / 60) % 60
            val hours = (remainingMillis / 1000 / 3600)
            
            binding.nextChangeText.text = if (hours > 0) {
                "Next change in: ${hours}h ${minutes}m"
            } else {
                "Next change in: ${minutes}m"
            }
            binding.nextChangeText.visibility = View.VISIBLE
        } else if (nextTime != 0L) {
            binding.nextChangeText.text = "Next change: Soon"
            binding.nextChangeText.visibility = View.VISIBLE
        } else {
            binding.nextChangeText.visibility = View.GONE
        }
    }

    private fun toggleWallpaperWork() {
        Logger.log(this, "MainActivity", "Toggling wallpaper work")
        WorkManager.getInstance(this).getWorkInfosForUniqueWork(WallpaperWorker.UNIQUE_WORK_NAME).get().let { workInfos ->
             val isActive = workInfos?.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED } == true
            if (isActive) {
                Logger.log(this, "MainActivity", "Stopping existing work")
                WallpaperWorker.stopWork(this)
            } else {
                Logger.log(this, "MainActivity", "Starting new work")
                saveScreenSize(this)
                WallpaperWorker.startWork(this)
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }
}
