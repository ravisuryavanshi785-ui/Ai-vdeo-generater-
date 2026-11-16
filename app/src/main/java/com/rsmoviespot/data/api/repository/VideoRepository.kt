package com.rsmoviespot.data.repository

import com.rsmoviespot.BuildConfig
import com.rsmoviespot.data.api.CloudflareApi
import com.rsmoviespot.data.api.CloudflareImageRequest
import com.rsmoviespot.data.api.EdgeTtsApi
import com.rsmoviespot.data.api.EdgeTtsRequest
import com.rsmoviespot.data.api.EdgeTtsSynthesisResult
import com.rsmoviespot.data.api.GroqApi
import com.rsmoviespot.data.api.GroqChatRequest
import com.rsmoviespot.data.api.GroqMessage
import com.rsmoviespot.data.api.GroqPlainChatRequest
import com.rsmoviespot.data.api.GroqResponseFormat
import com.rsmoviespot.domain.models.VideoScriptResponse
import com.rsmoviespot.domain.models.VideoSlab
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
import java.io.IOException
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
    suspend fun generateContinuousAudioWithVtt(text: String): EdgeTtsSynthesisResult
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

    private data class ScriptAttempt(
        val modelId: String,
        val useJsonMode: Boolean,
        val maxTokens: Int
    )

    override suspend fun generateScript(
        topic: String,
        isShorts: Boolean
    ): VideoScriptResponse {
        require(topic.isNotBlank()) { "Topic must not be blank." }
        require(BuildConfig.GROQ_API_KEY.isNotBlank()) {
            "GROQ_API_KEY is empty."
        }

        val defaultFormatGuide = if (isShorts) {
            "Vertical 9:16 YouTube Shorts. Unless the user specifies another length, create 8 to 10 tightly connected scenes (~40-50 seconds total)."
        } else {
            "Horizontal 16:9 YouTube video. Unless the user specifies another length, create 10 to 14 tightly connected scenes (~65-85 seconds total)."
        }

        val userPrompt = buildString {
            appendLine("USER TOPIC & INSTRUCTIONS (OBEY STRICTLY):")
            appendLine(topic.trim())
            appendLine()
            appendLine("Format guideline: $defaultFormatGuide")
            appendLine("CRITICAL: Write ONE continuous, connected spoken Hindi script split smoothly across the 'slabs' array — reading every slab's voiceover_text back-to-back must sound like ONE single unbroken storyteller, NEVER isolated bullet facts.")
            appendLine("CRITICAL FOR PHOTOS: Every slab's image_prompt must depict the exact thing/action happening in THAT slab's line (with varied settings/angles and has_character=false when no main character is needed).")
            appendLine("Note: Avoid unescaped double quotes inside JSON string values; use single quotes if quoting dialogue.")
        }

        val messages = listOf(
            GroqMessage(role = "system", content = SYSTEM_PROMPT),
            GroqMessage(role = "user", content = userPrompt)
        )

        val attempts = listOf(
            ScriptAttempt("llama-3.3-70b-versatile", useJsonMode = true, maxTokens = 6000),
            ScriptAttempt("llama-3.3-70b-versatile", useJsonMode = false, maxTokens = 6000),
            ScriptAttempt("openai/gpt-oss-120b", useJsonMode = false, maxTokens = 8192)
        )

        var lastError: Throwable? = null
        for ((index, attempt) in attempts.withIndex()) {
            try {
                val response = callWithHttpUrl {
                    if (attempt.useJsonMode) {
                        groqApi.generateContent(
                            authHeader = "Bearer ${BuildConfig.GROQ_API_KEY}",
                            request = GroqChatRequest(
                                model = attempt.modelId,
                                messages = messages,
                                temperature = 0.75,
                                maxTokens = attempt.maxTokens,
                                responseFormat = GroqResponseFormat(type = "json_object")
                            )
                        )
                    } else {
                        groqApi.generateContentPlain(
                            authHeader = "Bearer ${BuildConfig.GROQ_API_KEY}",
                            request = GroqPlainChatRequest(
                                model = attempt.modelId,
                                messages = messages,
                                temperature = 0.75,
                                maxTokens = attempt.maxTokens
                            )
                        )
                    }
                }

                val generatedText = response.choices
                    .firstOrNull()
                    ?.message
                    ?.content
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Groq returned empty result.")

                return parseScriptResponse(generatedText)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                if (index == attempts.lastIndex) {
                    throw error
                }
            }
        }

        throw lastError ?: IllegalStateException("Failed to generate script.")
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
                    numSteps = 16,
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

    override suspend fun generateContinuousAudioWithVtt(text: String): EdgeTtsSynthesisResult {
        require(text.isNotBlank()) { "Voiceover text must not be blank." }
        return edgeTtsApi.synthesizeWithSubtitles(
            text = text,
            voice = HINDI_VOICE
        )
    }

    private fun parseScriptResponse(rawText: String): VideoScriptResponse {
        val extractedJson = extractJsonPayload(rawText)
            .replace(Regex(",\\s*([}\\]])"), "$1")
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
                    has_character = dto.hasCharacter ?: false
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
        var lastError: Throwable? = null

        repeat(MAX_API_ATTEMPTS) { attempt ->
            try {
                return request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HttpException) {
                val code = error.code()
                val url = error.response()
                    ?.raw()
                    ?.request
                    ?.url
                    ?.toString()
                    ?: "URL unavailable"
                val body = error.response()?.errorBody()?.string().orEmpty()
                val wrapped = IllegalStateException(
                    "HTTP $code Error at URL: $url. Body: $body",
                    error
                )
                lastError = wrapped
                if ((code == 429 || code in 500..599) && attempt < MAX_API_ATTEMPTS - 1) {
                    delay(1500L * (attempt + 1))
                } else {
                    throw wrapped
                }
            } catch (error: IOException) {
                lastError = error
                if (attempt < MAX_API_ATTEMPTS - 1) {
                    delay(1500L * (attempt + 1))
                } else {
                    throw error
                }
            }
        }

        throw lastError ?: IllegalStateException("API request failed.")
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
        const val MAX_API_ATTEMPTS = 3
        const val HINDI_VOICE = "hi-IN-MadhurNeural"

        const val SHORTS_IMAGE_WIDTH = 768
        const val SHORTS_IMAGE_HEIGHT = 1344
        const val LANDSCAPE_IMAGE_WIDTH = 1344
        const val LANDSCAPE_IMAGE_HEIGHT = 768
        const val DEFAULT_NEGATIVE_PROMPT =
            "text, watermark, signature, logo, blurry, low quality, distorted, deformed, extra limbs, extra arms, extra fingers, collage, split screen"

        const val SYSTEM_PROMPT =
            "You are an elite Hindi YouTube storyteller, movie critic, and film director for RS Movie Spot.\n" +
                "Think like ONE continuous spoken voiceover (like Dhruv Rathee / Nitish Rajput / top Hindi movie roast creators) where every scene's voiceover_text is literally the NEXT flowing phrase of the same unbroken story!\n\n" +
                "RULE 1 — CONTINUOUS NARRATION (NEVER DISCONNECTED FACTS):\n" +
                "- Write ONE seamless story arc: Hook -> Buildup -> Twist/Contrast -> Climax -> Punchy Ending.\n" +
                "- Each slab's voiceover_text (12-18 spoken Hindi words, ~4.5-5.5s each, or adjust count to match user's requested duration like 1 min = ~12 scenes, 1.5 min = ~16-18 scenes) MUST connect smoothly to the previous and next slab using natural spoken connectors ('लेकिन...', 'मगर असली मज़ा तो तब आया जब...', 'और यहीं पर...', 'सोचिए...', 'पर दिक्कत ये थी कि...').\n" +
                "- NEVER write isolated encyclopedia lines! When all voiceover_text values are joined with spaces and read aloud by ONE voice, it must sound 100% continuous.\n" +
                "- BAD (Disconnected Facts): 'आदिपुरुष का बजट 500 करोड़ था।' -> 'इसके डायलॉग खराब थे।' -> 'पब्लिक को गुस्सा आया।'\n" +
                "- GOOD (Continuous Flow): 'सोचिए, पाँच सौ करोड़ रुपये लगाकर कोई फिल्म बनाए...' -> 'और दर्शक थिएटर में बैठकर सिर्फ ये सोचें कि ये कौन सा कार्टून चल रहा है!' -> 'क्योंकि मेकर्स ने रामायण की भावना के बजाय सस्ते वीएफएक्स पर पैसा उड़ा दिया...' -> 'और ऊपर से ऐसे डायलॉग लिख दिए जिन्हें सुनकर खुद पब्लिक का पारा चढ़ गया!'\n" +
                "- Use accurate spoken Hindi in Devanagari (करोड़, बॉक्स ऑफिस, थिएटर, दर्शक). Never put unescaped double quotes inside JSON strings (use single quotes if needed).\n\n" +
                "RULE 2 — SCENE-MATCHED PHOTOS (EVERY SCENE LOOKS DIFFERENT & RELEVANT):\n" +
                "- Each slab's image_prompt MUST visually show what is happening in THAT SPECIFIC line — NEVER repeat generic standing portraits!\n" +
                "- Set has_character=false whenever the scene is about crowds, audiences, money/budget, green-screen VFX, locations, forts, battlefields, cinema halls, or objects. Only set has_character=true when the main character from character_bible is directly in that shot.\n" +
                "- Vary shot types constantly: extreme close-up emotion, wide aerial view, over-the-shoulder, cinema theater audience reaction, messy green-screen studio, dramatic silhouette, burning torches, war map table.\n\n" +
                "Return ONLY valid JSON matching this schema:\n" +
                "{\n" +
                "  \"character_bible\": \"Unchanging look of main character in English (if topic has one main character, else empty string)\",\n" +
                "  \"visual_style\": \"Unified cinematic visual style, color palette, lighting\",\n" +
                "  \"bgm_mood\": \"epic_dark | devotional | suspense | sci_fi | emotional\",\n" +
                "  \"slabs\": [\n" +
                "    {\n" +
                "      \"voiceover_text\": \"Next continuous spoken Hindi segment in Devanagari\",\n" +
                "      \"has_character\": false,\n" +
                "      \"image_prompt\": \"Detailed English visual prompt strictly illustrating this specific line with camera angle, action, and setting\",\n" +
                "      \"sfx_cue\": \"boom | thunder | sword | fire | whoosh\"\n" +
                "    }\n" +
                "  ]\n" +
                "}"
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
