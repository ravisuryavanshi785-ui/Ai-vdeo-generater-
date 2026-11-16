package com.rsmoviespot.data.api

import android.util.Base64
import com.rsmoviespot.data.local.VoiceCloneConfig
import com.rsmoviespot.domain.models.VideoSlab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class ModalClipInput(
    val id: Int,
    val audio_b64: String,
    val pitch_shift: Int = 0
)

@Serializable
private data class ModalBatchRequest(
    val pin: String,
    val reference_audio_b64: String,
    val clips: List<ModalClipInput>,
    val diffusion_steps: Int = 20,
    val auto_f0_adjust: Boolean = true,
    val inference_cfg_rate: Float = 0.7f
)

@Serializable
private data class ModalClipOutput(
    val id: Int,
    val wav_b64: String
)

@Serializable
private data class ModalBatchResponse(
    val ok: Boolean = false,
    val error: String = "",
    val clips: List<ModalClipOutput> = emptyList()
)

@Singleton
class PersonalVoiceApi @Inject constructor(
    okHttpClient: OkHttpClient,
    baseJson: Json
) {
    private val modalClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val apiJson = Json(baseJson) {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun testConnection(serverUrl: String, pin: String): Result<String> =
        withContext(Dispatchers.IO) {
            val cleanUrl = serverUrl.trim()
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                return@withContext Result.failure(IOException("URL must start with https://"))
            }

            runCatching {
                val payload = apiJson.encodeToString(
                    ModalBatchRequest.serializer(),
                    ModalBatchRequest(
                        pin = pin.trim(),
                        reference_audio_b64 = "",
                        clips = emptyList()
                    )
                )
                val parsed = postToModal(cleanUrl, payload)
                when {
                    parsed.error.contains("Invalid PIN", ignoreCase = true) ->
                        throw IOException("Invalid Secret PIN")
                    parsed.ok || parsed.error.contains("No audio clips", ignoreCase = true) ->
                        "Server connected & PIN verified!"
                    parsed.error.isNotBlank() ->
                        throw IOException(parsed.error)
                    else ->
                        "Server responded cleanly!"
                }
            }
        }

    suspend fun cloneClips(
        config: VoiceCloneConfig,
        sampleFile: File,
        rawAudioFiles: List<File>,
        slabs: List<VideoSlab>,
        onBatchProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<Int, ByteArray> = withContext(Dispatchers.IO) {
        val cleanUrl = config.serverUrl.trim()
        require(cleanUrl.startsWith("http")) { "Modal Server URL is not configured." }
        require(sampleFile.exists() && sampleFile.length() > 1024L) { "Voice sample file is missing." }

        val refB64 = Base64.encodeToString(sampleFile.readBytes(), Base64.NO_WRAP)
        val resultMap = mutableMapOf<Int, ByteArray>()
        val total = rawAudioFiles.size
        val batches = rawAudioFiles.indices.chunked(BATCH_SIZE)

        batches.forEach { batchIndices ->
            val clipInputs = batchIndices.map { idx ->
                val pitchShift = when (slabs.getOrNull(idx)?.voice_emotion?.lowercase()) {
                    "climax", "hook" -> 0
                    "suspense" -> -1
                    else -> 0
                }
                ModalClipInput(
                    id = idx,
                    audio_b64 = Base64.encodeToString(rawAudioFiles[idx].readBytes(), Base64.NO_WRAP),
                    pitch_shift = pitchShift
                )
            }

            val payload = apiJson.encodeToString(
                ModalBatchRequest.serializer(),
                ModalBatchRequest(
                    pin = config.secretPin.trim(),
                    reference_audio_b64 = refB64,
                    clips = clipInputs,
                    diffusion_steps = 20,
                    auto_f0_adjust = true,
                    inference_cfg_rate = 0.7f
                )
            )

            val response = postToModal(cleanUrl, payload)
            if (!response.ok) {
                throw IOException(response.error.ifBlank { "Modal voice cloning failed." })
            }

            response.clips.forEach { out ->
                val wavBytes = Base64.decode(out.wav_b64, Base64.DEFAULT)
                if (wavBytes.size > 44) {
                    resultMap[out.id] = wavBytes
                }
            }
            onBatchProgress(resultMap.size, total)
        }

        if (resultMap.size != total) {
            throw IOException("Incomplete voice clone output (${resultMap.size}/$total clips).")
        }
        resultMap
    }

    private fun postToModal(url: String, jsonBody: String): ModalBatchResponse {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        modalClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Modal HTTP ${response.code}: ${rawBody.take(180)}")
            }
            if (rawBody.isBlank()) {
                throw IOException("Modal returned an empty response.")
            }
            return apiJson.decodeFromString(ModalBatchResponse.serializer(), rawBody)
        }
    }

    private companion object {
        const val BATCH_SIZE = 8
    }
}
