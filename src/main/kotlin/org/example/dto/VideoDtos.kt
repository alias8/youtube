package org.example.dto

data class UploadUrlResponse(val uploadUrl: String, val s3Key: String)

data class RegisterVideoRequest(
    val title: String,
    val description: String = "",
    val s3Key: String,
    val durationSeconds: Long = 0
)

data class VideoResponse(
    val id: String,
    val title: String,
    val description: String,
    val uploaderId: String,
    val durationSeconds: Long,
    val status: String,
    val createdAt: String
)

data class PlaybackUrlResponse(val playbackUrl: String)
