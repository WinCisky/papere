package com.ssimo.papere

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class WallpaperWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val sharedPreferences = appContext.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient()

    override suspend fun doWork(): Result {
        Logger.log(applicationContext, TAG, "Starting wallpaper background work")

        val batteryLevel = getBatteryLevel()
        val wifiConnected = isWifiConnected()

        if (!wifiConnected || batteryLevel <= 50) {
            Logger.log(applicationContext, TAG, "Conditions not met: WiFi=$wifiConnected, Battery=$batteryLevel%. Postponing for 1h.")
            scheduleNextWork(1, TimeUnit.HOURS)
            return Result.success()
        }

        val failureCount = sharedPreferences.getInt("failure_count", 0)
        val imageUrl = "https://picsum.photos/1920/1080" // Placeholder static endpoint

        try {
            Logger.log(applicationContext, TAG, "Downloading image from: $imageUrl")
            val imageFile = downloadImage(imageUrl) ?: throw Exception("Download failed")
            Logger.log(applicationContext, TAG, "Image downloaded to: ${imageFile.absolutePath}")

            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: throw Exception("Decoding failed")
            
            val wallpaperManager = WallpaperManager.getInstance(applicationContext)
            val width = wallpaperManager.desiredMinimumWidth
            val height = wallpaperManager.desiredMinimumHeight
            Logger.log(applicationContext, TAG, "Device desired wallpaper size: ${width}x${height}")
            
            val croppedBitmap = cropBitmap(bitmap, width, height)
            Logger.log(applicationContext, TAG, "Image cropped to target ratio")

            wallpaperManager.setBitmap(croppedBitmap)
            Logger.log(applicationContext, TAG, "Wallpaper set successfully")
            
            // Success
            onSuccess(imageFile)
            return Result.success()
            
        } catch (e: Exception) {
            Logger.log(applicationContext, TAG, "Error setting wallpaper: " + e.message)
            return onFailure(failureCount)
        }
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

    private fun downloadImage(url: String): File? {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        
        val file = File(applicationContext.cacheDir, "temp_wallpaper_${System.currentTimeMillis()}.jpg")
        response.body?.byteStream()?.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file
    }

    private fun cropBitmap(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val srcWidth = src.width
        val srcHeight = src.height
        
        val srcRatio = srcWidth.toFloat() / srcHeight
        val targetRatio = targetWidth.toFloat() / targetHeight
        
        var finalWidth = srcWidth
        var finalHeight = srcHeight
        var xOffset = 0
        var yOffset = 0
        
        if (srcRatio > targetRatio) {
            finalWidth = (srcHeight * targetRatio).toInt()
            xOffset = (srcWidth - finalWidth) / 2
        } else {
            finalHeight = (srcWidth / targetRatio).toInt()
            yOffset = (srcHeight - finalHeight) / 2
        }
        
        return Bitmap.createBitmap(src, xOffset, yOffset, finalWidth, finalHeight)
    }

    private fun onSuccess(newImageFile: File) {
        Logger.log(applicationContext, TAG, "Handling success. Next change in 4 hours.")
        val oldPath = sharedPreferences.getString("current_wallpaper_path", null)
        if (oldPath != null) {
            val oldFile = File(oldPath)
            if (oldFile.exists()) {
                val deleted = oldFile.delete()
                Logger.log(applicationContext, TAG, "Old wallpaper deleted: $deleted")
            }
        }
        
        sharedPreferences.edit()
            .putString("current_wallpaper_path", newImageFile.absolutePath)
            .putInt("failure_count", 0)
            .apply()

        scheduleNextWork(4, TimeUnit.HOURS)
    }

    private fun onFailure(failureCount: Int): Result {
        val newFailureCount = failureCount + 1
        Logger.log(applicationContext, TAG, "Handling failure. Failure count: $newFailureCount")
        sharedPreferences.edit().putInt("failure_count", newFailureCount).apply()
        
        if (newFailureCount < 3) {
            Logger.log(applicationContext, TAG, "Retrying in 15 minutes.")
            scheduleNextWork(15, TimeUnit.MINUTES)
        } else {
            Logger.log(applicationContext, TAG, "Max failures reached. Stopping retries.")
        }
        
        return Result.failure()
    }

    private fun scheduleNextWork(delay: Long, unit: TimeUnit) {
        Logger.log(applicationContext, TAG, "Scheduling next work in $delay $unit")
        
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setInitialDelay(delay, unit)
            .setConstraints(constraints)
            .addTag(TAG)
            .build()
            
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    companion object {
        const val TAG = "WallpaperWorker"
        const val UNIQUE_WORK_NAME = "WallpaperChangeWork"
        
        fun startWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<WallpaperWorker>()
                .setConstraints(constraints)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        }

        fun stopWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
