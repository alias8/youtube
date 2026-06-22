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
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Base64

@Service
class VideoService(
    private val videoRepository: VideoRepository,
    private val videoSearchRepository: VideoSearchRepository,
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
        return video.toResponse()
    }

    fun search(query: String, limit: Int = 20): List<VideoResponse> =
        videoSearchRepository.search(query, PageRequest.of(0, limit))
            .map { it.content.toResponse() }

    fun getById(id: String): Video? = videoRepository.findById(id).orElse(null)

    fun list(cursor: String?, limit: Int = 20): VideoPageResponse {
        val pageable = PageRequest.of(0, limit + 1)
        val videos = if (cursor == null) {
            videoRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
        } else {
            val (createdAt, id) = decodeCursor(cursor)
            videoRepository.findPage(createdAt, id, pageable)
        }
        val hasMore = videos.size > limit
        val page = if (hasMore) videos.dropLast(1) else videos
        return VideoPageResponse(
            videos = page.map { it.toResponse() },
            nextCursor = if (hasMore) encodeCursor(page.last()) else null,
            hasMore = hasMore
        )
    }

    private fun encodeCursor(video: Video): String =
        Base64.getEncoder().encodeToString("${video.createdAt.toEpochMilli()}:${video.id}".toByteArray())

    private fun decodeCursor(cursor: String): Pair<Instant, String> {
        val decoded = String(Base64.getDecoder().decode(cursor))
        val (epochMilli, id) = decoded.split(":", limit = 2)
        return Instant.ofEpochMilli(epochMilli.toLong()) to id
    }

    fun getResponseById(id: String): VideoResponse? = videoRepository.findById(id).orElse(null)?.toResponse()

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

    private fun Video.toResponse() = VideoResponse(
        id = id,
        title = title,
        description = description,
        uploaderId = uploaderId,
        durationSeconds = durationSeconds,
        status = status.name,
        createdAt = createdAt.toString(),
        viewCount = viewCount
    )

    private fun VideoDocument.toResponse() = VideoResponse(
        id = id,
        title = title,
        description = description,
        uploaderId = uploaderId,
        durationSeconds = 0,
        status = status,
        createdAt = createdAt.toString(),
        viewCount = 0
    )
}
