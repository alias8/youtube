package org.example.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "watch_history",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "video_id"])]
)
class WatchHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String = "",

    @Column(name = "user_id", nullable = false)
    val userId: String = "",

    @Column(name = "video_id", nullable = false)
    val videoId: String = "",

    var watchedAt: Instant = Instant.now(),

    val lastWatchedSeconds: Long = 0
)
