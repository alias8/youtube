package org.example.repository

import org.example.model.VideoViewEvent
import org.springframework.data.jpa.repository.JpaRepository

interface VideoViewEventRepository : JpaRepository<VideoViewEvent, String>
