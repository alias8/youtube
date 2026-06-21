package org.example.service

import org.example.dto.HistogramResponse
import org.example.model.VideoViewEvent
import org.example.repository.VideoRepository
import org.example.repository.VideoViewEventRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service

private const val BUCKET_COUNT = 100

@Service
class AnalyticsService(
    private val videoRepository: VideoRepository,
    private val videoViewEventRepository: VideoViewEventRepository,
    private val redisTemplate: RedisTemplate<String, String>,
    private val kafkaEventProducer: KafkaEventProducer
) {
    fun recordView(videoId: String, userId: String?, startSeconds: Long, endSeconds: Long) {
        val video = videoRepository.findById(videoId).orElse(null) ?: return

        videoViewEventRepository.save(
            VideoViewEvent(videoId = videoId, userId = userId, startSeconds = startSeconds, endSeconds = endSeconds)
        )

        val duration = video.durationSeconds
        if (duration > 0) {
            val startBucket = ((startSeconds.toDouble() / duration) * BUCKET_COUNT).toInt().coerceIn(0, BUCKET_COUNT - 1)
            val endBucket = ((endSeconds.toDouble() / duration) * BUCKET_COUNT).toInt().coerceIn(0, BUCKET_COUNT - 1)
            val hashOps = redisTemplate.opsForHash<String, String>()
            for (bucket in startBucket..endBucket) {
                hashOps.increment("histogram:$videoId", bucket.toString(), 1)
            }
        }

        kafkaEventProducer.publishViewEvent(videoId)
    }

    fun getHistogram(videoId: String): HistogramResponse {
        val hashOps = redisTemplate.opsForHash<String, String>()
        val entries = hashOps.entries("histogram:$videoId")
        val buckets = (0 until BUCKET_COUNT).map { i -> entries[i.toString()]?.toLong() ?: 0L }
        return HistogramResponse(videoId = videoId, buckets = buckets, totalViews = buckets.sum())
    }
}
