package com.rsmoviespot.data.api

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class EdgeTtsApi @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    // Concurrent map to safely handle multiple slabs processing at the same time
    private val continuations = ConcurrentHashMap<String, CancellableContinuation<ResponseBody>>()

    init {
        // FIX 1: Hamesha Main Thread par start karo taaki Looper crash na ho
        Handler(Looper.getMainLooper()).post {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("hi", "IN")
                    tts?.setPitch(1.0f)
                    tts?.setSpeechRate(0.95f)
                    
                    // FIX 2: Listener sirf ek baar set hoga globally
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}

                        override fun onDone(id: String?) {
                            id?.let { utteranceId ->
                                val cont = continuations.remove(utteranceId)
                                if (cont != null && cont.isActive) {
                                    val tempFile = File(context.cacheDir, "$utteranceId.wav")
                                    try {
                                        if (tempFile.exists()) {
                                            val bytes = tempFile.readBytes()
                                            tempFile.delete()
                                            cont.resume(bytes.toResponseBody("audio/wav".toMediaType()))
                                        } else {
                                            cont.resumeWithException(Exception("Audio file not found"))
                                        }
                                    } catch (e: Exception) {
                                        cont.resumeWithException(e)
                                    }
                                }
                            }
                        }

                        override fun onError(id: String?) {
                            id?.let { utteranceId ->
                                val cont = continuations.remove(utteranceId)
                                if (cont != null && cont.isActive) {
                                    cont.resumeWithException(Exception("Android Local TTS failed"))
                                }
                            }
                        }
                    })
                    isInitialized = true
                }
            }
        }
    }

    suspend fun synthesize(request: EdgeTtsRequest): ResponseBody = withContext(Dispatchers.IO) {
        // FIX 3: Agar TTS abhi start ho raha hai, toh app ko crash karne ki jagah thoda wait karao
        var retries = 0
        while (!isInitialized && retries < 10) {
            delay(500)
            retries++
        }

        suspendCancellableCoroutine { continuation ->
            if (!isInitialized || tts == null) {
                continuation.resumeWithException(Exception("TTS Engine failed to initialize."))
                return@suspendCancellableCoroutine
            }

            val utteranceId = UUID.randomUUID().toString()
            val tempFile = File(context.cacheDir, "$utteranceId.wav")

            continuations[utteranceId] = continuation

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            val result = tts?.synthesizeToFile(request.text, params, tempFile, utteranceId)
            
            if (result == TextToSpeech.ERROR) {
                continuations.remove(utteranceId)
                if (continuation.isActive) {
                    continuation.resumeWithException(Exception("Failed to start TTS synthesis"))
                }
            }

            continuation.invokeOnCancellation {
                tts?.stop()
                continuations.remove(utteranceId)
            }
        }
    }
}

@Serializable
data class EdgeTtsRequest(
    val text: String,
    val voice: String,
    val outputFormat: String = "audio-24khz-48kbitrate-mono-mp3"
)
