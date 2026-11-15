package com.rsmoviespot.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface GroqApi {

    @POST("chat/completions")
    suspend fun generateContent(
        @Header("Authorization") authHeader: String,
        @Body request: GroqChatRequest
    ): GroqChatResponse
}

@Serializable
data class GroqChatRequest(
    // YAHAN FIX KIYA HAI: '=' aur aage ki string hata di hai taaki Kotlin isey JSON se delete na kare
    val model: String, 
    val messages: List<GroqMessage>,
    val temperature: Double = 0.7
)

@Serializable
data class GroqMessage(
    val role: String, // "system", "user", ya "assistant"
    val content: String
)

@Serializable
data class GroqChatResponse(
    val choices: List<GroqChoice> = emptyList()
)

@Serializable
data class GroqChoice(
    val message: GroqMessage? = null
)
