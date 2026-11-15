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

class VideoRenderEngine @Inject constructor(
    private val videoRepository: VideoRepository,
    @ApplicationContext private val context: Context
) {
    suspend fun render(
        topic: String,
        isShorts: Boolean,
        customBgmUri: Uri? = null,
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
            progress(2, "Generating script…")
            val script = videoRepository.generateScript(topic, isShorts)
            require(script.slabs.isNotEmpty()) { "The generated script has no slabs." }

            val baseSeed = (topic.trim().lowercase().hashCode() and 0x7FFFFFFF)
                .coerceAtLeast(1000)

            val bgmBedFile = prepareBgmBed(
                renderDirectory = renderDirectory,
                mood = script.bgm_mood,
                customBgmUri = customBgmUri
            )

            val segmentFiles = mutableListOf<File>()
            val slabCount = script.slabs.size

            script.slabs.forEachIndexed { index, slab ->
                val baseProgress = 8 + (index * 77 / slabCount)
                progress(
                    baseProgress.coerceIn(8, 85),
                    "Creating scene ${index + 1} of $slabCount…"
                )

                val imageFile = File(renderDirectory, "slab_${index + 1}.png")
                val voiceFile = File(renderDirectory, "slab_${index + 1}.mp3")
                val mixedAudioFile = File(renderDirectory, "mixed_${index + 1}.m4a")
                val segmentFile = File(renderDirectory, "segment_${index + 1}.mp4")

                val imagePrompt = buildSceneImagePrompt(
                    characterBible = script.character_bible,
                    visualStyle = script.visual_style,
                    scenePrompt = slab.image_prompt,
                    hasCharacter = slab.has_character,
                    isShorts = isShorts
                )

                // Vary seed per scene so poses/scenes stay fresh while character_bible locks appearance.
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

                videoRepository.generateAudioForSlab(slab.voiceover_text).use { body ->
                    voiceFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
                if (!voiceFile.exists() || voiceFile.length() == 0L) {
                    throw IOException("Generated audio for scene ${index + 1} is empty.")
                }

                val durationSeconds = getAudioDurationSeconds(voiceFile)

                val renderProgress = 8 + (((index + 0.5) * 77) / slabCount).roundToInt()
                progress(
                    renderProgress.coerceIn(8, 85),
                    "Rendering scene ${index + 1} of $slabCount…"
                )

                val finalAudioForScene = mixSceneAudioSafely(
                    renderDirectory = renderDirectory,
                    sceneIndex = index,
                    voiceFile = voiceFile,
                    bgmBedFile = bgmBedFile,
                    sfxCue = slab.sfx_cue,
                    durationSeconds = durationSeconds,
                    outputFile = mixedAudioFile
                )

                runFfmpeg(
                    createSegmentArguments(
                        sceneIndex = index,
                        imageFile = imageFile,
                        audioFile = finalAudioForScene,
                        outputFile = segmentFile,
                        width = width,
                        height = height,
                        durationSeconds = durationSeconds
                    )
                )
                segmentFiles += segmentFile
            }

            progress(88, "Joining video segments…")
            val concatList = File(renderDirectory, "segments.txt")
            concatList.writeText(
                segmentFiles.joinToString(separator = "\n") { file ->
                    "file '${escapeConcatPath(file.absolutePath)}'"
                } + "\n"
            )

            val finalFile = File(renderDirectory, "final_video.mp4")
            runFfmpeg(createConcatArguments(concatList, finalFile))

            progress(96, "Saving video to gallery…")
            val galleryUri = publishToMediaStore(finalFile, topic)
            progress(100, "Video ready")

            VideoRenderResult(
                galleryUri = galleryUri,
                segmentCount = segmentFiles.size,
                width = width,
                height = height
            )
        } finally {
            renderDirectory.deleteRecursively()
        }
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

        // 4 chords x 4 seconds each; frequencies in 130Hz–554Hz so phone speakers play them clearly.
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
                // epic_dark / suspense / sci_fi: D minor dramatic progression
                doubleArrayOf(146.83, 220.00, 293.66, 349.23), // Dm
                doubleArrayOf(116.54, 174.61, 233.08, 293.66), // Bb
                doubleArrayOf(130.81, 196.00, 261.63, 329.63), // C
                doubleArrayOf(110.00, 164.81, 220.00, 277.18)  // A
            )
        }

        val beatDuration = 0.5
        val chordDuration = 4.0

        writeMono16BitWav(outputFile, sampleRate, totalSamples) { i ->
            val t = i.toDouble() / sampleRate
            val chordIndex = ((t / chordDuration).toInt()).coerceIn(0, chords.size - 1)
            val chordLocalT = t - chordIndex * chordDuration
            val chord = chords[chordIndex]

            // Smooth cross-envelope per 4s chord so there are zero clicks
            val chordEnv = (chordLocalT / 0.15).coerceAtMost(1.0) *
                ((chordDuration - chordLocalT) / 0.15).coerceAtMost(1.0)
            val lfo = 0.85 + 0.15 * sin(2.0 * PI * 0.5 * t)

            var pad = 0.0
            for (freq in chord) {
                pad += sin(2.0 * PI * freq * t) * 0.18 +
                    sin(2.0 * PI * freq * 2.0 * t) * 0.06
            }
            pad *= chordEnv * lfo

            // Punchy drum thump every 0.5s (audible 140Hz -> 65Hz transient)
            val beatT = t % beatDuration
            val drumEnv = exp(-14.0 * beatT)
            val drumFreq = 65.0 + 95.0 * exp(-25.0 * beatT)
            val drum = sin(2.0 * PI * drumFreq * beatT) * drumEnv * 0.42

            (pad + drum).coerceIn(-0.95, 0.95)
        }
    }

    private fun synthesizeSceneSfxWav(
        renderDirectory: File,
        sceneIndex: Int,
        sfxCue: String
    ): File? {
        val sfxFile = File(renderDirectory, "sfx_${sceneIndex + 1}.wav")
        val sampleRate = 44100
        val rand = Random(sceneIndex * 131 + sfxCue.hashCode())

        return runCatching {
            when (sfxCue.lowercase()) {
                "thunder" -> {
                    val total = (sampleRate * 1.6).toInt()
                    var lp = 0.0
                    writeMono16BitWav(sfxFile, sampleRate, total) { i ->
                        val t = i.toDouble() / sampleRate
                        val white = rand.nextDouble(-1.0, 1.0)
                        lp = lp * 0.94 + white * 0.06
                        val crack = exp(-12.0 * t) * white * 0.35
                        val rumble = exp(-1.8 * t) * lp * 2.4
                        (crack + rumble).coerceIn(-0.95, 0.95)
                    }
                }
                "sword" -> {
                    val total = (sampleRate * 0.55).toInt()
                    writeMono16BitWav(sfxFile, sampleRate, total) { i ->
                        val t = i.toDouble() / sampleRate
                        val env = exp(-8.0 * t)
                        val metallic = sin(2.0 * PI * 1450.0 * t) * 0.45 +
                            sin(2.0 * PI * 2180.0 * t) * 0.30 +
                            sin(2.0 * PI * 3120.0 * t) * 0.15
                        (metallic * env).coerceIn(-0.95, 0.95)
                    }
                }
                "boom" -> {
                    val total = (sampleRate * 1.0).toInt()
                    writeMono16BitWav(sfxFile, sampleRate, total) { i ->
                        val t = i.toDouble() / sampleRate
                        val env = exp(-4.2 * t)
                        val freq = 60.0 + 110.0 * exp(-9.0 * t)
                        val sub = sin(2.0 * PI * freq * t) * 0.75 +
                            sin(2.0 * PI * freq * 2.0 * t) * 0.25
                        (sub * env).coerceIn(-0.95, 0.95)
                    }
                }
                else -> {
                    // whoosh / fire
                    val total = (sampleRate * 0.65).toInt()
                    var bp = 0.0
                    writeMono16BitWav(sfxFile, sampleRate, total) { i ->
                        val t = i.toDouble() / sampleRate
                        val env = sin(PI * (t / 0.65).coerceIn(0.0, 1.0)).pow(1.4)
                        val white = rand.nextDouble(-1.0, 1.0)
                        bp = bp * 0.85 + white * 0.15
                        (bp * env * 1.6).coerceIn(-0.95, 0.95)
                    }
                }
            }
            sfxFile.takeIf { it.exists() && it.length() > 512L }
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

    private suspend fun mixSceneAudioSafely(
        renderDirectory: File,
        sceneIndex: Int,
        voiceFile: File,
        bgmBedFile: File?,
        sfxCue: String,
        durationSeconds: Double,
        outputFile: File
    ): File {
        if (bgmBedFile == null) return voiceFile

        val sfxFile = synthesizeSceneSfxWav(renderDirectory, sceneIndex, sfxCue)
        val formattedDuration = String.format(Locale.US, "%.2f", durationSeconds)

        val args = if (sfxFile != null) {
            arrayOf(
                "-y",
                "-i", voiceFile.absolutePath,
                "-stream_loop", "-1", "-i", bgmBedFile.absolutePath,
                "-i", sfxFile.absolutePath,
                "-filter_complex",
                "[0:a]apad,volume=1.0[v];" +
                    "[1:a]volume=0.28[b];" +
                    "[2:a]volume=0.38[s];" +
                    "[v][b][s]amix=inputs=3:duration=first:normalize=0[out]",
                "-map", "[out]",
                "-t", formattedDuration,
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "44100",
                "-ac", "2",
                outputFile.absolutePath
            )
        } else {
            arrayOf(
                "-y",
                "-i", voiceFile.absolutePath,
                "-stream_loop", "-1", "-i", bgmBedFile.absolutePath,
                "-filter_complex",
                "[0:a]apad,volume=1.0[v];" +
                    "[1:a]volume=0.28[b];" +
                    "[v][b]amix=inputs=2:duration=first:normalize=0[out]",
                "-map", "[out]",
                "-t", formattedDuration,
                "-c:a", "aac",
                "-b:a", "192k",
                "-ar", "44100",
                "-ac", "2",
                outputFile.absolutePath
            )
        }

        val mixedOk = runCatching { runFfmpeg(args) }.isSuccess
        return if (mixedOk && outputFile.exists() && outputFile.length() > 0L) {
            outputFile
        } else {
            voiceFile
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
                (durationMs / 1000.0 + AUDIO_TAIL_PADDING_SECONDS)
                    .coerceAtLeast(MIN_SEGMENT_DURATION_SECONDS)
            } else {
                DEFAULT_SEGMENT_DURATION_SECONDS
            }
        } catch (_: Exception) {
            DEFAULT_SEGMENT_DURATION_SECONDS
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun createSegmentArguments(
        sceneIndex: Int,
        imageFile: File,
        audioFile: File,
        outputFile: File,
        width: Int,
        height: Int,
        durationSeconds: Double
    ): Array<String> {
        val totalFrames = (durationSeconds * FRAME_RATE)
            .roundToInt()
            .coerceAtLeast(FRAME_RATE * 2)
        val denom = (totalFrames - 1).coerceAtLeast(1)
        val formattedDuration = String.format(Locale.US, "%.2f", durationSeconds)
        val fadeOutStart = String.format(
            Locale.US,
            "%.2f",
            (durationSeconds - 0.25).coerceAtLeast(0.5)
        )

        val upWidth = (width * 3) / 2
        val upHeight = (height * 3) / 2

        val motionFilter = when (sceneIndex % 4) {
            0 ->
                "zoompan=z='min(zoom+0.0012,1.18)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':" +
                    "d=$totalFrames:s=${width}x${height}:fps=$FRAME_RATE"
            1 ->
                "zoompan=z='if(lte(on,1),1.18,max(1.0,zoom-0.0012))':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':" +
                    "d=$totalFrames:s=${width}x${height}:fps=$FRAME_RATE"
            2 ->
                "zoompan=z='1.14':x='(iw-iw/zoom)*on/$denom':y='ih/2-(ih/zoom/2)':" +
                    "d=$totalFrames:s=${width}x${height}:fps=$FRAME_RATE"
            else ->
                "zoompan=z='1.14':x='iw/2-(iw/zoom/2)':y='(ih-ih/zoom)*(1-on/$denom)':" +
                    "d=$totalFrames:s=${width}x${height}:fps=$FRAME_RATE"
        }

        val fadePrefix = if (sceneIndex > 0) "fade=t=in:st=0:d=0.22," else ""
        val filter =
            "scale=$upWidth:$upHeight:force_original_aspect_ratio=increase," +
                "crop=$upWidth:$upHeight," +
                "setsar=1," +
                "$motionFilter," +
                "${fadePrefix}fade=t=out:st=$fadeOutStart:d=0.25," +
                "format=yuv420p"

        return arrayOf(
            "-y",
            "-i", imageFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-vf", filter,
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-af", "apad",
            "-t", formattedDuration,
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "21",
            "-threads", "2",
            "-pix_fmt", "yuv420p",
            "-r", FRAME_RATE.toString(),
            "-c:a", "aac",
            "-b:a", "192k",
            "-ar", "44100",
            "-ac", "2",
            "-movflags", "+faststart",
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
        "-movflags", "+faststart",
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
        const val AUDIO_TAIL_PADDING_SECONDS = 0.35
        const val MIN_SEGMENT_DURATION_SECONDS = 3.0
        const val DEFAULT_SEGMENT_DURATION_SECONDS = 5.0
    }
}
