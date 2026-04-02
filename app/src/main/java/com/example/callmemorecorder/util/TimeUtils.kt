package com.example.callmemorecorder.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Format duration from milliseconds to HH:mm:ss or mm:ss
 */
fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

/**
 * Format timestamp to human-readable datetime string
 */
fun formatDatetime(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * Format timestamp to date only
 */
fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
