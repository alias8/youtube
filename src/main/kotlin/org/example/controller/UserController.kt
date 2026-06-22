package org.example.controller

import org.example.dto.WatchHistoryEntry
import org.example.repository.VideoRepository
import org.example.repository.WatchHistoryRepository
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/users/me")
class UserController(
    private val watchHistoryRepository: WatchHistoryRepository,
    private val videoRepository: VideoRepository
) {
    @GetMapping("/history")
    fun getHistory(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<List<WatchHistoryEntry>> {
        val history = watchHistoryRepository.findByUserIdOrderByWatchedAtDesc(
            userId = authentication.name,
            pageable = PageRequest.of(page, size)
        )

        val videoIds = history.map { it.videoId }.toSet()
        val videosById = videoRepository.findAllById(videoIds).associateBy { it.id }

        val response = history.mapNotNull { entry ->
            val video = videosById[entry.videoId] ?: return@mapNotNull null
            WatchHistoryEntry(
                videoId = video.id,
                title = video.title,
                durationSeconds = video.durationSeconds,
                watchedAt = entry.watchedAt.toString(),
                lastWatchedSeconds = entry.lastWatchedSeconds
            )
        }

        return ResponseEntity.ok(response)
    }
}
