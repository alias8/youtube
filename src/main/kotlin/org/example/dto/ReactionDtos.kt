package org.example.dto

data class ReactionResponse(
    val videoId: String,
    val likes: Long,
    val dislikes: Long,
    val userReaction: String?  // "LIKE", "DISLIKE", or null
)
