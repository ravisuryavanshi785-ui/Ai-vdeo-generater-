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
    val model: String,
    val messages: List<GroqMessage>,
    val temperature: Double,
    @SerialName("max_tokens")
    val maxTokens: Int,
    @SerialName("response_format")
    val responseFormat: GroqResponseFormat
)

@Serializable
data class GroqResponseFormat(
    val type: String
)

@Serializable
data class GroqMessage(
    val role: String,
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
