package com.rsmoviespot.service

import android.content.ContentValues
import android.content.Context
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
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    /**
     * Generates and publishes a video.
     *
     * Invoke from a foreground service or another lifecycle-managed background
     * execution context. progress is 0..100 and can be wired to the service's
     * notification updates.
     */
    suspend fun render(
        topic: String,
        isShorts: Boolean,
        progress: (Int, String) -> Unit = { _, _ -> }
    ): VideoRenderResult = withContext(Dispatchers.IO) {
        require(topic.isNotBlank()) { "Topic must not be blank." }

        val width = if (isShorts) SHORTS_WIDTH else LANDSCAPE_WIDTH
        val height = if (isShorts) SHORTS_HEIGHT else LANDSCAPE_HEIGHT
        val renderDirectory = File(context.cacheDir, "video-render-${UUID.randomUUID()}")
        if (!renderDirectory.mkdirs()) {
            throw IOException("Could not create render directory.")
        }

        try {
            progress(0, "Generating script…")
            val script = videoRepository.generateScript(topic)
            require(script.slabs.isNotEmpty()) { "The generated script has no slabs." }

            val segmentFiles = mutableListOf<File>()
            val slabCount = script.slabs.size

            script.slabs.forEachIndexed { index, slab ->
                val slabProgress = (index * 85 / slabCount).coerceIn(0, 85)
                progress(slabProgress, "Generating slab ${index + 1} of $slabCount…")

                val imageFile = File(renderDirectory, "slab_${index + 1}.jpg")
                val audioFile = File(renderDirectory, "slab_${index + 1}.mp3")
                val segmentFile = File(renderDirectory, "segment_${index + 1}.mp4")

                // Include the requested aspect ratio and dimensions in the prompt.
                // The exact output dimensions still depend on the HF model/API;
                // FFmpeg below forces every rendered segment to the selected size.
                val imagePrompt = buildString {
                    append(slab.image_prompt)
                    append(
                        if (isShorts) {
                            ", vertical portrait composition, 9:16 aspect ratio, " +
                                "target image size 1080x1920"
                        } else {
                            ", horizontal landscape composition, 16:9 aspect ratio, " +
                                "target image size 1920x1080"
                        }
                    )
                }

                videoRepository.generateImageForSlab(imagePrompt).use { body ->
                    imageFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }

                videoRepository.generateAudioForSlab(slab.voiceover_text).use { body ->
                    audioFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }

                runFfmpeg(createSegmentCommand(imageFile, audioFile, segmentFile, width, height))
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
            runFfmpeg(createConcatCommand(concatList, finalFile))

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

    private fun createSegmentCommand(
        imageFile: File,
        audioFile: File,
        outputFile: File,
        width: Int,
        height: Int
    ): String {
        val filter = (
            "scale=$width:$height:force_original_aspect_ratio=increase," +
                "crop=$width:$height," +
                "zoompan=" +
                "z='min(zoom+0.0005,1.08)':" +
                "x='(iw-iw/zoom)*on/249':" +
                "y='(ih-ih/zoom)*on/249':" +
                "d=250:s=${width}x${height}:fps=$FRAME_RATE," +
                "format=yuv420p"
            )

        return listOf(
            "-y",
            "-loop 1",
            "-framerate $FRAME_RATE",
            "-i ${quoteFfmpegPath(imageFile)}",
            "-i ${quoteFfmpegPath(audioFile)}",
            "-vf \"$filter\"",
            "-map 0:v:0",
            "-map 1:a:0",
            "-af apad",
            "-t $SEGMENT_DURATION_SECONDS",
            "-c:v libx264",
            "-preset slow",
            "-crf 18",
            "-profile:v high",
            "-pix_fmt yuv420p",
            "-r $FRAME_RATE",
            "-c:a aac",
            "-b:a 192k",
            "-movflags +faststart",
            quoteFfmpegPath(outputFile)
        ).joinToString(" ")
    }

    private fun createConcatCommand(concatList: File, outputFile: File): String =
        listOf(
            "-y",
            "-f concat",
            "-safe 0",
            "-i ${quoteFfmpegPath(concatList)}",
            "-c copy",
            "-movflags +faststart",
            quoteFfmpegPath(outputFile)
        ).joinToString(" ")

    private suspend fun runFfmpeg(command: String): FFmpegSession =
        suspendCancellableCoroutine { continuation ->
            val session = FFmpegKit.executeAsync(command) { completedSession ->
                if (!continuation.isActive) return@executeAsync

                if (ReturnCode.isSuccess(completedSession.returnCode)) {
                    continuation.resume(completedSession)
                } else {
                    val details = completedSession.allLogsAsString
                        ?.takeIf(String::isNotBlank)
                        ?: completedSession.failStackTrace
                        ?: "No FFmpeg error details available."

                    continuation.resumeWithException(
                        IOException("FFmpeg command failed: $details")
                    )
                }
            }

            continuation.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
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
            } else {
                @Suppress("DEPRECATION")
                put(
                    MediaStore.Video.Media.DATA,
                    File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MOVIES
                        ),
                        "RSMovieSpot/$displayName"
                    ).absolutePath
                )
            }
        }

        val collection = MediaStore.Video.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
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

    private fun quoteFfmpegPath(file: File): String =
        "'${file.absolutePath.replace("'", "'\\''")}'"

    private fun escapeConcatPath(path: String): String =
        path.replace("\\", "\\\\").replace("'", "'\\''")

    private companion object {
        const val SHORTS_WIDTH = 1080
        const val SHORTS_HEIGHT = 1920
        const val LANDSCAPE_WIDTH = 1920
        const val LANDSCAPE_HEIGHT = 1080

        const val FRAME_RATE = 25
        const val SEGMENT_DURATION_SECONDS = 10
    }
}
