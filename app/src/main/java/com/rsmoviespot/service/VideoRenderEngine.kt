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
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

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

            // Same seed across all scenes improves character and style consistency.
            val videoSeed = (topic.trim().lowercase().hashCode() and 0x7FFFFFFF)
                .coerceAtLeast(1000)

            val bgmBedFile = prepareBgmBed(renderDirectory, script.bgm_mood)

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

                val imagePrompt = buildConsistentImagePrompt(
                    characterBible = script.character_bible,
                    visualStyle = script.visual_style,
                    scenePrompt = slab.image_prompt,
                    isShorts = isShorts
                )

                videoRepository.generateImageForSlab(
                    prompt = imagePrompt,
                    isShorts = isShorts,
                    seed = videoSeed
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

    private fun buildConsistentImagePrompt(
        characterBible: String,
        visualStyle: String,
        scenePrompt: String,
        isShorts: Boolean
    ): String = buildString {
        if (characterBible.isNotBlank()) {
            append("Main character (strictly identical across every scene): ")
            append(characterBible.trim())
            append(". ")
        }
        append("Scene action: ")
        append(scenePrompt.trim())
        if (visualStyle.isNotBlank()) {
            append(". Art style & lighting: ")
            append(visualStyle.trim())
        }
        append(
            if (isShorts) {
                ", single cohesive cinematic frame, vertical portrait composition, 9:16 aspect ratio"
            } else {
                ", single cohesive cinematic frame, horizontal landscape composition, 16:9 aspect ratio"
            }
        )
    }

    private suspend fun prepareBgmBed(
        renderDirectory: File,
        mood: String
    ): File? {
        val rawResourceFile = copyOptionalRawAudio(renderDirectory, mood)
        if (rawResourceFile != null) return rawResourceFile

        val synthesizedBgm = File(renderDirectory, "bgm_bed.wav")
        val lavfiExpression = when (mood.lowercase()) {
            "devotional" ->
                "aevalsrc='0.22*sin(2*PI*130.81*t)+0.16*sin(2*PI*196.00*t)+0.10*sin(2*PI*261.63*t)':s=44100:d=20"
            "suspense" ->
                "aevalsrc='0.35*sin(2*PI*58*t)*exp(-3*mod(t,1.0))+0.15*sin(2*PI*62*t)':s=44100:d=20"
            "sci_fi" ->
                "aevalsrc='0.25*sin(2*PI*73.42*t)+0.18*sin(2*PI*110*t)*sin(2*PI*0.4*t)+0.12*sin(2*PI*220*t)':s=44100:d=20"
            "emotional" ->
                "aevalsrc='0.24*sin(2*PI*174.61*t)+0.16*sin(2*PI*220.00*t)+0.12*sin(2*PI*261.63*t)':s=44100:d=20"
            else ->
                // epic_dark: deep pulsing war-drum sub-bass + cinematic harmonic drone
                "aevalsrc='0.45*sin(2*PI*55*t)*exp(-4.5*mod(t,0.75))+0.22*sin(2*PI*110*t)+0.12*sin(2*PI*165*t)':s=44100:d=20"
        }

        val success = runCatching {
            runFfmpeg(
                arrayOf(
                    "-y",
                    "-f", "lavfi",
                    "-i", lavfiExpression,
                    "-af", "lowpass=f=360,aecho=0.8:0.85:70:0.45",
                    "-ar", "44100",
                    "-ac", "2",
                    synthesizedBgm.absolutePath
                )
            )
        }.isSuccess

        return if (success && synthesizedBgm.exists() && synthesizedBgm.length() > 0L) {
            synthesizedBgm
        } else {
            null
        }
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

    private suspend fun synthesizeSceneSfx(
        renderDirectory: File,
        sceneIndex: Int,
        sfxCue: String
    ): File? {
        val sfxFile = File(renderDirectory, "sfx_${sceneIndex + 1}.wav")
        val (lavfiSource, filter) = when (sfxCue.lowercase()) {
            "thunder" ->
                "anoisesrc=color=brown:amplitude=0.85:r=44100:d=2.2" to
                    "lowpass=f=240,aecho=0.8:0.85:110:0.5,afade=t=out:st=0.8:d=1.4"
            "fire" ->
                "anoisesrc=color=pink:amplitude=0.28:r=44100:d=3.0" to
                    "bandpass=f=1200:w=800,tremolo=f=8:d=0.65,afade=t=out:st=1.8:d=1.2"
            "sword" ->
                "aevalsrc='0.45*sin(2*PI*1750*t)*exp(-9*t)+0.35*sin(2*PI*2450*t)*exp(-11*t)':s=44100:d=0.6" to
                    "highpass=f=800,aecho=0.8:0.7:40:0.3"
            "boom" ->
                "aevalsrc='0.8*sin(2*PI*(95-55*min(t,0.7))*t)*exp(-3.2*t)':s=44100:d=1.3" to
                    "lowpass=f=220"
            else ->
                // whoosh
                "anoisesrc=color=pink:amplitude=0.45:r=44100:d=0.65" to
                    "bandpass=f=650:w=420,afade=t=in:st=0:d=0.25,afade=t=out:st=0.3:d=0.35"
        }

        val success = runCatching {
            runFfmpeg(
                arrayOf(
                    "-y",
                    "-f", "lavfi",
                    "-i", lavfiSource,
                    "-af", filter,
                    "-ar", "44100",
                    "-ac", "2",
                    sfxFile.absolutePath
                )
            )
        }.isSuccess

        return if (success && sfxFile.exists() && sfxFile.length() > 0L) sfxFile else null
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

        val sfxFile = synthesizeSceneSfx(renderDirectory, sceneIndex, sfxCue)
        val formattedDuration = String.format(Locale.US, "%.2f", durationSeconds)

        val args = if (sfxFile != null) {
            arrayOf(
                "-y",
                "-i", voiceFile.absolutePath,
                "-stream_loop", "-1", "-i", bgmBedFile.absolutePath,
                "-i", sfxFile.absolutePath,
                "-filter_complex",
                "[0:a]apad,volume=1.0[v];" +
                    "[1:a]volume=0.18[b];" +
                    "[2:a]volume=0.32[s];" +
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
                    "[1:a]volume=0.18[b];" +
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

        // 1.5x supersample prevents zoompan subpixel jitter while staying light on mobile RAM
        val upWidth = (width * 3) / 2
        val upHeight = (height * 3) / 2

        val motionFilter = when (sceneIndex % 4) {
            0 ->
                // Dramatic Zoom In
                "zoompan=z='min(zoom+0.0012,1.18)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':" +
                    "d=$totalFrames:s=${width}x${height}:fps=$FRAME_RATE"
            1 ->
                // Dramatic Zoom Out
                "zoompan=z='if(lte(on,1),1.18,max(1.0,zoom-0.0012))':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':" +
                    "d=$totalFrames:s=${width}x${height}:fps=$FRAME_RATE"
            2 ->
                // Cinematic Horizontal Pan
                "zoompan=z='1.14':x='(iw-iw/zoom)*on/$denom':y='ih/2-(ih/zoom/2)':" +
                    "d=$totalFrames:s=${width}x${height}:fps=$FRAME_RATE"
            else ->
                // Cinematic Vertical Reveal Tilt
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
        const val DEFAULT_SEGMENT_DURATION_SECONDS = 6.0
    }
}
