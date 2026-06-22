package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.example.dto.ViewEventMessage
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class KafkaEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    fun publishVideoRegistered(videoId: String) {
        kafkaTemplate.send("video-registered", videoId)
    }

    fun publishViewEvent(videoId: String, userId: String?, startSeconds: Long, endSeconds: Long) {
        val payload = objectMapper.writeValueAsString(ViewEventMessage(videoId, userId, startSeconds, endSeconds))
        kafkaTemplate.send("view-event", payload)
    }
}
