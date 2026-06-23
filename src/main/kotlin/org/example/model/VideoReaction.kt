package org.example.model

import jakarta.persistence.*

@Entity
@Table(
    name = "video_reactions",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "video_id"])]
)
class VideoReaction(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: String = "",

    @Column(name = "user_id", nullable = false)
    val userId: String = "",

    @Column(name = "video_id", nullable = false)
    val videoId: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: ReactionType = ReactionType.LIKE
)

enum class ReactionType { LIKE, DISLIKE }
