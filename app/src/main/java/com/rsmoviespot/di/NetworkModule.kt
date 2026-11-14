package com.rsmoviespot.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.rsmoviespot.data.api.EdgeTtsApi
import com.rsmoviespot.data.api.GeminiApi
import com.rsmoviespot.data.api.HuggingFaceApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val jsonMediaType = "application/json".toMediaType()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideGeminiApi(client: OkHttpClient): GeminiApi =
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(jsonMediaType))
            .build()
            .create(GeminiApi::class.java)

    @Provides
    @Singleton
    fun provideHuggingFaceApi(client: OkHttpClient): HuggingFaceApi =
        Retrofit.Builder()
            .baseUrl("https://api-inference.huggingface.co/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(jsonMediaType))
            .build()
            .create(HuggingFaceApi::class.java)

    @Provides
    @Singleton
    fun provideEdgeTtsApi(client: OkHttpClient): EdgeTtsApi =
        Retrofit.Builder()
            // Is URL ko apne actual Edge TTS REST adapter ke URL se replace karein.
            .baseUrl("https://your-edge-tts-adapter.example/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(jsonMediaType))
            .build()
            .create(EdgeTtsApi::class.java)
}
