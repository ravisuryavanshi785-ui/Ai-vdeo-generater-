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
import com.rsmoviespot.domain.models.VideoSlab
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.ResponseBody
import retrofit2.HttpException
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
        isLenient = true
    }

    override suspend fun generateScript(topic: String): VideoScriptResponse {
        require(topic.isNotBlank()) { "Topic must not be blank." }
        require(BuildConfig.GEMINI_API_KEY.isNotBlank()) {
            "GEMINI_API_KEY is empty. Check the GitHub Actions secret and BuildConfig setup."
        }

        val prompt = SYSTEM_PROMPT_TEMPLATE.replace("{topic}", topic.trim())

        val response = callWithHttpUrl {
            geminiApi.generateContent(
                apiKey = BuildConfig.GEMINI_API_KEY,
                request = GeminiGenerateContentRequest(
                    systemInstruction = GeminiContent(
                        parts = listOf(GeminiPart(text = prompt))
                    ),
                    contents = listOf(
                        GeminiContent(
                            role = "user",
                            parts = listOf(GeminiPart(text = "Generate the script now."))
                        )
                    )
                )
            )
        }

        val generatedText = response.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?: error("Gemini returned no generated script text.")

        return parseScriptResponse(generatedText)
    }

    private fun parseScriptResponse(text: String): VideoScriptResponse {
        val cleanedText = text
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val root = json.parseToJsonElement(cleanedText)

        val slabElements: List<JsonElement> = when (root) {
            is JsonArray -> root

            is JsonObject -> {
                val slabs = root["slabs"] ?: root["scenes"]
                    ?: error("Gemini JSON object must contain a 'slabs' or 'scenes' array.")

                slabs as? JsonArray
                    ?: error("Gemini 'slabs'/'scenes' value must be a JSON array.")
            }

            else -> error("Gemini response must be a JSON array or object containing slabs.")
        }

        val slabs = slabElements.mapIndexed { index, element ->
            val generatedSlab = json.decodeFromJsonElement<GeneratedSlabDto>(element)

            VideoSlab(
                id = generatedSlab.id ?: index + 1,
                voiceover_text = generatedSlab.voiceoverText,
                image_prompt = generatedSlab.imagePrompt
            )
        }

        require(slabs.isNotEmpty()) { "Gemini returned an empty slab list." }
        return VideoScriptResponse(slabs = slabs)
    }

    override suspend fun generateImageForSlab(prompt: String): ResponseBody {
        require(prompt.isNotBlank()) { "Image prompt must not be blank." }
        require(BuildConfig.HUGGING_FACE_KEY.isNotBlank()) {
            "HUGGING_FACE_KEY is empty. Check the GitHub Actions secret and BuildConfig setup."
        }

        return callWithHttpUrl {
            huggingFaceApi.generateImage(
                authorization = "Bearer ${BuildConfig.HUGGING_FACE_KEY}",
                model = HUGGING_FACE_IMAGE_MODEL,
                request = HuggingFaceImageRequest(inputs = prompt)
            )
        }
    }

    override suspend fun generateAudioForSlab(text: String): ResponseBody {
        require(text.isNotBlank()) { "Voiceover text must not be blank." }

        return callWithHttpUrl {
            edgeTtsApi.synthesize(
                EdgeTtsRequest(
                    text = text,
                    voice = HINDI_VOICE
                )
            )
        }
    }

    private suspend fun <T> callWithHttpUrl(request: suspend () -> T): T {
        try {
            return request()
        } catch (error: HttpException) {
            val url = error.response()
                ?.raw()
                ?.request
                ?.url
                ?.toString()
                ?: "URL unavailable"

            throw IllegalStateException(
                "HTTP ${error.code()} Error at URL: $url",
                error
            )
        }
    }

    @Serializable
    private data class GeneratedSlabDto(
        val id: Int? = null,
        @SerialName("voiceover_text")
        val voiceoverText: String,
        @SerialName("image_prompt")
        val imagePrompt: String
    )

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
