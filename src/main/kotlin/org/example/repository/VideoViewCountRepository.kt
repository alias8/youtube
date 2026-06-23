package org.example.repository

import org.example.model.VideoViewCount
import org.springframework.data.jpa.repository.JpaRepository

interface VideoViewCountRepository : JpaRepository<VideoViewCount, String>
