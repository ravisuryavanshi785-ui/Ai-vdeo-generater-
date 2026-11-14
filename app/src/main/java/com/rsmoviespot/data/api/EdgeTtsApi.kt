package com.rsmoviespot.data.api

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.POST

interface EdgeTtsApi {

    @POST("v1/tts")
    suspend fun synthesize(
        @Body request: EdgeTtsRequest
    ): ResponseBody
}

@Serializable
data class EdgeTtsRequest(
    val text: String,
    val voice: String,
    val outputFormat: String = "audio-24khz-48kbitrate-mono-mp3"
)