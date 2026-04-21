package com.ssimo.papere

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private const val PREFS_NAME = "app_logs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LOGS = 50

    fun log(context: Context, tag: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $tag: $message"
        
        Log.d(tag, message)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentLogs = prefs.getStringSet(KEY_LOGS, LinkedHashSet())?.toMutableList() ?: mutableListOf()
        
        currentLogs.add(0, logEntry)
        if (currentLogs.size > MAX_LOGS) {
            currentLogs.removeAt(currentLogs.size - 1)
        }

        prefs.edit().putStringSet(KEY_LOGS, currentLogs.toSet()).apply()
        
        // Trigger a refresh if possible (using a simple callback or LiveData would be better, 
        // but for simplicity we'll rely on the activity polling or re-reading)
    }

    fun getLogs(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_LOGS, emptySet())?.toList()?.sortedByDescending { it } ?: emptyList()
    }
}
