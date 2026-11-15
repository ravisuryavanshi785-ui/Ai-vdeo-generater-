package com.rsmoviespot.data.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class EdgeTtsApi @Inject constructor(
    baseOkHttpClient: OkHttpClient
) {
    private val wsClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun synthesize(request: EdgeTtsRequest): ResponseBody =
        suspendCancellableCoroutine { continuation ->
            val trustedClientToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
            val secMsGec = generateSecMsGec(trustedClientToken)
            val connectionId = UUID.randomUUID().toString().replace("-", "")

            val url =
                "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                    "?TrustedClientToken=$trustedClientToken" +
                    "&Sec-MS-GEC=$secMsGec" +
                    "&Sec-MS-GEC-Version=1-130.0.2849.68" +
                    "&ConnectionId=$connectionId"

            val wsRequest = Request.Builder()
                .url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
                )
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .build()

            val audioBuffer = ByteArrayOutputStream()
            val requestId = UUID.randomUUID().toString().replace("-", "")

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val timestamp = getEdgeTimestamp()
                    val configMsg =
                        "X-Timestamp:$timestamp\r\n" +
                            "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"${request.outputFormat}"}}}}"""

                    val escapedText = escapeXml(request.text)
                    val ssml =
                        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='hi-IN'>" +
                            "<voice name='${request.voice}'>" +
                            "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>$escapedText</prosody>" +
                            "</voice></speak>"

                    val ssmlMsg =
                        "X-RequestId:$requestId\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${timestamp}Z\r\n" +
                            "Path:ssml\r\n\r\n" +
                            ssml

                    webSocket.send(configMsg)
                    webSocket.send(ssmlMsg)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (data.size >= 2) {
                        val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        val audioStart = 2 + headerLen
                        if (audioStart < data.size) {
                            audioBuffer.write(data, audioStart, data.size - audioStart)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, "Completed")
                        val audioBytes = audioBuffer.toByteArray()
                        if (continuation.isActive) {
                            if (audioBytes.isNotEmpty()) {
                                continuation.resume(
                                    audioBytes.toResponseBody("audio/mpeg".toMediaType())
                                )
                            } else {
                                continuation.resumeWithException(
                                    IllegalStateException("Edge TTS returned empty audio stream.")
                                )
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (continuation.isActive) {
                        val code = response?.code?.let { " (HTTP $it)" }.orEmpty()
                        continuation.resumeWithException(
                            IllegalStateException("Edge TTS WebSocket failed$code: ${t.message}", t)
                        )
                    }
                }
            }

            val webSocket = wsClient.newWebSocket(wsRequest, listener)
            continuation.invokeOnCancellation {
                webSocket.cancel()
            }
        }

    private fun getEdgeTimestamp(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(System.currentTimeMillis())
    }

    private fun generateSecMsGec(trustedClientToken: String): String {
        val winEpochSeconds = 11644473600L
        var ticks = (System.currentTimeMillis() / 1000L) + winEpochSeconds
        ticks -= ticks % 300L
        val ticks100ns = ticks * 10_000_000L
        val strToHash = "$ticks100ns$trustedClientToken"
        val digest = MessageDigest.getInstance("SHA-256").digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun escapeXml(input: String): String =
        input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}

@Serializable
data class EdgeTtsRequest(
    val text: String,
    val voice: String,
    val outputFormat: String = "audio-24khz-48kbitrate-mono-mp3"
)
