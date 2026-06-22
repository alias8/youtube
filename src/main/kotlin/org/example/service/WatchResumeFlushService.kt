package org.example.service

import org.example.repository.WatchHistoryRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class WatchResumeFlushService(
    private val watchHistoryRepository: WatchHistoryRepository,
    private val redisTemplate: RedisTemplate<String, String>
) {
    @Scheduled(fixedDelay = 30_000)
    fun flush() {
        val setOps = redisTemplate.opsForSet()
        val pending = setOps.members(PENDING_WATCH_RESUME_FLUSH_KEY) ?: return
        if (pending.isEmpty()) return

        for (entry in pending) {
            val (userId, videoId) = entry.split(":", limit = 2)
            val seconds = redisTemplate.opsForValue().get("watch-resume:$userId:$videoId")?.toLongOrNull() ?: continue
            watchHistoryRepository.findByUserIdAndVideoId(userId, videoId)?.let {
                watchHistoryRepository.save(it.copy(lastWatchedSeconds = seconds))
            }
            setOps.remove(PENDING_WATCH_RESUME_FLUSH_KEY, entry)
        }
    }
}
