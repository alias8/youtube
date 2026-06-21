package org.example.dto

data class ViewEventRequest(
    val videoId: String,
    val startSeconds: Long,
    val endSeconds: Long
)

data class HistogramResponse(
    val videoId: String,
    val buckets: List<Long>,
    val totalViews: Long
)
