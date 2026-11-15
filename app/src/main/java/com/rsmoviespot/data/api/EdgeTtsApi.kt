package com.rsmoviespot.data.api

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.util.Locale
import java.util.UUID
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
    private var isInitializing = false
    private val pendingRequests = mutableListOf<() -> Unit>()

    suspend fun synthesize(request: EdgeTtsRequest): ResponseBody = suspendCancellableCoroutine { continuation ->
        val utteranceId = UUID.randomUUID().toString()
        val tempFile = File(context.cacheDir, "$utteranceId.wav")

        val executeSynthesis = {
            val listener = object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    if (id == utteranceId && tempFile.exists()) {
                        val bytes = tempFile.readBytes()
                        tempFile.delete() // Clean up storage
                        
                        if (continuation.isActive) {
                            // VideoRepository ko lagega API se MP3 aaya, par ye local WAV bytes hain
                            continuation.resume(bytes.toResponseBody("audio/wav".toMediaType()))
                        }
                    }
                }

                override fun onError(id: String?) {
                    if (id == utteranceId && continuation.isActive) {
                        continuation.resumeWithException(Exception("Android Local TTS engine failed"))
                    }
                }
            }
            
            tts?.setOnUtteranceProgressListener(listener)
            
            // Network synthesis use karne ka try karega (better quality voice)
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            
            tts?.synthesizeToFile(request.text, params, tempFile, utteranceId)
        }

        // Initialize TTS engine only once
        if (isInitialized && tts != null) {
            executeSynthesis()
        } else {
            pendingRequests.add(executeSynthesis)
            if (!isInitializing) {
                isInitializing = true
                tts = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        // Hindi (India) set kar rahe hain
                        tts?.language = Locale("hi", "IN")
                        
                        // Voice pitch & speed adjustments to sound more natural
                        tts?.setPitch(1.0f)
                        tts?.setSpeechRate(0.95f) 
                        
                        isInitialized = true
                        pendingRequests.forEach { it.invoke() }
                        pendingRequests.clear()
                    } else {
                        if (continuation.isActive) {
                            continuation.resumeWithException(Exception("Failed to initialize Android Text-to-Speech"))
                        }
                    }
                    isInitializing = false
                }
            }
        }
        
        continuation.invokeOnCancellation {
            tts?.stop()
        }
    }
}

// Data class same rakhi hai taaki VideoRepositoryImpl crash na ho
@Serializable
data class EdgeTtsRequest(
    val text: String,
    val voice: String,
    val outputFormat: String = "audio-24khz-48kbitrate-mono-mp3"
)
