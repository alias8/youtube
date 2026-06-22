package org.example.service

import org.example.model.VideoHistogram
import org.example.repository.VideoHistogramRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class HistogramFlushService(
    private val videoHistogramRepository: VideoHistogramRepository,
    private val redisTemplate: RedisTemplate<String, String>
) {
    // fixedDelay: waits 60s after the previous run completes, avoiding overlapping flushes
    @Scheduled(fixedDelay = 60_000)
    fun flush() {
        val setOps = redisTemplate.opsForSet()
        val pendingIds = setOps.members(PENDING_FLUSH_KEY) ?: return
        if (pendingIds.isEmpty()) return

        val hashOps = redisTemplate.opsForHash<String, String>()
        for (videoId in pendingIds) {
            val entries = hashOps.entries("histogram:$videoId")
            if (entries.isEmpty()) continue

            val buckets = (0 until BUCKET_COUNT).map { i -> entries[i.toString()]?.toLong() ?: 0L }
            videoHistogramRepository.save(
                VideoHistogram(videoId = videoId, buckets = buckets, lastFlushedAt = Instant.now())
            )
            setOps.remove(PENDING_FLUSH_KEY, videoId)
        }
    }
}
