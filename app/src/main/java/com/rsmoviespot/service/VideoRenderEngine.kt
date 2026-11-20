package com.rsmoviespot.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.rsmoviespot.data.repository.VideoRepository
import com.rsmoviespot.domain.models.InputMode
import com.rsmoviespot.domain.models.VideoLanguage
import com.rsmoviespot.domain.models.VideoScriptResponse
import com.rsmoviespot.domain.models.VideoSlab
import com.rsmoviespot.domain.models.VoiceGender
import com.rsmoviespot.domain.models.VoiceTone
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class VideoRenderResult(
    val galleryUri: Uri,
    val thumbnailUri: Uri? = null,
    val script: VideoScriptResponse,
    val segmentCount: Int,
    val width: Int,
    val height: Int
)

private data class OverlaySpec(
    val file: File,
    val xExpr: String,
    val yExpr: String,
    val enableExpr: String? = null
)

private data class PopCaptionChunk(
    val file: File,
    val stripHeight: Int,
    val startSeconds: Double,
    val endSeconds: Double
)

private data class PreparedSceneVisual(
    val rawClipFile: File? = null,
    val imageFile1: File? = null,
    val imageFile2: File? = null,
    val transitionFrameFile: File? = null,
    val isLiveVisual: Boolean = false
)

private enum class BgmStyle {
    COMEDY_ROAST,
    EPIC_ACTION,
    SUSPENSE_THRILLER,
    EMOTIONAL_SAD,
    DEVOTIONAL,
    SCI_FI_TECH,
    MOTIVATIONAL,
    HORROR_DARK
}

class VideoRenderEngine @Inject constructor(
    private val videoRepository: VideoRepository,
    @ApplicationContext private val context: Context
) {
    suspend fun render(
        topic: String,
        isShorts: Boolean,
        customBgmUri: Uri? = null,
        preGeneratedScript: VideoScriptResponse? = null,
        language: VideoLanguage = VideoLanguage.AUTO,
        voiceGender: VoiceGender = VoiceGender.MALE,
        inputMode: InputMode = InputMode.AUTO,
        voiceTone: VoiceTone = VoiceTone.AUTO,
        progress: (Int, String) -> Unit = { _, _ -> }
    ): VideoRenderResult = withContext(Dispatchers.IO) {
        require(topic.isNotBlank()) { "Topic must not be blank." }

        val width = if (isShorts) SHORTS_WIDTH else LANDSCAPE_WIDTH
        val height = if (isShorts) SHORTS_HEIGHT else LANDSCAPE_HEIGHT
        val renderDirectory = File(
            context.cacheDir,
            "video-render-${UUID.randomUUID()}"
        )

        if (!renderDirectory.exists() && !renderDirectory.mkdirs()) {
            throw IOException("Could not create render directory.")
        }

        try {
            val script = if (preGeneratedScript != null && preGeneratedScript.slabs.isNotEmpty()) {
                progress(4, "Using your approved script…")
                preGeneratedScript
            } else {
                progress(2, "Generating story script…")
                videoRepository.generateScript(
                    topic = topic,
                    isShorts = isShorts,
                    language = language,
                    voiceGender = voiceGender,
                    inputMode = inputMode,
                    voiceTone = voiceTone
                )
            }
            require(script.slabs.isNotEmpty()) { "The generated script has no slabs." }

            val activeVoiceName = if (language != VideoLanguage.AUTO) {
                language.resolveVoice(voiceGender)
            } else {
                enforceVoiceGender(script.voice_name, voiceGender)
            }
            val wantsMaleVoice = voiceGender == VoiceGender.MALE

            val baseSeed = (topic.trim().lowercase().hashCode() and 0x7FFFFFFF)
                .coerceAtLeast(1000)

            progress(7, "Recording expressive voiceover…")
            val trimmedVoiceWavs = synthesizeAndTrimVoiceClips(
                renderDirectory = renderDirectory,
                slabs = script.slabs,
                voiceName = activeVoiceName,
                wantsMaleVoice = wantsMaleVoice,
                voiceTone = voiceTone,
                progress = progress
            )

            val voiceListFile = File(renderDirectory, "voice_parts.txt")
            voiceListFile.writeText(
                trimmedVoiceWavs.joinToString("\n") { "file '${escapeConcatPath(it.absolutePath)}'" } + "\n"
            )
            val fullVoiceFile = File(renderDirectory, "full_voice.wav")
            runFfmpeg(createConcatArguments(voiceListFile, fullVoiceFile))

            val sceneDurations = trimmedVoiceWavs.map { wavFile ->
                getPcmWavDurationSeconds(wavFile).coerceAtLeast(0.60)
            }.toMutableList()
            sceneDurations[sceneDurations.lastIndex] =
                sceneDurations.last() + AUDIO_TAIL_PADDING_SECONDS

            val sceneFramesList = sceneDurations.map { dur ->
                (dur * FRAME_RATE).roundToInt().coerceAtLeast(MIN_SCENE_FRAMES)
            }
            val totalFrames = sceneFramesList.sum()
            val exactVideoDuration = totalFrames.toDouble() / FRAME_RATE

            val bgmBedFile = prepareBgmBed(
                renderDirectory = renderDirectory,
                mood = script.bgm_mood,
                customBgmUri = customBgmUri,
                bgmSeed = baseSeed
            )

            val watermarkPng = File(renderDirectory, "hud_watermark.png").also {
                renderWatermarkPng(it, isShorts)
            }
            val subscribeEndCardPng = File(renderDirectory, "hud_subscribe.png").also {
                renderSubscribeEndCardPng(it, isShorts)
            }

            progress(18, "Fetching HD visuals & clips…")
            val preparedVisuals = prepareAllSceneVisuals(
                renderDirectory = renderDirectory,
                slabs = script.slabs,
                characterBible = script.character_bible,
                visualStyle = script.visual_style,
                isShorts = isShorts,
                width = width,
                height = height,
                baseSeed = baseSeed,
                progress = progress
            )

            val videoSegmentFiles = mutableListOf<File>()
            val slabCount = script.slabs.size
            var coverBaseImageFile: File? = null
            var prevTransitionFile: File? = null

            script.slabs.forEachIndexed { index, slab ->
                val encodeProgress = 48 + (index * 36 / slabCount)
                progress(
                    encodeProgress.coerceIn(48, 84),
                    "Rendering smooth scene ${index + 1} of $slabCount…"
                )

                val videoSegmentFile = File(renderDirectory, "vseg_${index + 1}.mp4")
                val segmentExactDuration = sceneFramesList[index].toDouble() / FRAME_RATE
                val visual = preparedVisuals[index]

                val popCaptions = runCatching {
                    createPopCaptionOverlays(
                        renderDirectory = renderDirectory,
                        sceneIndex = index,
                        fullSceneText = slab.voiceover_text,
                        sceneDurationSeconds = segmentExactDuration,
                        videoWidth = width,
                        isShorts = isShorts
                    )
                }.getOrDefault(emptyList())

                val statBadgePng = if (slab.stat_badge.isNotBlank()) {
                    val badgeFile = File(renderDirectory, "badge_${index + 1}.png")
                    if (renderStatBadgePng(slab.stat_badge, width, isShorts, badgeFile)) badgeFile else null
                } else {
                    null
                }

                val overlaySpecs = buildSceneOverlaySpecs(
                    popCaptions = popCaptions,
                    statBadgePng = statBadgePng,
                    watermarkPng = watermarkPng,
                    subscribeEndCardPng = if (index == slabCount - 1) subscribeEndCardPng else null,
                    sceneDurationSeconds = segmentExactDuration,
                    videoWidth = width,
                    videoHeight = height,
                    isShorts = isShorts
                )

                var encodedClip = false
                val rawClipFile = visual.rawClipFile
                if (rawClipFile != null && rawClipFile.exists()) {
                    if (index == 0 && coverBaseImageFile == null) {
                        val rawCover = File(renderDirectory, "cover_raw_clip.png")
                        val gotRawCover = runCatching {
                            runFfmpeg(
                                arrayOf(
                                    "-y", "-ss", "0.35",
                                    "-i", rawClipFile.absolutePath,
                                    "-vframes", "1",
                                    rawCover.absolutePath
                                )
                            )
                            rawCover.exists() && rawCover.length() > 2048L
                        }.getOrDefault(false)
                        if (gotRawCover) coverBaseImageFile = rawCover
                    }

                    encodedClip = runCatching {
                        runFfmpeg(
                            createVideoClipSegmentArguments(
                                clipFile = rawClipFile,
                                prevTransitionFile = prevTransitionFile,
                                overlaySpecs = overlaySpecs,
                                outputFile = videoSegmentFile,
                                width = width,
                                height = height,
                                frames = sceneFramesList[index],
                                sceneIndex = index,
                                totalScenes = slabCount
                            )
                        )
                        videoSegmentFile.exists() && videoSegmentFile.length() > 2048L
                    }.getOrDefault(false)

                    if (encodedClip) {
                        val tailRaw = File(renderDirectory, "clip_tail_raw_${index + 1}.png")
                        val tailReady = File(renderDirectory, "clip_tail_${index + 1}.png")
                        val gotTail = runCatching {
                            runFfmpeg(
                                arrayOf(
                                    "-y", "-sseof", "-0.15",
                                    "-i", rawClipFile.absolutePath,
                                    "-vframes", "1",
                                    tailRaw.absolutePath
                                )
                            )
                            tailRaw.exists() &&
                                composeStudioFrameFromRealPhoto(tailRaw, tailReady, width, height)
                        }.getOrDefault(false)
                        tailRaw.delete()
                        prevTransitionFile = if (gotTail) tailReady else visual.transitionFrameFile
                    }
                    rawClipFile.delete()
                }

                if (!encodedClip) {
                    val imageFile1 = visual.imageFile1 ?: File(renderDirectory, "fb_emerg_${index + 1}.png").also {
                        createFallbackCinemaFrame(it, width, height, index)
                    }
                    if (index == 0 && coverBaseImageFile == null) {
                        coverBaseImageFile = File(renderDirectory, "cover_base.png").also {
                            imageFile1.copyTo(it, overwrite = true)
                        }
                    }

                    val layoutMode = slab.layout_mode.lowercase().trim()
                    val imageFile2 = visual.imageFile2

                    if ((layoutMode == "split" || layoutMode == "flip") && imageFile2 != null && imageFile2.exists()) {
                        if (layoutMode == "split") {
                            composeSplitFrame(imageFile1, imageFile2, imageFile1, width, height)
                            runFfmpeg(
                                createVideoOnlySegmentArguments(
                                    sceneIndex = index,
                                    totalScenes = slabCount,
                                    imageFile = imageFile1,
                                    prevTransitionFile = prevTransitionFile,
                                    overlaySpecs = overlaySpecs,
                                    outputFile = videoSegmentFile,
                                    width = width,
                                    height = height,
                                    segmentFrames = sceneFramesList[index]
                                )
                            )
                            prevTransitionFile = imageFile1
                        } else {
                            runFfmpeg(
                                createFlipVideoSegmentArguments(
                                    sceneIndex = index,
                                    totalScenes = slabCount,
                                    imageFile1 = imageFile1,
                                    imageFile2 = imageFile2,
                                    prevTransitionFile = prevTransitionFile,
                                    overlaySpecs = overlaySpecs,
                                    outputFile = videoSegmentFile,
                                    width = width,
                                    height = height,
                                    segmentFrames = sceneFramesList[index]
                                )
                            )
                            prevTransitionFile = imageFile2
                        }
                    } else {
                        runFfmpeg(
                            createVideoOnlySegmentArguments(
                                sceneIndex = index,
                                totalScenes = slabCount,
                                imageFile = imageFile1,
                                prevTransitionFile = prevTransitionFile,
                                overlaySpecs = overlaySpecs,
                                outputFile = videoSegmentFile,
                                width = width,
                                height = height,
                                segmentFrames = sceneFramesList[index]
                            )
                        )
                        prevTransitionFile = imageFile1
                    }
                }

                videoSegmentFiles += videoSegmentFile
            }

            progress(86, "Syncing scenes with voiceover…")
            val concatList = File(renderDirectory, "segments.txt")
            concatList.writeText(
                videoSegmentFiles.joinToString(separator = "\n") { file ->
                    "file '${escapeConcatPath(file.absolutePath)}'"
                } + "\n"
            )

            val silentVideoFile = File(renderDirectory, "silent_video.mp4")
            runFfmpeg(createConcatArguments(concatList, silentVideoFile))

            if (coverBaseImageFile == null) {
                val extractedCover = File(renderDirectory, "cover_extracted.png")
                val extracted = runCatching {
                    runFfmpeg(
                        arrayOf(
                            "-y", "-ss", "0.5",
                            "-i", silentVideoFile.absolutePath,
                            "-vframes", "1",
                            extractedCover.absolutePath
                        )
                    )
                    extractedCover.exists() && extractedCover.length() > 2048L
                }.getOrDefault(false)
                if (extracted) coverBaseImageFile = extractedCover
            }

            progress(90, "Mastering emotional voice & studio BGM…")
            val mixedAudioFile = File(renderDirectory, "final_audio.m4a")
            val finalAudioReady = mixAudioTrackSafely(
                renderDirectory = renderDirectory,
                fullVoiceFile = fullVoiceFile,
                bgmBedFile = bgmBedFile,
                sfxTrackFile = null,
                voiceTone = voiceTone,
                totalDurationSeconds = exactVideoDuration,
                outputFile = mixedAudioFile
            )

            progress(93, "Finalizing video file…")
            val finalFile = File(renderDirectory, "final_video.mp4")
            muxFinalVideo(
                silentVideoFile = silentVideoFile,
                mixedAudioFile = finalAudioReady,
                fallbackVoiceFile = fullVoiceFile,
                totalDurationSeconds = exactVideoDuration,
                outputFile = finalFile
            )

            progress(96, "Saving video & cover thumbnail…")
            val exportTitle = script.youtube_title.ifBlank { topic }
            val actionUri = publishToMediaStore(finalFile, exportTitle)
            val thumbnailUri = coverBaseImageFile?.let { baseImg ->
                runCatching {
                    val thumbJpg = File(renderDirectory, "cover_thumb.jpg")
                    renderCoverThumbnailJpg(
                        baseImageFile = baseImg,
                        headlineText = exportTitle,
                        isShorts = isShorts,
                        outputFile = thumbJpg
                    )
                    publishThumbnailToMediaStore(thumbJpg, exportTitle)
                }.getOrNull()
            }

            progress(100, "Video ready")

            VideoRenderResult(
                galleryUri = actionUri,
                thumbnailUri = thumbnailUri,
                script = script,
                segmentCount = videoSegmentFiles.size,
                width = width,
                height = height
            )
        } finally {
            renderDirectory.deleteRecursively()
        }
    }

    private fun enforceVoiceGender(rawVoiceName: String, requestedGender: VoiceGender): String {
        val candidate = rawVoiceName.trim()
        if (candidate.isBlank()) return VideoLanguage.HINDI.resolveVoice(requestedGender)

        val matchedLang = VideoLanguage.values().firstOrNull { lang ->
            lang != VideoLanguage.AUTO && (
                lang.maleVoice.equals(candidate, ignoreCase = true) ||
                    lang.femaleVoice.equals(candidate, ignoreCase = true)
                )
        }
        if (matchedLang != null) {
            return matchedLang.resolveVoice(requestedGender)
        }

        val localePrefix = candidate.split('-').take(2).joinToString("-")
        if (localePrefix.isNotBlank()) {
            val langByPrefix = VideoLanguage.values().firstOrNull { lang ->
                lang != VideoLanguage.AUTO &&
                    lang.maleVoice.startsWith("$localePrefix-", ignoreCase = true)
            }
            if (langByPrefix != null) {
                return langByPrefix.resolveVoice(requestedGender)
            }
        }

        return VideoLanguage.HINDI.resolveVoice(requestedGender)
    }

    private suspend fun prepareAllSceneVisuals(
        renderDirectory: File,
        slabs: List<VideoSlab>,
        characterBible: String,
        visualStyle: String,
        isShorts: Boolean,
        width: Int,
        height: Int,
        baseSeed: Int,
        progress: (Int, String) -> Unit
    ): List<PreparedSceneVisual> = coroutineScope {
        val total = slabs.size.coerceAtLeast(1)
        val completedCount = AtomicInteger(0)
        val semaphore = Semaphore(2)
        val usedRealPhotoUrls = ConcurrentHashMap.newKeySet<String>()
        val usedClipUrls = ConcurrentHashMap.newKeySet<String>()

        val initialResults = slabs.mapIndexed { index, slab ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val prepared = withTimeoutOrNull(95_000L) {
                        val isVideoClip = slab.visual_source.equals("video_clip", ignoreCase = true) ||
                            slab.visual_source.equals("meme_clip", ignoreCase = true)

                        var rawClip: File? = null
                        if (isVideoClip && slab.real_photo_query.isNotBlank()) {
                            val candidateClip = File(renderDirectory, "clip_raw_${index + 1}.mp4")
                            val downloaded = runCatching {
                                videoRepository.downloadInternetVideoClip(
                                    query = slab.real_photo_query,
                                    isMeme = slab.visual_source.equals("meme_clip", ignoreCase = true),
                                    usedUrls = usedClipUrls,
                                    destinationFile = candidateClip
                                )
                            }.getOrDefault(false)
                            if (downloaded) rawClip = candidateClip else candidateClip.delete()
                        }

                        if (rawClip != null) {
                            PreparedSceneVisual(rawClipFile = rawClip, isLiveVisual = true)
                        } else {
                            val layoutMode = slab.layout_mode.lowercase().trim()
                            val primaryPair = resolveSceneImageFileOrNull(
                                renderDirectory = renderDirectory,
                                tag = "${index + 1}_1",
                                visualSource = if (isVideoClip) "real_photo" else slab.visual_source,
                                realQuery = slab.real_photo_query,
                                scenePrompt = slab.image_prompt,
                                characterBible = characterBible,
                                visualStyle = visualStyle,
                                hasCharacter = slab.has_character,
                                isShorts = isShorts,
                                width = width,
                                height = height,
                                seed = baseSeed + (index * 37),
                                usedUrls = usedRealPhotoUrls,
                                sceneIndex = index
                            )

                            val hasSecondPrompt = slab.image_prompt_2.isNotBlank() ||
                                (slab.visual_source_2.equals("real_photo", ignoreCase = true) &&
                                    slab.real_photo_query_2.isNotBlank())

                            val img2 = if ((layoutMode == "split" || layoutMode == "flip") && hasSecondPrompt) {
                                resolveSceneImageFileOrNull(
                                    renderDirectory = renderDirectory,
                                    tag = "${index + 1}_2",
                                    visualSource = slab.visual_source_2.ifBlank { "ai_generated" },
                                    realQuery = slab.real_photo_query_2,
                                    scenePrompt = slab.image_prompt_2.ifBlank { slab.image_prompt },
                                    characterBible = characterBible,
                                    visualStyle = visualStyle,
                                    hasCharacter = slab.has_character,
                                    isShorts = isShorts,
                                    width = width,
                                    height = height,
                                    seed = baseSeed + (index * 37) + 19,
                                    usedUrls = usedRealPhotoUrls,
                                    sceneIndex = index
                                )?.first
                            } else {
                                null
                            }

                            if (primaryPair != null) {
                                PreparedSceneVisual(
                                    imageFile1 = primaryPair.first,
                                    imageFile2 = img2,
                                    transitionFrameFile = img2 ?: primaryPair.first,
                                    isLiveVisual = true
                                )
                            } else {
                                PreparedSceneVisual(isLiveVisual = false)
                            }
                        }
                    } ?: PreparedSceneVisual(isLiveVisual = false)

                    val done = completedCount.incrementAndGet()
                    val pct = 18 + (done * 28 / total)
                    progress(pct.coerceIn(18, 46), "Prepared visual $done of $total…")
                    prepared
                }
            }
        }.awaitAll().toMutableList()

        val validImagePool = initialResults.mapNotNull { it.imageFile1?.takeIf { f -> f.exists() && f.length() > 2048L } }
        var globalFallbackImg: File? = null

        for (i in initialResults.indices) {
            if (initialResults[i].isLiveVisual) continue

            val reusedSource = if (validImagePool.isNotEmpty()) {
                val neighborOffset = listOf(1, -1, 2, -2, 3, -3)
                    .mapNotNull { delta -> initialResults.getOrNull(i + delta)?.imageFile1 }
                    .firstOrNull { it.exists() && it.length() > 2048L }
                neighborOffset ?: validImagePool[i % validImagePool.size]
            } else {
                null
            }

            val cloneTarget = File(renderDirectory, "slab_${i + 1}_reuse.png")
            if (reusedSource != null && reusedSource.exists()) {
                reusedSource.copyTo(cloneTarget, overwrite = true)
            } else {
                val baseFallback = globalFallbackImg ?: File(renderDirectory, "slab_fb_master.png").also {
                    createFallbackCinemaFrame(it, width, height, i)
                    globalFallbackImg = it
                }
                baseFallback.copyTo(cloneTarget, overwrite = true)
            }
            initialResults[i] = PreparedSceneVisual(
                imageFile1 = cloneTarget,
                transitionFrameFile = cloneTarget,
                isLiveVisual = false
            )
        }

        initialResults
    }

    private suspend fun resolveSceneImageFileOrNull(
        renderDirectory: File,
        tag: String,
        visualSource: String,
        realQuery: String,
        scenePrompt: String,
        characterBible: String,
        visualStyle: String,
        hasCharacter: Boolean,
        isShorts: Boolean,
        width: Int,
        height: Int,
        seed: Int,
        usedUrls: MutableSet<String>,
        sceneIndex: Int
    ): Pair<File, Boolean>? {
        val imageFile = File(renderDirectory, "slab_$tag.png")
        val wantsRealPhoto = visualSource.equals("real_photo", ignoreCase = true) && realQuery.isNotBlank()

        if (wantsRealPhoto) {
            val rawRealFile = File(renderDirectory, "real_raw_$tag.img")
            val downloaded = runCatching {
                videoRepository.downloadRealPhoto(
                    query = realQuery,
                    usedUrls = usedUrls,
                    destinationFile = rawRealFile,
                    preferPortrait = isShorts
                )
            }.getOrDefault(false)

            val prepared = if (downloaded) {
                runCatching {
                    composeStudioFrameFromRealPhoto(rawRealFile, imageFile, width, height) &&
                        !isMostlyBlackOrCorrupt(imageFile)
                }.getOrDefault(false)
            } else {
                false
            }
            rawRealFile.delete()
            if (prepared) return imageFile to true
        }

        val primaryPrompt = buildSceneImagePrompt(
            characterBible = characterBible,
            visualStyle = visualStyle,
            scenePrompt = scenePrompt,
            hasCharacter = hasCharacter
        )

        val primaryOk = runCatching {
            videoRepository.generateImageForSlab(
                prompt = primaryPrompt,
                isShorts = isShorts,
                seed = seed
            ).use { body ->
                imageFile.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }
            validateImageFile(imageFile, sceneIndex + 1)
            !isMostlyBlackOrCorrupt(imageFile)
        }.getOrDefault(false)

        if (primaryOk) return imageFile to true

        val retryOk = runCatching {
            val safePrompt = buildTopicSafeRetryPrompt(scenePrompt, visualStyle)
            videoRepository.generateImageForSlab(
                prompt = safePrompt,
                isShorts = isShorts,
                seed = seed + 101
            ).use { body ->
                imageFile.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }
            validateImageFile(imageFile, sceneIndex + 1)
            !isMostlyBlackOrCorrupt(imageFile)
        }.getOrDefault(false)

        if (retryOk) return imageFile to true

        imageFile.delete()
        return null
    }

    private fun buildTopicSafeRetryPrompt(scenePrompt: String, visualStyle: String): String {
        val coreWords = sanitizePromptText(scenePrompt)
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .take(12)
            .joinToString(" ")
            .ifBlank { "dramatic scenic storytelling moment" }

        val styleHint = sanitizePromptText(visualStyle)
            .split(Regex("\\s+"))
            .take(6)
            .joinToString(" ")
            .ifBlank { "raw 35mm DSLR photograph" }

        return "$coreWords, clean crisp lighting, shallow depth of field, $styleHint"
    }

    private fun isMostlyBlackOrCorrupt(imageFile: File): Boolean {
        if (!imageFile.exists() || imageFile.length() < 2048L) return true
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeFile(imageFile.absolutePath, opts) ?: return true
        return try {
            if (bmp.width < 16 || bmp.height < 16) return true
            var darkCount = 0
            var totalSamples = 0
            val stepX = (bmp.width / 12).coerceAtLeast(1)
            val stepY = (bmp.height / 12).coerceAtLeast(1)
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) {
                    val px = bmp.getPixel(x, y)
                    val lum = (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000
                    if (lum < 14) darkCount++
                    totalSamples++
                    x += stepX
                }
                y += stepY
            }
            totalSamples == 0 || (darkCount.toFloat() / totalSamples.toFloat()) > 0.93f
        } finally {
            bmp.recycle()
        }
    }

    private fun createFallbackCinemaFrame(
        outputFile: File,
        width: Int,
        height: Int,
        seedOffset: Int
    ) {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            val topColor = if (seedOffset % 2 == 0) 0xFF281216.toInt() else 0xFF102032.toInt()
            val bottomColor = 0xFF0A0D12.toInt()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    topColor, bottomColor, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFA826 }
            canvas.drawCircle(width * 0.5f, height * 0.38f, width * 0.42f, glowPaint)
            outputFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
        } finally {
            bmp.recycle()
        }
    }

    private fun composeSplitFrame(
        file1: File,
        file2: File,
        outputFile: File,
        width: Int,
        height: Int
    ) {
        val b1 = BitmapFactory.decodeFile(file1.absolutePath) ?: return
        val b2 = BitmapFactory.decodeFile(file2.absolutePath)
        if (b2 == null) {
            b1.recycle()
            return
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val divider = Paint().apply { color = 0xFFFFE135.toInt() }
            if (height > width) {
                val halfH = height / 2
                drawCenterCropBitmap(canvas, b1, Rect(0, 0, width, halfH - 3), paint)
                drawCenterCropBitmap(canvas, b2, Rect(0, halfH + 3, width, height), paint)
                canvas.drawRect(0f, (halfH - 3).toFloat(), width.toFloat(), (halfH + 3).toFloat(), divider)
            } else {
                val halfW = width / 2
                drawCenterCropBitmap(canvas, b1, Rect(0, 0, halfW - 3, height), paint)
                drawCenterCropBitmap(canvas, b2, Rect(halfW + 3, 0, width, height), paint)
                canvas.drawRect((halfW - 3).toFloat(), 0f, (halfW + 3).toFloat(), height.toFloat(), divider)
            }
            outputFile.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 96, it) }
        } finally {
            b1.recycle()
            b2.recycle()
            out.recycle()
        }
    }

    private fun drawCenterCropBitmap(canvas: Canvas, src: Bitmap, dst: Rect, paint: Paint) {
        val srcAspect = src.width.toFloat() / src.height.toFloat()
        val dstAspect = dst.width().toFloat() / dst.height().toFloat()
        val srcRect = if (srcAspect > dstAspect) {
            val cropW = (src.height * dstAspect).roundToInt().coerceIn(1, src.width)
            val left = (src.width - cropW) / 2
            Rect(left, 0, left + cropW, src.height)
        } else {
            val cropH = (src.width / dstAspect).roundToInt().coerceIn(1, src.height)
            val top = ((src.height - cropH) * 0.08f).roundToInt().coerceIn(0, src.height - cropH)
            Rect(0, top, src.width, top + cropH)
        }
        canvas.drawBitmap(src, srcRect, dst, paint)
    }

    private fun composeStudioFrameFromRealPhoto(
        rawFile: File,
        outputFile: File,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean {
        val srcBitmap = BitmapFactory.decodeFile(rawFile.absolutePath) ?: return false
        if (srcBitmap.width <= 0 || srcBitmap.height <= 0) {
            srcBitmap.recycle()
            return false
        }

        var tinyBitmap: Bitmap? = null
        var outBitmap: Bitmap? = null
        try {
            val srcW = srcBitmap.width
            val srcH = srcBitmap.height
            val srcAspect = srcW.toFloat() / srcH.toFloat()
            val dstAspect = targetWidth.toFloat() / targetHeight.toFloat()

            outBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            if (targetHeight > targetWidth && srcAspect in 0.50f..0.78f) {
                val cropRect = if (srcAspect > dstAspect) {
                    val cropW = (srcH * dstAspect).roundToInt().coerceIn(1, srcW)
                    val left = (srcW - cropW) / 2
                    Rect(left, 0, left + cropW, srcH)
                } else {
                    val cropH = (srcW / dstAspect).roundToInt().coerceIn(1, srcH)
                    val top = ((srcH - cropH) * 0.10f).roundToInt().coerceIn(0, srcH - cropH)
                    Rect(0, top, srcW, top + cropH)
                }
                canvas.drawBitmap(srcBitmap, cropRect, Rect(0, 0, targetWidth, targetHeight), paint)
            } else {
                val bgCropRect = if (srcAspect > dstAspect) {
                    val cropW = (srcH * dstAspect).roundToInt().coerceIn(1, srcW)
                    val left = (srcW - cropW) / 2
                    Rect(left, 0, left + cropW, srcH)
                } else {
                    val cropH = (srcW / dstAspect).roundToInt().coerceIn(1, srcH)
                    val top = (srcH - cropH) / 2
                    Rect(0, top, srcW, top + cropH)
                }

                val blurW = (targetWidth / 24).coerceAtLeast(32)
                val blurH = (targetHeight / 24).coerceAtLeast(32)
                tinyBitmap = Bitmap.createBitmap(blurW, blurH, Bitmap.Config.ARGB_8888)
                Canvas(tinyBitmap).drawBitmap(
                    srcBitmap,
                    bgCropRect,
                    Rect(0, 0, blurW, blurH),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )

                canvas.drawBitmap(tinyBitmap, null, Rect(0, 0, targetWidth, targetHeight), paint)
                canvas.drawColor(0x45000000)

                val maxW = targetWidth * 0.92f
                val maxH = targetHeight * 0.68f
                val scale = minOf(maxW / srcW.toFloat(), maxH / srcH.toFloat())
                val drawW = srcW * scale
                val drawH = srcH * scale
                val left = (targetWidth - drawW) / 2f
                val top = ((targetHeight - drawH) * 0.36f).coerceAtLeast(110f)
                canvas.drawBitmap(srcBitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
            }

            outputFile.outputStream().use { out ->
                outBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            return outputFile.exists() && outputFile.length() > 1024L
        } finally {
            tinyBitmap?.recycle()
            outBitmap?.recycle()
            srcBitmap.recycle()
        }
    }

    private fun buildSceneOverlaySpecs(
        popCaptions: List<PopCaptionChunk>,
        statBadgePng: File?,
        watermarkPng: File,
        subscribeEndCardPng: File?,
        sceneDurationSeconds: Double,
        videoWidth: Int,
        videoHeight: Int,
        isShorts: Boolean
    ): List<OverlaySpec> {
        val specs = mutableListOf<OverlaySpec>()
        val lastIdx = popCaptions.lastIndex

        popCaptions.forEachIndexed { idx, chunk ->
            val overlayY = if (isShorts) {
                (videoHeight - chunk.stripHeight - 360).coerceAtLeast(0)
            } else {
                (videoHeight - chunk.stripHeight - 55).coerceAtLeast(0)
            }
            val st = String.format(Locale.US, "%.3f", chunk.startSeconds)
            val en = String.format(Locale.US, "%.3f", chunk.endSeconds)
            val enableRule = if (idx == lastIdx) {
                "gte(t,$st)"
            } else {
                "gte(t,$st)*lt(t,$en)"
            }
            specs += OverlaySpec(
                file = chunk.file,
                xExpr = "0",
                yExpr = overlayY.toString(),
                enableExpr = enableRule
            )
        }

        if (statBadgePng != null && statBadgePng.exists()) {
            val badgeY = if (isShorts) 210 else 65
            specs += OverlaySpec(file = statBadgePng, xExpr = "(W-w)/2", yExpr = badgeY.toString())
        }

        val wmX = if (isShorts) "42" else "48"
        val wmY = if (isShorts) "118" else "44"
        specs += OverlaySpec(file = watermarkPng, xExpr = wmX, yExpr = wmY)

        if (subscribeEndCardPng != null && subscribeEndCardPng.exists() && sceneDurationSeconds > 1.2) {
            val startSub = String.format(Locale.US, "%.2f", (sceneDurationSeconds - 1.6).coerceAtLeast(0.2))
            val subY = if (isShorts) 290 else 135
            specs += OverlaySpec(
                file = subscribeEndCardPng,
                xExpr = "(W-w)/2",
                yExpr = subY.toString(),
                enableExpr = "gte(t,$startSub)"
            )
        }

        return specs
    }

    private fun renderWatermarkPng(outputFile: File, isShorts: Boolean) {
        val w = if (isShorts) 310 else 280
        val h = 58
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x9939FF14.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            val rect = RectF(2f, 2f, w - 2f, h - 2f)
            canvas.drawRoundRect(rect, 28f, 28f, bg)
            canvas.drawRoundRect(rect, 28f, 28f, border)

            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = if (isShorts) 24f else 22f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("@RSMovieSpot", w / 2f, h * 0.66f, txt)
            outputFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bmp.recycle()
        }
    }

    private fun renderSubscribeEndCardPng(outputFile: File, isShorts: Boolean) {
        val w = if (isShorts) 430 else 380
        val h = 76
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE50914.toInt() }
            val rect = RectF(2f, 2f, w - 2f, h - 2f)
            canvas.drawRoundRect(rect, 36f, 36f, bg)
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = if (isShorts) 30f else 27f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("SUBSCRIBE FOR MORE!", w / 2f, h * 0.64f, txt)
            outputFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bmp.recycle()
        }
    }

    private fun renderStatBadgePng(
        badgeText: String,
        videoWidth: Int,
        isShorts: Boolean,
        outputFile: File
    ): Boolean {
        val clean = badgeText.trim().uppercase(Locale.US)
        if (clean.isBlank()) return false

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = if (isShorts) 38f else 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val textW = paint.measureText(clean).coerceAtMost(videoWidth - 140f)
        val w = (textW + 64f).roundToInt().let { if (it % 2 == 0) it else it + 1 }.coerceAtLeast(160)
        val h = if (isShorts) 74 else 66

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bmp)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD600.toInt() }
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val rect = RectF(2f, 2f, w - 2f, h - 2f)
            canvas.drawRoundRect(rect, 20f, 20f, bg)
            canvas.drawRoundRect(rect, 20f, 20f, border)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(clean, w / 2f, h * 0.66f, paint)
            outputFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            outputFile.exists()
        } finally {
            bmp.recycle()
        }
    }

    private fun stripDecorativeEmojis(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val charCount = Character.charCount(cp)
            val isEmoji = (cp in 0x1F000..0x1FAFF) ||
                (cp in 0x2600..0x27BF) ||
                cp == 0xFE0F ||
                cp == 0x200D
            if (!isEmoji) sb.appendCodePoint(cp)
            i += charCount
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun renderCoverThumbnailJpg(
        baseImageFile: File,
        headlineText: String,
        isShorts: Boolean,
        outputFile: File
    ) {
        val src = BitmapFactory.decodeFile(baseImageFile.absolutePath) ?: return
        val thumbW = if (isShorts) SHORTS_WIDTH else 1280
        val thumbH = if (isShorts) SHORTS_HEIGHT else 720
        val out = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            drawCenterCropBitmap(
                canvas, src, Rect(0, 0, thumbW, thumbH),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            val scrim = Paint().apply {
                shader = LinearGradient(
                    0f, thumbH * 0.50f, 0f, thumbH.toFloat(),
                    0x00000000, 0xEC000000.toInt(), Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, thumbH * 0.50f, thumbW.toFloat(), thumbH.toFloat(), scrim)

            val cleanTitle = stripDecorativeEmojis(headlineText)
                .ifBlank { headlineText.trim() }
                .take(56)
            val maxW = (thumbW - 110).coerceAtLeast(300)
            var fontSize = if (isShorts) 72f else 62f
            lateinit var textPaint: TextPaint
            lateinit var layout: StaticLayout

            while (fontSize >= 46f) {
                textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFE135.toInt()
                    textSize = fontSize
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }
                layout = StaticLayout.Builder.obtain(cleanTitle, 0, cleanTitle.length, textPaint, maxW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(4f, 1.05f)
                    .setMaxLines(2)
                    .build()
                if (layout.lineCount <= 2 && layout.getEllipsisCount(layout.lineCount - 1) == 0) {
                    break
                }
                fontSize -= 4f
            }

            val strokePaint = TextPaint(textPaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = 12f
                strokeJoin = Paint.Join.ROUND
                color = Color.BLACK
            }
            val strokeLayout = StaticLayout.Builder.obtain(cleanTitle, 0, cleanTitle.length, strokePaint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(4f, 1.05f)
                .setMaxLines(2)
                .build()

            val bottomMargin = if (isShorts) 105f else 56f
            canvas.save()
            canvas.translate((thumbW - maxW) / 2f, (thumbH - layout.height - bottomMargin).coerceAtLeast(80f))
            strokeLayout.draw(canvas)
            layout.draw(canvas)
            canvas.restore()

            outputFile.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        } finally {
            src.recycle()
            out.recycle()
        }
    }

    private suspend fun synthesizeAndTrimVoiceClips(
        renderDirectory: File,
        slabs: List<VideoSlab>,
        voiceName: String,
        wantsMaleVoice: Boolean,
        voiceTone: VoiceTone,
        progress: (Int, String) -> Unit
    ): List<File> = coroutineScope {
        val total = slabs.size.coerceAtLeast(1)
        val doneCount = AtomicInteger(0)
        val semaphore = Semaphore(2)

        val rawItems = slabs.mapIndexed { index, slab ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val rawMp3 = File(renderDirectory, "raw_voice_${index + 1}.mp3")
                    val clipMeta = withTimeoutOrNull(45_000L) {
                        videoRepository.generateDynamicVoiceClipWithMeta(
                            text = slab.voiceover_text,
                            voiceName = voiceName,
                            emotion = slab.voice_emotion,
                            voiceTone = voiceTone
                        )
                    } ?: throw IOException("Voice generation timed out for scene ${index + 1}.")

                    rawMp3.writeBytes(clipMeta.audioBytes)
                    if (!rawMp3.exists() || rawMp3.length() == 0L) {
                        throw IOException("Voice generation returned empty audio for scene ${index + 1}.")
                    }
                    val done = doneCount.incrementAndGet()
                    val pct = 7 + (done * 10 / total)
                    progress(pct.coerceIn(7, 17), "Recording emotional voice $done of $total…")
                    rawMp3 to clipMeta.usedFallback
                }
            }
        }.awaitAll()

        val wavFiles = mutableListOf<File>()
        rawItems.forEachIndexed { index, (rawMp3, usedFallback) ->
            val trimmedWav = File(renderDirectory, "trimmed_voice_${index + 1}.wav")
            val emotion = slabs[index].voice_emotion
            val voiceFilter = buildVoiceNormalizeFilter(
                usedFallback = usedFallback,
                wantsMaleVoice = wantsMaleVoice,
                emotion = emotion,
                voiceTone = voiceTone
            )
            val normOk = runCatching {
                runFfmpeg(
                    arrayOf(
                        "-y",
                        "-i", rawMp3.absolutePath,
                        "-af", voiceFilter,
                        "-ar", "44100",
                        "-ac", "1",
                        "-c:a", "pcm_s16le",
                        trimmedWav.absolutePath
                    )
                )
                trimmedWav.exists() && trimmedWav.length() > 44L
            }.getOrDefault(false)

            if (!normOk) {
                runFfmpeg(
                    arrayOf(
                        "-y",
                        "-i", rawMp3.absolutePath,
                        "-ar", "44100",
                        "-ac", "1",
                        "-c:a", "pcm_s16le",
                        trimmedWav.absolutePath
                    )
                )
            }
            rawMp3.delete()
            wavFiles += trimmedWav
        }
        wavFiles
    }

    private fun buildVoiceNormalizeFilter(
        usedFallback: Boolean,
        wantsMaleVoice: Boolean,
        emotion: String,
        voiceTone: VoiceTone
    ): String {
        val emo = emotion.lowercase().trim()
        val trim = "silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.04," +
            "areverse,silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.05,areverse"

        if (usedFallback) {
            return if (wantsMaleVoice) {
                val tempo = when (emo) {
                    "suspense" -> "1.30"
                    "sad", "emotional" -> "1.26"
                    "excited", "hook", "climax" -> "1.46"
                    else -> "1.40"
                }
                "$trim,aresample=44100,asetrate=37485,aresample=44100,atempo=$tempo"
            } else {
                val tempo = when (emo) {
                    "suspense" -> "1.12"
                    "sad", "emotional" -> "1.08"
                    "excited", "hook", "climax" -> "1.25"
                    else -> "1.20"
                }
                "$trim,atempo=$tempo"
            }
        }

        val baseTempo = when (voiceTone.name.uppercase()) {
            "ENERGETIC", "HYPE" -> 1.10
            "FUNNY", "COMIC" -> 1.09
            "SERIOUS", "INTENSE" -> 1.03
            "NATURAL", "PODCAST", "HUMAN" -> 1.06
            else -> 1.07
        }

        val (tempoOffset, emotionEq) = when (emo) {
            "hook" -> 0.03 to "equalizer=f=3200:t=q:w=1.2:g=2.4,equalizer=f=125:t=q:w=1.0:g=1.4"
            "excited" -> 0.04 to "equalizer=f=3500:t=q:w=1.2:g=2.8,equalizer=f=140:t=q:w=1.0:g=1.6"
            "climax" -> 0.04 to "equalizer=f=3000:t=q:w=1.1:g=2.6,equalizer=f=110:t=q:w=0.9:g=2.5"
            "roast" -> 0.03 to "equalizer=f=2400:t=q:w=1.3:g=2.5,equalizer=f=3800:t=q:w=1.2:g=1.6"
            "question" -> -0.01 to "equalizer=f=2800:t=q:w=1.3:g=1.8"
            "suspense" -> -0.06 to "equalizer=f=105:t=q:w=0.9:g=3.2,equalizer=f=4200:t=q:w=1.2:g=-1.8"
            "sad", "emotional" -> -0.09 to "equalizer=f=150:t=q:w=1.0:g=2.2,equalizer=f=4800:t=q:w=1.2:g=-2.8,aecho=0.82:0.88:22:0.12"
            "warm" -> -0.01 to "equalizer=f=180:t=q:w=1.0:g=2.0,equalizer=f=5000:t=q:w=1.2:g=-1.0"
            else -> 0.0 to ""
        }

        val finalTempo = String.format(
            Locale.US,
            "%.2f",
            (baseTempo + tempoOffset).coerceIn(0.94, 1.18)
        )

        return if (emotionEq.isNotBlank()) {
            "$trim,atempo=$finalTempo,$emotionEq"
        } else {
            "$trim,atempo=$finalTempo"
        }
    }

    private fun getPcmWavDurationSeconds(wavFile: File): Double {
        if (!wavFile.exists() || wavFile.length() <= 44L) return 1.0
        val bytes = wavFile.readBytes()
        if (bytes.size <= 44) return 1.0

        var byteRate = 88200
        var dataLen = bytes.size - 44

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = ((bytes[offset + 4].toInt() and 0xFF)) or
                ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            if (chunkId == "fmt " && offset + 20 <= bytes.size && chunkSize >= 16) {
                byteRate = ((bytes[offset + 16].toInt() and 0xFF)) or
                    ((bytes[offset + 17].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 18].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 19].toInt() and 0xFF) shl 24)
            } else if (chunkId == "data") {
                dataLen = chunkSize.coerceIn(0, bytes.size - (offset + 8))
                break
            }
            if (chunkSize < 0) break
            offset += 8 + chunkSize + (chunkSize and 1)
        }
        return (dataLen.toDouble() / byteRate.coerceAtLeast(16000)).coerceAtLeast(0.45)
    }

    private fun createPopCaptionOverlays(
        renderDirectory: File,
        sceneIndex: Int,
        fullSceneText: String,
        sceneDurationSeconds: Double,
        videoWidth: Int,
        isShorts: Boolean
    ): List<PopCaptionChunk> {
        val cleanText = fullSceneText.trim().replace(Regex("\\s+"), " ")
        if (cleanText.isBlank()) return emptyList()

        val words = cleanText.split(' ').filter { it.isNotBlank() }
        val maxWordsPerChunk = if (isShorts) 4 else 6
        val chunks = mutableListOf<String>()

        if (words.size <= maxWordsPerChunk) {
            chunks += cleanText
        } else {
            var i = 0
            while (i < words.size) {
                val end = (i + maxWordsPerChunk).coerceAtMost(words.size)
                if (words.size - end == 1) {
                    chunks += words.subList(i, words.size).joinToString(" ")
                    break
                } else {
                    chunks += words.subList(i, end).joinToString(" ")
                    i = end
                }
            }
        }

        val totalChars = chunks.sumOf { it.length.coerceAtLeast(1) }.coerceAtLeast(1)
        var cursorSec = 0.0
        val result = mutableListOf<PopCaptionChunk>()

        chunks.forEachIndexed { chunkIdx, chunkText ->
            val share = chunkText.length.coerceAtLeast(1).toDouble() / totalChars
            val chunkDur = if (chunkIdx == chunks.lastIndex) {
                (sceneDurationSeconds - cursorSec).coerceAtLeast(0.25)
            } else {
                (sceneDurationSeconds * share).coerceAtLeast(0.25)
            }
            val startSec = cursorSec
            val endSec = if (chunkIdx == chunks.lastIndex) {
                sceneDurationSeconds + 0.05
            } else {
                (cursorSec + chunkDur).coerceAtMost(sceneDurationSeconds)
            }
            cursorSec = endSec

            val pngFile = File(renderDirectory, "cap_${sceneIndex + 1}_$chunkIdx.png")
            val stripH = renderSubtitleStripPng(chunkText, videoWidth, isShorts, pngFile)
            result += PopCaptionChunk(pngFile, stripH, startSec, endSec)
        }
        return result
    }

    private fun renderSubtitleStripPng(
        text: String,
        videoWidth: Int,
        isShorts: Boolean,
        outputFile: File
    ): Int {
        val cleanText = text.trim()
        val horizontalMargin = if (isShorts) 96 else 180
        val maxTextWidth = (videoWidth - horizontalMargin).coerceAtLeast(240)
        val boxPadX = if (isShorts) 26f else 28f
        val boxPadY = if (isShorts) 14f else 13f
        val maxStripHeight = if (isShorts) 260 else 190

        var fontSize = if (isShorts) 46f else 42f
        lateinit var fillPaint: TextPaint
        lateinit var layout: StaticLayout

        while (fontSize >= 28f) {
            fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFE135.toInt()
                textSize = fontSize
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            layout = StaticLayout.Builder.obtain(
                cleanText,
                0,
                cleanText.length,
                fillPaint,
                maxTextWidth
            )
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(4f, 1.04f)
                .setIncludePad(true)
                .build()

            if (layout.lineCount <= 2 && layout.height + boxPadY * 2 + 24 <= maxStripHeight) {
                break
            }
            fontSize -= 3f
        }

        val strokePaint = TextPaint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7.5f
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val strokeLayout = StaticLayout.Builder.obtain(
            cleanText,
            0,
            cleanText.length,
            strokePaint,
            maxTextWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(4f, 1.04f)
            .setIncludePad(true)
            .build()

        var maxLineWidth = 0f
        for (line in 0 until layout.lineCount) {
            val w = layout.getLineWidth(line)
            if (w > maxLineWidth) maxLineWidth = w
        }
        val contentWidth = maxLineWidth.coerceAtLeast(160f).coerceAtMost(maxTextWidth.toFloat())
        val contentHeight = layout.height.toFloat().coerceAtLeast(56f)

        val stripHeight = ((contentHeight + boxPadY * 2 + 28f).roundToInt())
            .coerceIn(100, maxStripHeight)
            .let { if (it % 2 == 0) it else it + 1 }

        val bitmap = Bitmap.createBitmap(videoWidth, stripHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)

            val boxWidth = (contentWidth + boxPadX * 2).coerceAtMost(videoWidth - 48f)
            val boxHeight = contentHeight + boxPadY * 2
            val boxLeft = (videoWidth - boxWidth) / 2f
            val boxTop = ((stripHeight - boxHeight) / 2f).coerceAtLeast(4f)
            val boxRect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xAA000000.toInt()
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x77FFE135.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }

            canvas.drawRoundRect(boxRect, 24f, 24f, bgPaint)
            canvas.drawRoundRect(boxRect, 24f, 24f, borderPaint)

            val textX = (videoWidth - maxTextWidth) / 2f
            val textY = boxTop + boxPadY

            canvas.save()
            canvas.translate(textX, textY)
            strokeLayout.draw(canvas)
            layout.draw(canvas)
            canvas.restore()

            outputFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            return stripHeight
        } finally {
            bitmap.recycle()
        }
    }

    private fun sanitizePromptText(raw: String): String =
        raw
            .replace(
                Regex(
                    "\\b(comment box|subscribe button|subscribe|social media|website|ui overlay|user interface|title card|movie poster|poster|text overlay|stat badge|youtube shorts?|video_clip|meme_clip|split layout|act as|collage|photo grid|grid of|panels|holding microphone|holding a microphone|holding mic)\\b",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("\\banimal\\s+(?:movie|film|teaser|trailer)\\b", RegexOption.IGNORE_CASE), "intense Bollywood action film")
            .replace(Regex("\\bspirit\\s+(?:movie|film|teaser|trailer)\\b", RegexOption.IGNORE_CASE), "fierce police thriller")
            .replace(Regex("\\bdark violence\\b|\\bbrutal violence\\b|\\bviolence\\b", RegexOption.IGNORE_CASE), "dramatic action confrontation in moody lighting")
            .replace(Regex("\\bblood rivers?\\b|\\brivers of blood\\b", RegexOption.IGNORE_CASE), "dramatic crimson-lit action showdown with flying sparks")
            .replace(Regex("\\bbox[- ]?office\\b", RegexOption.IGNORE_CASE), "packed enthusiastic crowd")
            .replace(Regex("\\bunreal engine(?:\\s*5)?\\b", RegexOption.IGNORE_CASE), "cinema film still")
            .replace(Regex("\\babstract\\b", RegexOption.IGNORE_CASE), "detailed scene")
            .replace(Regex("\\s{2,}"), " ")
            .trim(',', ' ', '.')

    private fun buildSceneImagePrompt(
        characterBible: String,
        visualStyle: String,
        scenePrompt: String,
        hasCharacter: Boolean
    ): String {
        val cleanScene = sanitizePromptText(scenePrompt)
        val cleanBible = sanitizePromptText(characterBible)
        val cleanStyle = sanitizePromptText(visualStyle)

        return buildString {
            append(cleanScene)
            if (hasCharacter && cleanBible.isNotBlank()) {
                append(". Main character look: ")
                append(cleanBible)
            }
            if (cleanStyle.isNotBlank()) {
                append(". Style: ")
                append(cleanStyle)
            }
        }
    }

    private fun prepareBgmBed(
        renderDirectory: File,
        mood: String,
        customBgmUri: Uri?,
        bgmSeed: Int
    ): File? {
        if (customBgmUri != null) {
            val copiedCustom = runCatching {
                val outFile = File(renderDirectory, "custom_bgm.audio")
                context.contentResolver.openInputStream(customBgmUri)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                outFile.takeIf { it.exists() && it.length() > 1024L }
            }.getOrNull()
            if (copiedCustom != null) return copiedCustom
        }

        val rawResourceFile = copyOptionalRawAudio(renderDirectory, mood)
        if (rawResourceFile != null) return rawResourceFile

        val synthFile = File(renderDirectory, "bgm_bed.wav")
        return runCatching {
            writeCinematicBgmWav(synthFile, mood, bgmSeed)
            synthFile.takeIf { it.exists() && it.length() > 1024L }
        }.getOrNull()
    }

    private fun copyOptionalRawAudio(renderDirectory: File, mood: String): File? {
        val cleanMood = mood.lowercase().trim()
        val shortMood = cleanMood.substringBefore('_')
        val candidates = listOf("bgm_$cleanMood", "bgm_$shortMood", "bgm_epic").distinct()
        for (name in candidates) {
            val resId = context.resources.getIdentifier(name, "raw", context.packageName)
            if (resId != 0) {
                val outFile = File(renderDirectory, "$name.mp3")
                context.resources.openRawResource(resId).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (outFile.exists() && outFile.length() > 0L) return outFile
            }
        }
        return null
    }

    private fun resolveBgmStyle(mood: String): BgmStyle {
        val normalized = mood.lowercase().trim()
        return when {
            normalized.contains("roast") || normalized.contains("comedy") ||
                normalized.contains("funny") || normalized.contains("satire") -> BgmStyle.COMEDY_ROAST
            normalized.contains("horror") || normalized.contains("scary") -> BgmStyle.HORROR_DARK
            normalized.contains("suspense") || normalized.contains("thriller") ||
                normalized.contains("mystery") || normalized.contains("crime") -> BgmStyle.SUSPENSE_THRILLER
            normalized.contains("emotional") || normalized.contains("sad") ||
                normalized.contains("drama") -> BgmStyle.EMOTIONAL_SAD
            normalized.contains("devotional") || normalized.contains("spiritual") ||
                normalized.contains("myth") -> BgmStyle.DEVOTIONAL
            normalized.contains("sci") || normalized.contains("tech") ||
                normalized.contains("cyber") || normalized.contains("future") -> BgmStyle.SCI_FI_TECH
            normalized.contains("motiv") || normalized.contains("inspir") ||
                normalized.contains("success") -> BgmStyle.MOTIVATIONAL
            else -> BgmStyle.EPIC_ACTION
        }
    }

    private fun writeCinematicBgmWav(outputFile: File, mood: String, bgmSeed: Int) {
        val sampleRate = 44100
        val style = resolveBgmStyle(mood)

        val semitoneShifts = intArrayOf(-3, -2, 0, 2, 3)
        val semitoneShift = semitoneShifts[(bgmSeed and 0x7FFFFFFF) % semitoneShifts.size]
        val pitchScale = 2.0.pow(semitoneShift / 12.0)
        val arpVariant = ((bgmSeed ushr 4) and 0x7FFFFFFF) % 3

        val beatDuration: Double
        val beatsPerLoop: Int
        val baseChords: Array<DoubleArray>

        when (style) {
            BgmStyle.COMEDY_ROAST -> {
                beatDuration = 0.50
                beatsPerLoop = 32
                baseChords = arrayOf(
                    doubleArrayOf(261.63, 329.63, 392.00, 523.25),
                    doubleArrayOf(196.00, 246.94, 293.66, 392.00),
                    doubleArrayOf(220.00, 261.63, 329.63, 440.00),
                    doubleArrayOf(174.61, 220.00, 261.63, 349.23)
                )
            }
            BgmStyle.SUSPENSE_THRILLER -> {
                beatDuration = 0.75
                beatsPerLoop = 16
                baseChords = arrayOf(
                    doubleArrayOf(164.81, 196.00, 246.94, 329.63),
                    doubleArrayOf(164.81, 174.61, 220.00, 349.23),
                    doubleArrayOf(164.81, 196.00, 246.94, 329.63),
                    doubleArrayOf(155.56, 185.00, 220.00, 311.13)
                )
            }
            BgmStyle.EMOTIONAL_SAD -> {
                beatDuration = 0.80
                beatsPerLoop = 16
                baseChords = arrayOf(
                    doubleArrayOf(220.00, 261.63, 329.63, 440.00),
                    doubleArrayOf(174.61, 220.00, 261.63, 349.23),
                    doubleArrayOf(130.81, 196.00, 261.63, 329.63),
                    doubleArrayOf(196.00, 246.94, 293.66, 392.00)
                )
            }
            BgmStyle.DEVOTIONAL -> {
                beatDuration = 0.75
                beatsPerLoop = 16
                baseChords = arrayOf(
                    doubleArrayOf(130.81, 196.00, 261.63, 293.66),
                    doubleArrayOf(174.61, 220.00, 261.63, 392.00),
                    doubleArrayOf(196.00, 261.63, 293.66, 392.00),
                    doubleArrayOf(130.81, 196.00, 261.63, 329.63)
                )
            }
            BgmStyle.SCI_FI_TECH -> {
                beatDuration = 0.50
                beatsPerLoop = 32
                baseChords = arrayOf(
                    doubleArrayOf(220.00, 261.63, 329.63, 440.00),
                    doubleArrayOf(174.61, 220.00, 261.63, 349.23),
                    doubleArrayOf(146.83, 220.00, 293.66, 349.23),
                    doubleArrayOf(164.81, 207.65, 246.94, 329.63)
                )
            }
            BgmStyle.MOTIVATIONAL -> {
                beatDuration = 0.60
                beatsPerLoop = 32
                baseChords = arrayOf(
                    doubleArrayOf(174.61, 220.00, 261.63, 349.23),
                    doubleArrayOf(196.00, 246.94, 293.66, 392.00),
                    doubleArrayOf(164.81, 196.00, 246.94, 329.63),
                    doubleArrayOf(220.00, 261.63, 329.63, 440.00)
                )
            }
            BgmStyle.HORROR_DARK -> {
                beatDuration = 0.80
                beatsPerLoop = 16
                baseChords = arrayOf(
                    doubleArrayOf(138.59, 164.81, 207.65, 277.18),
                    doubleArrayOf(138.59, 196.00, 246.94, 293.66),
                    doubleArrayOf(146.83, 174.61, 220.00, 293.66),
                    doubleArrayOf(138.59, 196.00, 233.08, 277.18)
                )
            }
            BgmStyle.EPIC_ACTION -> {
                beatDuration = 0.46875
                beatsPerLoop = 32
                baseChords = arrayOf(
                    doubleArrayOf(146.83, 220.00, 293.66, 349.23),
                    doubleArrayOf(116.54, 174.61, 233.08, 293.66),
                    doubleArrayOf(130.81, 196.00, 261.63, 329.63),
                    doubleArrayOf(110.00, 164.81, 220.00, 277.18)
                )
            }
        }

        val chords = Array(baseChords.size) { c ->
            DoubleArray(baseChords[c].size) { n -> baseChords[c][n] * pitchScale }
        }
        val loopDuration = beatsPerLoop * beatDuration
        val chordDuration = loopDuration / chords.size
        val totalSamples = (loopDuration * sampleRate).roundToInt()

        val arpOrder = when (arpVariant) {
            0 -> intArrayOf(0, 1, 2, 3, 2, 1, 3, 2)
            1 -> intArrayOf(0, 2, 1, 3, 0, 3, 2, 1)
            else -> intArrayOf(3, 2, 1, 0, 1, 2, 3, 2)
        }

        val rand = Random(bgmSeed)
        writeMono16BitWav(outputFile, sampleRate, totalSamples) { i ->
            val t = i.toDouble() / sampleRate
            val chordIndex = ((t / chordDuration).toInt()).coerceIn(0, chords.size - 1)
            val chordLocalT = t - chordIndex * chordDuration
            val chord = chords[chordIndex]
            val beatT = t % beatDuration
            val beatNumber = (t / beatDuration).toInt()
            val halfBeatT = t % (beatDuration * 0.5)
            val quarterBeatT = t % (beatDuration * 0.25)

            val loopEnv = (t / 0.04).coerceAtMost(1.0) *
                ((loopDuration - t) / 0.04).coerceAtMost(1.0)
            val chordEnv = (chordLocalT / 0.12).coerceAtMost(1.0) *
                ((chordDuration - chordLocalT) / 0.12).coerceAtMost(1.0)

            val sample = when (style) {
                BgmStyle.COMEDY_ROAST -> {
                    val stepIdx = ((t / (beatDuration * 0.5)).toInt()) % arpOrder.size
                    val pluckFreq = chord[arpOrder[stepIdx]] * 1.5
                    val pluckEnv = exp(-12.0 * halfBeatT)
                    val pluck = (sin(2.0 * PI * pluckFreq * halfBeatT) +
                        0.35 * sin(2.0 * PI * pluckFreq * 2.0 * halfBeatT)) * pluckEnv * 0.36
                    val bassFreq = chord[0] * 0.5
                    val bass = sin(2.0 * PI * bassFreq * beatT) * exp(-7.5 * beatT) * 0.34
                    val snap = if (beatNumber % 2 == 1) {
                        rand.nextDouble(-1.0, 1.0) * exp(-28.0 * beatT) * 0.26
                    } else {
                        0.0
                    }
                    pluck + bass + snap
                }
                BgmStyle.SUSPENSE_THRILLER -> {
                    val tick = (sin(2.0 * PI * 1950.0 * halfBeatT) * 0.5 +
                        rand.nextDouble(-1.0, 1.0) * 0.5) * exp(-55.0 * halfBeatT) * 0.22
                    val heartbeat = if (beatNumber % 2 == 0) {
                        sin(2.0 * PI * 72.0 * beatT) * exp(-12.0 * beatT) * 0.40
                    } else {
                        0.0
                    }
                    val bellFreq = chord[3] * 2.0
                    val bell = sin(2.0 * PI * bellFreq * chordLocalT) * exp(-1.8 * chordLocalT) * 0.20
                    val lowDrone = (sin(2.0 * PI * chord[0] * 0.5 * t) * 0.20 +
                        sin(2.0 * PI * chord[1] * t) * 0.10) * chordEnv
                    tick + heartbeat + bell + lowDrone
                }
                BgmStyle.EMOTIONAL_SAD -> {
                    val pianoAttack = (chordLocalT / 0.025).coerceAtMost(1.0)
                    val pianoDecay = exp(-1.4 * chordLocalT)
                    var piano = 0.0
                    for (freq in chord) {
                        piano += sin(2.0 * PI * freq * t) * 0.18 +
                            sin(2.0 * PI * freq * 2.0 * t) * 0.05
                    }
                    piano *= pianoAttack * pianoDecay
                    val stepIdx = ((t / beatDuration).toInt()) % arpOrder.size
                    val melodyFreq = chord[arpOrder[stepIdx]] * 2.0
                    val melody = sin(2.0 * PI * melodyFreq * beatT) *
                        (beatT / 0.015).coerceAtMost(1.0) * exp(-2.8 * beatT) * 0.24
                    val warmPad = sin(2.0 * PI * chord[0] * 0.5 * t) * chordEnv * 0.22
                    piano + melody + warmPad
                }
                BgmStyle.DEVOTIONAL -> {
                    val drone = (sin(2.0 * PI * chord[0] * 0.5 * t) * 0.22 +
                        sin(2.0 * PI * chord[1] * t) * 0.12 +
                        sin(2.0 * PI * chord[2] * t) * 0.08) * chordEnv
                    val stepIdx = ((t / beatDuration).toInt()) % arpOrder.size
                    val fluteFreq = chord[arpOrder[stepIdx]] * 2.0
                    val vibrato = 1.0 + 0.008 * sin(2.0 * PI * 5.2 * t)
                    val fluteEnv = sin(PI * (beatT / beatDuration).coerceIn(0.0, 1.0)).pow(0.8)
                    val flute = sin(2.0 * PI * fluteFreq * vibrato * t) * fluteEnv * 0.24
                    val bell = (sin(2.0 * PI * chord[3] * 2.0 * chordLocalT) +
                        0.4 * sin(2.0 * PI * chord[3] * 4.0 * chordLocalT)) *
                        exp(-2.6 * chordLocalT) * 0.16
                    drone + flute + bell
                }
                BgmStyle.SCI_FI_TECH -> {
                    val stepIdx = ((t / (beatDuration * 0.25)).toInt()) % arpOrder.size
                    val arpFreq = chord[arpOrder[stepIdx]] * 2.0
                    val arp = (sin(2.0 * PI * arpFreq * quarterBeatT) +
                        0.4 * sin(2.0 * PI * arpFreq * 2.0 * quarterBeatT)) *
                        exp(-14.0 * quarterBeatT) * 0.28
                    val pad = (sin(2.0 * PI * chord[0] * t) * 0.16 +
                        sin(2.0 * PI * chord[2] * t) * 0.12) * chordEnv
                    val kickFreq = 58.0 + 130.0 * exp(-30.0 * beatT)
                    val kick = sin(2.0 * PI * kickFreq * beatT) * exp(-11.0 * beatT) * 0.36
                    val hat = if (stepIdx % 2 == 1) {
                        rand.nextDouble(-1.0, 1.0) * exp(-45.0 * quarterBeatT) * 0.12
                    } else {
                        0.0
                    }
                    arp + pad + kick + hat
                }
                BgmStyle.MOTIVATIONAL -> {
                    val stepIdx = ((t / (beatDuration * 0.5)).toInt()) % arpOrder.size
                    val arpFreq = chord[arpOrder[stepIdx]] * 1.5
                    val arp = sin(2.0 * PI * arpFreq * halfBeatT) * exp(-7.5 * halfBeatT) * 0.28
                    var pad = 0.0
                    for (freq in chord) {
                        pad += sin(2.0 * PI * freq * t) * 0.12
                    }
                    pad *= chordEnv
                    val kickFreq = 62.0 + 95.0 * exp(-22.0 * beatT)
                    val kick = sin(2.0 * PI * kickFreq * beatT) * exp(-10.0 * beatT) * 0.34
                    arp + pad + kick
                }
                BgmStyle.HORROR_DARK -> {
                    val lfo = 0.70 + 0.30 * sin(2.0 * PI * 0.35 * t)
                    val drone = (sin(2.0 * PI * chord[0] * 0.5 * t) * 0.28 +
                        sin(2.0 * PI * chord[1] * t) * 0.16 +
                        sin(2.0 * PI * chord[3] * 1.5 * t) * 0.08) * chordEnv * lfo
                    val metalHit = if (beatNumber % 4 == 0) {
                        (sin(2.0 * PI * 740.0 * beatT) * 0.6 + sin(2.0 * PI * 1185.0 * beatT) * 0.4) *
                            exp(-2.4 * beatT) * 0.22
                    } else {
                        0.0
                    }
                    drone + metalHit
                }
                BgmStyle.EPIC_ACTION -> {
                    var brass = 0.0
                    for (freq in chord) {
                        brass += sin(2.0 * PI * freq * t) * 0.14 +
                            sin(2.0 * PI * freq * 2.0 * t) * 0.05 +
                            sin(2.0 * PI * freq * 3.0 * t) * 0.012
                    }
                    brass *= chordEnv * (0.85 + 0.15 * sin(2.0 * PI * 0.6 * t))
                    val stepIdx = ((t / (beatDuration * 0.5)).toInt()) % arpOrder.size
                    val motifFreq = chord[arpOrder[stepIdx]] * 1.5
                    val motif = sin(2.0 * PI * motifFreq * halfBeatT) * exp(-9.0 * halfBeatT) * 0.20
                    val drumFreq = 65.0 + 105.0 * exp(-26.0 * beatT)
                    val mainDrum = sin(2.0 * PI * drumFreq * beatT) * exp(-13.0 * beatT) * 0.40
                    val doubleHit = sin(2.0 * PI * 88.0 * halfBeatT) * exp(-18.0 * halfBeatT) * 0.18
                    brass + motif + mainDrum + doubleHit
                }
            }

            (sample * loopEnv).coerceIn(-0.92, 0.92)
        }
    }

    private inline fun writeMono16BitWav(
        file: File,
        sampleRate: Int,
        totalSamples: Int,
        sampleProvider: (Int) -> Double
    ) {
        val dataSize = totalSamples * 2
        BufferedOutputStream(file.outputStream(), 16384).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(sampleRate)
                putInt(sampleRate * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }
            out.write(header.array())

            val chunkBuffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until totalSamples) {
                val sample = (sampleProvider(i).coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
                chunkBuffer.putShort(sample)
                if (!chunkBuffer.hasRemaining()) {
                    out.write(chunkBuffer.array(), 0, chunkBuffer.position())
                    chunkBuffer.clear()
                }
            }
            if (chunkBuffer.position() > 0) {
                out.write(chunkBuffer.array(), 0, chunkBuffer.position())
            }
        }
    }

    private fun readMono16BitWavSamples(wavFile: File): ShortArray {
        if (!wavFile.exists() || wavFile.length() <= 44L) return ShortArray(0)
        val bytes = wavFile.readBytes()
        if (bytes.size <= 44) return ShortArray(0)

        var dataStart = 44
        var dataLen = bytes.size - 44

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = ((bytes[offset + 4].toInt() and 0xFF)) or
                ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            if (chunkId == "data") {
                dataStart = offset + 8
                dataLen = chunkSize.coerceIn(0, bytes.size - dataStart)
                break
            }
            if (chunkSize < 0) break
            offset += 8 + chunkSize + (chunkSize and 1)
        }

        val sampleCount = dataLen / 2
        val samples = ShortArray(sampleCount)
        val buffer = ByteBuffer.wrap(bytes, dataStart, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            samples[i] = buffer.short
        }
        return samples
    }

    private fun buildStudioVoiceFilter(voiceTone: VoiceTone): String =
        when (voiceTone.name.uppercase()) {
            "NATURAL", "PODCAST", "HUMAN" ->
                "highpass=f=65," +
                    "equalizer=f=110:t=q:w=0.9:g=6.5," +
                    "equalizer=f=210:t=q:w=1.2:g=3.2," +
                    "equalizer=f=480:t=q:w=1.4:g=-2.6," +
                    "equalizer=f=3100:t=q:w=1.4:g=2.6," +
                    "equalizer=f=6800:t=q:w=1.2:g=-3.0," +
                    "aecho=0.88:0.90:14:0.08," +
                    "acompressor=threshold=-20dB:ratio=3.0:attack=5:release=70:makeup=2.2," +
                    "alimiter=limit=0.95"

            "SERIOUS", "INTENSE" ->
                "highpass=f=55," +
                    "equalizer=f=95:t=q:w=0.85:g=7.0," +
                    "equalizer=f=200:t=q:w=1.1:g=3.5," +
                    "equalizer=f=480:t=q:w=1.4:g=-2.4," +
                    "equalizer=f=2900:t=q:w=1.4:g=2.0," +
                    "equalizer=f=6800:t=q:w=1.2:g=-2.5," +
                    "aecho=0.88:0.90:16:0.09," +
                    "acompressor=threshold=-20dB:ratio=3.0:attack=6:release=80:makeup=2.2," +
                    "alimiter=limit=0.95"

            "FUNNY", "COMIC", "ENERGETIC", "HYPE" ->
                "highpass=f=70," +
                    "equalizer=f=125:t=q:w=0.9:g=4.5," +
                    "equalizer=f=480:t=q:w=1.5:g=-2.2," +
                    "equalizer=f=3300:t=q:w=1.3:g=3.2," +
                    "equalizer=f=7200:t=q:w=1.2:g=-2.0," +
                    "acompressor=threshold=-19dB:ratio=2.8:attack=4:release=65:makeup=2.0," +
                    "alimiter=limit=0.95"

            else ->
                "highpass=f=65," +
                    "equalizer=f=110:t=q:w=0.9:g=5.8," +
                    "equalizer=f=205:t=q:w=1.15:g=2.8," +
                    "equalizer=f=480:t=q:w=1.4:g=-2.2," +
                    "equalizer=f=3100:t=q:w=1.4:g=2.4," +
                    "equalizer=f=6800:t=q:w=1.2:g=-2.5," +
                    "aecho=0.88:0.90:14:0.07," +
                    "acompressor=threshold=-20dB:ratio=2.8:attack=5:release=75:makeup=2.0," +
                    "alimiter=limit=0.95"
        }

    private suspend fun mixAudioTrackSafely(
        renderDirectory: File,
        fullVoiceFile: File,
        bgmBedFile: File?,
        sfxTrackFile: File?,
        voiceTone: VoiceTone,
        totalDurationSeconds: Double,
        outputFile: File
    ): File? {
        val formattedDuration = String.format(Locale.US, "%.2f", totalDurationSeconds)

        val eqVoiceFile = File(renderDirectory, "voice_eq.wav")
        val eqOk = runCatching {
            runFfmpeg(
                arrayOf(
                    "-y",
                    "-i", fullVoiceFile.absolutePath,
                    "-af", buildStudioVoiceFilter(voiceTone),
                    "-ar", "44100",
                    "-ac", "1",
                    "-c:a", "pcm_s16le",
                    eqVoiceFile.absolutePath
                )
            )
            eqVoiceFile.exists() && eqVoiceFile.length() > 44L
        }.getOrDefault(false)

        val safeEqOk = if (eqOk) {
            true
        } else {
            runCatching {
                runFfmpeg(
                    arrayOf(
                        "-y",
                        "-i", fullVoiceFile.absolutePath,
                        "-af",
                        "highpass=f=65," +
                            "equalizer=f=110:t=q:w=0.9:g=5.5," +
                            "equalizer=f=480:t=q:w=1.4:g=-2.0," +
                            "equalizer=f=3100:t=q:w=1.4:g=2.4," +
                            "acompressor=threshold=-19dB:ratio=2.6:attack=6:release=75," +
                            "volume=1.12",
                        "-ar", "44100",
                        "-ac", "1",
                        "-c:a", "pcm_s16le",
                        eqVoiceFile.absolutePath
                    )
                )
                eqVoiceFile.exists() && eqVoiceFile.length() > 44L
            }.getOrDefault(false)
        }

        val voiceSource = if (safeEqOk && eqVoiceFile.exists() && eqVoiceFile.length() > 44L) {
            eqVoiceFile
        } else {
            fullVoiceFile
        }

        val bgmWavFile = if (bgmBedFile != null) {
            if (bgmBedFile.extension.equals("wav", ignoreCase = true)) {
                bgmBedFile
            } else {
                val converted = File(renderDirectory, "bgm_44k.wav")
                val ok = runCatching {
                    runFfmpeg(
                        arrayOf(
                            "-y",
                            "-i", bgmBedFile.absolutePath,
                            "-t", formattedDuration,
                            "-ar", "44100",
                            "-ac", "1",
                            "-c:a", "pcm_s16le",
                            converted.absolutePath
                        )
                    )
                }.isSuccess
                if (ok && converted.exists() && converted.length() > 44L) converted else null
            }
        } else {
            null
        }

        val mixedWavFile = File(renderDirectory, "mixed_master.wav")
        val kotlinMixOk = runCatching {
            val voiceSamples = readMono16BitWavSamples(voiceSource)
            val bgmSamples = bgmWavFile?.let { readMono16BitWavSamples(it) } ?: ShortArray(0)
            val sfxSamples = sfxTrackFile?.let { readMono16BitWavSamples(it) } ?: ShortArray(0)
            val totalSamples = (totalDurationSeconds * 44100.0).roundToInt().coerceAtLeast(44100)

            val windowSize = 1102
            val bgmGains = DoubleArray(totalSamples)
            var smoothedGain = 0.14
            var cursor = 0
            while (cursor < totalSamples) {
                val end = minOf(cursor + windowSize, totalSamples)
                var sumSq = 0.0
                var count = 0
                for (k in cursor until minOf(end, voiceSamples.size)) {
                    val v = voiceSamples[k] / 32768.0
                    sumSq += v * v
                    count++
                }
                val rms = if (count > 0) sqrt(sumSq / count) else 0.0
                val targetGain = if (rms > 0.022) 0.075 else 0.24
                for (k in cursor until end) {
                    smoothedGain += (targetGain - smoothedGain) * 0.0022
                    bgmGains[k] = smoothedGain
                }
                cursor = end
            }

            writeMono16BitWav(mixedWavFile, 44100, totalSamples) { idx ->
                val v = if (idx < voiceSamples.size) voiceSamples[idx] / 32768.0 else 0.0
                val b = if (bgmSamples.isNotEmpty()) {
                    (bgmSamples[idx % bgmSamples.size] / 32768.0) * bgmGains[idx]
                } else {
                    0.0
                }
                val s = if (idx < sfxSamples.size) (sfxSamples[idx] / 32768.0) * 0.35 else 0.0
                (v + b + s).coerceIn(-0.96, 0.96)
            }
            mixedWavFile.exists() && mixedWavFile.length() > 44L
        }.getOrDefault(false)

        if (!kotlinMixOk) return null

        val aacOk = runCatching {
            runFfmpeg(
                arrayOf(
                    "-y",
                    "-i", mixedWavFile.absolutePath,
                    "-t", formattedDuration,
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-ar", "44100",
                    "-ac", "2",
                    outputFile.absolutePath
                )
            )
        }.isSuccess
        return outputFile.takeIf { aacOk && it.exists() && it.length() > 0L }
    }

    private suspend fun muxFinalVideo(
        silentVideoFile: File,
        mixedAudioFile: File?,
        fallbackVoiceFile: File,
        totalDurationSeconds: Double,
        outputFile: File
    ) {
        val formattedDuration = String.format(Locale.US, "%.2f", totalDurationSeconds)

        if (mixedAudioFile != null) {
            runFfmpeg(
                arrayOf(
                    "-y",
                    "-i", silentVideoFile.absolutePath,
                    "-i", mixedAudioFile.absolutePath,
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:v", "copy",
                    "-c:a", "copy",
                    "-t", formattedDuration,
                    "-movflags", "+faststart",
                    outputFile.absolutePath
                )
            )
        } else {
            runFfmpeg(
                arrayOf(
                    "-y",
                    "-i", silentVideoFile.absolutePath,
                    "-i", fallbackVoiceFile.absolutePath,
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:v", "copy",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-ar", "44100",
                    "-ac", "2",
                    "-t", formattedDuration,
                    "-movflags", "+faststart",
                    outputFile.absolutePath
                )
            )
        }
    }

    private fun validateImageFile(imageFile: File, sceneNumber: Int) {
        if (!imageFile.exists() || imageFile.length() < 64L) {
            throw IOException("Generated image for scene $sceneNumber is empty or invalid.")
        }

        val header = imageFile.inputStream().use { stream ->
            val buffer = ByteArray(16)
            val read = stream.read(buffer)
            if (read > 0) String(buffer, 0, read, Charsets.UTF_8).trimStart() else ""
        }
        if (header.startsWith("{") || header.startsWith("<")) {
            throw IOException("Image API returned non-image response for scene $sceneNumber.")
        }
    }

    private fun buildEdgeFades(sceneIndex: Int, totalScenes: Int, frames: Int): String {
        val durationSeconds = frames.toDouble() / FRAME_RATE
        val fadeOutStart = String.format(
            Locale.US,
            "%.2f",
            (durationSeconds - 0.28).coerceAtLeast(0.05)
        )
        return buildString {
            if (sceneIndex == 0) {
                append("fade=t=in:st=0:d=0.18,")
            }
            if (sceneIndex == totalScenes - 1) {
                append("fade=t=out:st=$fadeOutStart:d=0.26,")
            }
        }
    }

    private fun buildMotionAndGradeFilter(
        sceneIndex: Int,
        width: Int,
        height: Int,
        frames: Int
    ): String {
        val denom = (frames - 1).coerceAtLeast(1)
        val upWidth = (width * 3) / 2
        val upHeight = (height * 3) / 2

        val motionFilter = when (sceneIndex % 6) {
            0 ->
                "zoompan=z='1.04+0.11*on/$denom':" +
                    "x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            1 ->
                "zoompan=z='1.15-0.11*on/$denom':" +
                    "x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            2 ->
                "zoompan=z='1.14':x='(iw-iw/zoom)*on/$denom':y='ih/2-(ih/zoom/2)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            3 ->
                "zoompan=z='1.14':x='(iw-iw/zoom)*(1-on/$denom)':y='ih/2-(ih/zoom/2)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            4 ->
                "zoompan=z='1.14':x='iw/2-(iw/zoom/2)':y='(ih-ih/zoom)*(1-on/$denom)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            else ->
                "zoompan=z='1.06+0.10*on/$denom':x='(iw-iw/zoom)*on/$denom':y='(ih-ih/zoom)*0.32':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
        }

        val baseGrade = "eq=contrast=1.06:saturation=1.10:brightness=0.01,unsharp=3:3:0.45,vignette=PI/6"
        return "scale=$upWidth:$upHeight:force_original_aspect_ratio=increase," +
            "crop=$upWidth:$upHeight,setsar=1,$motionFilter,$baseGrade"
    }

    private fun appendDissolveTransition(
        args: MutableList<String>,
        filterBuilder: StringBuilder,
        baseLabel: String,
        prevTransitionFile: File?,
        nextInputIndex: Int,
        width: Int,
        height: Int
    ): Pair<String, Int> {
        if (prevTransitionFile == null || !prevTransitionFile.exists()) {
            return baseLabel to nextInputIndex
        }
        args += listOf(
            "-loop", "1",
            "-t", "0.26",
            "-r", FRAME_RATE.toString(),
            "-i", prevTransitionFile.absolutePath
        )
        filterBuilder.append(
            "[$nextInputIndex:v]scale=${width}:${height}:force_original_aspect_ratio=increase," +
                "crop=${width}:${height},setsar=1,format=yuva420p," +
                "fade=t=out:st=0:d=0.22:alpha=1[trprev];" +
                "[$baseLabel][trprev]overlay=0:0:eof_action=pass[trdone];"
        )
        return "trdone" to (nextInputIndex + 1)
    }

    private fun appendOverlayChain(
        args: MutableList<String>,
        filterBuilder: StringBuilder,
        initialLabel: String,
        overlaySpecs: List<OverlaySpec>,
        startInputIndex: Int,
        edgeFades: String
    ) {
        if (overlaySpecs.isEmpty()) {
            filterBuilder.append("[$initialLabel]${edgeFades}format=yuv420p[vout]")
            return
        }
        var currentLabel = initialLabel
        overlaySpecs.forEachIndexed { idx, spec ->
            args += listOf("-i", spec.file.absolutePath)
            val inputIdx = startInputIndex + idx
            val nextLabel = "ov_$idx"
            val enableClause = spec.enableExpr?.let { ":enable='$it'" } ?: ""
            filterBuilder.append(
                "[$currentLabel][$inputIdx:v]overlay=${spec.xExpr}:${spec.yExpr}:eof_action=repeat$enableClause[$nextLabel];"
            )
            currentLabel = nextLabel
        }
        filterBuilder.append("[$currentLabel]${edgeFades}format=yuv420p[vout]")
    }

    private fun createVideoOnlySegmentArguments(
        sceneIndex: Int,
        totalScenes: Int,
        imageFile: File,
        prevTransitionFile: File?,
        overlaySpecs: List<OverlaySpec>,
        outputFile: File,
        width: Int,
        height: Int,
        segmentFrames: Int
    ): Array<String> {
        val frames = segmentFrames.coerceAtLeast(MIN_SCENE_FRAMES)
        val motionAndGrade = buildMotionAndGradeFilter(sceneIndex, width, height, frames)
        val edgeFades = buildEdgeFades(sceneIndex, totalScenes, frames)

        val args = mutableListOf("-y", "-i", imageFile.absolutePath)
        val filterBuilder = StringBuilder("[0:v]$motionAndGrade[bgbase];")
        val (postTransLabel, nextInputIdx) = appendDissolveTransition(
            args = args,
            filterBuilder = filterBuilder,
            baseLabel = "bgbase",
            prevTransitionFile = prevTransitionFile,
            nextInputIndex = 1,
            width = width,
            height = height
        )
        appendOverlayChain(args, filterBuilder, postTransLabel, overlaySpecs, startInputIndex = nextInputIdx, edgeFades = edgeFades)

        args += listOf(
            "-filter_complex", filterBuilder.toString(),
            "-map", "[vout]",
            "-frames:v", frames.toString(),
            "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
            "-threads", "2", "-pix_fmt", "yuv420p", "-r", FRAME_RATE.toString(),
            outputFile.absolutePath
        )
        return args.toTypedArray()
    }

    private fun createFlipVideoSegmentArguments(
        sceneIndex: Int,
        totalScenes: Int,
        imageFile1: File,
        imageFile2: File,
        prevTransitionFile: File?,
        overlaySpecs: List<OverlaySpec>,
        outputFile: File,
        width: Int,
        height: Int,
        segmentFrames: Int
    ): Array<String> {
        val frames = segmentFrames.coerceAtLeast(MIN_SCENE_FRAMES)
        val motionAndGrade = buildMotionAndGradeFilter(sceneIndex, width, height, frames)
        val edgeFades = buildEdgeFades(sceneIndex, totalScenes, frames)
        val switchSec = String.format(Locale.US, "%.2f", (frames.toDouble() / FRAME_RATE) * 0.5)

        val args = mutableListOf("-y", "-i", imageFile1.absolutePath, "-i", imageFile2.absolutePath)
        val filterBuilder = StringBuilder(
            "[0:v]$motionAndGrade[p1];" +
                "[1:v]$motionAndGrade[p2];" +
                "[p1][p2]overlay=0:0:enable='gte(t,$switchSec)'[flipbase];"
        )
        val (postTransLabel, nextInputIdx) = appendDissolveTransition(
            args = args,
            filterBuilder = filterBuilder,
            baseLabel = "flipbase",
            prevTransitionFile = prevTransitionFile,
            nextInputIndex = 2,
            width = width,
            height = height
        )
        appendOverlayChain(args, filterBuilder, postTransLabel, overlaySpecs, startInputIndex = nextInputIdx, edgeFades = edgeFades)

        args += listOf(
            "-filter_complex", filterBuilder.toString(),
            "-map", "[vout]",
            "-frames:v", frames.toString(),
            "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
            "-threads", "2", "-pix_fmt", "yuv420p", "-r", FRAME_RATE.toString(),
            outputFile.absolutePath
        )
        return args.toTypedArray()
    }

    private fun createVideoClipSegmentArguments(
        clipFile: File,
        prevTransitionFile: File?,
        overlaySpecs: List<OverlaySpec>,
        outputFile: File,
        width: Int,
        height: Int,
        frames: Int,
        sceneIndex: Int,
        totalScenes: Int
    ): Array<String> {
        val safeFrames = frames.coerceAtLeast(MIN_SCENE_FRAMES)
        val blurW = (width / 16).coerceAtLeast(32).let { if (it % 2 == 0) it else it + 1 }
        val blurH = (height / 16).coerceAtLeast(32).let { if (it % 2 == 0) it else it + 1 }
        val edgeFades = buildEdgeFades(sceneIndex, totalScenes, safeFrames)

        val safeCrop =
            "crop='if(gt(iw,ih),trunc(iw*0.84/2)*2,trunc(iw*0.88/2)*2)':'if(gt(iw,ih),trunc(ih*0.76/2)*2,trunc(ih*0.84/2)*2)':'iw*0.04':'ih*0.04'"

        val args = mutableListOf("-y", "-stream_loop", "-1", "-i", clipFile.absolutePath)
        val filterBuilder = StringBuilder(
            "[0:v]fps=$FRAME_RATE,setsar=1,$safeCrop,split=2[bgsrc][fgsrc];" +
                "[bgsrc]scale=$blurW:$blurH:force_original_aspect_ratio=increase,crop=$blurW:$blurH," +
                "scale=$width:$height:flags=bilinear,eq=brightness=-0.20:saturation=0.85[bg0];" +
                "[fgsrc]scale=$width:$height:force_original_aspect_ratio=decrease,pad=ceil(iw/2)*2:ceil(ih/2)*2[fg];" +
                "[bg0][fg]overlay=(W-w)/2:(H-h)/2,eq=contrast=1.06:saturation=1.10,unsharp=3:3:0.35,vignette=PI/6[clipbase];"
        )
        val (postTransLabel, nextInputIdx) = appendDissolveTransition(
            args = args,
            filterBuilder = filterBuilder,
            baseLabel = "clipbase",
            prevTransitionFile = prevTransitionFile,
            nextInputIndex = 1,
            width = width,
            height = height
        )
        appendOverlayChain(args, filterBuilder, postTransLabel, overlaySpecs, startInputIndex = nextInputIdx, edgeFades = edgeFades)

        args += listOf(
            "-filter_complex", filterBuilder.toString(),
            "-map", "[vout]",
            "-frames:v", safeFrames.toString(),
            "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
            "-threads", "2", "-pix_fmt", "yuv420p", "-r", FRAME_RATE.toString(),
            outputFile.absolutePath
        )
        return args.toTypedArray()
    }

    private fun createConcatArguments(
        concatList: File,
        outputFile: File
    ): Array<String> = arrayOf(
        "-y",
        "-f", "concat",
        "-safe", "0",
        "-i", concatList.absolutePath,
        "-c", "copy",
        outputFile.absolutePath
    )

    private suspend fun runFfmpeg(arguments: Array<String>): FFmpegSession =
        suspendCancellableCoroutine { continuation ->
            try {
                val session = FFmpegKit.executeWithArgumentsAsync(arguments) { completedSession ->
                    if (!continuation.isActive) return@executeWithArgumentsAsync

                    if (ReturnCode.isSuccess(completedSession.returnCode)) {
                        continuation.resume(completedSession)
                    } else {
                        val details = completedSession.allLogsAsString
                            ?.takeIf(String::isNotBlank)
                            ?: completedSession.failStackTrace
                            ?: "No FFmpeg error details available."

                        continuation.resumeWithException(
                            IOException("FFmpeg failed: $details")
                        )
                    }
                }

                continuation.invokeOnCancellation {
                    FFmpegKit.cancel(session.sessionId)
                }
            } catch (throwable: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IOException(
                            "FFmpeg startup error (${throwable::class.java.simpleName}): ${throwable.message ?: "Unknown"}",
                            throwable
                        )
                    )
                }
            }
        }

    private fun publishToMediaStore(sourceFile: File, titleOrTopic: String): Uri {
        require(sourceFile.exists() && sourceFile.length() > 1024L) {
            "Rendered video file is missing or empty (${sourceFile.length()} bytes)."
        }

        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000L
        val fileBase = "RSMovieSpot_${safeFileName(titleOrTopic)}_$nowMs"
        val displayName = "$fileBase.mp4"

        val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
        exportDir.listFiles()?.forEach { oldFile ->
            if (oldFile.isFile) oldFile.delete()
        }
        val exportCopy = File(exportDir, displayName)
        sourceFile.copyTo(exportCopy, overwrite = true)

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.TITLE, fileBase)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, nowSec)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowSec)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DCIM}/Camera"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val galleryUri = resolver.insert(collection, values)
            ?: throw IOException("Could not create a MediaStore video entry.")

        try {
            val output = resolver.openOutputStream(galleryUri)
                ?: throw IOException("Could not open MediaStore output stream.")

            output.use { destination ->
                sourceFile.inputStream().use { input -> input.copyTo(destination) }
                destination.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completedValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(galleryUri, completedValues, null, null)
            }

            runCatching {
                resolver.query(
                    galleryUri,
                    arrayOf(MediaStore.Video.Media.DATA),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val path = cursor.getString(0)
                        if (!path.isNullOrBlank()) {
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(path),
                                arrayOf("video/mp4"),
                                null
                            )
                        }
                    }
                }
            }
        } catch (error: Exception) {
            resolver.delete(galleryUri, null, null)
            throw error
        }

        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportCopy
            )
        }.getOrDefault(galleryUri)
    }

    private fun publishThumbnailToMediaStore(sourceFile: File, titleOrTopic: String): Uri? {
        if (!sourceFile.exists() || sourceFile.length() < 1024L) return null
        val nowMs = System.currentTimeMillis()
        val displayName = "RSMovieSpot_Cover_${safeFileName(titleOrTopic)}_$nowMs.jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, nowMs / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RS Movie Spot")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { dest ->
                sourceFile.inputStream().use { it.copyTo(dest) }
            } ?: throw IOException("Could not write cover thumbnail.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        }.getOrElse {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun safeFileName(value: String): String =
        stripDecorativeEmojis(value)
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('_')
            .take(45)
            .ifBlank { "Video" }

    private fun escapeConcatPath(path: String): String =
        path.replace("\\", "\\\\").replace("'", "'\\''")

    private companion object {
        const val SHORTS_WIDTH = 1080
        const val SHORTS_HEIGHT = 1920
        const val LANDSCAPE_WIDTH = 1920
        const val LANDSCAPE_HEIGHT = 1080
        const val FRAME_RATE = 25
        const val MIN_SCENE_FRAMES = 15
        const val AUDIO_TAIL_PADDING_SECONDS = 0.35
    }
}
