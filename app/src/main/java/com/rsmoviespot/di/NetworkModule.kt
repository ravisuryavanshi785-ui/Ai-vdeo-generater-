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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import retrofit2.Retrofit
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val bootstrapClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val cloudflareDns = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
                InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1))
            )
            .includeIPv6(true)
            .build()

        return OkHttpClient.Builder()
            .dns(cloudflareDns)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideGeminiApi(
        client: OkHttpClient,
        json: Json
    ): GeminiApi =
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(GeminiApi::class.java)

    @Provides
    @Singleton
    fun provideHuggingFaceApi(
        client: OkHttpClient,
        json: Json
    ): HuggingFaceApi =
        Retrofit.Builder()
            .baseUrl("https://api.endpoints.huggingface.cloud/")
            .client(client)
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(HuggingFaceApi::class.java)

    @Provides
    @Singleton
    fun provideEdgeTtsApi(
        client: OkHttpClient,
        json: Json
    ): EdgeTtsApi =
        Retrofit.Builder()
            .baseUrl("https://your-edge-tts-adapter.example/")
            .client(client)
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(EdgeTtsApi::class.java)
}
