package org.example.model

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import java.time.Instant

@Document(indexName = "videos")
data class VideoDocument(
    @Id
    val id: String,

    @Field(type = FieldType.Text, analyzer = "english")
    val title: String,

    @Field(type = FieldType.Text, analyzer = "english")
    val description: String,

    @Field(type = FieldType.Keyword)
    val uploaderId: String,

    @Field(type = FieldType.Keyword)
    val status: String,

    @Field(type = FieldType.Date)
    val createdAt: Instant
)
