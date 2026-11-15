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

            val segmentFiles = mutableListOf<File>()
            val slabCount = script.slabs.size

            script.slabs.forEachIndexed { index, slab ->
                val baseProgress = 8 + (index * 77 / slabCount)
                progress(
                    baseProgress.coerceIn(8, 85),
                    "Creating scene ${index + 1} of $slabCount…"
                )

                val imageFile = File(renderDirectory, "slab_${index + 1}.png")
                val audioFile = File(renderDirectory, "slab_${index + 1}.mp3")
                val segmentFile = File(renderDirectory, "segment_${index + 1}.mp4")

                val imagePrompt = buildString {
                    append(slab.image_prompt)
                    append(
                        if (isShorts) {
                            ", vertical portrait composition, 9:16 aspect ratio"
                        } else {
                            ", horizontal landscape composition, 16:9 aspect ratio"
                        }
                    )
                }

                videoRepository.generateImageForSlab(imagePrompt, isShorts).use { body ->
                    imageFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
                validateImageFile(imageFile, index + 1)

                videoRepository.generateAudioForSlab(slab.voiceover_text).use { body ->
                    audioFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
                if (!audioFile.exists() || audioFile.length() == 0L) {
                    throw IOException("Generated audio for scene ${index + 1} is empty.")
                }

                val durationSeconds = getAudioDurationSeconds(audioFile)
                val renderProgress = 8 + (((index + 0.5) * 77) / slabCount).roundToInt()
                progress(
                    renderProgress.coerceIn(8, 85),
                    "Rendering scene ${index + 1} of $slabCount…"
                )

                runFfmpeg(
                    createSegmentArguments(
                        imageFile = imageFile,
                        audioFile = audioFile,
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
        val formattedDuration = String.format(Locale.US, "%.2f", durationSeconds)

        val filter =
            "scale=$width:$height:force_original_aspect_ratio=increase," +
                "crop=$width:$height," +
                "setsar=1," +
                "zoompan=z='min(zoom+0.0004,1.08)':" +
                "x='iw/2-(iw/zoom/2)':" +
                "y='ih/2-(ih/zoom/2)':" +
                "d=$totalFrames:s=${width}x${height}:fps=$FRAME_RATE," +
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
