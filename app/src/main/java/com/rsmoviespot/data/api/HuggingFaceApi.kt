package com.rsmoviespot.data.api

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface HuggingFaceApi {

    @POST("v1/text-to-image")
    suspend fun generateImage(
        @Header("Authorization") authorization: String,
        @Body request: TextToImageRequest
    ): ResponseBody
}

@Serializable
data class TextToImageRequest(
    val inputs: String,
    val model: String = "stabilityai/stable-diffusion-3-medium-diffusers",
    val provider: String = "hf-inference",
    val parameters: Map<String, @Serializable(with = AnySerializer::class) Any>? = null
)
