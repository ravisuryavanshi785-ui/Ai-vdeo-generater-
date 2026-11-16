package com.rsmoviespot.data.api

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.whitemagic2014.tts.TTS
import io.github.whitemagic2014.tts.TTSVoice
import io.github.whitemagic2014.tts.bean.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class EdgeTtsSynthesisResult(
    val audioBytes: ByteArray,
    val vttContent: String?
)

@Singleton
class EdgeTtsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    suspend fun synthesize(request: EdgeTtsRequest): ResponseBody {
        val result = synthesizeWithSubtitles(request.text, request.voice)
        return result.audioBytes.toResponseBody("audio/mpeg".toMediaType())
    }

    suspend fun synthesizeSpeechClip(
        text: String,
        voice: String,
        rate: String = DEFAULT_VOICE_RATE,
        pitch: String = DEFAULT_VOICE_PITCH
    ): ByteArray = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "Voiceover text must not be blank." }

        val edgeResult = runCatching {
            synthesizeWithEdgeNeural(
                text = cleanText,
                voiceName = voice,
                rate = rate,
                pitch = pitch,
                withVtt = false
            )
        }.getOrNull()

        if (edgeResult != null && edgeResult.audioBytes.isNotEmpty()) {
            return@withContext edgeResult.audioBytes
        }

        val fallbackBytes = synthesizeWithGoogleFallback(cleanText, voice)
        if (fallbackBytes.isEmpty()) {
            throw IOException("TTS produced empty audio output.")
        }
        fallbackBytes
    }

    suspend fun synthesizeWithSubtitles(
        text: String,
        voice: String,
        rate: String = DEFAULT_VOICE_RATE,
        pitch: String = DEFAULT_VOICE_PITCH
    ): EdgeTtsSynthesisResult = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "Voiceover text must not be blank." }

        val edgeResult = runCatching {
            synthesizeWithEdgeNeural(
                text = cleanText,
                voiceName = voice,
                rate = rate,
                pitch = pitch,
                withVtt = true
            )
        }.getOrNull()

        if (edgeResult != null && edgeResult.audioBytes.isNotEmpty()) {
            return@withContext edgeResult
        }

        val fallbackBytes = synthesizeWithGoogleFallback(cleanText, voice)
        if (fallbackBytes.isEmpty()) {
            throw IOException("TTS produced empty audio output.")
        }

        EdgeTtsSynthesisResult(
            audioBytes = fallbackBytes,
            vttContent = null
        )
    }

    private fun synthesizeWithEdgeNeural(
        text: String,
        voiceName: String,
        rate: String,
        pitch: String,
        withVtt: Boolean
    ): EdgeTtsSynthesisResult {
        val voice = findEdgeVoice(voiceName)
            ?: throw IOException("Edge Neural voice not found: $voiceName")

        val tempDir = File(context.cacheDir, "edge-tts-${UUID.randomUUID()}")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw IOException("Could not create Edge TTS temp directory.")
        }

        try {
            val fileBaseName = "voice"
            val builder = TTS(voice, text)
                .findHeadHook()
                .storage(tempDir.absolutePath)
                .fileName(fileBaseName)
                .overwrite(true)
                .voiceRate(rate)
                .voicePitch(pitch)

            if (withVtt) {
                builder.enableVttFile()
            }

            val resultName = builder
                .formatMp3()
                .trans()

            val directFile = resultName?.takeIf { it.isNotBlank() }?.let { name ->
                val candidate = File(name)
                when {
                    candidate.isAbsolute && candidate.exists() -> candidate
                    File(tempDir, name).exists() -> File(tempDir, name)
                    File(tempDir, "$name.mp3").exists() -> File(tempDir, "$name.mp3")
                    else -> null
                }
            }

            val outputFile = directFile
                ?: File(tempDir, "$fileBaseName.mp3").takeIf { it.exists() }
                ?: tempDir.listFiles()?.firstOrNull {
                    it.isFile && it.length() > 0L && !it.extension.equals("vtt", ignoreCase = true)
                }
                ?: throw IOException("Edge TTS output file was not created.")

            val bytes = outputFile.readBytes()
            if (bytes.isEmpty()) {
                throw IOException("Edge TTS output file is empty.")
            }

            val vttText = if (withVtt) {
                val vttFile = File(tempDir, "${outputFile.nameWithoutExtension}.vtt").takeIf { it.exists() }
                    ?: File(tempDir, "$fileBaseName.vtt").takeIf { it.exists() }
                    ?: tempDir.listFiles()?.firstOrNull {
                        it.isFile && it.extension.equals("vtt", ignoreCase = true) && it.length() > 0L
                    }
                vttFile?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
            } else {
                null
            }

            return EdgeTtsSynthesisResult(
                audioBytes = bytes,
                vttContent = vttText
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun findEdgeVoice(requestedVoice: String): Voice? {
        val voices = TTSVoice.provides()
        val exact = voices.firstOrNull { it.shortName.equals(requestedVoice, ignoreCase = true) }
        if (exact != null) return exact

        val locale = requestedVoice.split('-').take(2).joinToString("-")
        val langPrefix = requestedVoice.substringBefore('-').trim()

        if (locale.isNotBlank()) {
            val localeMatch = voices.firstOrNull { it.locale.equals(locale, ignoreCase = true) }
            if (localeMatch != null) return localeMatch
        }
        if (langPrefix.length in 2..3) {
            val langMatch = voices.firstOrNull { it.locale.startsWith(langPrefix, ignoreCase = true) }
            if (langMatch != null) return langMatch
        }

        return voices.firstOrNull { it.shortName.equals("hi-IN-MadhurNeural", ignoreCase = true) }
            ?: voices.firstOrNull { it.shortName.equals("hi-IN-SwaraNeural", ignoreCase = true) }
            ?: voices.firstOrNull { it.locale.startsWith("hi", ignoreCase = true) }
    }

    private suspend fun synthesizeWithGoogleFallback(
        cleanText: String,
        voiceName: String
    ): ByteArray {
        val languageCode = resolveLanguageCode(voiceName)
        val chunks = splitIntoChunks(cleanText, MAX_CHUNK_LENGTH)
        require(chunks.isNotEmpty()) { "No speakable text chunks found." }

        val outputStream = ByteArrayOutputStream()

        chunks.forEachIndexed { index, chunk ->
            val chunkBytes = fetchChunkWithRetry(chunk = chunk, languageCode = languageCode)
            val audioPayload = if (index > 0) stripId3v2Header(chunkBytes) else chunkBytes
            outputStream.write(audioPayload)
        }

        return outputStream.toByteArray()
    }

    private suspend fun fetchChunkWithRetry(
        chunk: String,
        languageCode: String
    ): ByteArray {
        var lastError: Exception? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return fetchTtsChunk(chunk, languageCode)
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(500L * (attempt + 1))
                }
            }
        }

        throw IOException(
            "TTS synthesis failed after $MAX_ATTEMPTS attempts: ${lastError?.message ?: "Unknown error"}",
            lastError
        )
    }

    private fun fetchTtsChunk(chunk: String, languageCode: String): ByteArray {
        val url = "https://translate.google.com/translate_tts".toHttpUrl().newBuilder()
            .addQueryParameter("ie", "UTF-8")
            .addQueryParameter("tl", languageCode)
            .addQueryParameter("client", "tw-ob")
            .addQueryParameter("q", chunk)
            .build()

        val httpRequest = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            .build()

        okHttpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("TTS HTTP ${response.code}: ${response.message}")
            }

            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.isNotBlank() &&
                !contentType.contains("audio", ignoreCase = true) &&
                !contentType.contains("octet-stream", ignoreCase = true)
            ) {
                throw IOException("Unexpected TTS Content-Type: $contentType")
            }

            val bytes = response.body?.bytes()
                ?: throw IOException("TTS returned an empty response body.")

            if (bytes.isEmpty()) {
                throw IOException("TTS returned 0 audio bytes.")
            }

            return bytes
        }
    }

    private fun splitIntoChunks(text: String, maxLength: Int): List<String> {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        for (token in tokens) {
            if (token.length > maxLength) {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString())
                    current = StringBuilder()
                }
                token.chunked(maxLength).forEach { part ->
                    chunks.add(part)
                }
                continue
            }

            val separatorLength = if (current.isEmpty()) 0 else 1
            if (current.length + separatorLength + token.length > maxLength) {
                chunks.add(current.toString())
                current = StringBuilder(token)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(token)
            }
        }

        if (current.isNotEmpty()) {
            chunks.add(current.toString())
        }

        return chunks
    }

    private fun stripId3v2Header(bytes: ByteArray): ByteArray {
        if (bytes.size <= 10) return bytes
        val isId3 = bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()
        if (!isId3) return bytes

        val tagSize = ((bytes[6].toInt() and 0x7F) shl 21) or
            ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or
            (bytes[9].toInt() and 0x7F)

        val audioStart = 10 + tagSize
        return if (audioStart in 10 until bytes.size) {
            bytes.copyOfRange(audioStart, bytes.size)
        } else {
            bytes
        }
    }

    private fun resolveLanguageCode(voice: String): String {
        val prefix = voice.substringBefore('-').trim().lowercase()
        return if (prefix.length in 2..3 && prefix.all(Char::isLetter)) prefix else "hi"
    }

    private companion object {
        const val MAX_CHUNK_LENGTH = 160
        const val MAX_ATTEMPTS = 3
        const val DEFAULT_VOICE_RATE = "+14%"
        const val DEFAULT_VOICE_PITCH = "-2Hz"
    }
}

@Serializable
data class EdgeTtsRequest(
    val text: String,
    val voice: String,
    val outputFormat: String = "audio-24khz-48kbitrate-mono-mp3"
)
