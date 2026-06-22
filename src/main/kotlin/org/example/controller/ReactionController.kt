package org.example.controller

import org.example.dto.ReactionResponse
import org.example.model.ReactionType
import org.example.service.ReactionService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/videos/{videoId}/reactions")
class ReactionController(private val reactionService: ReactionService) {

    @PostMapping("/like")
    fun like(
        @PathVariable videoId: String,
        authentication: Authentication
    ): ResponseEntity<Void> {
        reactionService.react(videoId, authentication.name, ReactionType.LIKE)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/dislike")
    fun dislike(
        @PathVariable videoId: String,
        authentication: Authentication
    ): ResponseEntity<Void> {
        reactionService.react(videoId, authentication.name, ReactionType.DISLIKE)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping
    fun remove(
        @PathVariable videoId: String,
        authentication: Authentication
    ): ResponseEntity<Void> {
        reactionService.removeReaction(videoId, authentication.name)
        return ResponseEntity.ok().build()
    }

    @GetMapping
    fun get(
        @PathVariable videoId: String,
        authentication: Authentication?
    ): ResponseEntity<ReactionResponse> {
        return ResponseEntity.ok(reactionService.getReactions(videoId, authentication?.name))
    }
}
