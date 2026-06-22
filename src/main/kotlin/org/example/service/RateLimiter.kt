package org.example.service

import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.util.UUID

// Sliding window log: each request is stored as a ZSET member scored by timestamp.
// On each call: evict entries outside the window, add current request, count remaining.
// Lua script makes all three ops atomic — no race between concurrent requests.
/*
* So inside the Lua script:                                                                                                                              
  - KEYS[1] = "rate-limit:reactions:userId123" (1-indexed, not 0)                                                                                        
  - ARGV[1] = "1719051234567" (now)                                                                                                                      
  - ARGV[2] = "60000" (windowMs)                                                                                                                         
  - ARGV[3] = "20" (limit)                                                                                                                               
  - ARGV[4] = "1719051234567:a3f9..." (nonce) 
* */
private val SLIDING_WINDOW_SCRIPT = DefaultRedisScript<Long>().apply {
    setScriptText("""
        local now = tonumber(ARGV[1])
        local windowMs = tonumber(ARGV[2])
        local limit = tonumber(ARGV[3])
        local nonce = ARGV[4]
        redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - windowMs) // evict old entries
        redis.call('ZADD', KEYS[1], now, nonce) // add the current request
        redis.call('PEXPIRE', KEYS[1], windowMs * 2)
        local count = redis.call('ZCARD', KEYS[1]) // count what's left
        if count > limit then return 0 else return 1 end
    """.trimIndent())
    setResultType(Long::class.java)
}

@Service
class RateLimiter(private val redisTemplate: RedisTemplate<String, String>) {

    fun isAllowed(key: String, limit: Int, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        // Nonce makes each request a unique ZSET member even at the same millisecond
        val nonce = "$now:${UUID.randomUUID()}"
        val result = redisTemplate.execute(
            SLIDING_WINDOW_SCRIPT,
            listOf(key),
            now.toString(), windowMs.toString(), limit.toString(), nonce
        )
        return result == 1L
    }
}
