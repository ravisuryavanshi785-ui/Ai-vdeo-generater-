package com.rsmoviespot.data.api

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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EdgeTtsApi @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun synthesize(request: EdgeTtsRequest): ResponseBody = withContext(Dispatchers.IO) {
        val cleanText = request.text.trim()
        require(cleanText.isNotEmpty()) { "Voiceover text must not be blank." }

        val languageCode = resolveLanguageCode(request.voice)
        val chunks = splitIntoChunks(cleanText, MAX_CHUNK_LENGTH)
        require(chunks.isNotEmpty()) { "No speakable text chunks found." }

        val outputStream = ByteArrayOutputStream()

        chunks.forEachIndexed { index, chunk ->
            val chunkBytes = fetchChunkWithRetry(chunk = chunk, languageCode = languageCode)
            val audioPayload = if (index > 0) stripId3v2Header(chunkBytes) else chunkBytes
            outputStream.write(audioPayload)
        }

        val finalBytes = outputStream.toByteArray()
        if (finalBytes.isEmpty()) {
            throw IOException("TTS produced empty audio output.")
        }

        return@withContext finalBytes.toResponseBody("audio/mpeg".toMediaType())
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
    }
}

@Serializable
data class EdgeTtsRequest(
    val text: String,
    val voice: String,
    val outputFormat: String = "audio-24khz-48kbitrate-mono-mp3"
)
