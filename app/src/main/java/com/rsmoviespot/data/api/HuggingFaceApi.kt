package com.rsmoviespot.data.api

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface HuggingFaceApi {

    @POST("hf-inference/models/{model}")
    suspend fun generateImage(
        @Header("Authorization") authorization: String,
        @Path(value = "model", encoded = true) model: String,
        @Body request: HuggingFaceImageRequest
    ): ResponseBody
}

@Serializable
data class HuggingFaceImageRequest(
    val inputs: String
)
