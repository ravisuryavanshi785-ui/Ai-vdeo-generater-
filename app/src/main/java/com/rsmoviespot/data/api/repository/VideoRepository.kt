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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

interface VideoRepository {
    suspend fun generateScript(topic: String, isShorts: Boolean = true): VideoScriptResponse
    suspend fun generateImageForSlab(
        prompt: String,
        isShorts: Boolean = true,
        seed: Int = 42
    ): ResponseBody
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
            "YouTube Shorts (vertical 9:16, 7 to 9 fast-paced scenes, 10-14 words per scene, hook in scene 1)"
        } else {
            "YouTube landscape video (16:9, 8 to 12 engaging scenes, 12-18 words per scene)"
        }

        val systemPrompt = SYSTEM_PROMPT_TEMPLATE
            .replace("{format}", formatInstruction)
            .replace("{topic}", topic.trim())

        val response = callWithHttpUrl {
            groqApi.generateContent(
                authHeader = "Bearer ${BuildConfig.GROQ_API_KEY}",
                request = GroqChatRequest(
                    model = "openai/gpt-oss-120b",
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
                    temperature = 0.75
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
        isShorts: Boolean,
        seed: Int
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
                    negativePrompt = DEFAULT_NEGATIVE_PROMPT,
                    seed = seed,
                    numSteps = 20,
                    guidance = 7.5
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

        var characterBible = ""
        var visualStyle = ""
        var bgmMood = "epic_dark"

        val slabElements: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                characterBible = root["character_bible"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                visualStyle = root["visual_style"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                bgmMood = root["bgm_mood"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    .ifBlank { "epic_dark" }

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
            val sfxCue = dto.sfxCue?.trim()?.lowercase()?.ifBlank { "whoosh" } ?: "whoosh"
            if (voiceover.isBlank() || prompt.isBlank()) {
                null
            } else {
                VideoSlab(
                    id = dto.id ?: index + 1,
                    voiceover_text = voiceover,
                    image_prompt = prompt,
                    sfx_cue = sfxCue,
                    has_character = dto.hasCharacter ?: true
                )
            }
        }

        require(slabs.isNotEmpty()) { "Groq returned an empty script." }
        return VideoScriptResponse(
            character_bible = characterBible,
            visual_style = visualStyle,
            bgm_mood = bgmMood,
            slabs = slabs
        )
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
        val imagePrompt: String,
        @SerialName("sfx_cue")
        val sfxCue: String? = null,
        @SerialName("has_character")
        val hasCharacter: Boolean? = null
    )

    private companion object {
        const val HINDI_VOICE = "hi-IN-MadhurNeural"

        const val SHORTS_IMAGE_WIDTH = 768
        const val SHORTS_IMAGE_HEIGHT = 1344
        const val LANDSCAPE_IMAGE_WIDTH = 1344
        const val LANDSCAPE_IMAGE_HEIGHT = 768
        const val DEFAULT_NEGATIVE_PROMPT =
            "text, watermark, signature, logo, blurry, low quality, distorted, deformed, extra limbs, extra arms, extra fingers, collage, split screen"

        const val SYSTEM_PROMPT_TEMPLATE =
            "You are a professional Hindi video scriptwriter and visual director for RS Movie Spot. " +
                "Create a script for a {format} on the topic \"{topic}\". " +
                "Return ONLY a valid JSON object (no markdown, no explanation) with this structure: " +
                "{" +
                "\"character_bible\": \"Short fixed description of the main character if the story has one (face, exact outfit/armor colors, single prop)\", " +
                "\"visual_style\": \"Unified cinematic art style and lighting across scenes\", " +
                "\"bgm_mood\": \"One of: epic_dark, devotional, suspense, sci_fi, emotional\", " +
                "\"slabs\": [" +
                "{\"voiceover_text\": \"1 short punchy Hindi sentence (10-14 words max) in Devanagari; spell करोड़ properly, transliterate English words like बॉक्स ऑफिस, कलेक्शन\", " +
                "\"has_character\": true_or_false, " +
                "\"image_prompt\": \"Rich visual scene in English. IMPORTANT: Do NOT repeat the same standing portrait! Vary shots wildly: crowded cinema theatre, wide battlefield, intense close-up, aerial landscape, golden coins/trophy, dramatic silhouette. Set has_character=false when showing environments/crowds/objects, and has_character=true ONLY when the main character appears in a distinct action pose or angle. Use 'Lord Rama' instead of 'Ram'.\", " +
                "\"sfx_cue\": \"One of: boom, thunder, sword, fire, whoosh\"}" +
                "]}"
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
