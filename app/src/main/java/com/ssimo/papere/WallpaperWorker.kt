package com.ssimo.papere

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.text.Html
import androidx.core.app.NotificationCompat
import androidx.work.*
import android.app.NotificationChannel
import android.app.NotificationManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

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

        val wifiConnected = isWifiConnected()
        val batteryLevel = getBatteryLevel()
        val requireWifi = sharedPreferences.getBoolean("require_wifi", true)
        val requireBattery = sharedPreferences.getBoolean("require_battery", true)

        if ((requireWifi && !wifiConnected) || (requireBattery && batteryLevel <= 50)) {
            Logger.log(applicationContext, TAG, "Conditions not met: WiFi=$wifiConnected (Required: $requireWifi), Battery=$batteryLevel% (Required: $requireBattery). Postponing for 15m.")
            scheduleAndSaveNext(15L, TimeUnit.MINUTES)
            return Result.success()
        }

        val failureCount = sharedPreferences.getInt("failure_count", 0)

        try {
            val imageData = fetchRandomImageData()

            Logger.log(applicationContext, TAG, "Downloading image from: ${imageData.url}")
            val imageFile = downloadImage(imageData.url)
            Logger.log(applicationContext, TAG, "Image downloaded to: ${imageFile.absolutePath}")

            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: throw Exception("Decoding failed for file ${imageFile.absolutePath}")

            val wallpaperManager = WallpaperManager.getInstance(applicationContext)
            val width = sharedPreferences.getInt("screen_width", 1080)
            val height = sharedPreferences.getInt("screen_height", 1920)
            wallpaperManager.suggestDesiredDimensions(width, height)

            val croppedBitmap = cropBitmap(bitmap, width, height)
            val visibleCropHint = android.graphics.Rect(0, 0, croppedBitmap.width, croppedBitmap.height)
            wallpaperManager.setBitmap(croppedBitmap, visibleCropHint, true)

            Logger.log(applicationContext, TAG, "Wallpaper set successfully")
            onSuccess(imageFile, imageData)
            return Result.success()

        } catch (e: Exception) {
            val stackTrace = android.util.Log.getStackTraceString(e)
            Logger.log(applicationContext, TAG, "Error setting wallpaper: ${e.message ?: e}\n$stackTrace")
            return onFailure(failureCount)
        }
    }

    // ── Data model ────────────────────────────────────────────────────────────

    data class ImageData(
        val url: String,
        val author: String,
        val license: String,
        val licenseUrl: String,
        val descriptionUrl: String
    )

    // ── Core fetch logic ──────────────────────────────────────────────────────

    private fun fetchRandomImageData(): ImageData {
        val (macroKey, category) = selectCategory()
        Logger.log(applicationContext, TAG, "Selected Macro: $macroKey, Category: $category")

        val indexKey = "index_${category.hashCode()}"
        val currentIndex = sharedPreferences.getInt(indexKey, 0)

        // 1. Get file titles from category
        val fileTitles = fetchCategoryMembers(category)

        // 2. Batch-query image info (url, size, metadata) for all members
        val pagesByTitle = fetchImageInfoBatch(fileTitles)

        // 3. Pick the first image ≥ 2000×2000, starting from saved index
        val startIndex = if (currentIndex >= fileTitles.size) 0 else currentIndex
        var selectedInfo: JSONObject? = null
        var nextIndex = startIndex

        for (attempt in fileTitles.indices) {
            val idx = (startIndex + attempt) % fileTitles.size
            val title = fileTitles[idx]
            val info = pagesByTitle[title]?.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val w = info.optInt("width", 0)
            val h = info.optInt("height", 0)

            if (w >= MIN_RESOLUTION && h >= MIN_RESOLUTION) {
                selectedInfo = info
                nextIndex = (idx + 1) % fileTitles.size
                Logger.log(applicationContext, TAG, "Selected image: $title (${w}x${h}) at index $idx")
                break
            } else {
                Logger.log(applicationContext, TAG, "Skipping low-res image: $title (${w}x${h})")
            }
        }

        if (selectedInfo == null) {
            throw Exception("No images >= ${MIN_RESOLUTION}x${MIN_RESOLUTION} found in $category")
        }

        sharedPreferences.edit().putInt(indexKey, nextIndex).apply()
        return parseImageData(selectedInfo)
    }

    private fun selectCategory(): Pair<String, String> {
        val enabledKeys = CATEGORIES.keys.filter { sharedPreferences.getBoolean(it, true) }
        val macroKey = if (enabledKeys.isNotEmpty()) enabledKeys.random() else CATEGORIES.keys.random()
        val category = (CATEGORIES[macroKey] ?: CATEGORIES.values.first()).random()
        return macroKey to category
    }

    private fun fetchCategoryMembers(category: String): List<String> {
        val json = apiGet(
            "action" to "query",
            "list" to "categorymembers",
            "cmtitle" to category,
            "cmtype" to "file",
            "cmlimit" to "50",
            "format" to "json"
        )
        val members = json.optJSONObject("query")?.optJSONArray("categorymembers")
            ?: throw Exception("No category members found for $category")
        if (members.length() == 0) throw Exception("Empty category: $category")

        return (0 until members.length()).map { members.getJSONObject(it).getString("title") }
    }

    private fun fetchImageInfoBatch(titles: List<String>): Map<String, JSONObject> {
        val json = apiGet(
            "action" to "query",
            "titles" to titles.joinToString("|"),
            "prop" to "imageinfo",
            "iiprop" to "url|size|extmetadata",
            "format" to "json"
        )
        val pages = json.optJSONObject("query")?.optJSONObject("pages")
            ?: throw Exception("No pages in image info response")

        val result = mutableMapOf<String, JSONObject>()
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.getJSONObject(keys.next())
            result[page.optString("title")] = page
        }
        return result
    }

    private fun parseImageData(info: JSONObject): ImageData {
        val imageUrl = info.getString("url")
        val descriptionUrl = info.optString("descriptionurl", "")
        val ext = info.optJSONObject("extmetadata")

        return ImageData(
            url = imageUrl,
            author = htmlToText(ext?.optJSONObject("Artist")?.optString("value", "Wikimedia Commons") ?: "Wikimedia Commons"),
            license = ext?.optJSONObject("LicenseShortName")?.optString("value", "See description") ?: "See description",
            licenseUrl = ext?.optJSONObject("LicenseUrl")?.optString("value", descriptionUrl) ?: descriptionUrl,
            descriptionUrl = descriptionUrl
        )
    }

    // ── Network helpers ───────────────────────────────────────────────────────

    private fun apiGet(vararg params: Pair<String, String>): JSONObject {
        val urlBuilder = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("commons.wikimedia.org")
            .addPathSegment("w")
            .addPathSegment("api.php")
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }

        val request = Request.Builder().url(urlBuilder.build()).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP Error ${response.code}: ${urlBuilder.build()}")

        val body = response.body?.string() ?: throw Exception("Empty response body")
        return JSONObject(body)
    }

    private fun isWifiConnected(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun getBatteryLevel(): Int {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun downloadImage(url: String): File {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .addHeader("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.7,en;q=0.6")
            .addHeader("Connection", "close")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Download image HTTP Error: ${response.code} ${response.message}. Body: ${response.body?.string()}")
        }

        val file = File(applicationContext.cacheDir, "temp_wallpaper_${System.currentTimeMillis()}.jpg")
        val body = response.body ?: throw Exception("Empty body stream when downloading image")
        body.byteStream().use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file
    }

    // ── Image helpers ─────────────────────────────────────────────────────────

    private fun cropBitmap(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val srcRatio = src.width.toFloat() / src.height
        val targetRatio = targetWidth.toFloat() / targetHeight

        val (finalWidth, finalHeight) = if (srcRatio > targetRatio) {
            (src.height * targetRatio).toInt() to src.height
        } else {
            src.width to (src.width / targetRatio).toInt()
        }
        val xOffset = (src.width - finalWidth) / 2
        val yOffset = (src.height - finalHeight) / 2

        return Bitmap.createBitmap(src, xOffset, yOffset, finalWidth, finalHeight)
    }

    private fun htmlToText(html: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html).toString().trim()
        }

    // ── Scheduling & lifecycle ────────────────────────────────────────────────

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

        val nextDelay = sharedPreferences.getLong("update_frequency_hours", 4L)
        sharedPreferences.edit()
            .putString("current_wallpaper_path", newImageFile.absolutePath)
            .putString("attribution_author", imageData.author)
            .putString("attribution_license", imageData.license)
            .putString("attribution_license_url", imageData.licenseUrl)
            .putString("attribution_description_url", imageData.descriptionUrl)
            .putInt("failure_count", 0)
            .putLong("last_change_time", System.currentTimeMillis())
            .putLong("next_execution_time", System.currentTimeMillis() + TimeUnit.HOURS.toMillis(nextDelay))
            .apply()

        scheduleNextWork(nextDelay, TimeUnit.HOURS)
    }

    private fun onFailure(failureCount: Int): Result {
        val newCount = failureCount + 1
        Logger.log(applicationContext, TAG, "Handling failure. Failure count: $newCount")

        if (newCount < 3) {
            Logger.log(applicationContext, TAG, "Retrying in 15 minutes.")
            sharedPreferences.edit().putInt("failure_count", newCount).apply()
            scheduleAndSaveNext(15L, TimeUnit.MINUTES)
        } else {
            sharedPreferences.edit()
                .putInt("failure_count", newCount)
                .putLong("next_execution_time", 0L)
                .apply()
            Logger.log(applicationContext, TAG, "Max failures reached. Stopping retries.")
            sendErrorNotification()
        }
        return Result.failure()
    }

    private fun scheduleAndSaveNext(delay: Long, unit: TimeUnit) {
        sharedPreferences.edit()
            .putLong("next_execution_time", System.currentTimeMillis() + unit.toMillis(delay))
            .apply()
        scheduleNextWork(delay, unit)
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

    private fun sendErrorNotification() {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "wallpaper_error_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Errori Sfondo",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Aggiornamento Sfondo Disabilitato")
            .setContentText("Troppi fallimenti consecutivi. L'aggiornamento automatico dello sfondo è stato disabilitato. Riattivalo manualmente.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }

    // ── Constants & companion ──────────────────────────────────────────────────

    companion object {
        const val TAG = "WallpaperWorker"
        const val UNIQUE_WORK_NAME = "WallpaperChangeWork"

        fun startWork(context: Context) {
            val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            val requireWifi = prefs.getBoolean("require_wifi", true)
            val networkType = if (requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()

            val lastChangeTime = prefs.getLong("last_change_time", 0L)
            val currentTime = System.currentTimeMillis()
            val minIntervalMillis = 5 * 60 * 1000L // 5 minutes

            val timeSinceLastChange = currentTime - lastChangeTime
            val delayMillis = if (lastChangeTime > 0 && timeSinceLastChange < minIntervalMillis) {
                minIntervalMillis - timeSinceLastChange
            } else {
                0L
            }

            val workRequestBuilder = OneTimeWorkRequestBuilder<WallpaperWorker>()
                .setConstraints(constraints)
                .addTag(TAG)

            if (delayMillis > 0) {
                workRequestBuilder.setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            }

            val workRequest = workRequestBuilder.build()

            prefs.edit()
                .putLong("next_execution_time", currentTime + delayMillis)
                .putInt("failure_count", 0)
                .apply()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
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
