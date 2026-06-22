package org.example.service

import org.example.dto.ReactionResponse
import org.example.model.ReactionType
import org.example.model.VideoReaction
import org.example.repository.VideoReactionRepository
import org.example.utils.reactionCountKey
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service

@Service
class ReactionService(
    private val videoReactionRepository: VideoReactionRepository,
    private val redisTemplate: RedisTemplate<String, String>
) {
    fun react(videoId: String, userId: String, newType: ReactionType) {
        val existing = videoReactionRepository.findByUserIdAndVideoId(userId, videoId)
        if (existing?.type == newType) return

        if (existing != null) {
            // Switching reaction type — seed both keys before touching either counter
            seedIfAbsent(videoId, existing.type)
            seedIfAbsent(videoId, newType)
            videoReactionRepository.save(existing.copy(type = newType))
            redisTemplate.opsForValue().increment(reactionCountKey(videoId, existing.type), -1)
            redisTemplate.opsForValue().increment(reactionCountKey(videoId, newType), 1)
        } else {
            seedIfAbsent(videoId, newType)
            videoReactionRepository.save(VideoReaction(userId = userId, videoId = videoId, type = newType))
            redisTemplate.opsForValue().increment(reactionCountKey(videoId, newType), 1)
        }
    }

    fun removeReaction(videoId: String, userId: String) {
        val existing = videoReactionRepository.findByUserIdAndVideoId(userId, videoId) ?: return
        seedIfAbsent(videoId, existing.type)
        videoReactionRepository.delete(existing)
        redisTemplate.opsForValue().increment(reactionCountKey(videoId, existing.type), -1)
    }

    fun getReactions(videoId: String, userId: String?): ReactionResponse {
        val likes = getCount(videoId, ReactionType.LIKE)
        val dislikes = getCount(videoId, ReactionType.DISLIKE)
        val userReaction = userId?.let {
            videoReactionRepository.findByUserIdAndVideoId(it, videoId)?.type?.name
        }
        return ReactionResponse(videoId, likes, dislikes, userReaction)
    }

    private fun seedIfAbsent(videoId: String, type: ReactionType) {
        val key = reactionCountKey(videoId, type)
        if (!redisTemplate.hasKey(key)) {
            val count = videoReactionRepository.countByVideoIdAndType(videoId, type)
            redisTemplate.opsForValue().setIfAbsent(key, count.toString())
        }
    }

    private fun getCount(videoId: String, type: ReactionType): Long {
        val key = reactionCountKey(videoId, type)
        return redisTemplate.opsForValue().get(key)?.toLong()
            ?: videoReactionRepository.countByVideoIdAndType(videoId, type).also {
                redisTemplate.opsForValue().set(key, it.toString())
            }
    }
}
