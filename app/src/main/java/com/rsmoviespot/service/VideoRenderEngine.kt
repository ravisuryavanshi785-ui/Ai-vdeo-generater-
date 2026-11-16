package com.rsmoviespot.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.rsmoviespot.data.repository.VideoRepository
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
import kotlin.random.Random

data class VideoRenderResult(
    val galleryUri: Uri,
    val segmentCount: Int,
    val width: Int,
    val height: Int
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
                    voiceGender = voiceGender
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

            val videoSegmentFiles = mutableListOf<File>()
            val slabCount = script.slabs.size

            script.slabs.forEachIndexed { index, slab ->
                val baseProgress = 14 + (index * 68 / slabCount)
                progress(
                    baseProgress.coerceIn(14, 82),
                    "Creating scene ${index + 1} of $slabCount…"
                )

                val imageFile = File(renderDirectory, "slab_${index + 1}.png")
                val videoSegmentFile = File(renderDirectory, "vseg_${index + 1}.mp4")

                val imagePrompt = buildSceneImagePrompt(
                    characterBible = script.character_bible,
                    visualStyle = script.visual_style,
                    scenePrompt = slab.image_prompt,
                    hasCharacter = slab.has_character
                )

                val sceneSeed = baseSeed + (index * 37)

                videoRepository.generateImageForSlab(
                    prompt = imagePrompt,
                    isShorts = isShorts,
                    seed = sceneSeed
                ).use { body ->
                    imageFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
                validateImageFile(imageFile, index + 1)

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

                val renderProgress = 14 + (((index + 0.5) * 68) / slabCount).roundToInt()
                progress(
                    renderProgress.coerceIn(14, 82),
                    "Animating scene ${index + 1} of $slabCount…"
                )

                runFfmpeg(
                    createVideoOnlySegmentArguments(
                        sceneIndex = index,
                        totalScenes = slabCount,
                        imageFile = imageFile,
                        popCaptions = popCaptions,
                        outputFile = videoSegmentFile,
                        width = width,
                        height = height,
                        segmentFrames = sceneFramesList[index]
                    )
                )
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

            progress(90, "Mastering studio voice, BGM & SFX…")
            val sfxTrackFile = synthesizeTimedSfxTrackWav(
                renderDirectory = renderDirectory,
                slabs = script.slabs,
                sceneStartSeconds = sceneStartSeconds,
                totalDurationSeconds = exactVideoDuration
            )

            val mixedAudioFile = File(renderDirectory, "final_audio.m4a")
            val finalAudioReady = mixAudioTrackSafely(
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

            progress(96, "Saving video to gallery…")
            val galleryUri = publishToMediaStore(finalFile, topic)
            progress(100, "Video ready")

            VideoRenderResult(
                galleryUri = galleryUri,
                segmentCount = videoSegmentFiles.size,
                width = width,
                height = height
            )
        } finally {
            renderDirectory.deleteRecursively()
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

    private fun buildSceneImagePrompt(
        characterBible: String,
        visualStyle: String,
        scenePrompt: String,
        hasCharacter: Boolean
    ): String {
        val sanitizedScene = scenePrompt
            .replace(
                Regex(
                    "\\b(comment box|subscribe button|subscribe|social media|website|ui overlay|user interface|title card|movie poster|poster|text overlay)\\b",
                    RegexOption.IGNORE_CASE
                ),
                "dramatic cinematic background"
            )
            .trim()

        return buildString {
            if (hasCharacter && characterBible.isNotBlank()) {
                append("Character appearance: ")
                append(characterBible.trim())
                append(". ")
            }
            append(sanitizedScene)
            if (visualStyle.isNotBlank()) {
                append(". Style: ")
                append(visualStyle.trim())
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

                    val isSnareBeat = (beatNumber % 2 == 1)
                    val snap = if (isSnareBeat) {
                        rand.nextDouble(-1.0, 1.0) * exp(-28.0 * beatT) * 0.26
                    } else 0.0

                    pluck + bass + snap
                }
                BgmStyle.SUSPENSE_THRILLER -> {
                    val tick = (sin(2.0 * PI * 1950.0 * halfBeatT) * 0.5 +
                        rand.nextDouble(-1.0, 1.0) * 0.5) * exp(-55.0 * halfBeatT) * 0.22

                    val heartbeat = if (beatNumber % 2 == 0) {
                        sin(2.0 * PI * 72.0 * beatT) * exp(-12.0 * beatT) * 0.40
                    } else 0.0

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
                    } else 0.0

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
                    } else 0.0

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

    private suspend fun mixAudioTrackSafely(
        fullVoiceFile: File,
        bgmBedFile: File?,
        sfxTrackFile: File?,
        totalDurationSeconds: Double,
        outputFile: File
    ): File? {
        val formattedDuration = String.format(Locale.US, "%.2f", totalDurationSeconds)
        val voiceFilter =
            "[0:a]apad,atrim=0:$formattedDuration,asetpts=PTS-STARTPTS," +
                "highpass=f=85," +
                "equalizer=f=180:t=o:w=1.2:g=3.5," +
                "equalizer=f=3200:t=o:w=1.5:g=3.0," +
                "acompressor=threshold=-16dB:ratio=3:attack=5:release=60," +
                "volume=1.15[v]"

        val args = when {
            bgmBedFile != null && sfxTrackFile != null -> arrayOf(
                "-y",
                "-i", fullVoiceFile.absolutePath,
                "-stream_loop", "-1", "-t", formattedDuration, "-i", bgmBedFile.absolutePath,
                "-i", sfxTrackFile.absolutePath,
                "-filter_complex",
                "$voiceFilter;" +
                    "[1:a]atrim=0:$formattedDuration,asetpts=PTS-STARTPTS,volume=0.25[b];" +
                    "[2:a]atrim=0:$formattedDuration,asetpts=PTS-STARTPTS,volume=0.35[s];" +
                    "[v][b][s]amix=inputs=3:duration=first:normalize=0[aout]",
                "-map", "[aout]",
                "-t", formattedDuration,
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "44100",
                "-ac", "2",
                outputFile.absolutePath
            )
            bgmBedFile != null -> arrayOf(
                "-y",
                "-i", fullVoiceFile.absolutePath,
                "-stream_loop", "-1", "-t", formattedDuration, "-i", bgmBedFile.absolutePath,
                "-filter_complex",
                "$voiceFilter;" +
                    "[1:a]atrim=0:$formattedDuration,asetpts=PTS-STARTPTS,volume=0.25[b];" +
                    "[v][b]amix=inputs=2:duration=first:normalize=0[aout]",
                "-map", "[aout]",
                "-t", formattedDuration,
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "44100",
                "-ac", "2",
                outputFile.absolutePath
            )
            sfxTrackFile != null -> arrayOf(
                "-y",
                "-i", fullVoiceFile.absolutePath,
                "-i", sfxTrackFile.absolutePath,
                "-filter_complex",
                "$voiceFilter;" +
                    "[1:a]atrim=0:$formattedDuration,asetpts=PTS-STARTPTS,volume=0.35[s];" +
                    "[v][s]amix=inputs=2:duration=first:normalize=0[aout]",
                "-map", "[aout]",
                "-t", formattedDuration,
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "44100",
                "-ac", "2",
                outputFile.absolutePath
            )
            else -> arrayOf(
                "-y",
                "-i", fullVoiceFile.absolutePath,
                "-filter_complex",
                "$voiceFilter",
                "-map", "[v]",
                "-t", formattedDuration,
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "44100",
                "-ac", "2",
                outputFile.absolutePath
            )
        }

        val ok = runCatching { runFfmpeg(args) }.isSuccess
        return outputFile.takeIf { ok && it.exists() && it.length() > 0L }
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

    private fun createVideoOnlySegmentArguments(
        sceneIndex: Int,
        totalScenes: Int,
        imageFile: File,
        popCaptions: List<PopCaptionChunk>,
        outputFile: File,
        width: Int,
        height: Int,
        segmentFrames: Int
    ): Array<String> {
        val frames = segmentFrames.coerceAtLeast(1)
        val denom = (frames - 1).coerceAtLeast(1)
        val durationSeconds = frames.toDouble() / FRAME_RATE
        val fadeOutStart = String.format(
            Locale.US,
            "%.2f",
            (durationSeconds - 0.30).coerceAtLeast(0.05)
        )

        val upWidth = (width * 3) / 2
        val upHeight = (height * 3) / 2

        val motionFilter = when (sceneIndex % 4) {
            0 ->
                "zoompan=z='min(zoom+0.0025,1.25)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            1 ->
                "zoompan=z='if(lte(on,1),1.25,max(1.0,zoom-0.0025))':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            2 ->
                "zoompan=z='1.20':x='(iw-iw/zoom)*on/$denom':y='ih/2-(ih/zoom/2)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            else ->
                "zoompan=z='1.20':x='iw/2-(iw/zoom/2)':y='(ih-ih/zoom)*(1-on/$denom)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
        }

        val edgeFades = buildString {
            if (sceneIndex == 0) append("fade=t=in:st=0:d=0.22,")
            if (sceneIndex == totalScenes - 1) append("fade=t=out:st=$fadeOutStart:d=0.28,")
        }

        val baseGrade = "eq=contrast=1.06:saturation=1.10,vignette=PI/6"

        if (popCaptions.isNotEmpty()) {
            val args = mutableListOf("-y", "-i", imageFile.absolutePath)
            popCaptions.forEach { chunk ->
                args += "-i"
                args += chunk.file.absolutePath
            }

            val filterBuilder = StringBuilder()
            filterBuilder.append(
                "[0:v]scale=$upWidth:$upHeight:force_original_aspect_ratio=increase," +
                    "crop=$upWidth:$upHeight," +
                    "setsar=1," +
                    "$motionFilter," +
                    "$baseGrade[bg0];"
            )

            popCaptions.forEachIndexed { idx, chunk ->
                val inputIndex = idx + 1
                val prevLabel = "bg$idx"
                val nextLabel = if (idx == popCaptions.lastIndex) "vcap" else "bg${idx + 1}"
                val overlayY = if (height > width) {
                    (height - chunk.stripHeight - 380).coerceAtLeast(0)
                } else {
                    (height - chunk.stripHeight - 55).coerceAtLeast(0)
                }
                val st = String.format(Locale.US, "%.2f", chunk.startSeconds)
                val en = String.format(Locale.US, "%.2f", chunk.endSeconds)
                filterBuilder.append(
                    "[$prevLabel][$inputIndex:v]overlay=0:$overlayY:eof_action=repeat:enable='between(t,$st,$en)'[$nextLabel];"
                )
            }

            filterBuilder.append("[vcap]${edgeFades}format=yuv420p[vout]")

            args += listOf(
                "-filter_complex", filterBuilder.toString(),
                "-map", "[vout]",
                "-frames:v", frames.toString(),
                "-an",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "21",
                "-threads", "2",
                "-pix_fmt", "yuv420p",
                "-r", FRAME_RATE.toString(),
                outputFile.absolutePath
            )
            return args.toTypedArray()
        }

        val filter =
            "scale=$upWidth:$upHeight:force_original_aspect_ratio=increase," +
                "crop=$upWidth:$upHeight," +
                "setsar=1," +
                "$motionFilter," +
                "$baseGrade," +
                edgeFades +
                "format=yuv420p"

        return arrayOf(
            "-y",
            "-i", imageFile.absolutePath,
            "-vf", filter,
            "-frames:v", frames.toString(),
            "-an",
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "21",
            "-threads", "2",
            "-pix_fmt", "yuv420p",
            "-r", FRAME_RATE.toString(),
            outputFile.absolutePath
        )
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

    private fun publishToMediaStore(sourceFile: File, topic: String): Uri {
        require(sourceFile.exists() && sourceFile.length() > 1024L) {
            "Rendered video file is missing or empty (${sourceFile.length()} bytes)."
        }

        val resolver = context.contentResolver
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000L
        val displayName = "${safeFileName(topic)}_$nowMs.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.TITLE, displayName.removeSuffix(".mp4"))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, nowSec)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowSec)
            put(MediaStore.Video.Media.SIZE, sourceFile.length())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DCIM}/RSMovieSpot"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create a MediaStore video entry.")

        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Could not open MediaStore output stream.")

            output.use { destination ->
                sourceFile.inputStream().use { input -> input.copyTo(destination) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completedValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, completedValues, null, null)
            }

            runCatching {
                resolver.query(
                    uri,
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

            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun safeFileName(value: String): String =
        value.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(60)
            .ifBlank { "RSMovieSpot" }

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
