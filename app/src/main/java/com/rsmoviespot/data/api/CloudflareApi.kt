package com.rsmoviespot.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface CloudflareApi {

    @POST("accounts/{accountId}/ai/run/{model}")
    suspend fun generateImage(
        @Header("Authorization") authorization: String,
        @Path("accountId") accountId: String,
        @Path(value = "model", encoded = true) model: String,
        @Body request: CloudflareImageRequest
    ): ResponseBody
}

@Serializable
data class CloudflareImageRequest(
    val prompt: String,
    val width: Int,
    val height: Int,
    @SerialName("negative_prompt")
    val negativePrompt: String,
    val seed: Int,
    @SerialName("num_steps")
    val numSteps: Int,
    val guidance: Double
)
