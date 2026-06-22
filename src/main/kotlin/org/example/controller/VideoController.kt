package org.example.controller

import org.example.dto.PlaybackUrlResponse
import org.example.dto.RegisterVideoRequest
import org.example.dto.UploadUrlResponse
import org.example.dto.VideoPageResponse
import org.example.dto.VideoResponse
import org.example.service.AnalyticsService
import org.example.service.VideoService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/videos")
class VideoController(
    private val videoService: VideoService,
    private val analyticsService: AnalyticsService
) {

    @GetMapping("/upload-url")
    fun getUploadUrl(
        @RequestParam filename: String,
        @RequestParam(defaultValue = "video/mp4") contentType: String
    ): ResponseEntity<UploadUrlResponse> {
        return ResponseEntity.ok(videoService.generateUploadUrl(filename, contentType))
    }

    @PostMapping
    fun register(
        @RequestBody request: RegisterVideoRequest,
        authentication: Authentication
    ): ResponseEntity<VideoResponse> {
        val video = videoService.registerVideo(request, authentication.name)
        return ResponseEntity.status(HttpStatus.CREATED).body(video)
    }

    @GetMapping
    fun list(
        @RequestParam cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<VideoPageResponse> = ResponseEntity.ok(videoService.list(cursor, limit))

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ResponseEntity<VideoResponse> {
        val video = videoService.getResponseById(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(video)
    }

    @GetMapping("/{id}/playback-url")
    fun getPlaybackUrl(@PathVariable id: String): ResponseEntity<PlaybackUrlResponse> {
        val response = videoService.getPlaybackUrl(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(response)
    }

    // Client calls this after 10 seconds of actual playback to record the video in watch history.
    @PostMapping("/{id}/watched")
    fun markWatched(
        @PathVariable id: String,
        authentication: Authentication
    ): ResponseEntity<Void> {
        analyticsService.markWatched(id, authentication.name)
        return ResponseEntity.ok().build()
    }
}
