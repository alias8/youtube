package org.example.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration
import java.util.UUID

@Service
class S3Service(
    private val s3Presigner: S3Presigner,
    @Value("\${aws.s3.bucket}") private val bucket: String,
    @Value("\${aws.s3.upload-url-expiration-minutes:15}") private val uploadExpiryMinutes: Long,
    @Value("\${aws.s3.playback-url-expiration-minutes:60}") private val playbackExpiryMinutes: Long
) {
    fun generateUploadUrl(filename: String, contentType: String): Pair<String, String> {
        val s3Key = "videos/${UUID.randomUUID()}/${filename}"
        val putRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(s3Key)
            .contentType(contentType)
            .build()
        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(uploadExpiryMinutes))
            .putObjectRequest(putRequest)
            .build()
        val url = s3Presigner.presignPutObject(presignRequest).url().toString()
        return Pair(url, s3Key)
    }

    fun generatePlaybackUrl(s3Key: String): String {
        val getRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(s3Key)
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(playbackExpiryMinutes))
            .getObjectRequest(getRequest)
            .build()
        return s3Presigner.presignGetObject(presignRequest).url().toString()
    }
}
