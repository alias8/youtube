package org.example.dto

data class WatchHistoryEntry(
    val videoId: String,
    val title: String,
    val durationSeconds: Long,
    val watchedAt: String,
    val lastWatchedSeconds: Long
)
