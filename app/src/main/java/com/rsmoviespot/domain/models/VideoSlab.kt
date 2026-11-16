package com.rsmoviespot.domain.models

import kotlinx.serialization.Serializable

enum class VoiceGender(val label: String) {
    MALE("Male"),
    FEMALE("Female")
}

enum class VideoLanguage(
    val label: String,
    val languageInstruction: String,
    val maleVoice: String,
    val femaleVoice: String
) {
    AUTO("Auto", "", "hi-IN-MadhurNeural", "hi-IN-SwaraNeural"),
    HINDI("Hindi", "spoken Hindi in Devanagari script", "hi-IN-MadhurNeural", "hi-IN-SwaraNeural"),
    ENGLISH_IN("English (IN)", "natural Indian English", "en-IN-PrabhatNeural", "en-IN-NeerjaNeural"),
    ENGLISH_US("English (US)", "punchy American English", "en-US-AndrewNeural", "en-US-AvaNeural"),
    MARATHI("Marathi", "natural Marathi in Devanagari script", "mr-IN-ManoharNeural", "mr-IN-AarohiNeural"),
    GUJARATI("Gujarati", "natural Gujarati in Gujarati script", "gu-IN-NiranjanNeural", "gu-IN-DhwaniNeural"),
    BENGALI("Bengali", "natural Bengali in Bengali script", "bn-IN-BashkarNeural", "bn-IN-TanishaaNeural"),
    TAMIL("Tamil", "natural Tamil in Tamil script", "ta-IN-ValluvarNeural", "ta-IN-PallaviNeural"),
    TELUGU("Telugu", "natural Telugu in Telugu script", "te-IN-MohanNeural", "te-IN-ShrutiNeural"),
    KANNADA("Kannada", "natural Kannada in Kannada script", "kn-IN-GaganNeural", "kn-IN-SapnaNeural"),
    MALAYALAM("Malayalam", "natural Malayalam in Malayalam script", "ml-IN-MidhunNeural", "ml-IN-SobhanaNeural"),
    PUNJABI("Punjabi", "natural Punjabi in Gurmukhi script", "pa-IN-OjasNeural", "pa-IN-VaaniNeural"),
    URDU("Urdu", "natural Urdu in Perso-Arabic script", "ur-IN-SalmanNeural", "ur-IN-GulNeural"),
    SPANISH("Spanish", "natural Spanish", "es-ES-AlvaroNeural", "es-ES-ElviraNeural"),
    FRENCH("French", "natural French", "fr-FR-HenriNeural", "fr-FR-DeniseNeural"),
    JAPANESE("Japanese", "natural Japanese", "ja-JP-KeitaNeural", "ja-JP-NanamiNeural"),
    ARABIC("Arabic", "natural Arabic", "ar-SA-HamedNeural", "ar-SA-ZariyahNeural");

    fun resolveVoice(gender: VoiceGender): String =
        if (gender == VoiceGender.FEMALE) femaleVoice else maleVoice
}

@Serializable
data class VideoScriptResponse(
    val character_bible: String = "",
    val visual_style: String = "",
    val bgm_mood: String = "epic_action",
    val voice_name: String = "hi-IN-MadhurNeural",
    val slabs: List<VideoSlab>
)

@Serializable
data class VideoSlab(
    val id: Int,
    val voiceover_text: String,
    val image_prompt: String,
    val sfx_cue: String = "whoosh",
    val has_character: Boolean = true,
    val voice_emotion: String = "normal",
    val visual_source: String = "ai_generated",
    val real_photo_query: String = ""
)
