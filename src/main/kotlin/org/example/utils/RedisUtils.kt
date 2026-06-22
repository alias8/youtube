package org.example.utils

import org.example.model.ReactionType

fun histogramKey(videoId: String) = "histogram:$videoId"
fun reactionCountKey(videoId: String, type: ReactionType) = "reactions:${type.name.lowercase()}:$videoId"
