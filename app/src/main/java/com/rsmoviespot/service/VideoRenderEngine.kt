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
                customBgmUri = customBgmUri
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

            val finalFile = File(renderDirectory, "final_video.mp4")
            mixFinalVideoAudio(
                silentVideoFile = silentVideoFile,
                fullVoiceFile = fullVoiceFile,
                bgmBedFile = bgmBedFile,
                sfxTrackFile = sfxTrackFile,
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
        customBgmUri: Uri?
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

        // Built-in pure Kotlin 16-bit WAV music bed (phone-speaker audible mid-range chords + beat)
        val synthFile = File(renderDirectory, "bgm_bed.wav")
        return runCatching {
            writeCinematicBgmWav(synthFile, mood)
            synthFile.takeIf { it.exists() && it.length() > 1024L }
        }.getOrNull()
    }

    private fun copyOptionalRawAudio(renderDirectory: File, mood: String): File? {
        val candidates = listOf("bgm_${mood.lowercase()}", "bgm_epic")
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

    private fun writeCinematicBgmWav(outputFile: File, mood: String) {
        val sampleRate = 44100
        val durationSeconds = 16
        val totalSamples = sampleRate * durationSeconds

        val chords: Array<DoubleArray> = when (mood.lowercase()) {
            "devotional" -> arrayOf(
                doubleArrayOf(130.81, 196.00, 261.63, 329.63),
                doubleArrayOf(174.61, 220.00, 261.63, 349.23),
                doubleArrayOf(196.00, 246.94, 293.66, 392.00),
                doubleArrayOf(130.81, 196.00, 261.63, 329.63)
            )
            "emotional" -> arrayOf(
                doubleArrayOf(174.61, 220.00, 261.63, 349.23),
                doubleArrayOf(130.81, 196.00, 261.63, 329.63),
                doubleArrayOf(146.83, 220.00, 293.66, 349.23),
                doubleArrayOf(196.00, 246.94, 293.66, 392.00)
            )
            else -> arrayOf(
                doubleArrayOf(146.83, 220.00, 293.66, 349.23),
                doubleArrayOf(116.54, 174.61, 233.08, 293.66),
                doubleArrayOf(130.81, 196.00, 261.63, 329.63),
                doubleArrayOf(110.00, 164.81, 220.00, 277.18)
            )
        }

        val beatDuration = 0.5
        val chordDuration = 4.0

        writeMono16BitWav(outputFile, sampleRate, totalSamples) { i ->
            val t = i.toDouble() / sampleRate
            val chordIndex = ((t / chordDuration).toInt()).coerceIn(0, chords.size - 1)
            val chordLocalT = t - chordIndex * chordDuration
            val chord = chords[chordIndex]

            val chordEnv = (chordLocalT / 0.15).coerceAtMost(1.0) *
                ((chordDuration - chordLocalT) / 0.15).coerceAtMost(1.0)
            val lfo = 0.85 + 0.15 * sin(2.0 * PI * 0.5 * t)

            var pad = 0.0
            for (freq in chord) {
                pad += sin(2.0 * PI * freq * t) * 0.18 +
                    sin(2.0 * PI * freq * 2.0 * t) * 0.06
            }
            pad *= chordEnv * lfo

            val beatT = t % beatDuration
            val drumEnv = exp(-14.0 * beatT)
            val drumFreq = 65.0 + 95.0 * exp(-25.0 * beatT)
            val drum = sin(2.0 * PI * drumFreq * beatT) * drumEnv * 0.42

            (pad + drum).coerceIn(-0.95, 0.95)
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

    private suspend fun mixFinalVideoAudio(
        silentVideoFile: File,
        fullVoiceFile: File,
        bgmBedFile: File?,
        sfxTrackFile: File?,
        totalDurationSeconds: Double,
        outputFile: File
    ) {
        val formattedDuration = String.format(Locale.US, "%.2f", totalDurationSeconds)

        val args = when {
            bgmBedFile != null && sfxTrackFile != null -> arrayOf(
                "-y",
                "-i", silentVideoFile.absolutePath,
                "-i", fullVoiceFile.absolutePath,
                "-stream_loop", "-1", "-i", bgmBedFile.absolutePath,
                "-i", sfxTrackFile.absolutePath,
                "-filter_complex",
                "[1:a]apad,volume=1.0[v];" +
                    "[2:a]volume=0.26[b];" +
                    "[3:a]volume=0.35[s];" +
                    "[v][b][s]amix=inputs=3:duration=first:normalize=0[aout]",
                "-map", "0:v:0",
                "-map", "[aout]",
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "44100",
                "-ac", "2",
                "-t", formattedDuration,
                "-movflags", "+faststart",
                outputFile.absolutePath
            )
            bgmBedFile != null -> arrayOf(
                "-y",
                "-i", silentVideoFile.absolutePath,
                "-i", fullVoiceFile.absolutePath,
                "-stream_loop", "-1", "-i", bgmBedFile.absolutePath,
                "-filter_complex",
                "[1:a]apad,volume=1.0[v];" +
                    "[2:a]volume=0.26[b];" +
                    "[v][b]amix=inputs=2:duration=first:normalize=0[aout]",
                "-map", "0:v:0",
                "-map", "[aout]",
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "44100",
                "-ac", "2",
                "-t", formattedDuration,
                "-movflags", "+faststart",
                outputFile.absolutePath
            )
            else -> arrayOf(
                "-y",
                "-i", silentVideoFile.absolutePath,
                "-i", fullVoiceFile.absolutePath,
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-af", "apad",
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "44100",
                "-ac", "2",
                "-t", formattedDuration,
                "-movflags", "+faststart",
                outputFile.absolutePath
            )
        }

        val mixedOk = runCatching { runFfmpeg(args) }.isSuccess
        if (!mixedOk || !outputFile.exists() || outputFile.length() == 0L) {
            runFfmpeg(
                arrayOf(
                    "-y",
                    "-i", silentVideoFile.absolutePath,
                    "-i", fullVoiceFile.absolutePath,
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-af", "apad",
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
