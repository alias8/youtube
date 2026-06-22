package org.example.service

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

@Service
class KafkaConsumer {

    @KafkaListener(topics = ["video-registered"], groupId = "youtube-group")
    fun onVideoRegistered(videoId: String) {
        // Trigger transcoding pipeline, thumbnail generation, etc.
    }

    @KafkaListener(topics = ["view-event"], groupId = "youtube-group")
    fun onViewEvent(payload: String) {
        // In production: forward to cold storage (S3/GCS) for batch ML/recommendations.
        // Histogram persistence is handled by HistogramFlushService.
    }
}
