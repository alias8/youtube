package org.example.model

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "video_view_counts")
data class VideoViewCount(
    @Id
    val videoId: String,
    val count: Long = 0
)
