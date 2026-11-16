package com.rsmoviespot.service

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.rsmoviespot.data.repository.VideoRepository
import com.rsmoviespot.domain.models.VideoScriptResponse
import com.rsmoviespot.domain.models.VideoSlab
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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

private data class VttCue(
    val startSeconds: Double,
    val endSeconds: Double,
    val cleanLength: Int
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
                videoRepository.generateScript(topic, isShorts)
            }
            require(script.slabs.isNotEmpty()) { "The generated script has no slabs." }

            val baseSeed = (topic.trim().lowercase().hashCode() and 0x7FFFFFFF)
                .coerceAtLeast(1000)

            progress(7, "Recording continuous voiceover…")
            val fullNarrationText = buildContinuousNarrationText(script.slabs)
            val fullVoiceFile = File(renderDirectory, "full_voice.mp3")
            val ttsResult = videoRepository.generateContinuousAudioWithVtt(fullNarrationText)
            fullVoiceFile.writeBytes(ttsResult.audioBytes)
            if (!fullVoiceFile.exists() || fullVoiceFile.length() == 0L) {
                throw IOException("Generated voiceover audio is empty.")
            }

            val rawAudioDuration = getAudioDurationSeconds(fullVoiceFile)
            val totalVideoDuration = rawAudioDuration + AUDIO_TAIL_PADDING_SECONDS

            val sceneFramesList = computeSceneFrameCounts(
                slabs = script.slabs,
                vttContent = ttsResult.vttContent,
                rawAudioDuration = rawAudioDuration,
                totalVideoDuration = totalVideoDuration
            )
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
                val baseProgress = 12 + (index * 70 / slabCount)
                progress(
                    baseProgress.coerceIn(12, 82),
                    "Creating scene ${index + 1} of $slabCount…"
                )

                val imageFile = File(renderDirectory, "slab_${index + 1}.png")
                val videoSegmentFile = File(renderDirectory, "vseg_${index + 1}.mp4")

                val imagePrompt = buildSceneImagePrompt(
                    characterBible = script.character_bible,
                    visualStyle = script.visual_style,
                    scenePrompt = slab.image_prompt,
                    hasCharacter = slab.has_character,
                    isShorts = isShorts
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

                val renderProgress = 12 + (((index + 0.5) * 70) / slabCount).roundToInt()
                progress(
                    renderProgress.coerceIn(12, 82),
                    "Animating scene ${index + 1} of $slabCount…"
                )

                runFfmpeg(
                    createVideoOnlySegmentArguments(
                        sceneIndex = index,
                        totalScenes = slabCount,
                        imageFile = imageFile,
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

            progress(90, "Mixing continuous voice, BGM & SFX…")
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

    private fun buildContinuousNarrationText(slabs: List<VideoSlab>): String =
        slabs.joinToString(separator = " ") { it.voiceover_text.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun computeSceneFrameCounts(
        slabs: List<VideoSlab>,
        vttContent: String?,
        rawAudioDuration: Double,
        totalVideoDuration: Double
    ): List<Int> {
        val count = slabs.size
        val totalFrames = (totalVideoDuration * FRAME_RATE)
            .roundToInt()
            .coerceAtLeast(count * MIN_SCENE_FRAMES)

        if (count == 1) return listOf(totalFrames)

        val cues = parseVttCues(vttContent)
        val sceneWeights = slabs.map { computeSpeakingWeight(it.voiceover_text) }
        val totalWeight = sceneWeights.sum().coerceAtLeast(1.0)

        val boundaryTimes = DoubleArray(count + 1)
        boundaryTimes[0] = 0.0
        boundaryTimes[count] = totalVideoDuration

        if (cues.isNotEmpty()) {
            val totalCueChars = cues.sumOf { it.cleanLength }.coerceAtLeast(1)
            val vttEnd = cues.last().endSeconds.coerceAtLeast(0.5)
            val timeScale = (rawAudioDuration / vttEnd).coerceIn(0.85, 1.18)

            val cleanSceneLengths = slabs.map { cleanCharCount(it.voiceover_text).coerceAtLeast(1) }
            val totalSceneChars = cleanSceneLengths.sum().coerceAtLeast(1)

            var cumulativeSceneChars = 0
            for (i in 0 until count - 1) {
                cumulativeSceneChars += cleanSceneLengths[i]
                val targetCueChar = (cumulativeSceneChars.toDouble() / totalSceneChars) * totalCueChars
                val mappedVttTime = lookupTimeInVttCues(cues, targetCueChar)
                boundaryTimes[i + 1] = (mappedVttTime * timeScale).coerceIn(0.0, rawAudioDuration)
            }
        } else {
            var cumulativeWeight = 0.0
            for (i in 0 until count - 1) {
                cumulativeWeight += sceneWeights[i]
                boundaryTimes[i + 1] = (cumulativeWeight / totalWeight) * rawAudioDuration
            }
        }

        val boundaryFrames = IntArray(count + 1)
        boundaryFrames[0] = 0
        boundaryFrames[count] = totalFrames

        val minFramesPerScene = MIN_SCENE_FRAMES.coerceAtMost((totalFrames / count).coerceAtLeast(1))
        for (i in 1 until count) {
            val minAllowed = i * minFramesPerScene
            val maxAllowed = totalFrames - (count - i) * minFramesPerScene
            val rawFrame = (boundaryTimes[i] * FRAME_RATE).roundToInt()
            boundaryFrames[i] = rawFrame.coerceIn(minAllowed, maxAllowed)
            if (boundaryFrames[i] < boundaryFrames[i - 1] + minFramesPerScene) {
                boundaryFrames[i] = (boundaryFrames[i - 1] + minFramesPerScene).coerceAtMost(maxAllowed)
            }
        }

        return List(count) { i ->
            (boundaryFrames[i + 1] - boundaryFrames[i]).coerceAtLeast(1)
        }
    }

    private fun parseVttCues(vttContent: String?): List<VttCue> {
        if (vttContent.isNullOrBlank()) return emptyList()

        val timeRegex = Regex(
            """(\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}\s*-->\s*(\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}"""
        )
        val lines = vttContent.lines()
        val cues = mutableListOf<VttCue>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (timeRegex.containsMatchIn(line)) {
                val parts = line.split("-->")
                if (parts.size >= 2) {
                    val start = parseVttTimestamp(parts[0].trim())
                    val endToken = parts[1].trim().substringBefore(' ')
                    val end = parseVttTimestamp(endToken)

                    val textBuilder = StringBuilder()
                    var j = i + 1
                    while (j < lines.size && lines[j].isNotBlank()) {
                        if (textBuilder.isNotEmpty()) textBuilder.append(' ')
                        textBuilder.append(lines[j].trim())
                        j++
                    }

                    val cueChars = cleanCharCount(
                        textBuilder.toString().replace(Regex("<[^>]+>"), "")
                    ).coerceAtLeast(1)

                    if (start != null && end != null && end >= start) {
                        cues += VttCue(start, end, cueChars)
                    }
                    i = j
                    continue
                }
            }
            i++
        }
        return cues
    }

    private fun parseVttTimestamp(token: String): Double? {
        val normalized = token.replace(',', '.').trim()
        val parts = normalized.split(':')
        return when (parts.size) {
            3 -> {
                val h = parts[0].toDoubleOrNull() ?: return null
                val m = parts[1].toDoubleOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                h * 3600.0 + m * 60.0 + s
            }
            2 -> {
                val m = parts[0].toDoubleOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                m * 60.0 + s
            }
            else -> null
        }
    }

    private fun lookupTimeInVttCues(cues: List<VttCue>, targetChar: Double): Double {
        var acc = 0.0
        for (cue in cues) {
            val len = cue.cleanLength.toDouble().coerceAtLeast(1.0)
            if (targetChar <= acc + len) {
                val fraction = ((targetChar - acc) / len).coerceIn(0.0, 1.0)
                return cue.startSeconds + (cue.endSeconds - cue.startSeconds) * fraction
            }
            acc += len
        }
        return cues.last().endSeconds
    }

    private fun cleanCharCount(text: String): Int =
        text.count { !it.isWhitespace() && it !in "।.,!?;:\"'()-–—" }

    private fun computeSpeakingWeight(text: String): Double {
        val chars = cleanCharCount(text).coerceAtLeast(1)
        val majorPauses = text.count { it in "।!?." }
        val minorPauses = text.count { it in ",;:" }
        return chars + (majorPauses * 8.0) + (minorPauses * 4.0)
    }

    private fun buildSceneImagePrompt(
        characterBible: String,
        visualStyle: String,
        scenePrompt: String,
        hasCharacter: Boolean,
        isShorts: Boolean
    ): String = buildString {
        if (hasCharacter && characterBible.isNotBlank()) {
            append("Character appearance: ")
            append(characterBible.trim())
            append(". ")
        }
        append(scenePrompt.trim())
        if (visualStyle.isNotBlank()) {
            append(". Style: ")
            append(visualStyle.trim())
        }
        append(
            if (isShorts) {
                ", single cohesive cinematic frame, vertical 9:16 composition"
            } else {
                ", single cohesive cinematic frame, horizontal 16:9 composition"
            }
        )
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

        // Shift key and arpeggio pattern per topic so no two videos sound identical.
        val semitoneShifts = intArrayOf(-3, -2, 0, 2, 3)
        val semitoneShift = semitoneShifts[(bgmSeed and 0x7FFFFFFF) % semitoneShifts.size]
        val pitchScale = 2.0.pow(semitoneShift / 12.0)
        val arpVariant = ((bgmSeed ushr 4) and 0x7FFFFFFF) % 3

        val beatDuration: Double
        val beatsPerLoop: Int
        val baseChords: Array<DoubleArray>

        when (style) {
            BgmStyle.COMEDY_ROAST -> {
                beatDuration = 0.50 // 120 BPM bouncy
                beatsPerLoop = 32   // 16.0s seamless loop
                baseChords = arrayOf(
                    doubleArrayOf(261.63, 329.63, 392.00, 523.25), // C
                    doubleArrayOf(196.00, 246.94, 293.66, 392.00), // G
                    doubleArrayOf(220.00, 261.63, 329.63, 440.00), // Am
                    doubleArrayOf(174.61, 220.00, 261.63, 349.23)  // F
                )
            }
            BgmStyle.SUSPENSE_THRILLER -> {
                beatDuration = 0.75 // 80 BPM tense clock
                beatsPerLoop = 16   // 12.0s seamless loop
                baseChords = arrayOf(
                    doubleArrayOf(164.81, 196.00, 246.94, 329.63), // Em
                    doubleArrayOf(164.81, 174.61, 220.00, 349.23), // F/E tension
                    doubleArrayOf(164.81, 196.00, 246.94, 329.63), // Em
                    doubleArrayOf(155.56, 185.00, 220.00, 311.13)  // D#dim
                )
            }
            BgmStyle.EMOTIONAL_SAD -> {
                beatDuration = 0.80 // 75 BPM gentle piano
                beatsPerLoop = 16   // 12.8s seamless loop
                baseChords = arrayOf(
                    doubleArrayOf(220.00, 261.63, 329.63, 440.00), // Am
                    doubleArrayOf(174.61, 220.00, 261.63, 349.23), // F
                    doubleArrayOf(130.81, 196.00, 261.63, 329.63), // C
                    doubleArrayOf(196.00, 246.94, 293.66, 392.00)  // G
                )
            }
            BgmStyle.DEVOTIONAL -> {
                beatDuration = 0.75 // 80 BPM serene
                beatsPerLoop = 16   // 12.0s seamless loop
                baseChords = arrayOf(
                    doubleArrayOf(130.81, 196.00, 261.63, 293.66), // Csus2
                    doubleArrayOf(174.61, 220.00, 261.63, 392.00), // Fadd9
                    doubleArrayOf(196.00, 261.63, 293.66, 392.00), // Gsus4
                    doubleArrayOf(130.81, 196.00, 261.63, 329.63)  // C
                )
            }
            BgmStyle.SCI_FI_TECH -> {
                beatDuration = 0.50 // 120 BPM synthwave
                beatsPerLoop = 32   // 16.0s seamless loop
                baseChords = arrayOf(
                    doubleArrayOf(220.00, 261.63, 329.63, 440.00), // Am
                    doubleArrayOf(174.61, 220.00, 261.63, 349.23), // F
                    doubleArrayOf(146.83, 220.00, 293.66, 349.23), // Dm
                    doubleArrayOf(164.81, 207.65, 246.94, 329.63)  // E
                )
            }
            BgmStyle.MOTIVATIONAL -> {
                beatDuration = 0.60 // 100 BPM inspiring
                beatsPerLoop = 32   // 19.2s seamless loop
                baseChords = arrayOf(
                    doubleArrayOf(174.61, 220.00, 261.63, 349.23), // F
                    doubleArrayOf(196.00, 246.94, 293.66, 392.00), // G
                    doubleArrayOf(164.81, 196.00, 246.94, 329.63), // Em
                    doubleArrayOf(220.00, 261.63, 329.63, 440.00)  // Am
                )
            }
            BgmStyle.HORROR_DARK -> {
                beatDuration = 0.80 // 75 BPM eerie
                beatsPerLoop = 16   // 12.8s seamless loop
                baseChords = arrayOf(
                    doubleArrayOf(138.59, 164.81, 207.65, 277.18), // C#m
                    doubleArrayOf(138.59, 196.00, 246.94, 293.66), // tritone tension
                    doubleArrayOf(146.83, 174.61, 220.00, 293.66), // Dm
                    doubleArrayOf(138.59, 196.00, 233.08, 277.18)  // dim
                )
            }
            BgmStyle.EPIC_ACTION -> {
                beatDuration = 0.46875 // 128 BPM driving war rhythm
                beatsPerLoop = 32      // 15.0s seamless loop
                baseChords = arrayOf(
                    doubleArrayOf(146.83, 220.00, 293.66, 349.23), // Dm
                    doubleArrayOf(116.54, 174.61, 233.08, 293.66), // Bb
                    doubleArrayOf(130.81, 196.00, 261.63, 329.63), // C
                    doubleArrayOf(110.00, 164.81, 220.00, 277.18)  // A
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

            // Smooth cross-envelope so loop boundary at 0 / loopDuration never clicks.
            val loopEnv = (t / 0.04).coerceAtMost(1.0) *
                ((loopDuration - t) / 0.04).coerceAtMost(1.0)
            val chordEnv = (chordLocalT / 0.12).coerceAtMost(1.0) *
                ((chordDuration - chordLocalT) / 0.12).coerceAtMost(1.0)

            val sample = when (style) {
                BgmStyle.COMEDY_ROAST -> {
                    // Bouncy staccato pluck chords on every half-beat + cheerful bass + off-beat snap
                    val stepIdx = ((t / (beatDuration * 0.5)).toInt()) % arpOrder.size
                    val pluckFreq = chord[arpOrder[stepIdx]] * 1.5
                    val pluckEnv = exp(-12.0 * halfBeatT)
                    val pluck = (sin(2.0 * PI * pluckFreq * halfBeatT) +
                        0.35 * sin(2.0 * PI * pluckFreq * 2.0 * halfBeatT)) * pluckEnv * 0.36

                    val bassFreq = chord[0] * 0.5
                    val bass = sin(2.0 * PI * bassFreq * beatT) * exp(-7.5 * beatT) * 0.34

                    // Crisp snappy clap on odd beats (2 and 4)
                    val isSnareBeat = (beatNumber % 2 == 1)
                    val snap = if (isSnareBeat) {
                        rand.nextDouble(-1.0, 1.0) * exp(-28.0 * beatT) * 0.26
                    } else 0.0

                    pluck + bass + snap
                }
                BgmStyle.SUSPENSE_THRILLER -> {
                    // Ticking clock every half beat + slow heartbeat + eerie glassy chime on bar start
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
                    // Warm felt piano chords + delicate top melody notes (NO drums)
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
                    // Serene tanpura drone + bansuri vibrato melody + soft temple chime
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
                    // Fast 16th-note cyber synth arpeggio + punchy electronic kick + octave pad
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
                    // Bright uplifting plucks + warm anthem pad + steady kick
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
                    // Unsettling low drone + eerie shimmer + sparse metallic echo
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
                    // Double-hit war drums + rich brassy fifths + heroic rising arpeggio
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
        if (bgmBedFile == null && sfxTrackFile == null) return null

        val formattedDuration = String.format(Locale.US, "%.2f", totalDurationSeconds)

        val args = when {
            bgmBedFile != null && sfxTrackFile != null -> arrayOf(
                "-y",
                "-i", fullVoiceFile.absolutePath,
                "-stream_loop", "-1", "-t", formattedDuration, "-i", bgmBedFile.absolutePath,
                "-i", sfxTrackFile.absolutePath,
                "-filter_complex",
                "[0:a]apad,atrim=0:$formattedDuration,asetpts=PTS-STARTPTS,volume=1.0[v];" +
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
                "[0:a]apad,atrim=0:$formattedDuration,asetpts=PTS-STARTPTS,volume=1.0[v];" +
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
            else -> arrayOf(
                "-y",
                "-i", fullVoiceFile.absolutePath,
                "-i", sfxTrackFile!!.absolutePath,
                "-filter_complex",
                "[0:a]apad,atrim=0:$formattedDuration,asetpts=PTS-STARTPTS,volume=1.0[v];" +
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

    private fun getAudioDurationSeconds(audioFile: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioFile.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L

            if (durationMs > 200L) {
                (durationMs / 1000.0).coerceAtLeast(MIN_TOTAL_DURATION_SECONDS)
            } else {
                DEFAULT_TOTAL_DURATION_SECONDS
            }
        } catch (_: Exception) {
            DEFAULT_TOTAL_DURATION_SECONDS
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun createVideoOnlySegmentArguments(
        sceneIndex: Int,
        totalScenes: Int,
        imageFile: File,
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
                "zoompan=z='min(zoom+0.0012,1.18)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            1 ->
                "zoompan=z='if(lte(on,1),1.18,max(1.0,zoom-0.0012))':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            2 ->
                "zoompan=z='1.14':x='(iw-iw/zoom)*on/$denom':y='ih/2-(ih/zoom/2)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
            else ->
                "zoompan=z='1.14':x='iw/2-(iw/zoom/2)':y='(ih-ih/zoom)*(1-on/$denom)':" +
                    "d=$frames:s=${width}x${height}:fps=$FRAME_RATE"
        }

        val edgeFades = buildString {
            if (sceneIndex == 0) append("fade=t=in:st=0:d=0.22,")
            if (sceneIndex == totalScenes - 1) append("fade=t=out:st=$fadeOutStart:d=0.28,")
        }

        val filter =
            "scale=$upWidth:$upHeight:force_original_aspect_ratio=increase," +
                "crop=$upWidth:$upHeight," +
                "setsar=1," +
                "$motionFilter," +
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
        val resolver = context.contentResolver
        val displayName = "${safeFileName(topic)}_${System.currentTimeMillis()}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/RSMovieSpot"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

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
            .ifBlank { "video" }

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
        const val MIN_TOTAL_DURATION_SECONDS = 6.0
        const val DEFAULT_TOTAL_DURATION_SECONDS = 30.0
    }
}
