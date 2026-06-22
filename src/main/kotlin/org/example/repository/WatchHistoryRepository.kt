package org.example.repository

import org.example.model.WatchHistory
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface WatchHistoryRepository : JpaRepository<WatchHistory, String> {
    fun findByUserIdOrderByWatchedAtDesc(userId: String, pageable: Pageable): List<WatchHistory>
    fun findByUserIdAndVideoId(userId: String, videoId: String): WatchHistory?
}
