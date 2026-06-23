package org.example.service

import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class ViewCountFlushService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val jdbcTemplate: JdbcTemplate
) {
    @Scheduled(fixedDelay = 60_000)
    fun flush() {
        val videoIds = mutableListOf<String>() // eg. ["video-abc", "video-xyz", "video-def"]
        val scanOptions = ScanOptions.scanOptions().count(1000).build()
        redisTemplate.opsForSet().scan(PENDING_VIEW_COUNT_FLUSH_KEY, scanOptions).use { cursor ->
            cursor.forEach { videoIds.add(it) }
        }
        if (videoIds.isEmpty()) return

        val keys = videoIds.map { "views:total:$it" }
        val counts = redisTemplate.opsForValue().multiGet(keys) ?: return // eg ["1500", null, "87"]

        val rows = videoIds.zip(counts).mapNotNull { (videoId, count) ->
            count?.toLongOrNull()?.let { arrayOf<Any>(videoId, it) }
        }
        if (rows.isEmpty()) return

        jdbcTemplate.batchUpdate(
            """
            INSERT INTO video_view_counts (video_id, count) VALUES (?, ?)
            ON CONFLICT (video_id) DO UPDATE SET count = EXCLUDED.count
            """.trimIndent(),
            rows
        )

        redisTemplate.opsForSet().remove(PENDING_VIEW_COUNT_FLUSH_KEY, *videoIds.toTypedArray())
    }
}
