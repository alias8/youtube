package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

typealias TenantId = String

@Component
class FeatureFlagClient(
    @Value("\${feature-flags.url}") private val url: String,
    @Value("\${feature-flags.api-key}") private val apiKey: String,
    private val objectMapper: ObjectMapper
) {
    private data class CachedFlag(
        val enabled: Boolean,
        val rolloutPercentage: Int,
        val overrides: Map<TenantId, Boolean>
    )

    private val flags = ConcurrentHashMap<String, CachedFlag>()

    fun isEnabled(name: String, userId: TenantId): Boolean {
        val flag = flags[name] ?: return false
        if (!flag.enabled) return false
        flag.overrides[userId]?.let { return it }
        return bucket(name, userId) < flag.rolloutPercentage
    }

    // Hash flagName:userId to a stable 0–99 bucket so rollout is consistent per user.
    // Uses MurmurHash3 32-bit (same algorithm as LaunchDarkly, Unleash, Split).
    private fun bucket(flagName: String, userId: String): Int {
        val h = murmur3("$flagName:$userId")
        return (h and Int.MAX_VALUE) % 100
    }

    private fun murmur3(key: String, seed: Int = 0): Int {
        val data = key.toByteArray()
        val len = data.size
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593.toInt()
        var h1 = seed

        val numBlocks = len / 4
        for (i in 0 until numBlocks) {
            val base = i * 4
            var k1 = (data[base].toInt() and 0xFF) or
                     ((data[base + 1].toInt() and 0xFF) shl 8) or
                     ((data[base + 2].toInt() and 0xFF) shl 16) or
                     ((data[base + 3].toInt() and 0xFF) shl 24)
            k1 *= c1; k1 = k1.rotateLeft(15); k1 *= c2
            h1 = h1 xor k1; h1 = h1.rotateLeft(13); h1 = h1 * 5 + 0xe6546b64.toInt()
        }

        var k1: Int
        val tail = numBlocks * 4
        when (len and 3) {
            3 -> { k1 =          (data[tail + 2].toInt() and 0xFF) shl 16
                   k1 = k1 xor ((data[tail + 1].toInt() and 0xFF) shl 8)
                   k1 = k1 xor  (data[tail    ].toInt() and 0xFF)
                   k1 *= c1; k1 = k1.rotateLeft(15); k1 *= c2; h1 = h1 xor k1 }
            2 -> { k1 =          (data[tail + 1].toInt() and 0xFF) shl 8
                   k1 = k1 xor  (data[tail    ].toInt() and 0xFF)
                   k1 *= c1; k1 = k1.rotateLeft(15); k1 *= c2; h1 = h1 xor k1 }
            1 -> { k1 =          (data[tail    ].toInt() and 0xFF)
                   k1 *= c1; k1 = k1.rotateLeft(15); k1 *= c2; h1 = h1 xor k1 }
        }

        h1 = h1 xor len
        h1 = h1 xor (h1 ushr 16); h1 *= 0x85ebca6b.toInt()
        h1 = h1 xor (h1 ushr 13); h1 *= 0xc2b2ae35.toInt()
        h1 = h1 xor (h1 ushr 16)
        return h1
    }

    @EventListener(ApplicationReadyEvent::class)
    fun connect() {
        Thread.ofVirtual().name("ff-stream").start(::streamLoop)
    }

    private fun streamLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val conn = URL("$url/stream").openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.inputStream.bufferedReader().use { reader ->
                    var eventName = ""
                    reader.forEachLine { line ->
                        when {
                            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> handleEvent(eventName, line.removePrefix("data:").trim())
                            line.isEmpty() -> eventName = ""
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                Thread.sleep(5000)
            }
        }
    }

    private fun handleEvent(eventName: String, data: String) {
        when (eventName) {
            "put" -> {
                val node = objectMapper.readTree(data)
                node["flags"]?.forEach { cacheFlag(it["name"].asText(), it) }
            }
            "patch" -> {
                val node = objectMapper.readTree(data)
                cacheFlag(node["name"].asText(), node)
            }
        }
    }

    private fun cacheFlag(name: String, node: com.fasterxml.jackson.databind.JsonNode) {
        val overrides = node["flagOverrides"]
            ?.associate { it["userId"].asText() to it["override"].asBoolean() }
            ?: emptyMap()
        flags[name] = CachedFlag(
            enabled = node["enabled"].asBoolean(),
            rolloutPercentage = node["rolloutPercentage"].asInt(),
            overrides = overrides
        )
    }
}
