package org.example.service

import org.example.dto.HistogramResponse
import org.example.model.VideoHistogram
import org.example.model.WatchHistory
import org.example.repository.VideoHistogramRepository
import org.example.repository.VideoRepository
import org.example.repository.WatchHistoryRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

const val BUCKET_COUNT = 100
const val PENDING_FLUSH_KEY = "pending-histogram-flush"
const val PENDING_WATCH_RESUME_FLUSH_KEY = "pending-watch-resume-flush"
const val PENDING_VIEW_COUNT_FLUSH_KEY = "pending-view-count-flush"

@Service
class AnalyticsService(
    private val videoRepository: VideoRepository,
    private val videoHistogramRepository: VideoHistogramRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val redisTemplate: RedisTemplate<String, String>,
    private val kafkaEventProducer: KafkaEventProducer
) {
    fun recordView(videoId: String, userId: String?, startSeconds: Long, endSeconds: Long) {
        val video = videoRepository.findById(videoId).orElse(null) ?: return

        // Save to Redis straight away, this is flushed every minute
        val duration = video.durationSeconds
        if (duration > 0) {
            val histogramKey = "histogram:$videoId"
            val hashOps = redisTemplate.opsForHash<String, String>()

            // If the key is missing (first use or Redis restart), seed from Postgres so we
            // don't overwrite historical counts with a partial accumulation on the next flush.
            if (!redisTemplate.hasKey(histogramKey)) {
                videoHistogramRepository.findById(videoId).ifPresent { persisted ->
                    persisted.buckets.forEachIndexed { i, count ->
                        if (count > 0) hashOps.putIfAbsent(histogramKey, i.toString(), count.toString())
                    }
                }
            }

            val startBucket = ((startSeconds.toDouble() / duration) * BUCKET_COUNT).toInt().coerceIn(0, BUCKET_COUNT - 1)
            val endBucket = ((endSeconds.toDouble() / duration) * BUCKET_COUNT).toInt().coerceIn(0, BUCKET_COUNT - 1)
            for (bucket in startBucket..endBucket) {
                hashOps.increment(histogramKey, bucket.toString(), 1)
            }
            redisTemplate.opsForSet().add(PENDING_FLUSH_KEY, videoId)
        }

        updateLastWatchedSeconds(videoId, userId, endSeconds)
        kafkaEventProducer.publishViewEvent(videoId, userId, startSeconds, endSeconds)
    }
    
    // Called by client every 30 seconds. Writes to Redis only; WatchResumeFlushService persists to DB.
    fun updateLastWatchedSeconds(videoId: String, userId: String?, lastWatchedSeconds: Long) {
        if (userId == null) return
        redisTemplate.opsForValue().set(
            "watch-resume:$userId:$videoId",
            lastWatchedSeconds.toString(),
            Duration.ofDays(1)
        )
        redisTemplate.opsForSet().add(PENDING_WATCH_RESUME_FLUSH_KEY, "$userId:$videoId")
    }

    // Called by the client after 10 seconds of playback to record the video in watch history.
    fun markWatched(videoId: String, userId: String) {
        val existing = watchHistoryRepository.findByUserIdAndVideoId(userId, videoId)
        watchHistoryRepository.save(
            existing?.copy(watchedAt = Instant.now())
                ?: WatchHistory(userId = userId, videoId = videoId)
        )

        // Count the view only if this user hasn't watched within the last 24 hours.
        // setIfAbsent is atomic — no race between concurrent requests from the same user.
        val counted = redisTemplate.opsForValue()
            .setIfAbsent("view-dedup:$videoId:$userId", "1", Duration.ofDays(1))
        if (counted == true) {
            redisTemplate.opsForValue().increment("views:total:$videoId")
            redisTemplate.opsForSet().add(PENDING_VIEW_COUNT_FLUSH_KEY, videoId)
        }
    }

    fun getHistogram(videoId: String): HistogramResponse {
        val hashOps = redisTemplate.opsForHash<String, String>()
        val entries = hashOps.entries("histogram:$videoId")

        if (entries.isEmpty()) {
            // Redis is cold (restart or first read) — warm from Postgres
            val persisted = videoHistogramRepository.findById(videoId).orElse(null)
                ?: return HistogramResponse(videoId, List(BUCKET_COUNT) { 0L }, 0)
            persisted.buckets.forEachIndexed { i, count ->
                if (count > 0) hashOps.put("histogram:$videoId", i.toString(), count.toString())
            }
            return HistogramResponse(videoId, persisted.buckets, persisted.buckets.sum())
        }

        val buckets = (0 until BUCKET_COUNT).map { i -> entries[i.toString()]?.toLong() ?: 0L }
        return HistogramResponse(videoId, buckets, buckets.sum())
    }
}
