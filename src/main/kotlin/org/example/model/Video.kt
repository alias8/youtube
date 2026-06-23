package org.example.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "videos")
data class Video(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String = "",

    @Column(nullable = false)
    val title: String = "",

    @Column(columnDefinition = "TEXT")
    val description: String = "",

    @Column(nullable = false)
    val uploaderId: String = "",

    @Column(nullable = false)
    val s3Key: String = "",

    @Column(nullable = false)
    val contentType: String = "video/mp4",

    val durationSeconds: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: VideoStatus = VideoStatus.READY,

    val createdAt: Instant = Instant.now()
)

enum class VideoStatus { PROCESSING, READY }
