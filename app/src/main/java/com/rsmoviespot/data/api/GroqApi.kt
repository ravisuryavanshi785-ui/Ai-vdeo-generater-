package com.rsmoviespot.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface GroqApi {

    // Groq ka endpoint bohot simple hota hai
    @POST("chat/completions")
    suspend fun generateContent(
        // Gemini mein @Query tha, Groq mein @Header lagta hai (Bearer token ke liye)
        @Header("Authorization") authHeader: String,
        @Body request: GroqChatRequest
    ): GroqChatResponse
}

@Serializable
data class GroqChatRequest(
    // Default model set kar diya hai, taaki baar-baar pass na karna pade
    val model: String = "llama-3.3-70b-versatile",
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
