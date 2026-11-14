package com.rsmoviespot.data.repository

import com.rsmoviespot.BuildConfig
import com.rsmoviespot.data.api.EdgeTtsApi
import com.rsmoviespot.data.api.EdgeTtsRequest
import com.rsmoviespot.data.api.GeminiApi
import com.rsmoviespot.data.api.GeminiContent
import com.rsmoviespot.data.api.GeminiGenerateContentRequest
import com.rsmoviespot.data.api.GeminiPart
import com.rsmoviespot.data.api.HuggingFaceApi
import com.rsmoviespot.data.api.HuggingFaceImageRequest
import com.rsmoviespot.domain.models.VideoScriptResponse
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import javax.inject.Inject
import javax.inject.Singleton

interface VideoRepository {
    suspend fun generateScript(topic: String): VideoScriptResponse
    suspend fun generateImageForSlab(prompt: String): ResponseBody
    suspend fun generateAudioForSlab(text: String): ResponseBody
}

@Singleton
class VideoRepositoryImpl @Inject constructor(
    private val geminiApi: GeminiApi,
    private val huggingFaceApi: HuggingFaceApi,
    private val edgeTtsApi: EdgeTtsApi
) : VideoRepository {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun generateScript(topic: String): VideoScriptResponse {
        require(topic.isNotBlank()) { "Topic must not be blank." }

        val systemPrompt = SYSTEM_PROMPT_TEMPLATE.replace("{topic}", topic.trim())
        val response = geminiApi.generateContent(
            apiKey = BuildConfig.GEMINI_API_KEY,
            request = GeminiGenerateContentRequest(
                systemInstruction = GeminiContent(
                    parts = listOf(GeminiPart(text = systemPrompt))
                ),
                contents = listOf(
                    GeminiContent(
                        role = "user",
                        parts = listOf(GeminiPart(text = "Generate the script now."))
                    )
                )
            )
        )

        val generatedText = response.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?: error("Gemini returned no generated script text.")

        return json.decodeFromString<VideoScriptResponse>(generatedText)
    }

    override suspend fun generateImageForSlab(prompt: String): ResponseBody {
        require(prompt.isNotBlank()) { "Image prompt must not be blank." }

        return huggingFaceApi.generateImage(
            authorization = "Bearer ${BuildConfig.HUGGING_FACE_KEY}",
            model = HUGGING_FACE_IMAGE_MODEL,
            request = HuggingFaceImageRequest(inputs = prompt)
        )
    }

    override suspend fun generateAudioForSlab(text: String): ResponseBody {
        require(text.isNotBlank()) { "Voiceover text must not be blank." }

        return edgeTtsApi.synthesize(
            EdgeTtsRequest(
                text = text,
                voice = HINDI_VOICE
            )
        )
    }

    private companion object {
        const val SYSTEM_PROMPT_TEMPLATE =
            "You are a YouTube Shorts scriptwriter for the channel 'RS Movie Spot'. " +
                "The topic is {topic}. Create a highly engaging script divided exactly into " +
                "10-second slabs. Output ONLY valid JSON containing a list of slabs with " +
                "'voiceover_text' and 'image_prompt'. No markdown formatting, no explanations."

        const val HUGGING_FACE_IMAGE_MODEL =
            "stabilityai/stable-diffusion-xl-base-1.0"
        const val HINDI_VOICE = "hi-IN-MadhurNeural"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VideoRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindVideoRepository(
        implementation: VideoRepositoryImpl
    ): VideoRepository
}
