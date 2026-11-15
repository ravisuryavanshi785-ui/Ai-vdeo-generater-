package com.rsmoviespot.data.repository

import com.rsmoviespot.BuildConfig
import com.rsmoviespot.data.api.EdgeTtsApi
import com.rsmoviespot.data.api.EdgeTtsRequest
// Yahan update hai: Gemini hat gaya, Groq aa gaya
import com.rsmoviespot.data.api.GroqApi
import com.rsmoviespot.data.api.GroqChatRequest
import com.rsmoviespot.data.api.GroqMessage
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
    // Yahan GroqApi ko inject kiya
    private val groqApi: GroqApi,
    private val huggingFaceApi: HuggingFaceApi,
    private val edgeTtsApi: EdgeTtsApi
) : VideoRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun generateScript(topic: String): VideoScriptResponse {
        require(topic.isNotBlank()) { "Topic must not be blank." }
        // Groq ki key check kar rahe hain
        require(BuildConfig.GROQ_API_KEY.isNotBlank()) {
            "GROQ_API_KEY is empty."
        }

        val prompt = SYSTEM_PROMPT_TEMPLATE.replace("{topic}", topic.trim())

        return try {
            val response = callWithHttpUrl {
                // Groq API call
                groqApi.generateContent(
                    authHeader = "Bearer ${BuildConfig.GROQ_API_KEY}",
                    request = GroqChatRequest(
                        model = "llama-3.3-70b-versatile", // YAHAN FIX KIYA HAI: Model property pass karni zaroori hai
                        messages = listOf(
                            GroqMessage(
                                role = "system",
                                content = prompt
                            ),
                            GroqMessage(
                                role = "user",
                                content = "Generate the script now."
                            )
                        )
                    )
                )
            }

            // Groq ka response parse karna
            val generatedText = response.choices
                .firstOrNull()
                ?.message
                ?.content
                ?: throw IllegalStateException("Groq returned empty result.")

            parseScriptResponse(generatedText)
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string().orEmpty()
            throw IllegalStateException(
                "Groq API error: HTTP ${e.code()} -$errorBody",
                e
            )
        }
    }

    override suspend fun generateImageForSlab(prompt: String): ResponseBody {
        require(prompt.isNotBlank()) { "Image prompt must not be blank." }
        require(BuildConfig.HUGGING_FACE_KEY.isNotBlank()) {
            "HUGGING_FACE_KEY is empty."
        }

        return callWithHttpUrl {
            huggingFaceApi.generateImage(
                authorization = "Bearer ${BuildConfig.HUGGING_FACE_KEY}",
                model = "stabilityai/stable-diffusion-2-1",
                request = HuggingFaceImageRequest(
                    inputs = prompt
                )
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

    private fun parseScriptResponse(text: String): VideoScriptResponse {
        val cleaned = text
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val root = json.parseToJsonElement(cleaned)

        val slabElements: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val array = root["slabs"] ?: root["scenes"]
                    ?: error("Expected 'slabs' or 'scenes' JSON array.")
                array as? JsonArray
                    ?: error("'slabs' or 'scenes' must be a JSON array.")
            }
            else -> error("Groq response must be a JSON array or object.")
        }

        val slabs = slabElements.mapIndexed { index, element ->
            val dto = json.decodeFromJsonElement<GeneratedSlabDto>(element)
            VideoSlab(
                id = dto.id ?: index + 1,
                voiceover_text = dto.voiceoverText,
                image_prompt = dto.imagePrompt
            )
        }

        require(slabs.isNotEmpty()) { "Groq returned an empty script." }
        return VideoScriptResponse(slabs = slabs)
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

            val body = error.response()?.errorBody()?.string().orEmpty()
            throw IllegalStateException(
                "HTTP ${error.code()} Error at URL: $url. Body: $body",
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
        const val HINDI_VOICE = "hi-IN-MadhurNeural"

        const val SYSTEM_PROMPT_TEMPLATE =
            "You are a YouTube Shorts scriptwriter for RS Movie Spot. " +
                "The topic is {topic}. Return only a valid JSON array of objects " +
                "with 'voiceover_text' and 'image_prompt' fields."
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
