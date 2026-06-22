package org.example.service

import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class WatchResumeFlushService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val jdbcTemplate: JdbcTemplate
) {
    @Scheduled(fixedDelay = 30_000)
    fun flush() {
        val entries = mutableListOf<String>()
        val scanOptions = ScanOptions.scanOptions().count(1000).build()
        // SSCAN via .scan(...).use { } — cursor-based, non-blocking, processes in batches of ~1000 instead of loading everything at once
        redisTemplate.opsForSet().scan(PENDING_WATCH_RESUME_FLUSH_KEY, scanOptions).use { cursor ->
            cursor.forEach { entries.add(it) } // iterates until cursor exhausted
        }
        if (entries.isEmpty()) return

        // Single round-trip to Redis for all values
        val keys = entries.map { "watch-resume:$it" }
        val values = redisTemplate.opsForValue().multiGet(keys) ?: return //  single MGET command to Redis for all values; instead of N individual GETs

        val rows = entries.zip(values).mapNotNull { (entry, seconds) ->
            seconds?.toLongOrNull()?.let {
                val (userId, videoId) = entry.split(":", limit = 2)
                arrayOf<Any>(userId, videoId, it)
            }
        }
        if (rows.isEmpty()) return

        // one SQL statement covering all rows; instead of N SELECT + N UPDATE via JPA
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO watch_history (id, user_id, video_id, watched_at, last_watched_seconds)
            VALUES (gen_random_uuid(), ?, ?, NOW(), ?)
            ON CONFLICT (user_id, video_id) DO UPDATE SET last_watched_seconds = EXCLUDED.last_watched_seconds
            """.trimIndent(),
            rows
        )

        redisTemplate.opsForSet().remove(PENDING_WATCH_RESUME_FLUSH_KEY, *entries.toTypedArray())
    }
}
