package org.example.repository

import org.example.model.Video
import org.springframework.data.jpa.repository.JpaRepository

interface VideoRepository : JpaRepository<Video, String>
