package com.rsmoviespot.data.api

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class EdgeTtsSynthesisResult(
    val audioBytes: ByteArray,
    val vttContent: String?
)

data class EdgeTtsClipResult(
    val audioBytes: ByteArray,
    val usedFallback: Boolean
)

@Singleton
class EdgeTtsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    private val ttsHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private val edgeConcurrencyGate = Semaphore(2)
    private val consecutiveEdgeFailures = AtomicInteger(0)
    private val edgeDisabledUntilMs = AtomicLong(0L)
    private val clockSkewSeconds = AtomicLong(0L)

    suspend fun synthesize(request: EdgeTtsRequest): ResponseBody {
        val result = synthesizeWithSubtitles(request.text, request.voice)
        return result.audioBytes.toResponseBody("audio/mpeg".toMediaType())
    }

    suspend fun synthesizeSpeechClip(
        text: String,
        voice: String,
        rate: String = DEFAULT_VOICE_RATE,
        pitch: String = DEFAULT_VOICE_PITCH
    ): ByteArray = synthesizeSpeechClipWithMeta(
        text = text,
        voice = voice,
        rate = rate,
        pitch = pitch
    ).audioBytes

    suspend fun synthesizeSpeechClipWithMeta(
        text: String,
        voice: String,
        rate: String = DEFAULT_VOICE_RATE,
        pitch: String = DEFAULT_VOICE_PITCH
    ): EdgeTtsClipResult = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "Voiceover text must not be blank." }

        val safeRate = clampSafeRate(rate)
        val safePitch = sanitizeSafePitch(pitch)
        val targetVoice = resolveEdgeVoiceShortName(voice)

        if (System.currentTimeMillis() >= edgeDisabledUntilMs.get()) {
            val edgeBytes = try {
                edgeConcurrencyGate.withPermit {
                    synthesizeViaDirectEdgeWebSocketWithRetry(
                        text = cleanText,
                        voiceName = targetVoice,
                        rate = safeRate,
                        pitch = safePitch
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }

            if (edgeBytes != null && edgeBytes.isNotEmpty()) {
                consecutiveEdgeFailures.set(0)
                return@withContext EdgeTtsClipResult(
                    audioBytes = edgeBytes,
                    usedFallback = false
                )
            }

            val failures = consecutiveEdgeFailures.incrementAndGet()
            if (failures >= 3) {
                edgeDisabledUntilMs.set(System.currentTimeMillis() + EDGE_COOLDOWN_MS)
            }
        }

        val fallbackBytes = synthesizeWithGoogleFallback(cleanText, targetVoice)
        if (fallbackBytes.isEmpty()) {
            throw IOException("TTS produced empty audio output.")
        }
        EdgeTtsClipResult(
            audioBytes = fallbackBytes,
            usedFallback = true
        )
    }

    suspend fun synthesizeWithSubtitles(
        text: String,
        voice: String,
        rate: String = DEFAULT_VOICE_RATE,
        pitch: String = DEFAULT_VOICE_PITCH
    ): EdgeTtsSynthesisResult = withContext(Dispatchers.IO) {
        val clip = synthesizeSpeechClipWithMeta(
            text = text,
            voice = voice,
            rate = rate,
            pitch = pitch
        )
        EdgeTtsSynthesisResult(
            audioBytes = clip.audioBytes,
            vttContent = null
        )
    }

    private fun synthesizeViaDirectEdgeWebSocketWithRetry(
        text: String,
        voiceName: String,
        rate: String,
        pitch: String
    ): ByteArray {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                return executeEdgeWebSocket(
                    text = text,
                    voiceName = voiceName,
                    rate = rate,
                    pitch = pitch
                )
            } catch (err: Throwable) {
                lastError = err
                if (attempt == 0 && err.message?.contains("403") == true) {
                    syncClockSkewFromBing()
                }
            }
        }
        throw lastError ?: IOException("Edge TTS WebSocket synthesis failed.")
    }

    private fun executeEdgeWebSocket(
        text: String,
        voiceName: String,
        rate: String,
        pitch: String
    ): ByteArray {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val secMsGec = generateSecMsGec(clockSkewSeconds.get())

        val wsUrl = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("TrustedClientToken", TRUSTED_CLIENT_TOKEN)
            .addQueryParameter("Sec-MS-GEC", secMsGec)
            .addQueryParameter("Sec-MS-GEC-Version", SEC_MS_GEC_VERSION)
            .addQueryParameter("ConnectionId", connectionId)
            .build()
            .toString()
            .replaceFirst("https://", "wss://")

        val request = Request.Builder()
            .url(wsUrl)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", EDGE_ORIGIN)
            .header("User-Agent", EDGE_USER_AGENT)
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val future = CompletableFuture<ByteArray>()
        val audioStream = ByteArrayOutputStream(32768)
        val finished = AtomicBoolean(false)

        val locale = voiceName.split('-').take(2).joinToString("-").ifBlank { "en-US" }
        val escapedText = escapeXml(text)
        val timestamp = edgeTimestamp()

        val speechConfigMsg = "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""

        val ssmlBody = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$locale'>" +
            "<voice name='$voiceName'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='+0%'>$escapedText</prosody>" +
            "</voice></speak>"

        val ssmlMsg = "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:$timestamp\r\n" +
            "Path:ssml\r\n\r\n$ssmlBody"

        val socket = ttsHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(speechConfigMsg)
                    webSocket.send(ssmlMsg)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val arr = bytes.toByteArray()
                    if (arr.size < 2) return
                    val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                    val dataOffset = 2 + headerLen
                    if (headerLen > 0 && dataOffset <= arr.size) {
                        val header = String(arr, 2, headerLen, Charsets.US_ASCII)
                        if (header.contains("Path:audio", ignoreCase = true) && dataOffset < arr.size) {
                            synchronized(audioStream) {
                                audioStream.write(arr, dataOffset, arr.size - dataOffset)
                            }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end", ignoreCase = true) && finished.compareAndSet(false, true)) {
                        val bytes = synchronized(audioStream) { audioStream.toByteArray() }
                        webSocket.close(1000, "done")
                        if (bytes.isNotEmpty()) {
                            future.complete(bytes)
                        } else {
                            future.completeExceptionally(IOException("Edge TTS returned empty audio stream."))
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    if (finished.compareAndSet(false, true)) {
                        val bytes = synchronized(audioStream) { audioStream.toByteArray() }
                        if (bytes.isNotEmpty()) {
                            future.complete(bytes)
                        } else {
                            future.completeExceptionally(IOException("Edge TTS closed ($code): $reason"))
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    response?.header("Date")?.let { updateClockSkewFromDateHeader(it) }
                    if (finished.compareAndSet(false, true)) {
                        val status = response?.code
                        val msg = if (status != null) "HTTP $status ${t.message.orEmpty()}" else (t.message ?: "WebSocket failure")
                        future.completeExceptionally(IOException("Edge TTS failed: $msg", t))
                    }
                }
            }
        )

        return try {
            future.get(EDGE_TTS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            socket.cancel()
            throw IOException("Edge TTS timed out after ${EDGE_TTS_TIMEOUT_SECONDS}s", timeout)
        } catch (error: Throwable) {
            socket.cancel()
            throw IOException("Edge TTS error: ${error.cause?.message ?: error.message}", error)
        }
    }

    private fun generateSecMsGec(skewSec: Long): String {
        val unixSeconds = (System.currentTimeMillis() / 1000L) + skewSec
        val windowsSeconds = unixSeconds + 11_644_473_600L
        val roundedSeconds = windowsSeconds - (windowsSeconds % 300L)
        val fileTimeTicks = roundedSeconds * 10_000_000L
        val raw = "$fileTimeTicks$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun syncClockSkewFromBing() {
        runCatching {
            val req = Request.Builder()
                .url("https://speech.platform.bing.com/")
                .head()
                .header("User-Agent", EDGE_USER_AGENT)
                .build()
            ttsHttpClient.newCall(req).execute().use { resp ->
                resp.header("Date")?.let { updateClockSkewFromDateHeader(it) }
            }
        }
    }

    private fun updateClockSkewFromDateHeader(dateHeader: String) {
        runCatching {
            val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            val serverDate = parser.parse(dateHeader) ?: return
            val serverSec = serverDate.time / 1000L
            val localSec = System.currentTimeMillis() / 1000L
            clockSkewSeconds.set(serverSec - localSec)
        }
    }

    private fun edgeTimestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "${fmt.format(Date())} GMT+0000 (Coordinated Universal Time)"
    }

    private fun escapeXml(input: String): String =
        input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun resolveEdgeVoiceShortName(requestedVoice: String): String {
        val trimmed = requestedVoice.trim()
        if (trimmed.contains("Neural", ignoreCase = true) && trimmed.count { it == '-' } >= 2) {
            return trimmed
        }
        return "hi-IN-MadhurNeural"
    }

    private fun clampSafeRate(rawRate: String): String {
        val match = Regex("""([+-]?\d+)%""").find(rawRate.trim()) ?: return DEFAULT_VOICE_RATE
        val value = match.groupValues[1].toIntOrNull()?.coerceIn(-25, 35) ?: 22
        return if (value >= 0) "+$value%" else "$value%"
    }

    private fun sanitizeSafePitch(rawPitch: String): String {
        val match = Regex("""([+-]?\d+)Hz""", RegexOption.IGNORE_CASE).find(rawPitch.trim())
            ?: return DEFAULT_VOICE_PITCH
        val value = match.groupValues[1].toIntOrNull()?.coerceIn(-12, 12) ?: 1
        val nonZero = if (value == 0) 1 else value
        return if (nonZero > 0) "+${nonZero}Hz" else "${nonZero}Hz"
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
                    delay(450L * (attempt + 1))
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

        ttsHttpClient.newCall(httpRequest).execute().use { response ->
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
        const val DEFAULT_VOICE_RATE = "+24%"
        const val DEFAULT_VOICE_PITCH = "+1Hz"
        const val EDGE_TTS_TIMEOUT_SECONDS = 12L
        const val EDGE_COOLDOWN_MS = 60_000L

        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val SEC_MS_GEC_VERSION = "1-130.0.2849.68"
        const val EDGE_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        const val EDGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
    }
}

@Serializable
data class EdgeTtsRequest(
    val text: String,
    val voice: String,
    val outputFormat: String = "audio-24khz-48kbitrate-mono-mp3"
)
