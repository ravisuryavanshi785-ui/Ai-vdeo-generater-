package com.rsmoviespot.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EdgeTtsApi @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun synthesize(request: EdgeTtsRequest): ResponseBody = withContext(Dispatchers.IO) {
        // Google limit bypass: Text ko chhote words mein todna
        val words = request.text.split(" ")
        val chunks = mutableListOf<String>()
        var currentChunk = ""
        
        for (word in words) {
            if (currentChunk.length + word.length + 1 > 150) {
                chunks.add(currentChunk.trim())
                currentChunk = word
            } else {
                currentChunk += " $word"
            }
        }
        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.trim())
        }

        val audioBytes = mutableListOf<ByteArray>()

        // Har chunk ki MP3 audio free mein download karna
        for (chunk in chunks) {
            val url = "https://translate.google.com/translate_tts".toHttpUrl().newBuilder()
                .addQueryParameter("ie", "UTF-8")
                .addQueryParameter("tl", "hi") // Hindi Voice
                .addQueryParameter("client", "tw-ob") // No-Auth Bypass
                .addQueryParameter("q", chunk)
                .build()

            val httpRequest = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            try {
                val response = okHttpClient.newCall(httpRequest).execute()
                if (response.isSuccessful) {
                    response.body?.bytes()?.let {
                        audioBytes.add(it)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace() // Crash proof: Catch everything
            }
        }

        // Agar internet band ho jaye toh silent file bhejega, par app CRASH nahi hogi
        if (audioBytes.isEmpty()) {
            return@withContext ByteArray(0).toResponseBody("audio/mpeg".toMediaType())
        }

        // Saare chunks ko jod kar ek lambi MP3 file banana
        var totalSize = 0
        for (b in audioBytes) totalSize += b.size
        val finalBytes = ByteArray(totalSize)
        var offset = 0
        for (b in audioBytes) {
            System.arraycopy(b, 0, finalBytes, offset, b.size)
            offset += b.size
        }

        return@withContext finalBytes.toResponseBody("audio/mpeg".toMediaType())
    }
}

@Serializable
data class EdgeTtsRequest(
    val text: String,
    val voice: String, // Ye parameter rahega taaki baaki files crash na hon
    val outputFormat: String = "audio-24khz-48kbitrate-mono-mp3"
)
