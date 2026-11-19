package com.rsmoviespot.data.repository

import android.graphics.BitmapFactory
import com.rsmoviespot.BuildConfig
import com.rsmoviespot.data.api.CloudflareApi
import com.rsmoviespot.data.api.CloudflareImageRequest
import com.rsmoviespot.data.api.EdgeTtsApi
import com.rsmoviespot.data.api.EdgeTtsClipResult
import com.rsmoviespot.data.api.EdgeTtsRequest
import com.rsmoviespot.data.api.EdgeTtsSynthesisResult
import com.rsmoviespot.data.api.GroqApi
import com.rsmoviespot.data.api.GroqChatRequest
import com.rsmoviespot.data.api.GroqMessage
import com.rsmoviespot.data.api.GroqPlainChatRequest
import com.rsmoviespot.data.api.GroqResponseFormat
import com.rsmoviespot.domain.models.InputMode
import com.rsmoviespot.domain.models.VideoLanguage
import com.rsmoviespot.domain.models.VideoScriptResponse
import com.rsmoviespot.domain.models.VideoSlab
import com.rsmoviespot.domain.models.VoiceGender
import com.rsmoviespot.domain.models.VoiceTone
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

interface VideoRepository {
    suspend fun generateScript(
        topic: String,
        isShorts: Boolean = true,
        language: VideoLanguage = VideoLanguage.AUTO,
        voiceGender: VoiceGender = VoiceGender.MALE,
        inputMode: InputMode = InputMode.AUTO,
        voiceTone: VoiceTone = VoiceTone.AUTO
    ): VideoScriptResponse

    suspend fun generateImageForSlab(
        prompt: String,
        isShorts: Boolean = true,
        seed: Int = 42
    ): ResponseBody

    suspend fun downloadRealPhoto(
        query: String,
        usedUrls: MutableSet<String>,
        destinationFile: File,
        preferPortrait: Boolean = true
    ): Boolean

    suspend fun downloadInternetVideoClip(
        query: String,
        isMeme: Boolean,
        usedUrls: MutableSet<String>,
        destinationFile: File
    ): Boolean

    suspend fun fetchTrendingMovieTopics(): List<String>

    suspend fun generateAudioForSlab(text: String): ResponseBody

    suspend fun generateContinuousAudioWithVtt(text: String): EdgeTtsSynthesisResult

    suspend fun generateDynamicVoiceClip(
        text: String,
        voiceName: String,
        emotion: String,
        voiceTone: VoiceTone = VoiceTone.AUTO
    ): ByteArray

    suspend fun generateDynamicVoiceClipWithMeta(
        text: String,
        voiceName: String,
        emotion: String,
        voiceTone: VoiceTone = VoiceTone.AUTO
    ): EdgeTtsClipResult
}

@Singleton
class VideoRepositoryImpl @Inject constructor(
    private val groqApi: GroqApi,
    private val cloudflareApi: CloudflareApi,
    private val edgeTtsApi: EdgeTtsApi,
    private val okHttpClient: OkHttpClient
) : VideoRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var cachedChatModels: List<String>? = null

    private data class ScriptAttempt(
        val modelId: String,
        val useJsonMode: Boolean,
        val maxTokens: Int
    )

    private enum class RealVisualIntent { PERSON, MOVIE_POSTER, CHARACTER }

    private data class PhotoCandidate(
        val url: String,
        val width: Int,
        val height: Int,
        val priority: Int = 0,
        val fallbackUrl: String? = null
    ) {
        val isPortraitOrSquare: Boolean
            get() = width > 0 && height > 0 && height.toFloat() >= width.toFloat() * 0.92f
    }

    private data class VideoClipCandidate(
        val url: String,
        val score: Int
    )

    override suspend fun generateScript(
        topic: String,
        isShorts: Boolean,
        language: VideoLanguage,
        voiceGender: VoiceGender,
        inputMode: InputMode,
        voiceTone: VoiceTone
    ): VideoScriptResponse {
        val cleanInput = topic.trim()
        require(cleanInput.isNotBlank()) { "Topic must not be blank." }
        require(BuildConfig.GROQ_API_KEY.isNotBlank()) { "GROQ_API_KEY is empty." }

        val inputWordCount = cleanInput.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val isFullScriptMode = when (inputMode) {
            InputMode.EXACT_SCRIPT -> true
            InputMode.AI_PROMPT -> false
            InputMode.AUTO ->
                inputWordCount > FULL_SCRIPT_WORD_THRESHOLD &&
                    !isInstructionOrBriefPrompt(cleanInput, language)
        }

        val systemPrompt = if (isFullScriptMode) FULL_SCRIPT_SYSTEM_PROMPT else TOPIC_SYSTEM_PROMPT
        val targetGenderLabel = voiceGender.label
        val toneDirective = buildVoiceToneDirective(voiceTone)
        val languageDirective = if (language == VideoLanguage.AUTO) {
            "Language: Auto-detect from user request. Default to spoken Hindi (Devanagari) with voice_name '${VideoLanguage.HINDI.resolveVoice(voiceGender)}', UNLESS the user explicitly requested another language (e.g. English, Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, Punjabi, Urdu, Spanish, French, Japanese, Arabic), in which case write in THAT language and pick the matching $targetGenderLabel Edge TTS voice_name."
        } else {
            "Language MANDATORY: Write ALL voiceover_text strictly in ${language.languageInstruction} and set voice_name to '${language.resolveVoice(voiceGender)}'."
        }

        val userPrompt = if (isFullScriptMode) {
            buildString {
                appendLine("MODE: FULL USER SCRIPT ($inputWordCount words).")
                appendLine("DO NOT rewrite, paraphrase, translate, add, or change a single word. Keep the EXACT user text intact and split sequentially into 12 to 18 natural spoken scenes (~6-11 words / ~2.3-3.5s each).")
                appendLine("CRITICAL SPLIT RULE: NEVER split inside a person's full name (e.g., NEVER 'Pedro' | 'Pascal' or 'Robert' | 'Downey Jr' or 'Russo' | 'Brothers'), NEVER split numbers/currency ('₹2000' | 'Crore'), and NEVER cross a sentence period (e.g., NEVER 'hai. Pedro' in one slab and 'Pascal' in the next)! Always break at full stops (. ! ? ।) or clean comma/conjunction clauses.")
                appendLine("Select MULTIPLE distinct 'video_clip' (3-6 action/trailer/movie scenes) and 'meme_clip' (1-2 recognizable meme reactions) with concrete movie/character/meme English queries — NEVER query abstract phrases like '4 out of 5 stars', 'rating', 'review', 'ending', 'multiverse'.")
                appendLine("Whenever a real director or actor is named (e.g., 'Russo Brothers', 'Pedro Pascal', 'Robert Downey Jr'), prefer visual_source='real_photo' with ONLY their clean English name — NEVER draw deformed wax AI faces for real people.")
                appendLine("For ai_generated scenes, match the exact subject/action of THAT scene in crisp visual words (no movie titles, channel names, neon text, or holding-microphone hand closeups that cause deformed fingers/gibberish text).")
                appendLine("Set voice_name matching the script's language and $targetGenderLabel gender (e.g., Hindi: ${VideoLanguage.HINDI.resolveVoice(voiceGender)}, English IN: ${VideoLanguage.ENGLISH_IN.resolveVoice(voiceGender)}, English US: ${VideoLanguage.ENGLISH_US.resolveVoice(voiceGender)}, Marathi: ${VideoLanguage.MARATHI.resolveVoice(voiceGender)}).")
                appendLine("Voice delivery style: $toneDirective")
                appendLine("Return ONLY ONE complete valid JSON object with properly closed 'slabs' array ']' and root object '}'. Avoid unescaped double quotes inside JSON strings.")
                appendLine()
                appendLine("USER SCRIPT:")
                appendLine(cleanInput)
            }
        } else {
            val formatGuide = if (isShorts) {
                "Vertical 9:16 YouTube Shorts. Unless the user specifies another length, create 13 to 16 fast-paced scenes (~7-10 words / ~2.4-3.2s each, ~45-50s total) so NO image sits static for long!"
            } else {
                "Horizontal 16:9 YouTube video. Unless the user specifies another length, create 14 to 18 tightly connected scenes (~65-80s total)."
            }

            buildString {
                appendLine("MODE: TOPIC / DIRECTOR PROMPT MODE ($inputWordCount words). ACT ON EVERY INSTRUCTION BELOW AS THE FILM DIRECTOR:")
                appendLine(cleanInput)
                appendLine()
                appendLine("CRITICAL DIRECTOR PROMPT RULES:")
                appendLine("1) NEVER speak or copy meta-instructions ('Act as...', 'Create a fast 9:16...', 'Write a script...', 'Include...', 'Data to include', 'Scene 1:', 'Visual:', 'Image prompts') into voiceover_text! Put ONLY natural spoken storytelling narration in voiceover_text.")
                appendLine("2) Whenever the user describes visuals, scenes, camera shots, character appearance/costume, lighting, props, or style (in English, Hindi, or Hinglish), put those EXACT visual details right at the BEGINNING of image_prompt (in crisp English) with visual_source='ai_generated' — NEVER replace described visuals with generic crowds or unrelated real_photo.")
                appendLine("3) Match each scene's image_prompt to THAT SPECIFIC scene's action/character! If a scene focuses on a secondary character or scenery, put THAT subject FIRST in image_prompt and set has_character=false. NEVER include gibberish-prone text, logos, cinema marquees, or hands gripping microphones.")
                appendLine("4) Keep every scene's voiceover_text short and punchy (7-10 words) and NEVER split names across scenes. Dynamically choose 'video_clip' (3 to 6 scenes with recognizable concrete movie/character English queries) + 'meme_clip' (1 to 2 named meme templates) + 'real_photo' (for real actors/directors/movie posters) + 'ai_generated'.")
                appendLine(languageDirective)
                appendLine("Voice delivery & writing tone: $toneDirective")
                appendLine("Format guideline: $formatGuide")
                appendLine("Write ONE continuous, expressive storytelling monologue with exclamation (!), question (?), and dramatic pauses (...) so voice intonation sounds dynamic and alive — NEVER a flat newsreader.")
                appendLine("Return ONLY ONE complete valid JSON object with properly closed 'slabs' array ']' and root object '}'. Avoid unescaped double quotes inside JSON string values; use single quotes if quoting dialogue.")
            }
        }

        val messages = listOf(
            GroqMessage(role = "system", content = systemPrompt),
            GroqMessage(role = "user", content = userPrompt)
        )

        val candidateModels = resolveCandidateModels()
        val attempts = buildList {
            candidateModels.forEachIndexed { idx, modelId ->
                val tokens = if (modelId.contains("gpt-oss", ignoreCase = true) ||
                    modelId.contains("qwen", ignoreCase = true)
                ) {
                    16384
                } else {
                    8192
                }
                add(ScriptAttempt(modelId, useJsonMode = true, maxTokens = tokens))
                if (idx == 0) {
                    add(ScriptAttempt(modelId, useJsonMode = false, maxTokens = tokens))
                }
            }
        }

        val attemptErrors = mutableListOf<String>()
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
                                temperature = if (isFullScriptMode) 0.2 else 0.75,
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
                                temperature = if (isFullScriptMode) 0.2 else 0.75,
                                maxTokens = attempt.maxTokens
                            )
                        )
                    }
                }

                val generatedText = response.choices.firstOrNull()?.message?.content
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Groq (${attempt.modelId}) returned empty result.")

                return parseScriptResponse(generatedText, language, voiceGender)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                attemptErrors += "${attempt.modelId}(json=${attempt.useJsonMode}): ${error.message?.take(140) ?: error::class.java.simpleName}"
                if (index == attempts.lastIndex) {
                    throw IllegalStateException(attemptErrors.joinToString(" | "), error)
                }
            }
        }

        throw lastError ?: IllegalStateException("Failed to generate script.")
    }

    private fun buildVoiceToneDirective(voiceTone: VoiceTone): String =
        when (voiceTone.name.uppercase()) {
            "NATURAL", "PODCAST", "HUMAN" ->
                "Podcast / Human studio style: warm, conversational, relatable spoken cadence like a real YouTube host talking close to a condenser mic."
            "FUNNY", "COMIC" ->
                "Funny / Comic style: witty, playful sarcasm, punchy setup-and-payoff comedic rhythm, expressive reactions."
            "SERIOUS", "INTENSE" ->
                "Serious / Intense style: dramatic trailer-voice gravitas, tense stakes, weighty dramatic pauses."
            "ENERGETIC", "HYPE" ->
                "Energetic / Hype style: fast high-voltage hook, explosive excitement, crisp exclamation beats."
            else ->
                "Auto dynamic storytelling style: natural conversational flow matched to each scene's emotion."
        }

    private suspend fun resolveCandidateModels(): List<String> = withContext(Dispatchers.IO) {
        cachedChatModels?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

        val discovered = runCatching {
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .header("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
                .header("Accept", "application/json")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching emptyList<String>()
                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching emptyList()
                val data = root["data"] as? JsonArray ?: return@runCatching emptyList()
                data.mapNotNull { item ->
                    (item as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.trim()
                }.filter { id ->
                    val lower = id.lowercase()
                    id.isNotBlank() &&
                        !lower.contains("whisper") &&
                        !lower.contains("tts") &&
                        !lower.contains("orpheus") &&
                        !lower.contains("guard") &&
                        !lower.contains("embed") &&
                        !lower.contains("compound")
                }
            }
        }.getOrDefault(emptyList())

        val preferredOrder = listOf(
            "openai/gpt-oss-120b",
            "llama-3.3-70b-versatile",
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "qwen/qwen3-32b",
            "openai/gpt-oss-20b",
            "llama-3.1-8b-instant"
        )

        val finalModels = if (discovered.isNotEmpty()) {
            val sorted = mutableListOf<String>()
            for (pref in preferredOrder) {
                discovered.firstOrNull { it.equals(pref, ignoreCase = true) }?.let { sorted += it }
            }
            for (id in discovered) {
                val lower = id.lowercase()
                if (id !in sorted && (lower.contains("qwen") || lower.contains("llama") || lower.contains("gpt-oss"))) {
                    sorted += id
                }
            }
            sorted.distinct().take(4).ifEmpty { discovered.take(3) }
        } else {
            listOf(
                "openai/gpt-oss-120b",
                "llama-3.3-70b-versatile",
                "openai/gpt-oss-20b",
                "llama-3.1-8b-instant"
            )
        }

        if (discovered.isNotEmpty()) {
            cachedChatModels = finalModels
        }
        finalModels
    }

    private fun isInstructionOrBriefPrompt(input: String, language: VideoLanguage): Boolean {
        val lower = input.lowercase()

        val metaPromptRegex = Regex(
            """(?:\b(act\s+as|act\s+like|behave\s+like|roleplay\s+as|you\s+are\s+(?:a|an|the)?\b|your\s+task\s+is|your\s+job\s+is|prompt\s+engineer|image\s+generation\s+prompts?|critical\s+instructions|data\s+to\s+include|include\s+in\s+(?:the\s+)?script|include\s*:|aspect\s+ratio|9:16|16:9|do\s+not\s+generate|describe\s+(?:him|her|it|the\s+scene)\s+as|video_clip|meme_clip|real_photo|stat_badge|bgm_mood|youtube\s+short\s+style|movie\s+spot\s+director|director\.)\b|9\s*:\s*16|16\s*:\s*9)""",
            RegexOption.IGNORE_CASE
        )
        if (metaPromptRegex.containsMatchIn(input)) return true

        val sceneDirectionRegex = Regex(
            """(?:^|\n)\s*(?:scene\s*\d+\s*[:\-]|visuals?\s*:|image\s*prompt\s*:|voiceover\s*:|narration\s*:|include\s*:|hook\s*:|climax\s*:|style\s*:|\[visual[^\]]*\])""",
            RegexOption.IGNORE_CASE
        )
        if (sceneDirectionRegex.containsMatchIn(input)) return true

        val writeScriptRegex = Regex(
            """\b(write|create|generate|make|prepare|build|craft)\s+(?:me\s+)?(?:a|an|the)?\s*(?:[\w:-]+\s+){0,5}(?:voiceover\s+)?(?:script|short|shorts|video|story|reel|scene|breakdown|review|roast|explainer)\b""",
            RegexOption.IGNORE_CASE
        )
        if (writeScriptRegex.containsMatchIn(input)) return true

        val hindiPromptRegex = Regex(
            """(?:\b(script\s+likh\w*|video\s+bana\w*|short\s+bana\w*|reel\s+bana\w*|scene\s+bana\w*|visual\w*\s+bana\w*|par\s+(?:ek\s+)?video|ke\s+baare\s+mein\s+video|hindi\s+mein\s+(?:script|video)|(?:pehle|doosre|dusre|teesre|tisre)\s+scene\s+(?:me|mein)|scene\s+(?:me|mein)\s+.*dikha\w*)\b|स्क्रिप्ट\s+लिख|वीडियो\s+बना|शॉर्ट\s+बना)""",
            RegexOption.IGNORE_CASE
        )
        if (hindiPromptRegex.containsMatchIn(input)) return true

        val lines = input.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bulletLines = lines.count {
            it.startsWith("- ") || it.startsWith("* ") || it.startsWith("• ") ||
                it.matches(Regex("""^\d+[.)]\s+.+"""))
        }
        val hasBriefHeaders = listOf(
            "release date:", "advance booking:", "prediction:", "highlight:",
            "topic:", "instructions:", "requirements:", "note:", "include:", "hook:"
        ).any { lower.contains(it) }
        if (bulletLines >= 2 && hasBriefHeaders) return true

        if (language == VideoLanguage.HINDI ||
            language == VideoLanguage.MARATHI ||
            language == VideoLanguage.GUJARATI ||
            language == VideoLanguage.BENGALI ||
            language == VideoLanguage.TAMIL ||
            language == VideoLanguage.TELUGU ||
            language == VideoLanguage.KANNADA ||
            language == VideoLanguage.MALAYALAM ||
            language == VideoLanguage.PUNJABI
        ) {
            val nonAsciiLetters = input.count { it.code > 127 && it.isLetter() }
            val latinLetters = input.count { it in 'a'..'z' || it in 'A'..'Z' }
            if (nonAsciiLetters == 0 && latinLetters > 40 && input.split(Regex("\\s+")).size <= 55) {
                return true
            }
        }

        return false
    }

    override suspend fun fetchTrendingMovieTopics(): List<String> = withContext(Dispatchers.IO) {
        val rssUrl =
            "https://news.google.com/rss/search?q=Bollywood+OR+Hollywood+OR+Tollywood+box+office+movie+when:3d&hl=en-IN&gl=IN&ceid=IN:en"
        val xml = fetchTextString(rssUrl, WIKIMEDIA_USER_AGENT) ?: return@withContext emptyList()

        val itemTitleRegex = Regex(
            """<item>.*?<title>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</title>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        itemTitleRegex.findAll(xml)
            .map { match ->
                match.groupValues[1]
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&apos;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .substringBeforeLast(" - ")
                    .trim()
            }
            .filter { it.length >= 12 }
            .distinct()
            .take(8)
            .toList()
    }

    override suspend fun generateImageForSlab(
        prompt: String,
        isShorts: Boolean,
        seed: Int
    ): ResponseBody {
        require(prompt.isNotBlank()) { "Image prompt must not be blank." }
        require(BuildConfig.CLOUDFLARE_ACCOUNT_ID.isNotBlank()) { "CLOUDFLARE_ACCOUNT_ID is empty." }
        require(BuildConfig.CLOUDFLARE_API_TOKEN.isNotBlank()) { "CLOUDFLARE_API_TOKEN is empty." }

        val imageWidth = if (isShorts) SHORTS_IMAGE_WIDTH else LANDSCAPE_IMAGE_WIDTH
        val imageHeight = if (isShorts) SHORTS_IMAGE_HEIGHT else LANDSCAPE_IMAGE_HEIGHT
        val cleanPrompt = prompt.trim().trimEnd(',', '.')
        val promptLower = cleanPrompt.lowercase()
        val finalPrompt = cleanPrompt + buildImageSuffix(promptLower, isShorts)
        val negativePrompt = buildNegativePrompt(promptLower)

        return try {
            callWithHttpUrl(maxAttempts = 3) {
                cloudflareApi.generateImage(
                    authorization = "Bearer ${BuildConfig.CLOUDFLARE_API_TOKEN}",
                    accountId = BuildConfig.CLOUDFLARE_ACCOUNT_ID,
                    model = "@cf/stabilityai/stable-diffusion-xl-base-1.0",
                    request = CloudflareImageRequest(
                        prompt = finalPrompt,
                        width = imageWidth,
                        height = imageHeight,
                        negativePrompt = negativePrompt,
                        seed = seed,
                        numSteps = 18,
                        guidance = 7.5
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            callWithHttpUrl(maxAttempts = 2) {
                cloudflareApi.generateImage(
                    authorization = "Bearer ${BuildConfig.CLOUDFLARE_API_TOKEN}",
                    accountId = BuildConfig.CLOUDFLARE_ACCOUNT_ID,
                    model = "@cf/bytedance/stable-diffusion-xl-lightning",
                    request = CloudflareImageRequest(
                        prompt = finalPrompt,
                        width = imageWidth,
                        height = imageHeight,
                        negativePrompt = negativePrompt,
                        seed = seed,
                        numSteps = 6,
                        guidance = 2.0
                    )
                )
            }
        }
    }

    private fun buildImageSuffix(promptLower: String, isShorts: Boolean): String {
        val aspect = if (isShorts) "vertical 9:16 full-bleed single camera frame" else "horizontal 16:9 full-bleed single camera frame"
        val is3dAnimated = listOf("3d", "pixar", "disney", "cgi", "dreamworks").any { promptLower.contains(it) }
        val isStylized = is3dAnimated || listOf(
            "anime", "cartoon", "comic", "illustration", "oil painting", "watercolor", "sketch"
        ).any { promptLower.contains(it) }

        return when {
            is3dAnimated ->
                ", ONE single unified 3D animated Pixar feature film frame, $aspect, cinematic depth of field, rich outdoor 3D environment, soft volumetric sunlight, single cohesive story shot, no repeating pattern, no wallpaper, no sticker sheet, no collage, no grid, no text, no watermark"
            isStylized ->
                ", ONE single unified $aspect, ultra-detailed art direction, sharp focus, dramatic atmospheric lighting, no repeating pattern, no wallpaper, no collage, no grid, no split frames, no text, no watermark, no UI, no logos"
            else ->
                ", raw 35mm DSLR live-action movie set photograph, ONE single unified $aspect, candid film still, realistic human skin pores and natural fabric textures, shallow depth of field, cinema lighting, clean unlabelled background, no collage, no grid, no multiple panels, no text, no words, no signs, no watermark, no UI, no logos"
        }
    }

    private fun buildNegativePrompt(promptLower: String): String {
        val base = mutableListOf(
            "filmstrip", "multi-panel", "multiple panels", "multiple frames", "2x2", "3x3", "4x4",
            "photo grid", "picture grid", "triptych", "diptych", "quadrant", "contact sheet",
            "stacked frames", "comic panels", "grid", "storyboard", "collage", "photo collage",
            "split screen", "split frame", "montage", "mosaic", "tiled", "frames", "borders",
            "wallpaper", "repeating pattern", "seamless pattern", "sticker sheet", "clipart",
            "doodle pattern", "multiple copies", "clones", "duplicate character", "crowd of clones",
            "abstract", "geometric shapes", "cubes", "blocks", "colorful squares",
            "disembodied eye", "close-up single eye",
            "text", "watermark", "signature", "logo", "letters", "words", "caption", "subtitle",
            "gibberish text", "gibberish letters", "warped text", "random letters", "misspelled words",
            "neon sign text", "cinema marquee text", "billboard text", "pedestal text", "plaque",
            "signboard", "typography", "banner text",
            "ui", "interface", "comment box", "subscribe button", "social media", "website",
            "blurry", "low quality", "distorted", "deformed", "mutated anatomy",
            "deformed hands", "extra fingers", "twisted fingers", "fused fingers", "ugly hands",
            "creepy hand", "holding microphone badly",
            "extra limbs", "extra arms", "extra legs", "extra tail",
            "extra ears", "extra eyes", "extra trunk", "double trunk", "two trunks"
        )
        if (!promptLower.contains("poster") && !promptLower.contains("title card")) {
            base += listOf("poster", "title card")
        }
        val stylizedTokens = listOf(
            "anime", "cartoon", "3d", "pixar", "disney", "dreamworks", "cgi", "comic",
            "illustration", "painting", "drawing", "sketch"
        )
        if (stylizedTokens.none { promptLower.contains(it) }) {
            base += listOf(
                "cartoon", "anime", "3d render", "cgi", "illustration",
                "digital art", "digital painting", "concept art", "comic art", "comic book",
                "vector art", "cel shaded", "smooth shading", "airbrushed", "unreal engine",
                "painting", "drawing", "sketch", "plastic skin", "wax figure", "wax face",
                "plastic eyes", "uncanny valley", "cosplay", "toy", "action figure"
            )
        }
        return base.joinToString(", ")
    }

    override suspend fun downloadInternetVideoClip(
        query: String,
        isMeme: Boolean,
        usedUrls: MutableSet<String>,
        destinationFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext false

        val coreWords = extractCoreQueryWords(cleanQuery)
        if (coreWords.isEmpty() || isWeakClipQuery(cleanQuery, coreWords, isMeme)) {
            return@withContext false
        }

        val candidates = mutableListOf<VideoClipCandidate>()
        if (isMeme) {
            runCatching { collectTenorMemeClips(cleanQuery, coreWords, candidates) }
            runCatching { collectCoubVideoClips(cleanQuery, coreWords, candidates) }
            if (candidates.none { it.url !in usedUrls } && coreWords.size >= 3) {
                val tightQuery = coreWords.take(2).joinToString(" ")
                runCatching { collectTenorMemeClips(tightQuery, coreWords.take(2), candidates) }
                runCatching { collectCoubVideoClips(tightQuery, coreWords.take(2), candidates) }
            }
        } else {
            runCatching { collectCoubVideoClips(cleanQuery, coreWords, candidates) }
            if (candidates.none { it.url !in usedUrls } && coreWords.size >= 3) {
                val tightQuery = coreWords.take(2).joinToString(" ")
                runCatching { collectCoubVideoClips(tightQuery, coreWords.take(2), candidates) }
            }
            if (candidates.none { it.url !in usedUrls } && coreWords.size >= 2) {
                runCatching { collectTenorMemeClips(cleanQuery, coreWords, candidates) }
            }
        }

        val ordered = candidates.distinctBy { it.url }.sortedByDescending { it.score }
        for (candidate in ordered) {
            if (candidate.url in usedUrls) continue
            val ok = runCatching { downloadAndValidateVideoUrl(candidate.url, destinationFile) }
                .getOrDefault(false)
            if (ok) {
                usedUrls.add(candidate.url)
                return@withContext true
            }
        }
        false
    }

    private fun isWeakClipQuery(
        rawQuery: String,
        coreWords: List<String>,
        isMeme: Boolean
    ): Boolean {
        val lower = rawQuery.lowercase()
        val forbiddenFragments = listOf(
            "out of 5", "stars", "rating", "review", "subscribe", "comment",
            "ending", "part 1", "part 2", "box office", "multiverse", "cigar"
        )
        if (forbiddenFragments.any { lower.contains(it) }) return true

        val genericWords = setOf(
            "out", "five", "four", "three", "two", "one", "stars", "star",
            "rating", "review", "best", "worst", "good", "bad", "top", "epic",
            "awesome", "amazing", "cinema", "theater", "theatre", "audience"
        )
        val substantiveWords = coreWords.filter { it !in genericWords && !it.all(Char::isDigit) }
        return if (isMeme) {
            substantiveWords.isEmpty()
        } else {
            substantiveWords.isEmpty() || (coreWords.size == 1 && coreWords[0].length < 4)
        }
    }

    private fun extractCoreQueryWords(query: String): List<String> {
        val stopWords = setOf(
            "the", "a", "an", "of", "in", "on", "at", "to", "and", "or", "for", "with", "from",
            "out", "movie", "film", "cinema", "scene", "trailer", "teaser", "clip", "video",
            "meme", "funny", "reaction", "official", "hd", "4k", "whatsapp", "status",
            "shorts", "bollywood", "hollywood", "indian"
        )
        return query.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in stopWords }
            .distinct()
    }

    private fun collectCoubVideoClips(
        cleanQuery: String,
        coreWords: List<String>,
        outList: MutableList<VideoClipCandidate>
    ) {
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val url = "https://coub.com/api/v2/search/coubs?per_page=24&q=$encoded"
        val bodyText = fetchTextString(url, BROWSER_USER_AGENT, "application/json") ?: return
        val root = json.parseToJsonElement(bodyText) as? JsonObject ?: return
        val coubs = root["coubs"] as? JsonArray ?: return

        val requiredHits = when {
            coreWords.size <= 2 -> coreWords.size
            else -> ((coreWords.size * 2 + 2) / 3).coerceAtLeast(2)
        }
        val phraseLower = coreWords.joinToString(" ")
        val bannedTags = setOf(
            "gaming", "gameplay", "fortnite", "minecraft", "roblox",
            "valorant", "pubg", "freefire", "mannequin", "asmr", "amv",
            "tiktok", "reels", "selfie", "bathroom", "mirror", "shirtless",
            "gym", "dance", "twerk", "couple", "kiss", "fanedit",
            "velocity", "phonk", "anime edit", "sigma edit"
        )

        for (item in coubs) {
            val obj = item as? JsonObject ?: continue
            val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val tagsText = (obj["tags"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull }
                ?.joinToString(" ")
                .orEmpty()

            val searchable = "$title $tagsText".lowercase()
            if (bannedTags.any { tag -> Regex("""\b${Regex.escape(tag)}\b""").containsMatchIn(searchable) }) {
                continue
            }

            val wordHits = coreWords.count { word ->
                Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(searchable)
            }
            if (wordHits < requiredHits) continue

            val titleHits = coreWords.count { word ->
                Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(title.lowercase())
            }
            val likes = obj["likes_count"]?.jsonPrimitive?.intOrNull ?: 0
            if (titleHits == 0 && likes < 15) continue

            val videoObj = ((obj["file_versions"] as? JsonObject)
                ?.get("html5") as? JsonObject)
                ?.get("video") as? JsonObject ?: continue

            val mp4Url = (videoObj["high"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                ?: (videoObj["med"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                ?: continue
            if (!mp4Url.startsWith("http")) continue

            val phraseBonus = if (phraseLower.isNotBlank() && title.lowercase().contains(phraseLower)) 90
            else if (phraseLower.isNotBlank() && searchable.contains(phraseLower)) 50 else 0
            val score = (wordHits * 100) + (titleHits * 55) + phraseBonus + (likes / 10).coerceAtMost(50) + 40
            outList += VideoClipCandidate(mp4Url, score)
        }
    }

    private fun collectTenorMemeClips(
        cleanQuery: String,
        coreWords: List<String>,
        outList: MutableList<VideoClipCandidate>
    ) {
        val slug = cleanQuery.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.isBlank()) return

        val html = fetchTextString("https://tenor.com/search/$slug-gifs", BROWSER_USER_AGENT) ?: return
        val mp4Regex = Regex("""https://media\.tenor\.com/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+\.mp4""")
        val matches = mp4Regex.findAll(html).map { it.value }.distinct().toList()
        val requiredSlugHits = if (coreWords.size >= 2) 1 else 1

        for (mp4Url in matches) {
            val fileSlug = mp4Url.substringAfterLast('/').lowercase()
            val slugHits = coreWords.count { word -> fileSlug.contains(word) }
            if (slugHits < requiredSlugHits && coreWords.isNotEmpty()) continue

            val qualityBonus = if (mp4Url.contains("AAAPo")) 55 else 15
            outList += VideoClipCandidate(mp4Url, (slugHits * 90) + qualityBonus)
        }
    }

    private fun downloadAndValidateVideoUrl(url: String, destinationFile: File): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("text/html")) return false

            val body = response.body ?: return false
            destinationFile.outputStream().use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        }

        val size = destinationFile.length()
        if (!destinationFile.exists() || size < 20_000L || size > 25_000_000L) {
            destinationFile.delete()
            return false
        }

        val header = ByteArray(64)
        val readCount = destinationFile.inputStream().use { it.read(header) }
        if (readCount < 12) {
            destinationFile.delete()
            return false
        }
        val headerStr = String(header, 0, readCount, Charsets.ISO_8859_1)
        if (!headerStr.contains("ftyp")) {
            destinationFile.delete()
            return false
        }
        return true
    }

    override suspend fun downloadRealPhoto(
        query: String,
        usedUrls: MutableSet<String>,
        destinationFile: File,
        preferPortrait: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext false

        val intent = detectVisualIntent(cleanQuery)
        val subject = extractCleanSubject(cleanQuery)
        val candidates = linkedMapOf<String, PhotoCandidate>()

        when (intent) {
            RealVisualIntent.MOVIE_POSTER -> {
                runCatching { collectImdbPhotoCandidates(subject, candidates, preferMovies = true, allowPeople = false) }
                runCatching { collectItunesMovieCandidates(subject, candidates, listOf("IN", "US")) }
                runCatching { collectWikipediaSummaryCandidates("$subject (film)", candidates, requireEntertainment = true) }
                runCatching { collectWikipediaSummaryCandidates(subject, candidates, requireEntertainment = true) }
                runCatching { collectWikipediaSearchCandidates("$subject film poster", candidates, requireEntertainment = true) }
            }

            RealVisualIntent.CHARACTER -> {
                runCatching { collectWikipediaSummaryCandidates("$subject (Marvel Cinematic Universe)", candidates, false) }
                runCatching { collectWikipediaSummaryCandidates("$subject (character)", candidates, false) }
                runCatching { collectWikipediaSummaryCandidates(subject, candidates, false) }
                runCatching { collectImdbPhotoCandidates(subject, candidates, preferMovies = true, allowPeople = true) }
                runCatching { collectWikipediaSearchCandidates("$subject character", candidates, false) }
                runCatching { collectWikipediaSearchCandidates(subject, candidates, false) }
                runCatching { collectItunesMovieCandidates(subject, candidates, listOf("US", "IN")) }
                runCatching { collectWikimediaCommonsCandidates(subject, candidates) }
            }

            RealVisualIntent.PERSON -> {
                runCatching { collectWikipediaSummaryCandidates(subject, candidates, false) }
                runCatching { collectImdbPhotoCandidates(subject, candidates, preferMovies = false, allowPeople = true) }
                runCatching { collectWikipediaSearchCandidates(subject, candidates, false) }
                runCatching { collectWikimediaCommonsCandidates(subject, candidates) }
            }
        }

        val ordered = candidates.values.sortedWith(
            compareByDescending<PhotoCandidate> { it.priority }
                .thenByDescending { if (preferPortrait && it.isPortraitOrSquare) 1 else 0 }
        )

        for (candidate in ordered) {
            if (candidate.url in usedUrls) continue
            if (candidate.fallbackUrl != null && candidate.fallbackUrl in usedUrls) continue

            var downloaded = runCatching { downloadAndValidateImageUrl(candidate.url, destinationFile) }.getOrDefault(false)
            if (!downloaded && candidate.fallbackUrl != null) {
                downloaded = runCatching { downloadAndValidateImageUrl(candidate.fallbackUrl, destinationFile) }.getOrDefault(false)
            }

            if (downloaded) {
                usedUrls.add(candidate.url)
                candidate.fallbackUrl?.let { usedUrls.add(it) }
                return@withContext true
            }
        }
        false
    }

    private fun detectVisualIntent(rawQuery: String): RealVisualIntent {
        val lower = rawQuery.lowercase().trim()
        val movieKeywords = listOf(
            "movie", "film", "poster", "web series", "tv series", "series", "cinema", "trailer", "box office"
        )
        if (movieKeywords.any { lower.contains(it) }) return RealVisualIntent.MOVIE_POSTER

        val characterKeywords = listOf(
            "character", "superhero", "supervillain", "villain", "comic", "anime",
            "marvel", "dc comics", "mcu", "iron man", "ironman", "superman",
            "batman", "spider-man", "spiderman", "thor", "hulk", "captain america",
            "doctor strange", "doctor doom", "black panther", "deadpool", "wolverine", "joker",
            "thanos", "loki", "flash", "aquaman", "wonder woman", "venom",
            "ant-man", "hawkeye", "scarlet witch", "black widow", "goku", "naruto",
            "luffy", "krrish", "shaktimaan", "baahubali", "pushpa raj", "rocky bhai"
        )
        if (characterKeywords.any { lower.contains(it) }) return RealVisualIntent.CHARACTER

        return RealVisualIntent.PERSON
    }

    private fun extractCleanSubject(rawQuery: String): String {
        var cleaned = rawQuery.trim()
            .replace(
                Regex(
                    """\b(official\s+poster|movie\s+poster|film\s+poster|character\s+poster|poster|movie|film|web\s+series|tv\s+series|series|character|superhero|supervillain|villain|picture|photo|image|wallpaper|hd|4k)\b""",
                    RegexOption.IGNORE_CASE
                ),
                " "
            )
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', ':', ',', '.', '"', '\'')

        if (cleaned.isBlank()) cleaned = rawQuery.trim()
        return when (cleaned.lowercase()) {
            "ironman" -> "Iron Man"
            "spiderman" -> "Spider-Man"
            "antman" -> "Ant-Man"
            "xmen", "x men" -> "X-Men"
            "russo brothers" -> "Russo brothers"
            else -> cleaned
        }
    }

    private fun upgradeImdbUrl(url: String): String {
        val markerIdx = url.indexOf("._V1_")
        if (markerIdx == -1) return url
        val ext = url.substringAfterLast('.', "jpg").lowercase().let {
            if (it in setOf("jpg", "jpeg", "png", "webp")) it else "jpg"
        }
        return url.substring(0, markerIdx) + "._V1_SX1000_." + ext
    }

    private fun collectImdbPhotoCandidates(
        searchTerm: String,
        outMap: MutableMap<String, PhotoCandidate>,
        preferMovies: Boolean,
        allowPeople: Boolean
    ) {
        val normalized = searchTerm.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (normalized.isBlank()) return
        val firstChar = normalized.firstOrNull { it in 'a'..'z' || it in '0'..'9' } ?: 'x'
        val encoded = URLEncoder.encode(normalized, "UTF-8")
        val url = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$encoded.json"

        val bodyText = fetchJsonString(url) ?: return
        val root = json.parseToJsonElement(bodyText) as? JsonObject ?: return
        val results = root["d"] as? JsonArray ?: return

        val queryClean = searchTerm.lowercase().trim()
        val queryWords = queryClean.split(Regex("\\s+")).filter { it.length >= 2 }

        for (item in results) {
            val obj = item as? JsonObject ?: continue
            val label = obj["l"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val qid = obj["qid"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            val qLabel = obj["q"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            val releaseYear = obj["y"]?.jsonPrimitive?.intOrNull ?: 0

            val isMovieOrSeries = id.startsWith("tt") ||
                qid in setOf("movie", "tvseries", "tvminiseries", "tvmovie") ||
                qLabel.contains("feature") ||
                qLabel.contains("series")
            val isPerson = id.startsWith("nm")
            if (!allowPeople && isPerson) continue

            val matchesName = queryWords.isEmpty() || queryWords.any { label.contains(it) }
            if (!matchesName && !isPerson) continue

            val imgObj = obj["i"] as? JsonObject ?: continue
            val rawUrl = imgObj["imageUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (!rawUrl.startsWith("http")) continue

            val w = imgObj["width"]?.jsonPrimitive?.intOrNull ?: 0
            val h = imgObj["height"]?.jsonPrimitive?.intOrNull ?: 0
            val upgradedUrl = upgradeImdbUrl(rawUrl)
            val isHdUpgraded = upgradedUrl != rawUrl
            if (!isHdUpgraded && (w in 1..299 || h in 1..299)) continue

            val finalW = if (isHdUpgraded && w in 1..999) 1000 else w
            val finalH = if (isHdUpgraded && w > 0 && h > 0 && w < 1000) {
                (h.toFloat() * (1000f / w.toFloat())).toInt()
            } else {
                h
            }
            val fallback = if (isHdUpgraded && (w == 0 || w >= 300) && (h == 0 || h >= 300)) rawUrl else null

            val exactTitleBoost = if (label == queryClean) 4 else if (label.startsWith(queryClean)) 2 else 0
            val recencyBoost = when {
                isPerson -> 0
                releaseYear >= 2022 -> 3
                releaseYear >= 2015 -> 1
                else -> 0
            }
            val typeBoost = when {
                preferMovies && isMovieOrSeries -> 6
                !preferMovies && isPerson -> 6
                isMovieOrSeries || isPerson -> 2
                else -> 0
            }

            outMap.putIfAbsent(
                upgradedUrl,
                PhotoCandidate(upgradedUrl, finalW, finalH, exactTitleBoost + recencyBoost + typeBoost, fallback)
            )
        }
    }

    private fun collectItunesMovieCandidates(
        searchTerm: String,
        outMap: MutableMap<String, PhotoCandidate>,
        countries: List<String> = listOf("IN", "US")
    ) {
        val queryClean = searchTerm.lowercase().trim()
        val queryWords = queryClean.split(Regex("\\s+")).filter { it.length >= 2 }
        val encoded = URLEncoder.encode(searchTerm, "UTF-8")

        for (country in countries) {
            val url = "https://itunes.apple.com/search?term=$encoded&media=movie&entity=movie&limit=8&country=$country"
            val bodyText = fetchJsonString(url) ?: continue
            val root = runCatching { json.parseToJsonElement(bodyText) as? JsonObject }.getOrNull() ?: continue
            val results = root["results"] as? JsonArray ?: continue

            for (item in results) {
                val obj = item as? JsonObject ?: continue
                val trackName = obj["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                if (queryWords.isNotEmpty() && queryWords.none { trackName.contains(it) }) continue

                val artwork100 = obj["artworkUrl100"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (!artwork100.startsWith("http")) continue

                val releaseYear = obj["releaseDate"]?.jsonPrimitive?.contentOrNull
                    ?.substringBefore("-")?.toIntOrNull() ?: 0
                val recencyBoost = when {
                    releaseYear >= 2021 -> 3
                    releaseYear >= 2015 -> 1
                    else -> 0
                }
                val artworkHd = artwork100.replace(Regex("""\d+x\d+bb"""), "1400x1400bb")
                val artworkFallback = artwork100.replace(Regex("""\d+x\d+bb"""), "1000x1000bb")
                val exactBoost = if (trackName == queryClean) 5 else if (trackName.startsWith(queryClean)) 3 else 1

                outMap.putIfAbsent(
                    artworkHd,
                    PhotoCandidate(
                        url = artworkHd,
                        width = 933,
                        height = 1400,
                        priority = 4 + exactBoost + recencyBoost,
                        fallbackUrl = if (artworkFallback != artworkHd) artworkFallback else null
                    )
                )
            }
        }
    }

    private fun isNonEntertainmentWikiSummary(
        description: String,
        extract: String,
        requireEntertainment: Boolean
    ): Boolean {
        if (!requireEntertainment) return false
        val combined = "$description $extract".lowercase()
        val entertainmentHints = listOf(
            "film", "movie", "cinema", "directed by", "starring", "screenplay",
            "box office", "television", "web series", "tv series", "season",
            "animated", "soundtrack", "franchise", "superhero", "marvel", "dc comics"
        )
        return entertainmentHints.none { combined.contains(it) }
    }

    private fun collectWikipediaSummaryCandidates(
        pageQuery: String,
        outMap: MutableMap<String, PhotoCandidate>,
        requireEntertainment: Boolean
    ) {
        val title = pageQuery.replace(' ', '_')
        val encodedTitle = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encodedTitle"
        val bodyText = fetchJsonString(url) ?: return
        val root = json.parseToJsonElement(bodyText) as? JsonObject ?: return

        val pageType = root["type"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        if (pageType == "disambiguation") return

        val description = root["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val extract = root["extract"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (isNonEntertainmentWikiSummary(description, extract, requireEntertainment)) return

        listOf("originalimage", "thumbnail").forEach { key ->
            val imgObj = root[key] as? JsonObject ?: return@forEach
            val imgUrl = imgObj["source"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val w = imgObj["width"]?.jsonPrimitive?.intOrNull ?: 0
            val h = imgObj["height"]?.jsonPrimitive?.intOrNull ?: 0
            if (isCleanPhotoUrl(imgUrl)) {
                outMap.putIfAbsent(imgUrl, PhotoCandidate(imgUrl, w, h, priority = 5))
            }
        }
    }

    private fun collectWikipediaSearchCandidates(
        searchQuery: String,
        outMap: MutableMap<String, PhotoCandidate>,
        requireEntertainment: Boolean
    ) {
        val encoded = URLEncoder.encode(searchQuery, "UTF-8")
        val url =
            "https://en.wikipedia.org/w/api.php?action=query&generator=search" +
                "&gsrsearch=$encoded&gsrlimit=5&prop=pageimages|extracts" +
                "&exintro=1&explaintext=1&exsentences=2" +
                "&piprop=original|thumbnail&pithumbsize=1200&format=json"

        val bodyText = fetchJsonString(url) ?: return
        val root = json.parseToJsonElement(bodyText) as? JsonObject ?: return
        val pages = (root["query"] as? JsonObject)?.get("pages") as? JsonObject ?: return

        for (pageElem in pages.values) {
            val pageObj = pageElem as? JsonObject ?: continue
            val pageTitle = pageObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val pageExtract = pageObj["extract"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (isNonEntertainmentWikiSummary(pageTitle, pageExtract, requireEntertainment)) continue

            listOf("original", "thumbnail").forEach { key ->
                val imgObj = pageObj[key] as? JsonObject ?: return@forEach
                val imgUrl = imgObj["source"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val w = imgObj["width"]?.jsonPrimitive?.intOrNull ?: 0
                val h = imgObj["height"]?.jsonPrimitive?.intOrNull ?: 0
                if (isCleanPhotoUrl(imgUrl)) {
                    outMap.putIfAbsent(imgUrl, PhotoCandidate(imgUrl, w, h, priority = 3))
                }
            }
        }
    }

    private fun collectWikimediaCommonsCandidates(
        cleanQuery: String,
        outMap: MutableMap<String, PhotoCandidate>
    ) {
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val url =
            "https://commons.wikimedia.org/w/api.php?action=query&generator=search" +
                "&gsrnamespace=6&gsrsearch=$encoded&gsrlimit=12&prop=imageinfo" +
                "&iiprop=url|size|mime&iiurlwidth=1200&format=json"

        val bodyText = fetchJsonString(url) ?: return
        val root = json.parseToJsonElement(bodyText) as? JsonObject ?: return
        val pages = (root["query"] as? JsonObject)?.get("pages") as? JsonObject ?: return

        for (pageElem in pages.values) {
            val pageObj = pageElem as? JsonObject ?: continue
            val title = pageObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val imageInfo = (pageObj["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue

            val mime = imageInfo["mime"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            val width = imageInfo["width"]?.jsonPrimitive?.intOrNull ?: 0
            val height = imageInfo["height"]?.jsonPrimitive?.intOrNull ?: 0
            val thumbUrl = imageInfo["thumburl"]?.jsonPrimitive?.contentOrNull
            val fullUrl = imageInfo["url"]?.jsonPrimitive?.contentOrNull

            if (mime != "image/jpeg" && mime != "image/png") continue
            if (width < 400 || height < 400) continue

            val chosen = thumbUrl ?: fullUrl ?: continue
            if (isCleanPhotoUrl(title) && isCleanPhotoUrl(chosen)) {
                outMap.putIfAbsent(chosen, PhotoCandidate(chosen, width, height, priority = 0))
            }
        }
    }

    private fun isCleanPhotoUrl(urlOrTitle: String): Boolean {
        val lower = urlOrTitle.lowercase()
        if (lower.endsWith(".svg") || lower.endsWith(".gif") || lower.endsWith(".pdf")) return false
        val bannedTokens = listOf(
            "signature", "autograph", "logo", "icon", "flag", "map",
            "diagram", "chart", "coat_of_arms", "emblem", "stamp", "locator",
            "wordmark", "symbol"
        )
        return bannedTokens.none { lower.contains(it) }
    }

    private fun fetchJsonString(url: String): String? =
        fetchTextString(url, WIKIMEDIA_USER_AGENT, "application/json")

    private fun fetchTextString(
        url: String,
        userAgent: String,
        acceptHeader: String? = null
    ): String? {
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        if (!acceptHeader.isNullOrBlank()) builder.header("Accept", acceptHeader)
        okHttpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    private fun downloadAndValidateImageUrl(url: String, destinationFile: File): Boolean {
        val request = Request.Builder().url(url).header("User-Agent", WIKIMEDIA_USER_AGENT).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.isNotBlank() && (!contentType.startsWith("image/") || contentType.contains("svg"))) {
                return false
            }
            val body = response.body ?: return false
            destinationFile.outputStream().use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        }

        if (!destinationFile.exists() || destinationFile.length() < 8192L) {
            destinationFile.delete()
            return false
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(destinationFile.absolutePath, bounds)
        if (bounds.outWidth < 300 || bounds.outHeight < 300) {
            destinationFile.delete()
            return false
        }
        return true
    }

    override suspend fun generateAudioForSlab(text: String): ResponseBody {
        require(text.isNotBlank()) { "Voiceover text must not be blank." }
        return edgeTtsApi.synthesize(EdgeTtsRequest(text = text, voice = VideoLanguage.HINDI.maleVoice))
    }

    override suspend fun generateContinuousAudioWithVtt(text: String): EdgeTtsSynthesisResult {
        require(text.isNotBlank()) { "Voiceover text must not be blank." }
        return edgeTtsApi.synthesizeWithSubtitles(text = text, voice = VideoLanguage.HINDI.maleVoice)
    }

    override suspend fun generateDynamicVoiceClip(
        text: String,
        voiceName: String,
        emotion: String,
        voiceTone: VoiceTone
    ): ByteArray = generateDynamicVoiceClipWithMeta(
        text = text,
        voiceName = voiceName,
        emotion = emotion,
        voiceTone = voiceTone
    ).audioBytes

    override suspend fun generateDynamicVoiceClipWithMeta(
        text: String,
        voiceName: String,
        emotion: String,
        voiceTone: VoiceTone
    ): EdgeTtsClipResult {
        val cleanSpokenText = sanitizeTtsText(text)
        require(cleanSpokenText.isNotBlank()) { "Voiceover text must not be blank." }

        val (rate, pitch) = resolveProsody(emotion = emotion, voiceTone = voiceTone)
        return edgeTtsApi.synthesizeSpeechClipWithMeta(
            cleanSpokenText,
            voiceName.ifBlank { VideoLanguage.HINDI.maleVoice },
            rate,
            pitch
        )
    }

    private fun sanitizeTtsText(rawText: String): String = rawText
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun resolveProsody(emotion: String, voiceTone: VoiceTone): Pair<String, String> {
        val emo = emotion.lowercase().trim()
        return when (voiceTone.name.uppercase()) {
            "NATURAL", "PODCAST", "HUMAN" -> when (emo) {
                "hook" -> "+24%" to "+1Hz"
                "suspense" -> "+16%" to "-3Hz"
                "climax" -> "+26%" to "-2Hz"
                "question" -> "+20%" to "+2Hz"
                "excited", "roast" -> "+25%" to "+2Hz"
                else -> "+21%" to "-2Hz"
            }

            "FUNNY", "COMIC" -> when (emo) {
                "hook" -> "+28%" to "+4Hz"
                "suspense" -> "+20%" to "-1Hz"
                "climax" -> "+30%" to "+3Hz"
                "question" -> "+24%" to "+5Hz"
                "excited", "roast" -> "+29%" to "+4Hz"
                else -> "+25%" to "+2Hz"
            }

            "SERIOUS", "INTENSE" -> when (emo) {
                "hook" -> "+18%" to "-4Hz"
                "suspense" -> "+12%" to "-7Hz"
                "climax" -> "+20%" to "-5Hz"
                "question" -> "+15%" to "-2Hz"
                "excited", "roast" -> "+19%" to "-3Hz"
                else -> "+15%" to "-5Hz"
            }

            "ENERGETIC", "HYPE" -> when (emo) {
                "hook" -> "+30%" to "+3Hz"
                "suspense" -> "+22%" to "-2Hz"
                "climax" -> "+32%" to "+2Hz"
                "question" -> "+25%" to "+4Hz"
                "excited", "roast" -> "+31%" to "+3Hz"
                else -> "+27%" to "+1Hz"
            }

            else -> when (emo) {
                "hook" -> "+25%" to "+2Hz"
                "suspense" -> "+16%" to "-5Hz"
                "climax" -> "+27%" to "-1Hz"
                "question" -> "+20%" to "+2Hz"
                "excited", "roast" -> "+26%" to "+3Hz"
                else -> "+22%" to "-2Hz"
            }
        }
    }

    private fun parseScriptResponse(
        rawText: String,
        requestedLanguage: VideoLanguage,
        requestedGender: VoiceGender
    ): VideoScriptResponse {
        val extractedJson = extractJsonPayload(rawText)
        val repairedJson = repairJsonStructure(extractedJson)
            .replace(Regex(",\\s*([}\\]])"), "$1")
        val root = json.parseToJsonElement(repairedJson)

        var characterBible = ""
        var visualStyle = ""
        var bgmMood = "epic_action"
        var parsedVoiceName = ""
        var youtubeTitle = ""
        var youtubeDescription = ""
        var youtubeHashtags = ""
        var pinnedComment = ""

        val slabElements: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                characterBible = root["character_bible"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                visualStyle = root["visual_style"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                bgmMood = root["bgm_mood"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "epic_action" }
                parsedVoiceName = root["voice_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                youtubeTitle = root["youtube_title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                youtubeDescription = root["youtube_description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                youtubeHashtags = root["youtube_hashtags"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                pinnedComment = root["pinned_comment"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

                val array = root["slabs"] ?: root["scenes"] ?: root["script"]
                    ?: root.values.firstOrNull { it is JsonArray }
                    ?: error("Expected a JSON array of scenes in Groq response.")
                array as? JsonArray ?: error("'slabs' or 'scenes' must be a JSON array.")
            }
            else -> error("Groq response must be a JSON array or object.")
        }

        val allowedSources = setOf("ai_generated", "real_photo", "video_clip", "meme_clip")
        val slabs = slabElements.mapIndexedNotNull { index, element ->
            val dto = json.decodeFromJsonElement<GeneratedSlabDto>(element)
            val voiceover = dto.voiceoverText.trim()
            val prompt = dto.imagePrompt.trim()
            val sfxCue = dto.sfxCue?.trim()?.lowercase()?.ifBlank { "whoosh" } ?: "whoosh"
            val emotion = dto.voiceEmotion?.trim()?.lowercase()?.ifBlank { "normal" } ?: "normal"

            val rawQuery = dto.realPhotoQuery?.trim().orEmpty()
            val realQuery = if (rawQuery.lowercase() in setOf("none", "null", "n/a", "-")) "" else rawQuery
            val requestedSource = dto.visualSource?.trim()?.lowercase().orEmpty()
            val visualSource = if (requestedSource in allowedSources &&
                (requestedSource == "ai_generated" || realQuery.isNotBlank())
            ) {
                requestedSource
            } else {
                "ai_generated"
            }

            val rawQuery2 = dto.realPhotoQuery2?.trim().orEmpty()
            val realQuery2 = if (rawQuery2.lowercase() in setOf("none", "null", "n/a", "-")) "" else rawQuery2
            val prompt2 = dto.imagePrompt2?.trim().orEmpty()
            val visualSource2 = when {
                dto.visualSource2?.trim()?.equals("real_photo", ignoreCase = true) == true && realQuery2.isNotBlank() -> "real_photo"
                prompt2.isNotBlank() -> "ai_generated"
                else -> ""
            }
            val rawLayout = dto.layoutMode?.trim()?.lowercase().orEmpty()
            val hasSecondVisual = (visualSource2 == "real_photo" && realQuery2.isNotBlank()) || prompt2.isNotBlank()
            val layoutMode = if (visualSource !in setOf("video_clip", "meme_clip") &&
                hasSecondVisual && rawLayout in setOf("split", "flip")
            ) {
                rawLayout
            } else {
                "single"
            }
            val statBadge = dto.statBadge?.trim().orEmpty().take(32)

            if (voiceover.isBlank() || prompt.isBlank()) {
                null
            } else {
                VideoSlab(
                    id = dto.id ?: index + 1,
                    voiceover_text = voiceover,
                    image_prompt = prompt,
                    sfx_cue = sfxCue,
                    has_character = dto.hasCharacter ?: false,
                    voice_emotion = emotion,
                    visual_source = visualSource,
                    real_photo_query = if (visualSource != "ai_generated") realQuery else "",
                    layout_mode = layoutMode,
                    visual_source_2 = if (layoutMode != "single") visualSource2 else "",
                    real_photo_query_2 = if (layoutMode != "single" && visualSource2 == "real_photo") realQuery2 else "",
                    image_prompt_2 = if (layoutMode != "single") prompt2 else "",
                    stat_badge = statBadge
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
            youtube_title = youtubeTitle,
            youtube_description = youtubeDescription,
            youtube_hashtags = youtubeHashtags,
            pinned_comment = pinnedComment,
            slabs = slabs
        )
    }

    private fun extractJsonPayload(rawText: String): String {
        val cleaned = rawText
            .replace(Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()

        val firstBrace = cleaned.indexOf('{')
        val firstArrayStart = Regex("""\[\s*\{""").find(cleaned)?.range?.first ?: -1

        val startIndex = when {
            firstBrace != -1 && (firstArrayStart == -1 || firstBrace < firstArrayStart) -> firstBrace
            firstArrayStart != -1 -> firstArrayStart
            firstBrace != -1 -> firstBrace
            else -> cleaned.indexOf('[')
        }
        return if (startIndex >= 0) cleaned.substring(startIndex) else cleaned
    }

    private fun repairJsonStructure(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return trimmed

        val out = StringBuilder(trimmed.length + 16)
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false

        for (ch in trimmed) {
            if (inString) {
                when {
                    escaped -> {
                        out.append(ch)
                        escaped = false
                    }
                    ch == '\\' -> {
                        out.append(ch)
                        escaped = true
                    }
                    ch == '"' -> {
                        out.append(ch)
                        inString = false
                    }
                    ch == '\n' -> out.append("\\n")
                    ch == '\r' -> out.append("\\r")
                    ch == '\t' -> out.append("\\t")
                    ch.code < 0x20 -> out.append(' ')
                    else -> out.append(ch)
                }
                continue
            }

            when (ch) {
                '"' -> {
                    out.append(ch)
                    inString = true
                }
                '{' -> {
                    out.append(ch)
                    stack.addLast('}')
                }
                '[' -> {
                    out.append(ch)
                    stack.addLast(']')
                }
                '}', ']' -> {
                    if (stack.isEmpty()) {
                        break
                    }
                    if (stack.last() == ch) {
                        out.append(ch)
                        stack.removeLast()
                    } else if (ch in stack) {
                        while (stack.isNotEmpty() && stack.last() != ch) {
                            stripTrailingCommaAndSpace(out)
                            out.append(stack.removeLast())
                        }
                        if (stack.isNotEmpty() && stack.last() == ch) {
                            stripTrailingCommaAndSpace(out)
                            out.append(ch)
                            stack.removeLast()
                        }
                    }
                    if (stack.isEmpty()) break
                }
                else -> out.append(ch)
            }
        }

        if (inString) {
            if (escaped) out.setLength((out.length - 1).coerceAtLeast(0))
            out.append('"')
        }
        while (stack.isNotEmpty()) {
            stripTrailingCommaAndSpace(out)
            out.append(stack.removeLast())
        }
        return out.toString()
    }

    private fun stripTrailingCommaAndSpace(sb: StringBuilder) {
        var i = sb.length - 1
        while (i >= 0 && sb[i].isWhitespace()) i--
        if (i >= 0 && (sb[i] == ',' || sb[i] == ':')) {
            sb.setLength(i)
        }
    }

    private suspend fun <T> callWithHttpUrl(
        maxAttempts: Int = MAX_API_ATTEMPTS,
        request: suspend () -> T
    ): T {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HttpException) {
                val code = error.code()
                val url = error.response()?.raw()?.request?.url?.toString() ?: "URL unavailable"
                val body = error.response()?.errorBody()?.string().orEmpty()
                val wrapped = IllegalStateException("HTTP $code Error at URL: $url. Body: $body", error)
                lastError = wrapped
                if ((code == 429 || code in 500..599) && attempt < maxAttempts - 1) {
                    delay(1500L * (attempt + 1))
                } else {
                    throw wrapped
                }
            } catch (error: IOException) {
                lastError = error
                if (attempt < maxAttempts - 1) delay(1500L * (attempt + 1)) else throw error
            }
        }
        throw lastError ?: IllegalStateException("API request failed.")
    }

    @Serializable
    private data class GeneratedSlabDto(
        val id: Int? = null,
        @SerialName("voiceover_text") val voiceoverText: String,
        @SerialName("image_prompt") val imagePrompt: String,
        @SerialName("sfx_cue") val sfxCue: String? = null,
        @SerialName("has_character") val hasCharacter: Boolean? = null,
        @SerialName("voice_emotion") val voiceEmotion: String? = null,
        @SerialName("visual_source") val visualSource: String? = null,
        @SerialName("real_photo_query") val realPhotoQuery: String? = null,
        @SerialName("layout_mode") val layoutMode: String? = null,
        @SerialName("visual_source_2") val visualSource2: String? = null,
        @SerialName("real_photo_query_2") val realPhotoQuery2: String? = null,
        @SerialName("image_prompt_2") val imagePrompt2: String? = null,
        @SerialName("stat_badge") val statBadge: String? = null
    )

    private companion object {
        const val MAX_API_ATTEMPTS = 5
        const val FULL_SCRIPT_WORD_THRESHOLD = 25
        const val WIKIMEDIA_USER_AGENT = "RSMovieSpot/1.0 (https://rsmoviespot.app)"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        const val SHORTS_IMAGE_WIDTH = 768
        const val SHORTS_IMAGE_HEIGHT = 1344
        const val LANDSCAPE_IMAGE_WIDTH = 1344
        const val LANDSCAPE_IMAGE_HEIGHT = 768

        const val IMAGE_PROMPT_RULES =
            "PRIORITY 0 — USER-DESCRIBED SCENES & VISUALS ALWAYS WIN (HIGHEST PRIORITY):\n" +
                "- Whenever the user's prompt or script explicitly describes a specific visual scene, character appearance, outfit, location, lighting, style, camera shot, or action (whether in Hindi, Hinglish, or English, e.g., 'pehle scene me...', 'Scene 1:', '[Visual: ...]'), YOU MUST generate that EXACT visual in image_prompt and set visual_source='ai_generated' and real_photo_query=''.\n" +
                "- Front-load the exact described subject, costume/outfit, action, setting, props, and lighting in the FIRST 25-35 words of image_prompt so the image generator prioritizes them!\n" +
                "- If the user asks for a specific style (e.g., anime, 3D Pixar, noir, cyberpunk) or forbids posters/comics/real_photo, strictly honor that style across every scene.\n" +
                "- SCENE-BY-SCENE CHARACTER & ACTION MATCHING: NEVER put the main hero alone in every scene! If a scene talks about a monkey, butterfly, rabbit, parrot, villain, crowd, mud splash, rainbow sky, or water spray, describe THAT EXACT subject and action in the first 15 words of image_prompt, and set has_character=false whenever the scene is focused on secondary characters or scenery! Keep the main character's look consistent across their scenes with zero random hats/outfits unless mentioned.\n\n" +
                "DYNAMIC MULTI-CLIP MOVIE SCENES, MEMES, 2-PHOTO SPLITS & STAT BADGES:\n" +
                "- 'video_clip' (USE 3 to 6 SCENES depending on topic need, UNLESS user requests pure AI animation/no clips): Set visual_source='video_clip', layout_mode='single', and give EACH clip a DIFFERENT, concrete 2-3 word recognizable English movie/character/action query (e.g., 'Avengers Endgame', 'Robert Downey Jr', 'Thanos snap', 'Doctor Strange portal', 'cinema explosion'). NEVER repeat the same query across clips, and NEVER use abstract/vague queries like '4 out of 5 stars', 'rating', 'review', 'multiverse', 'subscribe', 'ending', or 'cigar'!\n" +
                "- 'meme_clip' (1 to 2 SCENES where comedy, shock, hype, or roast fits, unless user disables memes): Set visual_source='meme_clip', layout_mode='single', and real_photo_query to a named, well-known meme template (e.g. 'Hera Pheri paisa', 'shocked Pikachu', 'mind blown', 'Leonardo DiCaprio toast', 'spiderman pointing').\n" +
                "- 'split' / 'flip' 2-PHOTO SCENES: For record-comparison, clash, or vs scenes, set layout_mode='split' (or 'flip' for rapid dual reveal) with visual_source in {'real_photo','ai_generated'} and fill BOTH primary AND secondary visual fields (visual_source_2, real_photo_query_2, image_prompt_2)!\n" +
                "- STAT BADGES: Whenever a scene announces a HUGE number, budget, box office record, or release date (e.g. '$150M PRE-SALES', '₹2000 CR BUDGET', '17 DEC RELEASE'), set stat_badge to a crisp 2-4 word uppercase headline; otherwise set stat_badge=''.\n\n" +
                "REAL PHOTO / POSTER vs REALISTIC CHARACTER AI IMAGE RULES (When scene is not video_clip/meme_clip and user did NOT specify a custom/animated style):\n" +
                "  1) REAL-WORLD ACTOR / ACTRESS / DIRECTOR / FILMMAKER named in the scene (e.g., Russo Brothers, Christopher Nolan, James Gunn, Prabhas, Sandeep Reddy Vanga, Allu Arjun, Ranbir Kapoor, Shah Rukh Khan, Robert Downey Jr, Pedro Pascal, Chris Evans):\n" +
                "     ALWAYS set visual_source='real_photo' and real_photo_query to ONLY their clean English name (e.g. 'Russo Brothers', 'Pedro Pascal', 'Robert Downey Jr') — NEVER generate distorted AI faces for real directors or actors!\n" +
                "  2) SPECIFIC MOVIE / WEB SERIES / SHOW highlighted in the scene (e.g., Animal, Kabir Singh, Spirit, Pushpa 2, KGF, Avengers Endgame, Avengers Doomsday, Salaar, Spider-Man No Way Home):\n" +
                "     Set visual_source='real_photo' and real_photo_query to '<Exact Clean English Title> movie poster'. NEVER combine actor + movie in one query.\n" +
                "  3) FICTIONAL CHARACTER / SUPERHERO / VILLAIN (e.g., Iron Man, Superman, Batman, Spider-Man, Doctor Doom, Thanos, Joker, Deadpool, Thor, Goku, Rocky Bhai, Pushpa Raj, Hanuman):\n" +
                "     * 1 or 2 iconic character reveals: visual_source='real_photo' and real_photo_query='<Clean English Character Name> character'.\n" +
                "     * ALL STORY / ACTION / DIALOGUE scenes: visual_source='ai_generated' with raw LIVE-ACTION MOVIE PHOTOGRAPH image_prompt.\n" +
                "  4) ALL OTHER SCENES: set visual_source='ai_generated' and real_photo_query=''.\n" +
                "- ALWAYS write a rich, content-matched fallback image_prompt for EVERY slab (even video_clip/meme_clip/real_photo scenes).\n\n" +
                "IMAGE PROMPT RULES (SINGLE UNIFIED SCENE — ZERO TEXT / ZERO GIBBERISH / ZERO BAD HANDS):\n" +
                "- Every image_prompt MUST describe ONE SINGLE CONTINUOUS CAMERA SHOT of the exact scene (never collages, grids, wallpapers, sticker patterns, or multiple panels).\n" +
                "- NEVER write movie titles, channel names, signs, logos, neon letters, cinema marquees, or words inside image_prompt (NEVER write 'Secret Wars', 'Doctor Doom', 'RS Movie Spot', 'neon sign', 'billboard', or 'poster' inside ai_generated prompts — write pure visual description like 'armored figure in dark emerald cloak and brushed steel mask in ruined castle').\n" +
                "- NEVER prompt hands holding objects close to camera (NEVER write 'holding microphone' or 'holding phone' — use 'podcast host seated cleanly behind desk studio condenser mic on boom arm, relaxed natural posture').\n" +
                "- Unless user specifies animation/3D/anime/cartoon, write every image_prompt as a REAL LIVE-ACTION FILM SET PHOTOGRAPH (brushed steel, heavy wool cloak, rain droplets, sweat, warm streetlights, cinema depth of field) — NEVER write 'Marvel', 'comic', 'concept art', or 'neon digital glow' unless asked!\n" +
                "- NEVER write real living actor names inside image_prompt unless explicitly instructed for a mask/costume scene.\n" +
                "- Avoid literal title words like 'Animal', 'Spirit', 'Beast', 'Tiger', 'Lion', 'Ghost', 'King' when referring to a movie title.\n" +
                "- NEVER write 'box office', 'records', 'close-up eyes', 'comment box', 'subscribe', 'UI', 'poster', or 'text' inside image_prompt.\n" +
                "- Vary camera framing across scenes (wide shot, medium shot, over-the-shoulder, low-angle).\n\n"

        const val VOICE_AND_BGM_RULES =
            "VOICE EMOTION, BGM & YOUTUBE METADATA RULES:\n" +
                "- Pick bgm_mood: 'comedy_roast', 'epic_action', 'suspense_thriller', 'emotional_sad', 'devotional', 'sci_fi_tech', 'motivational', or 'horror_dark'.\n" +
                "- For each scene, set voice_emotion: 'hook', 'suspense', 'climax', 'question', 'excited', or 'normal'.\n" +
                "- Pick valid Edge TTS voice_name (e.g. hi-IN-MadhurNeural, hi-IN-SwaraNeural, en-IN-PrabhatNeural, en-IN-NeerjaNeural, en-US-AndrewNeural, en-US-AvaNeural, etc.).\n" +
                "- Also generate youtube_title (catchy <75 chars viral title with 1 emoji), youtube_description (2-3 crisp SEO lines for RS Movies Spot), youtube_hashtags ('#shorts #movies ...'), and pinned_comment (a debate/question to spark viewer comments).\n\n"

        const val JSON_SCHEMA_SPEC =
            "Return ONLY ONE valid JSON object (no markdown fences, no commentary) matching this schema:\n" +
                "{\n" +
                "  \"character_bible\": \"Fixed simple visual look of main character in English (no random hats/outfits unless needed)\",\n" +
                "  \"visual_style\": \"Visual medium matching user request (e.g. 3D Pixar animation or Raw live-action 35mm film)\",\n" +
                "  \"bgm_mood\": \"comedy_roast | epic_action | suspense_thriller | emotional_sad | devotional | sci_fi_tech | motivational | horror_dark\",\n" +
                "  \"voice_name\": \"Edge TTS voice shortName matching language and gender\",\n" +
                "  \"youtube_title\": \"Catchy viral YouTube title under 75 chars\",\n" +
                "  \"youtube_description\": \"2-3 line SEO YouTube description mentioning RS Movies Spot\",\n" +
                "  \"youtube_hashtags\": \"#shorts #bollywood #hollywood #rsmoviesspot\",\n" +
                "  \"pinned_comment\": \"Debate question to boost comments on this video\",\n" +
                "  \"slabs\": [\n" +
                "    {\n" +
                "      \"voiceover_text\": \"Natural complete clause of 6-11 words (NEVER cut mid-name or across period)\",\n" +
                "      \"voice_emotion\": \"hook | suspense | climax | question | excited | normal\",\n" +
                "      \"visual_source\": \"real_photo | ai_generated | video_clip | meme_clip\",\n" +
                "      \"real_photo_query\": \"Clean English query if real_photo/video_clip/meme_clip, else ''\",\n" +
                "      \"layout_mode\": \"single | split | flip\",\n" +
                "      \"visual_source_2\": \"real_photo | ai_generated | ''\",\n" +
                "      \"real_photo_query_2\": \"2nd photo query if split/flip and real_photo, else ''\",\n" +
                "      \"image_prompt_2\": \"2nd scene English prompt if split/flip, else ''\",\n" +
                "      \"stat_badge\": \"Optional 2-4 word uppercase stat headline (e.g. '₹80 CR DAY 1?') or ''\",\n" +
                "      \"has_character\": false,\n" +
                "      \"image_prompt\": \"Front-loaded crisp English prompt matching THAT scene's exact visual shot (no text/words/logos)\",\n" +
                "      \"sfx_cue\": \"boom | thunder | sword | fire | whoosh\"\n" +
                "    }\n" +
                "  ]\n" +
                "}"

        const val FULL_SCRIPT_SYSTEM_PROMPT =
            "You are a film director splitting an existing user-written script into fast visual scenes for RS Movie Spot.\n" +
                "CRITICAL FULL SCRIPT RULES:\n" +
                "- DO NOT rewrite, paraphrase, translate, summarize, add, drop, or change EVEN A SINGLE WORD of the user's spoken input.\n" +
                "- Keep exact user words intact and split sequentially into 12 to 18 natural spoken scenes (~6-11 words / ~2.3-3.5s each).\n" +
                "- NEVER split inside a person's full name (e.g. 'Pedro Pascal', 'Robert Downey Jr', 'Russo Brothers'), movie title, or currency number, and NEVER put the end of one sentence plus the start of the next sentence in a partial fragment — split at sentence boundaries (. ! ? ।) or natural comma/conjunction clauses.\n" +
                "- Concatenating all slabs' voiceover_text values with spaces MUST match the user's input word-for-word.\n" +
                "- Never put unescaped double quotes inside JSON strings (use single quotes if needed) and always close both ']' and '}'.\n\n" +
                IMAGE_PROMPT_RULES +
                VOICE_AND_BGM_RULES +
                JSON_SCHEMA_SPEC

        const val TOPIC_SYSTEM_PROMPT =
            "You are an elite YouTube storyteller, movie critic, and film director for RS Movie Spot.\n" +
                "Think like ONE continuous spoken storyteller where every scene's voiceover_text is the NEXT flowing phrase of the same unbroken story!\n\n" +
                "RULE 1 — CONTINUOUS HIGH-RETENTION FAST-PACED SCRIPT (13 TO 16 SCENES):\n" +
                "- Create 13 to 16 ultra-crisp scenes (~7-10 words / ~2.4-3.2s each for Shorts; 14-18 scenes for 16:9 YouTube) with seamless spoken transitions so visuals change every ~3 seconds and NO single image sits static!\n" +
                "- NEVER split a full name or sentence awkwardly mid-name across slabs.\n" +
                "- Put an explosive 2-second hook in Scene 1 with a bold number or shocking question.\n" +
                "- Use natural spoken punctuation ('!', '?', '...') so TTS pitch rises and falls dramatically.\n" +
                "- When writing in Hindi: use accurate Devanagari spelling (करोड़, बॉक्स ऑफिस, थिएटर, डूम्ज़-डे) and MANDATORY respectful plural pronouns (वे, उनकी, उनका, उन्हें) for deities and heroes — NEVER singular वह/उसकी/उसका.\n" +
                "- Never put unescaped double quotes inside JSON strings (use single quotes if needed) and always close both ']' and '}'.\n\n" +
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
