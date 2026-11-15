package com.rsmoviespot.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class VideoScriptResponse(
    val character_bible: String = "",
    val visual_style: String = "",
    val bgm_mood: String = "epic_dark",
    val slabs: List<VideoSlab>
)

@Serializable
data class VideoSlab(
    val id: Int,
    val voiceover_text: String,
    val image_prompt: String,
    val sfx_cue: String = "whoosh",
    val has_character: Boolean = true
)
