package org.example.repository

import org.example.model.VideoHistogram
import org.springframework.data.jpa.repository.JpaRepository

interface VideoHistogramRepository : JpaRepository<VideoHistogram, String>
