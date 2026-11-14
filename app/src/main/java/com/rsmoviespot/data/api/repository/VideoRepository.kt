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
            "GEMINI_API_KEY is empty. Check your GitHub Actions secret / local config."
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
            ?: throw IllegalStateException("Gemini returned no generated text.")

        return parseScriptResponse(generatedText)
    }

    private fun parseScriptResponse(text: String): VideoScriptResponse {
        val cleaned = text
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val root = json.parseToJsonElement(cleaned)

        val slabsJson: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val arr = root["slabs"] ?: root["scenes"]
                    ?: error("Expected JSON object with 'slabs' or 'scenes' array.")
                arr as? JsonArray
                    ?: error("'slabs'/'scenes' must be a JSON array.")
            }
            else -> error("Response must be a JSON array or object containing slabs.")
        }

        val slabs = slabsJson.mapIndexed { index, element ->
            val dto = json.decodeFromJsonElement<GeneratedSlabDto>(element)
            VideoSlab(
                id = dto.id ?: index + 1,
                voiceover_text = dto.voiceoverText,
                image_prompt = dto.imagePrompt
            )
        }

        require(slabs.isNotEmpty()) { "Gemini returned an empty script." }
        return VideoScriptResponse(slabs = slabs)
    }

    override suspend fun generateImageForSlab(prompt: String): ResponseBody {
        require(prompt.isNotBlank()) { "Image prompt must not be blank." }
        require(BuildConfig.HUGGING_FACE_KEY.isNotBlank()) {
            "HUGGING_FACE_KEY is empty. Check your API-key configuration."
        }

        return callWithHttpUrl {
            huggingFaceApi.generateImage(
                authorization = "Bearer ${BuildConfig.HUGGING_FACE_KEY}",
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

            val message = if (error.code() == 404) {
                "404 Error at URL: $url"
            } else {
                "HTTP ${error.code()} Error at URL: $url"
            }

            throw IllegalStateException(message, error)
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
        const val HINDI_VOICE = "hi-IN-MadhurNeural"

        const val SYSTEM_PROMPT_TEMPLATE =
            "You are a YouTube Shorts scriptwriter for the channel 'RS Movie Spot'. " +
                "The topic is {topic}. Create a highly engaging script divided exactly into " +
                "10-second slabs. Output ONLY valid JSON as an array of objects with " +
                "'voiceover_text' and 'image_prompt'. No markdown."
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
