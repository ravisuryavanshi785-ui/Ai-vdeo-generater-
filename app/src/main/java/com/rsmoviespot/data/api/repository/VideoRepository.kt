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
import com.rsmoviespot.domain.models.VideoLanguage
import com.rsmoviespot.domain.models.VideoScriptResponse
import com.rsmoviespot.domain.models.VideoSlab
import com.rsmoviespot.domain.models.VoiceGender
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
    suspend fun generateScript(
        topic: String,
        isShorts: Boolean = true,
        language: VideoLanguage = VideoLanguage.AUTO,
        voiceGender: VoiceGender = VoiceGender.MALE
    ): VideoScriptResponse

    suspend fun generateImageForSlab(
        prompt: String,
        isShorts: Boolean = true,
        seed: Int = 42
    ): ResponseBody

    suspend fun generateAudioForSlab(text: String): ResponseBody

    suspend fun generateContinuousAudioWithVtt(text: String): EdgeTtsSynthesisResult

    suspend fun generateDynamicVoiceClip(
        text: String,
        voiceName: String,
        emotion: String
    ): ByteArray
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
        isShorts: Boolean,
        language: VideoLanguage,
        voiceGender: VoiceGender
    ): VideoScriptResponse {
        val cleanInput = topic.trim()
        require(cleanInput.isNotBlank()) { "Topic must not be blank." }
        require(BuildConfig.GROQ_API_KEY.isNotBlank()) {
            "GROQ_API_KEY is empty."
        }

        val inputWordCount = cleanInput.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val isFullScriptMode = inputWordCount > FULL_SCRIPT_WORD_THRESHOLD

        val systemPrompt = if (isFullScriptMode) {
            FULL_SCRIPT_SYSTEM_PROMPT
        } else {
            TOPIC_SYSTEM_PROMPT
        }

        val targetGenderLabel = voiceGender.label
        val languageDirective = if (language == VideoLanguage.AUTO) {
            "Language: Auto-detect from user request. Default to spoken Hindi (Devanagari) with voice_name '${VideoLanguage.HINDI.resolveVoice(voiceGender)}', UNLESS the user explicitly requested another language (e.g. English, Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, Punjabi, Urdu, Spanish, French, Japanese, Arabic), in which case write in THAT language and pick the matching $targetGenderLabel Edge TTS voice_name."
        } else {
            "Language MANDATORY: Write ALL voiceover_text strictly in ${language.languageInstruction} and set voice_name to '${language.resolveVoice(voiceGender)}'."
        }

        val userPrompt = if (isFullScriptMode) {
            buildString {
                appendLine("MODE: FULL USER SCRIPT ($inputWordCount words).")
                appendLine("DO NOT rewrite, paraphrase, translate, add, or change a single word. Keep the EXACT user text intact and split sequentially into 12 to 15 short punchy visual scenes (splitting longer sentences cleanly at commas/clauses so each cut stays ~3.5-4.5s).")
                appendLine("Set voice_name matching the script's language and $targetGenderLabel gender (e.g., Hindi: ${VideoLanguage.HINDI.resolveVoice(voiceGender)}, English IN: ${VideoLanguage.ENGLISH_IN.resolveVoice(voiceGender)}, English US: ${VideoLanguage.ENGLISH_US.resolveVoice(voiceGender)}, Marathi: ${VideoLanguage.MARATHI.resolveVoice(voiceGender)}).")
                appendLine()
                appendLine("USER SCRIPT:")
                appendLine(cleanInput)
            }
        } else {
            val formatGuide = if (isShorts) {
                "Vertical 9:16 YouTube Shorts. Unless the user specifies another length, create 11 to 13 fast-paced scenes (~3.5-4s each, ~45s total)."
            } else {
                "Horizontal 16:9 YouTube video. Unless the user specifies another length, create 13 to 16 tightly connected scenes (~65-80s total)."
            }

            buildString {
                appendLine("MODE: TOPIC MODE ($inputWordCount words). FOLLOW EVERY INSTRUCTION STRICTLY:")
                appendLine(cleanInput)
                appendLine()
                appendLine(languageDirective)
                appendLine("Format guideline: $formatGuide")
                appendLine("Write ONE continuous, expressive storytelling monologue with exclamation (!), question (?), and dramatic pauses (...) so voice intonation sounds dynamic and alive — NEVER a flat newsreader.")
                appendLine("Avoid unescaped double quotes inside JSON string values; use single quotes if quoting dialogue.")
            }
        }

        val messages = listOf(
            GroqMessage(role = "system", content = systemPrompt),
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
                                temperature = if (isFullScriptMode) 0.25 else 0.78,
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
                                temperature = if (isFullScriptMode) 0.25 else 0.78,
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

                return parseScriptResponse(generatedText, language, voiceGender)
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
        val suffix = if (isShorts) IMAGE_SUFFIX_SHORTS else IMAGE_SUFFIX_LANDSCAPE
        val finalPrompt = prompt.trim().trimEnd(',', '.') + suffix

        return callWithHttpUrl {
            cloudflareApi.generateImage(
                authorization = "Bearer ${BuildConfig.CLOUDFLARE_API_TOKEN}",
                accountId = BuildConfig.CLOUDFLARE_ACCOUNT_ID,
                model = "@cf/stabilityai/stable-diffusion-xl-base-1.0",
                request = CloudflareImageRequest(
                    prompt = finalPrompt,
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
                voice = VideoLanguage.HINDI.maleVoice
            )
        )
    }

    override suspend fun generateContinuousAudioWithVtt(text: String): EdgeTtsSynthesisResult {
        require(text.isNotBlank()) { "Voiceover text must not be blank." }
        return edgeTtsApi.synthesizeWithSubtitles(
            text = text,
            voice = VideoLanguage.HINDI.maleVoice
        )
    }

    override suspend fun generateDynamicVoiceClip(
        text: String,
        voiceName: String,
        emotion: String
    ): ByteArray {
        require(text.isNotBlank()) { "Voiceover text must not be blank." }
        val (rate, pitch) = when (emotion.lowercase().trim()) {
            "hook" -> "+18%" to "+2Hz"
            "suspense" -> "+6%" to "-6Hz"
            "climax" -> "+21%" to "-1Hz"
            "question" -> "+11%" to "+2Hz"
            else -> "+14%" to "-2Hz"
        }
        return edgeTtsApi.synthesizeSpeechClip(
            text = text,
            voice = voiceName.ifBlank { VideoLanguage.HINDI.maleVoice },
            rate = rate,
            pitch = pitch
        )
    }

    private fun parseScriptResponse(
        rawText: String,
        requestedLanguage: VideoLanguage,
        requestedGender: VoiceGender
    ): VideoScriptResponse {
        val extractedJson = extractJsonPayload(rawText)
            .replace(Regex(",\\s*([}\\]])"), "$1")
        val root = json.parseToJsonElement(extractedJson)

        var characterBible = ""
        var visualStyle = ""
        var bgmMood = "epic_action"
        var parsedVoiceName = ""

        val slabElements: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                characterBible = root["character_bible"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                visualStyle = root["visual_style"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                bgmMood = root["bgm_mood"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    .ifBlank { "epic_action" }
                parsedVoiceName = root["voice_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

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
            val emotion = dto.voiceEmotion?.trim()?.lowercase()?.ifBlank { "normal" } ?: "normal"
            if (voiceover.isBlank() || prompt.isBlank()) {
                null
            } else {
                VideoSlab(
                    id = dto.id ?: index + 1,
                    voiceover_text = voiceover,
                    image_prompt = prompt,
                    sfx_cue = sfxCue,
                    has_character = dto.hasCharacter ?: false,
                    voice_emotion = emotion
                )
            }
        }

        require(slabs.isNotEmpty()) { "Groq returned an empty script." }

        val finalVoiceName = if (requestedLanguage != VideoLanguage.AUTO) {
            requestedLanguage.resolveVoice(requestedGender)
        } else {
            parsedVoiceName.ifBlank { VideoLanguage.HINDI.resolveVoice(requestedGender) }
        }

        return VideoScriptResponse(
            character_bible = characterBible,
            visual_style = visualStyle,
            bgm_mood = bgmMood,
            voice_name = finalVoiceName,
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
        val hasCharacter: Boolean? = null,
        @SerialName("voice_emotion")
        val voiceEmotion: String? = null
    )

    private companion object {
        const val MAX_API_ATTEMPTS = 3
        const val FULL_SCRIPT_WORD_THRESHOLD = 25

        const val SHORTS_IMAGE_WIDTH = 768
        const val SHORTS_IMAGE_HEIGHT = 1344
        const val LANDSCAPE_IMAGE_WIDTH = 1344
        const val LANDSCAPE_IMAGE_HEIGHT = 768

        const val IMAGE_SUFFIX_SHORTS =
            ", single continuous full-bleed frame, cinematic IMAX movie still, Unreal Engine 5, 8k resolution, photorealistic, vertical 9:16 aspect ratio, no text, no watermark, no letters, no UI, no logos, no words"
        const val IMAGE_SUFFIX_LANDSCAPE =
            ", single continuous full-bleed frame, cinematic IMAX movie still, Unreal Engine 5, 8k resolution, photorealistic, horizontal 16:9 aspect ratio, no text, no watermark, no letters, no UI, no logos, no words"

        const val DEFAULT_NEGATIVE_PROMPT =
            "filmstrip, multi-panel, stacked frames, comic panels, grid, storyboard, collage, split screen, text, watermark, signature, logo, letters, words, ui, interface, comment box, subscribe button, social media, website, poster, title card, blurry, low quality, distorted, deformed, extra limbs, extra arms, extra fingers"

        const val IMAGE_PROMPT_RULES =
            "IMAGE PROMPT RULES (STRICT):\n" +
                "- Describe ONLY physical characters, environments, lighting, and cinematic action.\n" +
                "- NEVER include words like 'comment box', 'subscribe', 'website', 'UI', 'social media', 'poster', 'title card', or 'text' in image_prompt, even if voiceover mentions commenting or box office numbers.\n" +
                "- NEVER write real celebrity/actor names (like Sunny Deol or Prabhas) inside image_prompt or character_bible — describe the iconic visual character directly (e.g. muscular divine Vanara warrior with golden mace gada, saffron dhoti, glowing aura) so the image generator renders the exact mythic/cinematic figure.\n" +
                "- For metaphors like 'पूरी दुनिया देखेगी' or 'वर्ल्ड रिकॉर्ड', NEVER ask for Earth globes or world maps — describe roaring packed cinema crowds, grand glowing Indian temples, or triumphant hero action.\n" +
                "- For the final call-to-action scene, always generate an epic triumphant hero shot of the main character (or a grand cinematic climax shot if no hero).\n" +
                "- Set has_character=false when showing crowds, audiences, forts, battlefields, cinema halls, or environments; set has_character=true only when the main character from character_bible is directly in frame.\n" +
                "- Vary camera angles constantly across consecutive scenes (wide aerial, close-up eyes, over-the-shoulder, silhouette, battlefield, palace interior).\n\n"

        const val VOICE_AND_BGM_RULES =
            "VOICE EMOTION & BGM RULES:\n" +
                "- Pick bgm_mood: 'comedy_roast', 'epic_action', 'suspense_thriller', 'emotional_sad', 'devotional', 'sci_fi_tech', 'motivational', or 'horror_dark'.\n" +
                "- For each scene, set voice_emotion to shift delivery dynamically: 'hook' (opening punch), 'suspense' (mystery/tension), 'climax' (intense/goosebumps), 'question' (engaging question/CTA), or 'normal'.\n" +
                "- Pick valid Edge TTS voice_name (e.g. hi-IN-MadhurNeural, hi-IN-SwaraNeural, en-IN-PrabhatNeural, en-IN-NeerjaNeural, en-US-AndrewNeural, en-US-AvaNeural, mr-IN-ManoharNeural, mr-IN-AarohiNeural, ta-IN-ValluvarNeural, ta-IN-PallaviNeural, etc.).\n\n"

        const val JSON_SCHEMA_SPEC =
            "Return ONLY valid JSON matching this schema:\n" +
                "{\n" +
                "  \"character_bible\": \"Unchanging visual look of main character in English without real actor names\",\n" +
                "  \"visual_style\": \"Unified cinematic visual style, color palette, lighting\",\n" +
                "  \"bgm_mood\": \"comedy_roast | epic_action | suspense_thriller | emotional_sad | devotional | sci_fi_tech | motivational | horror_dark\",\n" +
                "  \"voice_name\": \"Edge TTS voice shortName matching language and gender\",\n" +
                "  \"slabs\": [\n" +
                "    {\n" +
                "      \"voiceover_text\": \"Narration segment in target language\",\n" +
                "      \"voice_emotion\": \"hook | suspense | climax | question | normal\",\n" +
                "      \"has_character\": false,\n" +
                "      \"image_prompt\": \"English physical visual description only (NO text/UI/posters/comment box/actor names/world map)\",\n" +
                "      \"sfx_cue\": \"boom | thunder | sword | fire | whoosh\"\n" +
                "    }\n" +
                "  ]\n" +
                "}"

        const val FULL_SCRIPT_SYSTEM_PROMPT =
            "You are a film director splitting an existing user-written script into fast visual scenes for RS Movie Spot.\n" +
                "CRITICAL FULL SCRIPT RULE:\n" +
                "- DO NOT rewrite, paraphrase, translate, summarize, add, drop, or change EVEN A SINGLE WORD of the user's input.\n" +
                "- Keep exact user words intact and split sequentially into 12 to 15 short punchy scenes (splitting long sentences cleanly at commas/clauses so each visual stays ~3.5-4.5s).\n" +
                "- Concatenating all slabs' voiceover_text values with spaces MUST match the user's input word-for-word.\n\n" +
                IMAGE_PROMPT_RULES +
                VOICE_AND_BGM_RULES +
                JSON_SCHEMA_SPEC

        const val TOPIC_SYSTEM_PROMPT =
            "You are an elite YouTube storyteller, movie critic, and film director for RS Movie Spot.\n" +
                "Think like ONE continuous spoken storyteller where every scene's voiceover_text is the NEXT flowing phrase of the same unbroken story!\n\n" +
                "RULE 1 — CONTINUOUS HIGH-RETENTION SCRIPT (11 TO 15 SCENES):\n" +
                "- Create 11 to 13 crisp scenes (~8-13 words / ~3.5-4s each for Shorts; 13-16 scenes for 16:9 YouTube) with seamless spoken transitions.\n" +
                "- Use natural spoken punctuation ('!', '?', '...') and short energetic clauses so TTS pitch rises and falls dramatically instead of sounding flat.\n" +
                "- NEVER write isolated Wikipedia facts! Every scene must flow naturally from the previous scene.\n" +
                "- When writing in Hindi: use accurate Devanagari spelling (करोड़, बॉक्स ऑफिस, थिएटर) and MANDATORY respectful plural pronouns (वे, उनकी, उनका, उन्हें, महाबली, प्रभु) for deities and heroes — NEVER singular वह/उसकी/उसका.\n" +
                "- Never put unescaped double quotes inside JSON strings (use single quotes if needed).\n\n" +
                IMAGE_PROMPT_RULES +
                VOICE_AND_BGM_RULES +
                JSON_SCHEMA_SPEC
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
