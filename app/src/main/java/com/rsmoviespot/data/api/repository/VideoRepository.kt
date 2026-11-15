package com.rsmoviespot.data.repository

import com.rsmoviespot.BuildConfig
import com.rsmoviespot.data.api.CloudflareApi
import com.rsmoviespot.data.api.CloudflareImageRequest
import com.rsmoviespot.data.api.EdgeTtsApi
import com.rsmoviespot.data.api.EdgeTtsRequest
import com.rsmoviespot.data.api.GroqApi
import com.rsmoviespot.data.api.GroqChatRequest
import com.rsmoviespot.data.api.GroqMessage
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

        val defaultFormatGuide = if (isShorts) {
            "Vertical 9:16 YouTube Shorts. Unless the user specifies another length or scene count, create 7 to 8 tightly connected scenes (~40-50 seconds total)."
        } else {
            "Horizontal 16:9 YouTube video. Unless the user specifies another length or scene count, create 9 to 12 tightly connected scenes (~65-85 seconds total)."
        }

        val userPrompt = buildString {
            appendLine("USER REQUEST (FOLLOW EVERY INSTRUCTION HERE STRICTLY):")
            appendLine(topic.trim())
            appendLine()
            appendLine("Format guideline: $defaultFormatGuide")
            appendLine("Narrative mandate: Write ONE seamless, gripping Hindi story from start to finish where every scene flows naturally out of the previous scene. NEVER write disconnected encyclopedia facts or standalone summaries.")
            appendLine("If the user mentioned a specific duration (e.g. '1 minute', '30 sec'), exact scene count, specific style, or specific plot points, OBEY the user request above everything else.")
        }

        val modelsToTry = listOf(
            "llama-3.3-70b-versatile",
            "openai/gpt-oss-120b"
        )

        var lastError: Throwable? = null
        for ((index, modelId) in modelsToTry.withIndex()) {
            try {
                val response = callWithHttpUrl {
                    groqApi.generateContent(
                        authHeader = "Bearer ${BuildConfig.GROQ_API_KEY}",
                        request = GroqChatRequest(
                            model = modelId,
                            messages = listOf(
                                GroqMessage(
                                    role = "system",
                                    content = SYSTEM_PROMPT
                                ),
                                GroqMessage(
                                    role = "user",
                                    content = userPrompt
                                )
                            ),
                            temperature = 0.75,
                            maxTokens = 4096,
                            responseFormat = GroqResponseFormat(type = "json_object")
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                if (index == modelsToTry.lastIndex) {
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
            "You are an elite Hindi cinematic storyteller (like Nitish Rajput / Dhruv Rathee / top Indian documentary narrators) for RS Movie Spot.\n" +
                "Your #1 problem to AVOID: NEVER write disconnected Wikipedia facts or bullet-point trivia across scenes! Every scene must feel like the very next breath of ONE gripping story.\n\n" +
                "STRICT STORYTELLING RULES:\n" +
                "1. FIRST write the ENTIRE story in the \"continuous_script\" JSON field as ONE unbroken spoken Hindi monologue with a clear arc: Hook -> Rising Tension -> Turning Point -> Climax -> Goosebumps Ending.\n" +
                "2. THEN split that exact \"continuous_script\" sequentially into \"slabs\" (14-20 spoken Hindi words per slab for ~5 sec each, or as requested by user duration). Do NOT summarize or rewrite it into isolated facts inside slabs — reading all slabs back-to-back must sound like one smooth voiceover!\n" +
                "3. SCENE-TO-SCENE CONTINUATION: Every slab (except the last) MUST leave curiosity or connect directly to the next slab using natural spoken Hindi bridges like: \"लेकिन असली खेल तो तब शुरू हुआ जब...\", \"मगर सामने जो खड़ा था...\", \"और तभी उन्होंने एक ऐसी चाल चली...\", \"यहीं से पूरी कहानी पलट गई...\", \"पर सवाल ये था कि...\".\n" +
                "4. NO TEXTBOOK TONE: Use natural, emotional spoken Hindi in accurate Devanagari (e.g., करोड़, बॉक्स ऑफिस, छत्रपति शिवाजी महाराज). Avoid dry phrases like \"उनका जन्म ... में हुआ था\", \"उल्लेखनीय है कि\".\n" +
                "5. BAD (Disconnected Facts) vs GOOD (Connected Story):\n" +
                "   BAD: Scene 1: \"शिवाजी महाराज का जन्म 1630 में हुआ।\" | Scene 2: \"उन्होंने 16 साल की उम्र में तोरणा किला जीता।\" | Scene 3: \"उनकी सेना बहुत बहादुर थी।\"\n" +
                "   GOOD: Scene 1: \"सोचिए, सिर्फ सोलह साल की उम्र में एक लड़का मुगलों की सबसे बड़ी सल्तनत से टकराने निकल पड़ा...\" | Scene 2: \"लेकिन किसी ने नहीं सोचा था कि सह्याद्रि की पहाड़ियों से उठा ये तूफान...\" | Scene 3: \"एक रात चुपचाप तोरणा किले के दरवाज़े तोड़कर इतिहास की सबसे बड़ी जंग शुरू कर देगा!\"\n\n" +
                "Return ONLY valid JSON matching this exact schema (keep \"continuous_script\" BEFORE \"slabs\"):\n" +
                "{\n" +
                "  \"character_bible\": \"Exact unchanging visual look of main character in English (age, face, beard, exact outfit/armor colors, weapon)\",\n" +
                "  \"visual_style\": \"One unified cinematic art style, color palette, and dramatic lighting\",\n" +
                "  \"bgm_mood\": \"epic_dark | devotional | suspense | sci_fi | emotional\",\n" +
                "  \"continuous_script\": \"Full unbroken connected Hindi narration from hook to ending\",\n" +
                "  \"slabs\": [\n" +
                "    {\n" +
                "      \"voiceover_text\": \"Exact next sequential chunk of continuous_script in Devanagari\",\n" +
                "      \"has_character\": true,\n" +
                "      \"image_prompt\": \"Specific English visual scene matching this exact moment in the story (vary camera angles: wide battle, fort silhouette, close-up eyes, war map, crowd). Set has_character=false when character is not in frame.\",\n" +
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
