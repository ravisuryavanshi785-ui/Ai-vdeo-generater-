package com.rsmoviespot.domain.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VideoScriptResponse(
    val slabs: List<VideoSlab>
)

@Serializable
data class VideoSlab(
    val id: Int,
    val voiceover_text: String,
    val image_prompt: String
)
