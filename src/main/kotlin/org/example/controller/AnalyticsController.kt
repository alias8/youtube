package org.example.controller

import org.example.dto.HistogramResponse
import org.example.dto.ViewEventRequest
import org.example.service.AnalyticsService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/analytics")
class AnalyticsController(private val analyticsService: AnalyticsService) {

    // Called by client when they pause/stop video or close window
    @PostMapping("/view")
    fun recordView(
        @RequestBody request: ViewEventRequest,
        authentication: Authentication?
    ): ResponseEntity<Void> {
        analyticsService.recordView(
            videoId = request.videoId,
            userId = authentication?.name,
            startSeconds = request.startSeconds,
            endSeconds = request.endSeconds
        )
        return ResponseEntity.ok().build()
    }

    @GetMapping("/{videoId}/histogram")
    fun getHistogram(@PathVariable videoId: String): ResponseEntity<HistogramResponse> {
        return ResponseEntity.ok(analyticsService.getHistogram(videoId))
    }
}
