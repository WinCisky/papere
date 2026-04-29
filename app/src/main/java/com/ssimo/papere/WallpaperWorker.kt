package com.ssimo.papere

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.text.Html
import androidx.work.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class WallpaperWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val sharedPreferences = appContext.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "PapereWallpaper/1.0 (Android; +mailto:info@ssimo.dev; GitHub: https://github.com/WinCisky)"
                )
                .build()
            chain.proceed(request)
        }
        .build()

    override suspend fun doWork(): Result {
        Logger.log(applicationContext, TAG, "Starting wallpaper background work")

        val batteryLevel = getBatteryLevel()
        val wifiConnected = isWifiConnected()
        val requireWifi = sharedPreferences.getBoolean("require_wifi", true)
        val requireBattery = sharedPreferences.getBoolean("require_battery", true)

        if ((requireWifi && !wifiConnected) || (requireBattery && batteryLevel <= 50)) {
            Logger.log(applicationContext, TAG, "Conditions not met: WiFi=$wifiConnected (Required: $requireWifi), Battery=$batteryLevel% (Required: $requireBattery). Postponing for 1h.")
            scheduleNextWork(1, TimeUnit.HOURS)
            return Result.success()
        }

        val failureCount = sharedPreferences.getInt("failure_count", 0)

        try {
            val imageData = fetchRandomImageData() ?: throw Exception("Failed to fetch image data from Wikimedia")
            val imageUrl = imageData.url
            
            Logger.log(applicationContext, TAG, "Downloading image from: $imageUrl")
            val imageFile = downloadImage(imageUrl) ?: throw Exception("Download failed")
            Logger.log(applicationContext, TAG, "Image downloaded to: ${imageFile.absolutePath}")

            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: throw Exception("Decoding failed")

            val wallpaperManager = WallpaperManager.getInstance(applicationContext)
            val width = sharedPreferences.getInt("screen_width", 1080)
            val height = sharedPreferences.getInt("screen_height", 1920)
            wallpaperManager.suggestDesiredDimensions(width, height)


            val croppedBitmap = cropBitmap(bitmap, width, height)
            val visibleCropHint = android.graphics.Rect(0, 0, croppedBitmap.width, croppedBitmap.height)
            wallpaperManager.setBitmap(croppedBitmap, visibleCropHint, true)

            //wallpaperManager.setBitmap(croppedBitmap)
            Logger.log(applicationContext, TAG, "Wallpaper set successfully")
            
            // Success
            onSuccess(imageFile, imageData)
            return Result.success()
            
        } catch (e: Exception) {
            Logger.log(applicationContext, TAG, "Error setting wallpaper: " + e.message)
            return onFailure(failureCount)
        }
    }

    data class ImageData(
        val url: String,
        val author: String,
        val license: String,
        val licenseUrl: String,
        val descriptionUrl: String
    )

    private fun fetchRandomImageData(): ImageData? {
        val categories = listOf(
            "Category:Featured_pictures_of_nature",
            "Category:Featured_pictures_of_landscapes",
            "Category:Featured_pictures_of_sunsets",
            "Category:Featured_pictures_of_animals",
            "Category:Featured_pictures_of_flora"
        )
        val category = categories.random()
        
        // Random date in the past
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -Random.nextInt(365))

        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH)
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)

        val timestamp = "%04d-%02d-%02dT00:00:00Z".format(year, month, day)
        Logger.log(applicationContext, TAG, "Timestamp: $timestamp")


        val apiUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("commons.wikimedia.org")
            .addPathSegment("w")
            .addPathSegment("api.php")
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("generator", "categorymembers")
            .addQueryParameter("gcmtitle", category) // es: "Category:Featured_pictures_of_flora" NON encodato a mano
            .addQueryParameter("gcmtype", "file")
            .addQueryParameter("gcmlimit", "50")
            // opzionale ma utile per rendere variabili i risultati senza gcmstart
            .addQueryParameter("gcmnamespace", "6")
            // immagine + metadati
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url|extmetadata")
            .addQueryParameter("iiurlwidth", "1920")
            .apply {
                // se vuoi tenere gcmstart, aggiungilo qui
                addQueryParameter("gcmstart", timestamp)
                addQueryParameter("gcmsort", "timestamp")
            }
            .build()
            .toString()


        val request = Request.Builder().url(apiUrl).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        val bodyString = response.body?.string() ?: return null
        val json = JSONObject(bodyString)


        if (json.has("error")) {
            throw Exception("Wikimedia error: ${json.getJSONObject("error").optString("info")}")
        }

        val pagesArr = json.optJSONObject("query")?.optJSONArray("pages") ?: return null
        if (pagesArr.length() == 0) return null

        val randomPage = pagesArr.getJSONObject(Random.nextInt(pagesArr.length()))
        val imageInfo = randomPage.optJSONArray("imageinfo")?.optJSONObject(0) ?: return null
        val extMetadata = imageInfo.optJSONObject("extmetadata") ?: return null

        val rawArtist = extMetadata.optJSONObject("Artist")?.optString("value") ?: "Unknown"
        val artist = Html.fromHtml(rawArtist, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        val license = extMetadata.optJSONObject("LicenseShortName")?.optString("value") ?: "Unknown"
        val licenseUrl = extMetadata.optJSONObject("LicenseUrl")?.optString("value") ?: ""
        val descriptionUrl = imageInfo.optString("descriptionurl") ?: ""
        val downloadUrl = imageInfo.optString("thumburl").let { if (it.isNullOrEmpty()) imageInfo.optString("url") else it }

        return ImageData(downloadUrl, artist, license, licenseUrl, descriptionUrl)
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
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .addHeader("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.7,en;q=0.6")
            .addHeader("Connection", "close")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Logger.log(applicationContext, TAG, "Response code: ${response.code}")
            Logger.log(applicationContext, TAG, "Response message: ${response.message}")
            val errBody = response.body?.string()
            Logger.log(applicationContext, TAG, "Error body: $errBody")
            return null
        }
        
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

    private fun onSuccess(newImageFile: File, imageData: ImageData) {
        Logger.log(applicationContext, TAG, "Handling success. Next change in 4 hours.")
        val oldPath = sharedPreferences.getString("current_wallpaper_path", null)
        if (oldPath != null) {
            val oldFile = File(oldPath)
            if (oldFile.exists()) {
                val deleted = oldFile.delete()
                Logger.log(applicationContext, TAG, "Old wallpaper deleted: $deleted")
            }
        }
        
        val nextDelay = 4L
        val nextUnit = TimeUnit.HOURS
        
        sharedPreferences.edit()
            .putString("current_wallpaper_path", newImageFile.absolutePath)
            .putString("attribution_author", imageData.author)
            .putString("attribution_license", imageData.license)
            .putString("attribution_license_url", imageData.licenseUrl)
            .putString("attribution_description_url", imageData.descriptionUrl)
            .putInt("failure_count", 0)
            .putLong("next_execution_time", System.currentTimeMillis() + nextUnit.toMillis(nextDelay))
            .apply()

        scheduleNextWork(nextDelay, nextUnit)
    }

    private fun onFailure(failureCount: Int): Result {
        val newFailureCount = failureCount + 1
        Logger.log(applicationContext, TAG, "Handling failure. Failure count: $newFailureCount")
        
        if (newFailureCount < 3) {
            val nextDelay = 15L
            val nextUnit = TimeUnit.MINUTES
            sharedPreferences.edit()
                .putInt("failure_count", newFailureCount)
                .putLong("next_execution_time", System.currentTimeMillis() + nextUnit.toMillis(nextDelay))
                .apply()
            Logger.log(applicationContext, TAG, "Retrying in 15 minutes.")
            scheduleNextWork(nextDelay, nextUnit)
        } else {
            sharedPreferences.edit()
                .putInt("failure_count", newFailureCount)
                .putLong("next_execution_time", 0L)
                .apply()
            Logger.log(applicationContext, TAG, "Max failures reached. Stopping retries.")
        }
        
        return Result.failure()
    }

    private fun scheduleNextWork(delay: Long, unit: TimeUnit) {
        Logger.log(applicationContext, TAG, "Scheduling next work in $delay $unit")
        
        val requireWifi = sharedPreferences.getBoolean("require_wifi", true)
        val networkType = if (requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
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
            val requireWifi = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                .getBoolean("require_wifi", true)
            val networkType = if (requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<WallpaperWorker>()
                .setConstraints(constraints)
                .addTag(TAG)
                .build()
            
            context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).edit()
                .putLong("next_execution_time", System.currentTimeMillis()) // Immediate start
                .apply()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        }

        fun stopWork(context: Context) {
            context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).edit()
                .putLong("next_execution_time", 0L)
                .apply()
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
