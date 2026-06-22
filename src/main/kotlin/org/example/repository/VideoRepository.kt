package org.example.repository

import org.example.model.Video
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface VideoRepository : JpaRepository<Video, String> {

    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): List<Video>

    @Query("""
        SELECT v FROM Video v
        WHERE v.createdAt < :createdAt OR (v.createdAt = :createdAt AND v.id < :id)
        ORDER BY v.createdAt DESC, v.id DESC
    """)
    fun findPage(
        @Param("createdAt") createdAt: Instant,
        @Param("id") id: String,
        pageable: Pageable
    ): List<Video>
}
