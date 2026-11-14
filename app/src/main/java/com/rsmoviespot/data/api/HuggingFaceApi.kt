package com.rsmoviespot.data.api

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface HuggingFaceApi {

    @POST("hf-inference/models/stabilityai/stable-diffusion-3-medium-diffusers")
    suspend fun generateImage(
        @Header("Authorization") authorization: String,
        @Body request: HuggingFaceImageRequest
    ): ResponseBody
}

@Serializable
data class HuggingFaceImageRequest(
    val inputs: String
)
