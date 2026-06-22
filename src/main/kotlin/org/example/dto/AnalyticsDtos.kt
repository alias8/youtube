package org.example.dto

data class ViewEventRequest(
    val videoId: String,
    val startSeconds: Long,
    val endSeconds: Long
)

data class LastWatchedSecondsUpdated(
    val videoId: String,
    val lastWatchedSeconds: Long,
)

data class HistogramResponse(
    val videoId: String,
    val buckets: List<Long>,
    val totalViews: Long
)

data class ViewEventMessage(val videoId: String, val userId: String?, val startSeconds: Long, val endSeconds: Long)
