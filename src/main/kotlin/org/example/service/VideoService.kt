package org.example.service

import org.example.dto.PlaybackUrlResponse
import org.example.dto.RegisterVideoRequest
import org.example.dto.UploadUrlResponse
import org.example.dto.VideoPageResponse
import org.example.dto.VideoResponse
import org.example.model.Video
import org.example.model.VideoDocument
import org.example.repository.VideoRepository
import org.example.repository.VideoSearchRepository
import org.example.repository.VideoViewCountRepository
import org.example.repository.WatchHistoryRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Base64

@Service
class VideoService(
    private val videoRepository: VideoRepository,
    private val videoSearchRepository: VideoSearchRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val videoViewCountRepository: VideoViewCountRepository,
    private val redisTemplate: RedisTemplate<String, String>,
    private val s3Service: S3Service,
    private val kafkaEventProducer: KafkaEventProducer
) {
    fun generateUploadUrl(filename: String, contentType: String): UploadUrlResponse {
        val (url, s3Key) = s3Service.generateUploadUrl(filename, contentType)
        return UploadUrlResponse(uploadUrl = url, s3Key = s3Key)
    }

    fun registerVideo(request: RegisterVideoRequest, uploaderId: String): VideoResponse {
        val video = videoRepository.save(
            Video(
                title = request.title,
                description = request.description,
                uploaderId = uploaderId,
                s3Key = request.s3Key,
                durationSeconds = request.durationSeconds
            )
        )
        kafkaEventProducer.publishVideoRegistered(video.id)
        // Index in ES after Postgres write. ES is a read model — if this fails, Postgres is still consistent.
        runCatching { videoSearchRepository.save(video.toDocument()) }
        return video.toResponse(lastWatchedSeconds = null, viewCount = 0L)
    }

    fun search(query: String, limit: Int = 20, userId: String? = null): List<VideoResponse> {
        val hits = videoSearchRepository.search(query, PageRequest.of(0, limit)).toList()
        val ids = hits.map { it.content.id }
        val progress = watchProgressMap(userId, ids)
        val counts = viewCountMap(ids)
        return hits.map { it.content.toResponse(progress[it.content.id], counts[it.content.id] ?: 0L) }
    }

    fun getById(id: String): Video? = videoRepository.findById(id).orElse(null)

    fun list(cursor: String?, limit: Int = 20, userId: String? = null): VideoPageResponse {
        val pageable = PageRequest.of(0, limit + 1)
        val videos = if (cursor == null) {
            videoRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
        } else {
            val (createdAt, id) = decodeCursor(cursor)
            videoRepository.findPage(createdAt, id, pageable)
        }
        val hasMore = videos.size > limit
        val page = if (hasMore) videos.dropLast(1) else videos
        val progress = watchProgressMap(userId, page.map { it.id })
        val counts = viewCountMap(page.map { it.id })
        return VideoPageResponse(
            videos = page.map { it.toResponse(progress[it.id], counts[it.id] ?: 0L) },
            nextCursor = if (hasMore) encodeCursor(page.last()) else null,
            hasMore = hasMore
        )
    }

    private fun watchProgressMap(userId: String?, videoIds: Collection<String>): Map<String, Long> {
        if (userId == null || videoIds.isEmpty()) return emptyMap()
        return watchHistoryRepository.findByUserIdAndVideoIdIn(userId, videoIds)
            .associate { it.videoId to it.lastWatchedSeconds }
    }

    private fun viewCountMap(videoIds: Collection<String>): Map<String, Long> {
        if (videoIds.isEmpty()) return emptyMap()
        val keys = videoIds.map { "views:total:$it" }
        val values = redisTemplate.opsForValue().multiGet(keys) ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        val misses = mutableListOf<String>()
        videoIds.zip(values).forEach { (videoId, value) ->
            if (value != null) result[videoId] = value.toLong()
            else misses.add(videoId)
        }
        if (misses.isNotEmpty()) {
            videoViewCountRepository.findAllById(misses).forEach { result[it.videoId] = it.count }
        }
        return result
    }

    private fun encodeCursor(video: Video): String =
        Base64.getEncoder().encodeToString("${video.createdAt.toEpochMilli()}:${video.id}".toByteArray())

    private fun decodeCursor(cursor: String): Pair<Instant, String> {
        val decoded = String(Base64.getDecoder().decode(cursor))
        val (epochMilli, id) = decoded.split(":", limit = 2)
        return Instant.ofEpochMilli(epochMilli.toLong()) to id
    }

    fun getResponseById(id: String, userId: String? = null): VideoResponse? {
        val video = videoRepository.findById(id).orElse(null) ?: return null
        val lastWatchedSeconds = userId?.let {
            watchHistoryRepository.findByUserIdAndVideoId(it, id)?.lastWatchedSeconds
        }
        return video.toResponse(lastWatchedSeconds, viewCountMap(listOf(id))[id] ?: 0L)
    }

    fun getPlaybackUrl(id: String): PlaybackUrlResponse? {
        val video = getById(id) ?: return null
        return PlaybackUrlResponse(s3Service.generatePlaybackUrl(video.s3Key))
    }

    private fun Video.toDocument() = VideoDocument(
        id = id,
        title = title,
        description = description,
        uploaderId = uploaderId,
        status = status.name,
        createdAt = createdAt
    )

    private fun Video.toResponse(lastWatchedSeconds: Long?, viewCount: Long) = VideoResponse(
        id = id,
        title = title,
        description = description,
        uploaderId = uploaderId,
        durationSeconds = durationSeconds,
        status = status.name,
        createdAt = createdAt.toString(),
        viewCount = viewCount,
        lastWatchedSeconds = lastWatchedSeconds
    )

    private fun VideoDocument.toResponse(lastWatchedSeconds: Long?, viewCount: Long) = VideoResponse(
        id = id,
        title = title,
        description = description,
        uploaderId = uploaderId,
        durationSeconds = 0,
        status = status,
        createdAt = createdAt.toString(),
        viewCount = viewCount,
        lastWatchedSeconds = lastWatchedSeconds
    )
}
