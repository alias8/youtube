package org.example.repository

import org.example.model.ReactionType
import org.example.model.VideoReaction
import org.springframework.data.jpa.repository.JpaRepository

interface VideoReactionRepository : JpaRepository<VideoReaction, String> {
    fun findByUserIdAndVideoId(userId: String, videoId: String): VideoReaction?
    fun countByVideoIdAndType(videoId: String, type: ReactionType): Long
}
