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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
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
                    inputMode = inputMode
                )
            }
            require(script.slabs.isNotEmpty()) { "The generated script has no slabs." }

            val activeVoiceName = if (language != VideoLanguage.AUTO) {
                language.resolveVoice(voiceGender)
            } else {
                script.voice_name.ifBlank { VideoLanguage.HINDI.resolveVoice(voiceGender) }
            }

            val baseSeed = (topic.trim().lowercase().hashCode() and 0x7FFFFFFF)
                .coerceAtLeast(1000)

            progress(7, "Recording dynamic voiceover…")
            val trimmedVoiceWavs = synthesizeAndTrimVoiceClips(
                renderDirectory = renderDirectory,
                slabs = script.slabs,
                voiceName = activeVoiceName
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

            val sceneStartSeconds = mutableListOf<Double>()
            var frameCursor = 0
            for (frames in sceneFramesList) {
                sceneStartSeconds += frameCursor.toDouble() / FRAME_RATE
                frameCursor += frames
            }

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

            val videoSegmentFiles = mutableListOf<File>()
            val slabCount = script.slabs.size
            val usedRealPhotoUrls = mutableSetOf<String>()
            val usedClipUrls = mutableSetOf<String>()
            var coverBaseImageFile: File? = null

            script.slabs.forEachIndexed { index, slab ->
                val baseProgress = 14 + (index * 68 / slabCount)
                val videoSegmentFile = File(renderDirectory, "vseg_${index + 1}.mp4")
                val segmentExactDuration = sceneFramesList[index].toDouble() / FRAME_RATE

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

                val isVideoClip = slab.visual_source.equals("video_clip", ignoreCase = true) ||
                    slab.visual_source.equals("meme_clip", ignoreCase = true)

                var encodedClip = false
                if (isVideoClip && slab.real_photo_query.isNotBlank()) {
                    val clipLabel = if (slab.visual_source.equals("meme_clip", ignoreCase = true)) {
                        "Fetching internet meme (${slab.real_photo_query})…"
                    } else {
                        "Fetching movie/trailer clip (${slab.real_photo_query})…"
                    }
                    progress(baseProgress.coerceIn(14, 82), clipLabel)

                    val rawClipFile = File(renderDirectory, "clip_raw_${index + 1}.mp4")
                    val downloaded = runCatching {
                        videoRepository.downloadInternetVideoClip(
                            query = slab.real_photo_query,
                            isMeme = slab.visual_source.equals("meme_clip", ignoreCase = true),
                            usedUrls = usedClipUrls,
                            destinationFile = rawClipFile
                        )
                    }.getOrDefault(false)

                    if (downloaded) {
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

                        val renderProgress = 14 + (((index + 0.5) * 68) / slabCount).roundToInt()
                        progress(
                            renderProgress.coerceIn(14, 82),
                            "Editing clip scene ${index + 1} of $slabCount…"
                        )
                        encodedClip = runCatching {
                            runFfmpeg(
                                createVideoClipSegmentArguments(
                                    clipFile = rawClipFile,
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
                    }
                    rawClipFile.delete()
                }

                if (!encodedClip) {
                    val layoutMode = slab.layout_mode.lowercase().trim()
                    val imageFile1 = resolveSceneImageFile(
                        renderDirectory = renderDirectory,
                        tag = "${index + 1}_1",
                        visualSource = if (isVideoClip) "real_photo" else slab.visual_source,
                        realQuery = slab.real_photo_query,
                        scenePrompt = slab.image_prompt,
                        characterBible = script.character_bible,
                        visualStyle = script.visual_style,
                        hasCharacter = slab.has_character,
                        isShorts = isShorts,
                        width = width,
                        height = height,
                        seed = baseSeed + (index * 37),
                        usedUrls = usedRealPhotoUrls,
                        progressUpdate = { msg -> progress(baseProgress.coerceIn(14, 82), msg) },
                        sceneIndex = index,
                        slabCount = slabCount
                    )

                    if (index == 0 && coverBaseImageFile == null) {
                        coverBaseImageFile = File(renderDirectory, "cover_base.png").also {
                            imageFile1.copyTo(it, overwrite = true)
                        }
                    }

                    val hasSecondPrompt = slab.image_prompt_2.isNotBlank() ||
                        (slab.visual_source_2.equals("real_photo", ignoreCase = true) &&
                            slab.real_photo_query_2.isNotBlank())

                    if ((layoutMode == "split" || layoutMode == "flip") && hasSecondPrompt) {
                        val imageFile2 = resolveSceneImageFile(
                            renderDirectory = renderDirectory,
                            tag = "${index + 1}_2",
                            visualSource = slab.visual_source_2.ifBlank { "ai_generated" },
                            realQuery = slab.real_photo_query_2,
                            scenePrompt = slab.image_prompt_2.ifBlank { slab.image_prompt },
                            characterBible = script.character_bible,
                            visualStyle = script.visual_style,
                            hasCharacter = slab.has_character,
                            isShorts = isShorts,
                            width = width,
                            height = height,
                            seed = baseSeed + (index * 37) + 19,
                            usedUrls = usedRealPhotoUrls,
                            progressUpdate = { msg -> progress(baseProgress.coerceIn(14, 82), msg) },
                            sceneIndex = index,
                            slabCount = slabCount
                        )

                        val renderProgress = 14 + (((index + 0.5) * 68) / slabCount).roundToInt()
                        progress(
                            renderProgress.coerceIn(14, 82),
                            "Editing scene ${index + 1} of $slabCount…"
                        )

                        if (layoutMode == "split") {
                            composeSplitFrame(imageFile1, imageFile2, imageFile1, width, height)
                            runFfmpeg(
                                createVideoOnlySegmentArguments(
                                    sceneIndex = index,
                                    totalScenes = slabCount,
                                    imageFile = imageFile1,
                                    overlaySpecs = overlaySpecs,
                                    outputFile = videoSegmentFile,
                                    width = width,
                                    height = height,
                                    segmentFrames = sceneFramesList[index]
                                )
                            )
                        } else {
                            runFfmpeg(
                                createFlipVideoSegmentArguments(
                                    sceneIndex = index,
                                    totalScenes = slabCount,
                                    imageFile1 = imageFile1,
                                    imageFile2 = imageFile2,
                                    overlaySpecs = overlaySpecs,
                                    outputFile = videoSegmentFile,
                                    width = width,
                                    height = height,
                                    segmentFrames = sceneFramesList[index]
                                )
                            )
                        }
                    } else {
                        val renderProgress = 14 + (((index + 0.5) * 68) / slabCount).roundToInt()
                        progress(
                            renderProgress.coerceIn(14, 82),
                            "Editing scene ${index + 1} of $slabCount…"
                        )

                        runFfmpeg(
                            createVideoOnlySegmentArguments(
                                sceneIndex = index,
                                totalScenes = slabCount,
                                imageFile = imageFile1,
                                overlaySpecs = overlaySpecs,
                                outputFile = videoSegmentFile,
                                width = width,
                                height = height,
                                segmentFrames = sceneFramesList[index]
                            )
                        )
                    }
                }

                videoSegmentFiles += videoSegmentFile
            }

            progress(85, "Syncing scenes with voiceover…")
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

            progress(90, "Mastering studio voice, auto-ducked BGM & SFX…")
            val sfxTrackFile = synthesizeTimedSfxTrackWav(
                renderDirectory = renderDirectory,
                slabs = script.slabs,
                sceneStartSeconds = sceneStartSeconds,
                totalDurationSeconds = exactVideoDuration
            )

            val mixedAudioFile = File(renderDirectory, "final_audio.m4a")
            val finalAudioReady = mixAudioTrackSafely(
                renderDirectory = renderDirectory,
                fullVoiceFile = fullVoiceFile,
                bgmBedFile = bgmBedFile,
                sfxTrackFile = sfxTrackFile,
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

    private suspend fun resolveSceneImageFile(
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
        progressUpdate: (String) -> Unit,
        sceneIndex: Int,
        slabCount: Int
    ): File {
        val imageFile = File(renderDirectory, "slab_$tag.png")
        val wantsRealPhoto = visualSource.equals("real_photo", ignoreCase = true) && realQuery.isNotBlank()

        if (wantsRealPhoto) {
            progressUpdate("Fetching real photo ($realQuery)…")
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
            if (prepared) return imageFile
        }

        progressUpdate("Generating AI scene ${sceneIndex + 1} of $slabCount…")
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

        if (primaryOk) return imageFile

        val retryOk = runCatching {
            val is3dAnimated = listOf("3d", "pixar", "disney", "cartoon", "anime").any {
                scenePrompt.contains(it, ignoreCase = true) || visualStyle.contains(it, ignoreCase = true)
            }
            val safePrompt = if (is3dAnimated) {
                "3D Pixar Disney cartoon animation, vibrant sunny storybook forest meadow with glowing flowers and soft sunlight"
            } else {
                "dramatic packed Indian cinema theater, warm golden spotlights, action hero silhouette, bright colorful atmospheric lighting"
            }
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

        if (!retryOk) {
            createFallbackCinemaFrame(imageFile, width, height, sceneIndex)
        }
        return imageFile
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

        popCaptions.forEach { chunk ->
            val overlayY = if (isShorts) {
                (videoHeight - chunk.stripHeight - 360).coerceAtLeast(0)
            } else {
                (videoHeight - chunk.stripHeight - 55).coerceAtLeast(0)
            }
            val st = String.format(Locale.US, "%.2f", chunk.startSeconds)
            val en = String.format(Locale.US, "%.2f", chunk.endSeconds)
            specs += OverlaySpec(
                file = chunk.file,
                xExpr = "0",
                yExpr = overlayY.toString(),
                enableExpr = "between(t,$st,$en)"
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
        voiceName: String
    ): List<File> = coroutineScope {
        val semaphore = Semaphore(4)
        val rawFiles = slabs.mapIndexed { index, slab ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val rawMp3 = File(renderDirectory, "raw_voice_${index + 1}.mp3")
                    val bytes = videoRepository.generateDynamicVoiceClip(
                        text = slab.voiceover_text,
                        voiceName = voiceName,
                        emotion = slab.voice_emotion
                    )
                    rawMp3.writeBytes(bytes)
                    if (!rawMp3.exists() || rawMp3.length() == 0L) {
                        throw IOException("Voice generation returned empty audio for scene ${index + 1}.")
                    }
                    rawMp3
                }
            }
        }.awaitAll()

        val wavFiles = mutableListOf<File>()
        rawFiles.forEachIndexed { index, rawMp3 ->
            val trimmedWav = File(renderDirectory, "voice_part_${index + 1}.wav")
            val isLast = index == rawFiles.lastIndex
            val microBreath = if (isLast) 0.02 else 0.06

            val trimmedOk = runCatching {
                runFfmpeg(
                    arrayOf(
                        "-y",
                        "-i", rawMp3.absolutePath,
                        "-af",
                        "silenceremove=start_periods=1:start_duration=0.02:start_threshold=-45dB," +
                            "areverse," +
                            "silenceremove=start_periods=1:start_duration=0.02:start_threshold=-45dB," +
                            "areverse," +
                            "apad=pad_dur=$microBreath",
                        "-ar", "44100",
                        "-ac", "1",
                        "-c:a", "pcm_s16le",
                        trimmedWav.absolutePath
                    )
                )
            }.isSuccess

            if (!trimmedOk || !trimmedWav.exists() || trimmedWav.length() <= 44L) {
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
            wavFiles += trimmedWav
        }
        wavFiles
    }

    private fun getPcmWavDurationSeconds(wavFile: File): Double {
        val dataBytes = (wavFile.length() - 44L).coerceAtLeast(0L)
        return dataBytes.toDouble() / (44100.0 * 2.0)
    }

    private fun createPopCaptionOverlays(
        renderDirectory: File,
        sceneIndex: Int,
        fullSceneText: String,
        sceneDurationSeconds: Double,
        videoWidth: Int,
        isShorts: Boolean
    ): List<PopCaptionChunk> {
        val chunksText = splitIntoPopChunks(fullSceneText, maxWordsPerChunk = if (isShorts) 4 else 6)
        if (chunksText.isEmpty()) return emptyList()

        val weights = chunksText.map { chunk ->
            chunk.count { !it.isWhitespace() }.coerceAtLeast(1).toDouble()
        }
        val totalWeight = weights.sum().coerceAtLeast(1.0)

        val result = mutableListOf<PopCaptionChunk>()
        var cursorTime = 0.0

        chunksText.forEachIndexed { chunkIdx, chunkText ->
            val chunkDur = (weights[chunkIdx] / totalWeight) * sceneDurationSeconds
            val startTime = cursorTime
            val endTime = if (chunkIdx == chunksText.lastIndex) {
                sceneDurationSeconds + 0.05
            } else {
                cursorTime + chunkDur
            }
            cursorTime += chunkDur

            val pngFile = File(renderDirectory, "pop_${sceneIndex + 1}_${chunkIdx + 1}.png")
            val stripHeight = renderCaptionPng(
                text = chunkText,
                videoWidth = videoWidth,
                isShorts = isShorts,
                outputFile = pngFile
            )
            if (stripHeight != null && pngFile.exists()) {
                result += PopCaptionChunk(
                    file = pngFile,
                    stripHeight = stripHeight,
                    startSeconds = startTime,
                    endSeconds = endTime
                )
            }
        }
        return result
    }

    private fun splitIntoPopChunks(text: String, maxWordsPerChunk: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        if (words.size <= maxWordsPerChunk) return listOf(words.joinToString(" "))

        val chunks = mutableListOf<String>()
        var idx = 0
        while (idx < words.size) {
            val remaining = words.size - idx
            val takeCount = when {
                remaining <= maxWordsPerChunk -> remaining
                remaining == maxWordsPerChunk + 1 -> maxWordsPerChunk - 1
                else -> maxWordsPerChunk
            }
            chunks += words.subList(idx, idx + takeCount).joinToString(" ")
            idx += takeCount
        }
        return chunks
    }

    private fun renderCaptionPng(
        text: String,
        videoWidth: Int,
        isShorts: Boolean,
        outputFile: File
    ): Int? {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return null

        val horizontalMargin = if (isShorts) 105 else 180
        val maxTextWidth = (videoWidth - horizontalMargin * 2).coerceAtLeast(320)
        val boxPadX = if (isShorts) 30f else 34f
        val boxPadY = if (isShorts) 18f else 16f
        val maxStripHeight = if (isShorts) 320 else 240

        var fontSize = if (isShorts) 56f else 46f
        lateinit var fillPaint: TextPaint
        lateinit var layout: StaticLayout

        while (fontSize >= 36f) {
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

            if (layout.lineCount <= 2 && (layout.height + boxPadY * 2 + 24f) <= maxStripHeight) {
                break
            }
            fontSize -= 4f
        }

        val strokePaint = TextPaint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (isShorts) 11f else 9f
            strokeJoin = Paint.Join.ROUND
            strokeMiter = 10f
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
                    "\\b(comment box|subscribe button|subscribe|social media|website|ui overlay|user interface|title card|movie poster|poster|text overlay|stat badge|youtube shorts?|video_clip|meme_clip|split layout|act as|collage|photo grid|grid of|panels)\\b",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("\\banimal\\s+(?:movie|film|teaser|trailer)\\b", RegexOption.IGNORE_CASE), "intense Bollywood action film")
            .replace(Regex("\\bspirit\\s+(?:movie|film|teaser|trailer)\\b", RegexOption.IGNORE_CASE), "fierce police thriller")
            .replace(Regex("\\bdark violence\\b|\\bbrutal violence\\b|\\bviolence\\b", RegexOption.IGNORE_CASE), "dramatic action confrontation in moody lighting")
            .replace(Regex("\\bblood rivers?\\b|\\brivers of blood\\b", RegexOption.IGNORE_CASE), "dramatic crimson-lit action showdown with flying sparks")
            .replace(Regex("\\bbox[- ]?office\\b", RegexOption.IGNORE_CASE), "cheering packed cinema hall")
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
                            sin(2.0 * PI * freq * 2.0 * t) * 0.06 +
                            sin(2.0 * PI * freq * 3.0 * t) * 0.03
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

    private fun synthesizeTimedSfxTrackWav(
        renderDirectory: File,
        slabs: List<VideoSlab>,
        sceneStartSeconds: List<Double>,
        totalDurationSeconds: Double
    ): File? {
        val sfxTrackFile = File(renderDirectory, "sfx_track.wav")
        val sampleRate = 44100
        val totalSamples = (totalDurationSeconds * sampleRate).roundToInt().coerceAtLeast(sampleRate)

        return runCatching {
            val buffer = FloatArray(totalSamples)

            slabs.forEachIndexed { sceneIndex, slab ->
                val startSample = (sceneStartSeconds[sceneIndex] * sampleRate)
                    .roundToInt()
                    .coerceIn(0, totalSamples - 1)
                val rand = Random(sceneIndex * 131 + slab.sfx_cue.hashCode())

                when (slab.sfx_cue.lowercase()) {
                    "thunder" -> {
                        val len = (sampleRate * 1.4).toInt()
                        var lp = 0.0
                        for (k in 0 until len) {
                            val idx = startSample + k
                            if (idx >= totalSamples) break
                            val t = k.toDouble() / sampleRate
                            val white = rand.nextDouble(-1.0, 1.0)
                            lp = lp * 0.94 + white * 0.06
                            val crack = exp(-12.0 * t) * white * 0.35
                            val rumble = exp(-1.8 * t) * lp * 2.2
                            buffer[idx] += (crack + rumble).toFloat()
                        }
                    }
                    "sword" -> {
                        val len = (sampleRate * 0.5).toInt()
                        for (k in 0 until len) {
                            val idx = startSample + k
                            if (idx >= totalSamples) break
                            val t = k.toDouble() / sampleRate
                            val env = exp(-8.5 * t)
                            val metallic = sin(2.0 * PI * 1450.0 * t) * 0.45 +
                                sin(2.0 * PI * 2180.0 * t) * 0.30 +
                                sin(2.0 * PI * 3120.0 * t) * 0.15
                            buffer[idx] += (metallic * env).toFloat()
                        }
                    }
                    "boom" -> {
                        val len = (sampleRate * 0.9).toInt()
                        for (k in 0 until len) {
                            val idx = startSample + k
                            if (idx >= totalSamples) break
                            val t = k.toDouble() / sampleRate
                            val env = exp(-4.5 * t)
                            val freq = 60.0 + 110.0 * exp(-9.0 * t)
                            val sub = sin(2.0 * PI * freq * t) * 0.75 +
                                sin(2.0 * PI * freq * 2.0 * t) * 0.25
                            buffer[idx] += (sub * env).toFloat()
                        }
                    }
                    "fire" -> {
                        val len = (sampleRate * 0.8).toInt()
                        var bp = 0.0
                        for (k in 0 until len) {
                            val idx = startSample + k
                            if (idx >= totalSamples) break
                            val t = k.toDouble() / sampleRate
                            val env = exp(-3.8 * t) * sin(PI * (t / 0.8).coerceIn(0.0, 1.0)).pow(0.5)
                            val white = rand.nextDouble(-1.0, 1.0)
                            bp = bp * 0.80 + white * 0.20
                            buffer[idx] += (bp * env * 1.4).toFloat()
                        }
                    }
                    else -> {
                        val len = (sampleRate * 0.55).toInt()
                        var bp = 0.0
                        for (k in 0 until len) {
                            val idx = startSample + k
                            if (idx >= totalSamples) break
                            val t = k.toDouble() / sampleRate
                            val env = sin(PI * (t / 0.55).coerceIn(0.0, 1.0)).pow(1.4)
                            val white = rand.nextDouble(-1.0, 1.0)
                            bp = bp * 0.85 + white * 0.15
                            buffer[idx] += (bp * env * 1.5).toFloat()
                        }
                    }
                }
            }

            writeMono16BitWav(sfxTrackFile, sampleRate, totalSamples) { idx ->
                buffer[idx].toDouble().coerceIn(-0.95, 0.95)
            }
            sfxTrackFile.takeIf { it.exists() && it.length() > 512L }
        }.getOrNull()
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

    private suspend fun mixAudioTrackSafely(
        renderDirectory: File,
        fullVoiceFile: File,
        bgmBedFile: File?,
        sfxTrackFile: File?,
        totalDurationSeconds: Double,
        outputFile: File
    ): File? {
        val formattedDuration = String.format(Locale.US, "%.2f", totalDurationSeconds)

        // 1) Studio Mic EQ on single-input voice (no amix deadlock)
        val eqVoiceFile = File(renderDirectory, "voice_eq.wav")
        val eqOk = runCatching {
            runFfmpeg(
                arrayOf(
                    "-y",
                    "-i", fullVoiceFile.absolutePath,
                    "-af",
                    "highpass=f=85," +
                        "equalizer=f=180:t=o:w=1.2:g=3.5," +
                        "equalizer=f=3200:t=o:w=1.5:g=3.0," +
                        "acompressor=threshold=-16dB:ratio=3:attack=5:release=60," +
                        "volume=1.15",
                    "-ar", "44100",
                    "-ac", "1",
                    "-c:a", "pcm_s16le",
                    eqVoiceFile.absolutePath
                )
            )
        }.isSuccess
        val voiceSource = if (eqOk && eqVoiceFile.exists() && eqVoiceFile.length() > 44L) {
            eqVoiceFile
        } else {
            fullVoiceFile
        }

        // 2) Ensure BGM is mono 44.1kHz PCM WAV
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

        // 3) Envelope-following Auto-Ducking + Kotlin PCM Mix (Voice + Ducked BGM + SFX)
        val mixedWavFile = File(renderDirectory, "mixed_master.wav")
        val kotlinMixOk = runCatching {
            val voiceSamples = readMono16BitWavSamples(voiceSource)
            val bgmSamples = bgmWavFile?.let { readMono16BitWavSamples(it) } ?: ShortArray(0)
            val sfxSamples = sfxTrackFile?.let { readMono16BitWavSamples(it) } ?: ShortArray(0)
            val totalSamples = (totalDurationSeconds * 44100.0).roundToInt().coerceAtLeast(44100)

            val windowSize = 1102 // ~25ms at 44100Hz
            val bgmGains = DoubleArray(totalSamples)
            var smoothedGain = 0.22
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
                val targetGain = if (rms > 0.024) 0.14 else 0.36
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

        // 4) Single-input AAC encode
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
                "zoompan=z='if(lte(on,8),1.0+0.12*(on/8),min(zoom+0.0018,1.25))':" +
                    "x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            1 ->
                "zoompan=z='if(lte(on,8),1.26-0.12*(on/8),max(1.0,zoom-0.0018))':" +
                    "x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            2 ->
                "zoompan=z='1.20':x='(iw-iw/zoom)*on/$denom':y='ih/2-(ih/zoom/2)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            3 ->
                "zoompan=z='1.20':x='(iw-iw/zoom)*(1-on/$denom)':y='ih/2-(ih/zoom/2)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            4 ->
                "zoompan=z='1.20':x='iw/2-(iw/zoom/2)':y='(ih-ih/zoom)*(1-on/$denom)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            else ->
                "zoompan=z='min(zoom+0.0022,1.22)':x='(iw-iw/zoom)*on/$denom':y='(ih-ih/zoom)*0.32':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
        }

        val baseGrade = "eq=contrast=1.07:saturation=1.12:brightness=0.01,unsharp=3:3:0.35,vignette=PI/6"
        return "scale=$upWidth:$upHeight:force_original_aspect_ratio=increase," +
            "crop=$upWidth:$upHeight,setsar=1,$motionFilter,$baseGrade"
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
        appendOverlayChain(args, filterBuilder, "bgbase", overlaySpecs, startInputIndex = 1, edgeFades = edgeFades)

        args += listOf(
            "-filter_complex", filterBuilder.toString(),
            "-map", "[vout]",
            "-frames:v", frames.toString(),
            "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
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
        appendOverlayChain(args, filterBuilder, "flipbase", overlaySpecs, startInputIndex = 2, edgeFades = edgeFades)

        args += listOf(
            "-filter_complex", filterBuilder.toString(),
            "-map", "[vout]",
            "-frames:v", frames.toString(),
            "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
            "-threads", "2", "-pix_fmt", "yuv420p", "-r", FRAME_RATE.toString(),
            outputFile.absolutePath
        )
        return args.toTypedArray()
    }

    private fun createVideoClipSegmentArguments(
        clipFile: File,
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
            "crop='if(gt(iw,ih),trunc(iw*0.92/2)*2,trunc(iw*0.96/2)*2)':'if(gt(iw,ih),trunc(ih*0.88/2)*2,trunc(ih*0.94/2)*2)':'iw*0.02':'ih*0.02'"

        val args = mutableListOf("-y", "-stream_loop", "-1", "-i", clipFile.absolutePath)
        val filterBuilder = StringBuilder(
            "[0:v]fps=$FRAME_RATE,setsar=1,$safeCrop,split=2[bgsrc][fgsrc];" +
                "[bgsrc]scale=$blurW:$blurH:force_original_aspect_ratio=increase,crop=$blurW:$blurH," +
                "scale=$width:$height:flags=bilinear,eq=brightness=-0.20:saturation=0.85[bg0];" +
                "[fgsrc]scale=$width:$height:force_original_aspect_ratio=decrease,pad=ceil(iw/2)*2:ceil(ih/2)*2[fg];" +
                "[bg0][fg]overlay=(W-w)/2:(H-h)/2,eq=contrast=1.07:saturation=1.12,unsharp=3:3:0.30,vignette=PI/6[clipbase];"
        )
        appendOverlayChain(args, filterBuilder, "clipbase", overlaySpecs, startInputIndex = 1, edgeFades = edgeFades)

        args += listOf(
            "-filter_complex", filterBuilder.toString(),
            "-map", "[vout]",
            "-frames:v", safeFrames.toString(),
            "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
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
