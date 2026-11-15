package com.rsmoviespot.data.repository

import com.rsmoviespot.BuildConfig
import com.rsmoviespot.data.api.CloudflareApi
import com.rsmoviespot.data.api.CloudflareImageRequest
import com.rsmoviespot.data.api.EdgeTtsApi
import com.rsmoviespot.data.api.EdgeTtsRequest
import com.rsmoviespot.data.api.GroqApi
import com.rsmoviespot.data.api.GroqChatRequest
import com.rsmoviespot.data.api.GroqMessage
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
    suspend fun generateScript(topic: String, isShorts: Boolean = true): VideoScriptResponse
    suspend fun generateImageForSlab(prompt: String, isShorts: Boolean = true): ResponseBody
    suspend fun generateAudioForSlab(text: String): ResponseBody
}

@Singleton
class VideoRepositoryImpl @Inject constructor(
    private val groqApi: GroqApi,
    private val cloudflareApi: CloudflareApi,
    private val edgeTtsApi: EdgeTtsApi
) : VideoRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun generateScript(
        topic: String,
        isShorts: Boolean
    ): VideoScriptResponse {
        require(topic.isNotBlank()) { "Topic must not be blank." }
        require(BuildConfig.GROQ_API_KEY.isNotBlank()) {
            "GROQ_API_KEY is empty."
        }

        val formatInstruction = if (isShorts) {
            "YouTube Shorts (vertical 9:16, 5 to 6 punchy scenes, hook in scene 1)"
        } else {
            "YouTube landscape video (16:9, 6 to 8 engaging storytelling scenes)"
        }

        val systemPrompt = SYSTEM_PROMPT_TEMPLATE
            .replace("{format}", formatInstruction)
            .replace("{topic}", topic.trim())

        val response = callWithHttpUrl {
            groqApi.generateContent(
                authHeader = "Bearer ${BuildConfig.GROQ_API_KEY}",
                request = GroqChatRequest(
                    model = "openai/gpt-oss-20b",
                    messages = listOf(
                        GroqMessage(
                            role = "system",
                            content = systemPrompt
                        ),
                        GroqMessage(
                            role = "user",
                            content = "Create the JSON script for topic: ${topic.trim()}"
                        )
                    ),
                    temperature = 0.7
                )
            )
        }

        val generatedText = response.choices
            .firstOrNull()
            ?.message
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Groq returned empty result.")

        return parseScriptResponse(generatedText)
    }

    override suspend fun generateImageForSlab(
        prompt: String,
        isShorts: Boolean
    ): ResponseBody {
        require(prompt.isNotBlank()) { "Image prompt must not be blank." }
        require(BuildConfig.CLOUDFLARE_ACCOUNT_ID.isNotBlank()) {
            "CLOUDFLARE_ACCOUNT_ID is empty."
        }
        require(BuildConfig.CLOUDFLARE_API_TOKEN.isNotBlank()) {
            "CLOUDFLARE_API_TOKEN is empty."
        }

        val imageWidth = if (isShorts) SHORTS_IMAGE_WIDTH else LANDSCAPE_IMAGE_WIDTH
        val imageHeight = if (isShorts) SHORTS_IMAGE_HEIGHT else LANDSCAPE_IMAGE_HEIGHT

        return callWithHttpUrl {
            cloudflareApi.generateImage(
                authorization = "Bearer ${BuildConfig.CLOUDFLARE_API_TOKEN}",
                accountId = BuildConfig.CLOUDFLARE_ACCOUNT_ID,
                model = "@cf/stabilityai/stable-diffusion-xl-base-1.0",
                request = CloudflareImageRequest(
                    prompt = prompt,
                    width = imageWidth,
                    height = imageHeight,
                    negativePrompt = DEFAULT_NEGATIVE_PROMPT
                )
            )
        }
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

    private fun parseScriptResponse(rawText: String): VideoScriptResponse {
        val extractedJson = extractJsonPayload(rawText)
        val root = json.parseToJsonElement(extractedJson)

        val slabElements: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val array = root["slabs"]
                    ?: root["scenes"]
                    ?: root["script"]
                    ?: root.values.firstOrNull { it is JsonArray }
                    ?: error("Expected a JSON array of scenes in Groq response.")

                array as? JsonArray
                    ?: error("'slabs' or 'scenes' must be a JSON array.")
            }
            else -> error("Groq response must be a JSON array or object.")
        }

        val slabs = slabElements.mapIndexedNotNull { index, element ->
            val dto = json.decodeFromJsonElement<GeneratedSlabDto>(element)
            val voiceover = dto.voiceoverText.trim()
            val prompt = dto.imagePrompt.trim()
            if (voiceover.isBlank() || prompt.isBlank()) {
                null
            } else {
                VideoSlab(
                    id = dto.id ?: index + 1,
                    voiceover_text = voiceover,
                    image_prompt = prompt
                )
            }
        }

        require(slabs.isNotEmpty()) { "Groq returned an empty script." }
        return VideoScriptResponse(slabs = slabs)
    }

    private fun extractJsonPayload(rawText: String): String {
        val withoutFences = rawText
            .trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()

        val firstBracket = withoutFences.indexOf('[')
        val firstBrace = withoutFences.indexOf('{')

        return when {
            firstBracket != -1 && (firstBrace == -1 || firstBracket < firstBrace) -> {
                val lastBracket = withoutFences.lastIndexOf(']')
                if (lastBracket > firstBracket) {
                    withoutFences.substring(firstBracket, lastBracket + 1)
                } else {
                    withoutFences
                }
            }
            firstBrace != -1 -> {
                val lastBrace = withoutFences.lastIndexOf('}')
                if (lastBrace > firstBrace) {
                    withoutFences.substring(firstBrace, lastBrace + 1)
                } else {
                    withoutFences
                }
            }
            else -> withoutFences
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

        const val SHORTS_IMAGE_WIDTH = 768
        const val SHORTS_IMAGE_HEIGHT = 1344
        const val LANDSCAPE_IMAGE_WIDTH = 1344
        const val LANDSCAPE_IMAGE_HEIGHT = 768
        const val DEFAULT_NEGATIVE_PROMPT =
            "text, watermark, signature, logo, blurry, low quality, distorted, deformed"

        const val SYSTEM_PROMPT_TEMPLATE =
            "You are a professional Hindi video scriptwriter for RS Movie Spot. " +
                "Create a script for a {format} on the topic \"{topic}\". " +
                "Return ONLY a valid JSON array (no markdown, no explanation) where each item has: " +
                "\"voiceover_text\" (natural engaging spoken Hindi in Devanagari script, 1-2 crisp sentences per scene) and " +
                "\"image_prompt\" (detailed cinematic visual description in English for AI image generation, no text or watermarks)."
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
