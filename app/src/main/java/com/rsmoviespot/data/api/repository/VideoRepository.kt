package com.rsmoviespot.data.repository

import android.graphics.BitmapFactory
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
        voiceGender: VoiceGender = VoiceGender.MALE
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
    private val edgeTtsApi: EdgeTtsApi,
    private val okHttpClient: OkHttpClient
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

    private enum class RealVisualIntent {
        PERSON,
        MOVIE_POSTER,
        CHARACTER
    }

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
                    numSteps = 20,
                    guidance = 7.5
                )
            )
        }
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
                runCatching {
                    collectImdbPhotoCandidates(
                        searchTerm = subject,
                        outMap = candidates,
                        preferMovies = true,
                        allowPeople = false
                    )
                }
                runCatching {
                    collectItunesMovieCandidates(
                        searchTerm = subject,
                        outMap = candidates,
                        countries = listOf("IN", "US")
                    )
                }
                runCatching {
                    collectWikipediaSummaryCandidates(
                        pageQuery = "$subject (film)",
                        outMap = candidates,
                        requireEntertainment = true
                    )
                }
                runCatching {
                    collectWikipediaSummaryCandidates(
                        pageQuery = subject,
                        outMap = candidates,
                        requireEntertainment = true
                    )
                }
                runCatching {
                    collectWikipediaSearchCandidates(
                        searchQuery = "$subject film poster",
                        outMap = candidates,
                        requireEntertainment = true
                    )
                }
            }

            RealVisualIntent.CHARACTER -> {
                runCatching {
                    collectWikipediaSummaryCandidates(
                        pageQuery = "$subject (Marvel Cinematic Universe)",
                        outMap = candidates,
                        requireEntertainment = false
                    )
                }
                runCatching {
                    collectWikipediaSummaryCandidates(
                        pageQuery = "$subject (character)",
                        outMap = candidates,
                        requireEntertainment = false
                    )
                }
                runCatching {
                    collectWikipediaSummaryCandidates(
                        pageQuery = subject,
                        outMap = candidates,
                        requireEntertainment = false
                    )
                }
                runCatching {
                    collectImdbPhotoCandidates(
                        searchTerm = subject,
                        outMap = candidates,
                        preferMovies = true,
                        allowPeople = true
                    )
                }
                runCatching {
                    collectWikipediaSearchCandidates(
                        searchQuery = "$subject character",
                        outMap = candidates,
                        requireEntertainment = false
                    )
                }
                runCatching {
                    collectWikipediaSearchCandidates(
                        searchQuery = subject,
                        outMap = candidates,
                        requireEntertainment = false
                    )
                }
                runCatching {
                    collectItunesMovieCandidates(
                        searchTerm = subject,
                        outMap = candidates,
                        countries = listOf("US", "IN")
                    )
                }
                runCatching { collectWikimediaCommonsCandidates(subject, candidates) }
            }

            RealVisualIntent.PERSON -> {
                runCatching {
                    collectImdbPhotoCandidates(
                        searchTerm = subject,
                        outMap = candidates,
                        preferMovies = false,
                        allowPeople = true
                    )
                }
                runCatching {
                    collectWikipediaSummaryCandidates(
                        pageQuery = subject,
                        outMap = candidates,
                        requireEntertainment = false
                    )
                }
                runCatching {
                    collectWikipediaSearchCandidates(
                        searchQuery = subject,
                        outMap = candidates,
                        requireEntertainment = false
                    )
                }
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

            var downloaded = runCatching {
                downloadAndValidateImageUrl(candidate.url, destinationFile)
            }.getOrDefault(false)

            if (!downloaded && candidate.fallbackUrl != null) {
                downloaded = runCatching {
                    downloadAndValidateImageUrl(candidate.fallbackUrl, destinationFile)
                }.getOrDefault(false)
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
        if (movieKeywords.any { lower.contains(it) }) {
            return RealVisualIntent.MOVIE_POSTER
        }

        val characterKeywords = listOf(
            "character", "superhero", "supervillain", "villain", "comic", "anime",
            "marvel", "dc comics", "mcu", "iron man", "ironman", "superman",
            "batman", "spider-man", "spiderman", "thor", "hulk", "captain america",
            "doctor strange", "black panther", "deadpool", "wolverine", "joker",
            "thanos", "loki", "flash", "aquaman", "wonder woman", "venom",
            "ant-man", "hawkeye", "scarlet witch", "black widow", "goku", "naruto",
            "luffy", "krrish", "shaktimaan", "baahubali", "pushpa raj", "rocky bhai"
        )
        if (characterKeywords.any { lower.contains(it) }) {
            return RealVisualIntent.CHARACTER
        }

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

        if (cleaned.isBlank()) {
            cleaned = rawQuery.trim()
        }

        return when (cleaned.lowercase()) {
            "ironman" -> "Iron Man"
            "spiderman" -> "Spider-Man"
            "antman" -> "Ant-Man"
            "xmen", "x men" -> "X-Men"
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
            val typeBoost = when {
                preferMovies && isMovieOrSeries -> 6
                !preferMovies && isPerson -> 6
                isMovieOrSeries || isPerson -> 2
                else -> 0
            }

            outMap.putIfAbsent(
                upgradedUrl,
                PhotoCandidate(
                    url = upgradedUrl,
                    width = finalW,
                    height = finalH,
                    priority = exactTitleBoost + typeBoost,
                    fallbackUrl = fallback
                )
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
                val matchesTitle = queryWords.isEmpty() || queryWords.any { trackName.contains(it) }
                if (!matchesTitle) continue

                val artwork100 = obj["artworkUrl100"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (!artwork100.startsWith("http")) continue

                val artworkHd = artwork100.replace(Regex("""\d+x\d+bb"""), "1400x1400bb")
                val artworkFallback = artwork100.replace(Regex("""\d+x\d+bb"""), "1000x1000bb")
                val exactBoost = if (trackName == queryClean) 5 else if (trackName.startsWith(queryClean)) 3 else 1

                outMap.putIfAbsent(
                    artworkHd,
                    PhotoCandidate(
                        url = artworkHd,
                        width = 933,
                        height = 1400,
                        priority = 4 + exactBoost,
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
                outMap.putIfAbsent(imgUrl, PhotoCandidate(imgUrl, w, h, priority = 4))
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
        val queryObj = root["query"] as? JsonObject ?: return
        val pages = queryObj["pages"] as? JsonObject ?: return

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
                    outMap.putIfAbsent(imgUrl, PhotoCandidate(imgUrl, w, h, priority = 2))
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
        val queryObj = root["query"] as? JsonObject ?: return
        val pages = queryObj["pages"] as? JsonObject ?: return

        for (pageElem in pages.values) {
            val pageObj = pageElem as? JsonObject ?: continue
            val title = pageObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val imageInfo = (pageObj["imageinfo"] as? JsonArray)
                ?.firstOrNull() as? JsonObject ?: continue

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

    private fun fetchJsonString(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WIKIMEDIA_USER_AGENT)
            .header("Accept", "application/json")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    private fun downloadAndValidateImageUrl(url: String, destinationFile: File): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WIKIMEDIA_USER_AGENT)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.isNotBlank() &&
                (!contentType.startsWith("image/") || contentType.contains("svg"))
            ) {
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
            val rawQuery = dto.realPhotoQuery?.trim().orEmpty()
            val realQuery = if (rawQuery.lowercase() in setOf("none", "null", "n/a", "-")) "" else rawQuery
            val visualSource = if (
                dto.visualSource?.trim()?.equals("real_photo", ignoreCase = true) == true &&
                realQuery.isNotBlank()
            ) {
                "real_photo"
            } else {
                "ai_generated"
            }

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
                    real_photo_query = if (visualSource == "real_photo") realQuery else ""
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
                    delay(2000L * (attempt + 1))
                } else {
                    throw wrapped
                }
            } catch (error: IOException) {
                lastError = error
                if (attempt < MAX_API_ATTEMPTS - 1) {
                    delay(2000L * (attempt + 1))
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
        val voiceEmotion: String? = null,
        @SerialName("visual_source")
        val visualSource: String? = null,
        @SerialName("real_photo_query")
        val realPhotoQuery: String? = null
    )

    private companion object {
        const val MAX_API_ATTEMPTS = 5
        const val FULL_SCRIPT_WORD_THRESHOLD = 25
        const val WIKIMEDIA_USER_AGENT = "RSMovieSpot/1.0 (https://rsmoviespot.app)"

        const val SHORTS_IMAGE_WIDTH = 768
        const val SHORTS_IMAGE_HEIGHT = 1344
        const val LANDSCAPE_IMAGE_WIDTH = 1344
        const val LANDSCAPE_IMAGE_HEIGHT = 768

        const val IMAGE_SUFFIX_SHORTS =
            ", single continuous full-bleed live-action IMAX movie still, photorealistic 8k cinema photography, ARRI Alexa 65mm lens, realistic skin pores and costume fabric/metal textures, real-world atmospheric lighting, vertical 9:16 aspect ratio, no text, no watermark, no letters, no UI, no logos, no words"
        const val IMAGE_SUFFIX_LANDSCAPE =
            ", single continuous full-bleed live-action IMAX movie still, photorealistic 8k cinema photography, ARRI Alexa 65mm lens, realistic skin pores and costume fabric/metal textures, real-world atmospheric lighting, horizontal 16:9 aspect ratio, no text, no watermark, no letters, no UI, no logos, no words"

        const val DEFAULT_NEGATIVE_PROMPT =
            "filmstrip, multi-panel, stacked frames, comic panels, grid, storyboard, collage, split screen, cartoon, anime, 3d render, cgi, illustration, painting, drawing, sketch, plastic skin, wax figure, cosplay, toy, action figure, bird, wild animal, abstract, geometric shapes, cubes, blocks, colorful squares, disembodied eye, close-up single eye, text, watermark, signature, logo, letters, words, ui, interface, comment box, subscribe button, social media, website, poster, title card, blurry, low quality, distorted, deformed, extra limbs, extra arms, extra fingers"

        const val IMAGE_PROMPT_RULES =
            "REAL PHOTO / POSTER vs REALISTIC CHARACTER AI IMAGE RULES (VERY IMPORTANT):\n" +
                "- Balance REAL PHOTOS/POSTERS with CONTENT-MATCHED PHOTOREALISTIC AI SCENES:\n" +
                "  1) REAL-WORLD ACTOR / ACTRESS / DIRECTOR named in the scene (e.g., Prabhas, Sandeep Reddy Vanga, Allu Arjun, Ranbir Kapoor, Shah Rukh Khan, Robert Downey Jr.):\n" +
                "     Set visual_source='real_photo' and real_photo_query to ONLY their clean English name (e.g. 'Prabhas').\n" +
                "  2) SPECIFIC MOVIE / WEB SERIES / SHOW highlighted in the scene (e.g., Animal, Kabir Singh, Spirit, Pushpa 2, KGF, Avengers Endgame, Salaar):\n" +
                "     Set visual_source='real_photo' and real_photo_query to '<Clean English Title> movie poster' (e.g. 'Animal movie poster'). NEVER combine actor + movie in one query.\n" +
                "  3) FICTIONAL CHARACTER / SUPERHERO / VILLAIN / ICONIC HERO (e.g., Iron Man, Superman, Batman, Spider-Man, Thanos, Joker, Deadpool, Thor, Goku, Rocky Bhai, Pushpa Raj, Hanuman):\n" +
                "     * For MAX 1 or 2 iconic introduction/reveal scenes of that character, set visual_source='real_photo' and real_photo_query='<Clean English Character Name> character' (e.g. 'Iron Man character' or 'Superman character') to pull their real picture/poster.\n" +
                "     * For ALL STORY, ACTION, BATTLE, DIALOGUE, OR EMOTIONAL SCENES involving that character (what the scene content actually describes!), set visual_source='ai_generated' and real_photo_query='', AND write a hyper-detailed LIVE-ACTION photorealistic image_prompt featuring THAT EXACT CHARACTER in their iconic live-action costume/look performing the EXACT action, emotion, and setting from that scene's voiceover_text!\n" +
                "  4) ALL OTHER SCENES (action sequences, cinema theater crowds, box-office hype, atmospheric shots, final CTA):\n" +
                "     Set visual_source='ai_generated' and real_photo_query=''.\n" +
                "- ALWAYS still write a rich, content-matched live-action fallback image_prompt for EVERY slab (even real_photo slabs).\n\n" +
                "IMAGE PROMPT RULES (PHOTOREALISTIC & CONTENT-MATCHED):\n" +
                "- EVERY image_prompt MUST visually match the EXACT action, location, mood, and character state of that specific scene's voiceover_text — NEVER write generic unrelated scenes!\n" +
                "- When a fictional/superhero/mythological character is in the scene, explicitly name the character and describe realistic movie costume textures (scratched painted metal armor, woven suit fabric, real human skin pores, sweat, rain, dust, sparks, cinematic depth of field) so it looks like a real live-action movie frame — NEVER cartoon, CGI, or toy-like:\n" +
                "  * Iron Man example (flying/battle content): 'photorealistic live-action movie still of Iron Man in battle-scratched metallic red and gold Mark 85 armor hovering over a burning city street at night, glowing blue chest arc reactor and palm repulsor energy, flying embers, dramatic shadows'\n" +
                "  * Superman example (rain/intensity content): 'photorealistic live-action movie still of Superman with dark hair and intense expression wearing dark textured blue suit and flowing heavy red wool cape floating in stormy night rain amidst lightning and city skyscrapers'\n" +
                "  * Cop / Indian action hero example: 'photorealistic live-action Indian cinema still of a rugged bearded police officer in sweat-stained khaki uniform standing in heavy monsoon rain beside glowing red and blue police siren lights'\n" +
                "  * Theater / box office hype example: 'massive roaring Indian cinema audience cheering inside a grand packed movie hall facing a bright silver screen'\n" +
                "- NEVER write real living actor names (e.g. Prabhas, Ranbir, Tom Cruise) inside image_prompt — keep real living celebrities in real_photo, and describe Indian hero action shots physically.\n" +
                "- NEVER write movie title words with literal meanings in image_prompt or visual_style — specifically NEVER write the words 'Animal', 'Spirit', 'Beast', 'Tiger', 'Lion', 'Ghost', 'King' (otherwise the image model draws actual wild animals or birds!).\n" +
                "- NEVER write abstract phrases like 'dark violence', 'blood rivers', 'box office', 'records', 'explosion of red and blue blocks', or 'close-up eyes'.\n" +
                "- NEVER include words like 'comment box', 'subscribe', 'website', 'UI', 'social media', 'poster', 'title card', or 'text' inside image_prompt.\n" +
                "- For the final call-to-action scene, set visual_source='ai_generated' and describe an epic wide hero shot matching the story's main character or hero under dramatic cinema spotlights.\n" +
                "- Set has_character=true whenever the main character/hero is physically visible in frame; set has_character=false only for pure crowds, empty exteriors, or theater audience shots.\n" +
                "- Vary camera framing across consecutive scenes (wide establishing shot, medium hero shot, dramatic over-the-shoulder action, rain-soaked silhouette, low-angle power shot — NEVER extreme close-up single eye).\n\n"

        const val VOICE_AND_BGM_RULES =
            "VOICE EMOTION & BGM RULES:\n" +
                "- Pick bgm_mood: 'comedy_roast', 'epic_action', 'suspense_thriller', 'emotional_sad', 'devotional', 'sci_fi_tech', 'motivational', or 'horror_dark'.\n" +
                "- For each scene, set voice_emotion to shift delivery dynamically: 'hook' (opening punch), 'suspense' (mystery/tension), 'climax' (intense/goosebumps), 'question' (engaging question/CTA), or 'normal'.\n" +
                "- Pick valid Edge TTS voice_name (e.g. hi-IN-MadhurNeural, hi-IN-SwaraNeural, en-IN-PrabhatNeural, en-IN-NeerjaNeural, en-US-AndrewNeural, en-US-AvaNeural, mr-IN-ManoharNeural, mr-IN-AarohiNeural, ta-IN-ValluvarNeural, ta-IN-PallaviNeural, etc.).\n\n"

        const val JSON_SCHEMA_SPEC =
            "Return ONLY valid JSON matching this schema:\n" +
                "{\n" +
                "  \"character_bible\": \"Concrete physical live-action costume and appearance of the story's main character or action hero in English (no real living actor names)\",\n" +
                "  \"visual_style\": \"Live-action photorealistic IMAX cinema look, real-world textures, dramatic warm amber and deep shadow lighting (NO Unreal Engine, NO abstract colors)\",\n" +
                "  \"bgm_mood\": \"comedy_roast | epic_action | suspense_thriller | emotional_sad | devotional | sci_fi_tech | motivational | horror_dark\",\n" +
                "  \"voice_name\": \"Edge TTS voice shortName matching language and gender\",\n" +
                "  \"slabs\": [\n" +
                "    {\n" +
                "      \"voiceover_text\": \"Narration segment in target language\",\n" +
                "      \"voice_emotion\": \"hook | suspense | climax | question | normal\",\n" +
                "      \"visual_source\": \"real_photo | ai_generated\",\n" +
                "      \"real_photo_query\": \"Clean query: 'Prabhas' (actor), 'Animal movie poster' (film), or 'Iron Man character' (hero intro) if visual_source='real_photo', else ''\",\n" +
                "      \"has_character\": true,\n" +
                "      \"image_prompt\": \"Photorealistic live-action English scene matching exact voiceover_text action and character costume (NO 'Animal'/'Spirit'/cubes/eyes/UI/posters/living actor names)\",\n" +
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
