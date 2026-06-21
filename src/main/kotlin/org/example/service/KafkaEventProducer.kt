package org.example.service

import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class KafkaEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>
) {
    fun publishVideoRegistered(videoId: String) {
        kafkaTemplate.send("video-registered", videoId)
    }

    fun publishViewEvent(videoId: String) {
        kafkaTemplate.send("view-event", videoId)
    }
}
