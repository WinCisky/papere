package com.ssimo.papere

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
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
        Logger.log(this, "MainActivity", "Initializing MainActivity")
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

        binding.viewLogsButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        checkWorkStatus()
    }

    override fun onResume() {
        super.onResume()
        loadCurrentWallpaper()
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
                        binding.statusText.text = "active"
                        binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
                        binding.actionButton.text = "Deactivate"
                    } else {
                        binding.statusText.text = "inactive"
                        binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.red))
                        binding.actionButton.text = "Activate"
                    }
                }
                loadCurrentWallpaper() // Refresh image when status changes (might have finished a new download)
            })
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
                WallpaperWorker.startWork(this)
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }
}
