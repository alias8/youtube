package org.example.model

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "video_histograms")
class VideoHistogram(
    @Id
    val videoId: String = "",

    @Convert(converter = LongListConverter::class)
    @Column(columnDefinition = "TEXT", nullable = false)
    val buckets: List<Long> = List(100) { 0L },

    val lastFlushedAt: Instant = Instant.now()
)

@Converter
class LongListConverter : AttributeConverter<List<Long>, String> {
    private val mapper = ObjectMapper()
    private val type = object : TypeReference<List<Long>>() {}

    override fun convertToDatabaseColumn(attr: List<Long>?): String =
        mapper.writeValueAsString(attr ?: emptyList<Long>())

    override fun convertToEntityAttribute(data: String?): List<Long> =
        if (data.isNullOrBlank()) emptyList() else mapper.readValue(data, type)
}
