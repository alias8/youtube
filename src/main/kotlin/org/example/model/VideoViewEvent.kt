package org.example.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "video_view_events")
data class VideoViewEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String = "",

    @Column(nullable = false)
    val videoId: String = "",

    val userId: String? = null,

    val startSeconds: Long = 0,
    val endSeconds: Long = 0,

    val createdAt: Instant = Instant.now()
)
