package com.parental.control.app.screentime

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import java.util.*

/**
 * Feature 6: Screen Time & App Usage Tracker
 * Queries Android's UsageStatsManager to compute daily screen time and individual app usage statistics.
 */
class ScreenTimeManager(private val context: Context) {

    private val tag = "ScreenTimeManager"
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    data class AppUsageInfo(
        val packageName: String,
        val totalTimeInForegroundMs: Long,
        val totalTimeMinutes: Long = totalTimeInForegroundMs / (1000 * 60),
        val lastTimeUsed: Long
    )

    /**
     * Retrieves daily usage statistics for all apps used in the past 24 hours.
     */
    fun getDailyAppUsage(): List<AppUsageInfo> {
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val startTime = calendar.timeInMillis

        val stats: List<UsageStats> = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: emptyList()

        val results = stats
            .filter { it.totalTimeInForeground > 60000 } // Filter out apps used less than 1 minute
            .map {
                AppUsageInfo(
                    packageName = it.packageName,
                    totalTimeInForegroundMs = it.totalTimeInForeground,
                    lastTimeUsed = it.lastTimeUsed
                )
            }
            .sortedByDescending { it.totalTimeInForegroundMs }

        Log.d(tag, "Retrieved usage for ${results.size} active applications.")
        return results
    }

    /**
     * Calculates the total device screen-on time in hours and minutes for today.
     */
    fun getTotalDailyScreenTimeMinutes(): Long {
        val dailyUsage = getDailyAppUsage()
        return dailyUsage.sumOf { it.totalTimeMinutes }
    }
}
