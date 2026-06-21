package org.example.service

import org.example.dto.PlaybackUrlResponse
import org.example.dto.RegisterVideoRequest
import org.example.dto.UploadUrlResponse
import org.example.dto.VideoResponse
import org.example.model.Video
import org.example.repository.VideoRepository
import org.springframework.stereotype.Service

@Service
class VideoService(
    private val videoRepository: VideoRepository,
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
        return video.toResponse()
    }

    fun getById(id: String): Video? = videoRepository.findById(id).orElse(null)

    fun list(): List<VideoResponse> = videoRepository.findAll().map { it.toResponse() }

    fun getResponseById(id: String): VideoResponse? = videoRepository.findById(id).orElse(null)?.toResponse()

    fun getPlaybackUrl(id: String): PlaybackUrlResponse? {
        val video = getById(id) ?: return null
        return PlaybackUrlResponse(s3Service.generatePlaybackUrl(video.s3Key))
    }

    private fun Video.toResponse() = VideoResponse(
        id = id,
        title = title,
        description = description,
        uploaderId = uploaderId,
        durationSeconds = durationSeconds,
        status = status.name,
        createdAt = createdAt.toString()
    )
}
