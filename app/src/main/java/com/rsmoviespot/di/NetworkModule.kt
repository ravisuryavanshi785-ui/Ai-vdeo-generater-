package com.rsmoviespot.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.rsmoviespot.data.api.CloudflareApi
import com.rsmoviespot.data.api.GroqApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Dns
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
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val cloudflareDns = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
                InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1))
            )
            .includeIPv6(false)
            .build()

        val resilientDns = Dns { hostname ->
            try {
                cloudflareDns.lookup(hostname)
            } catch (_: Exception) {
                Dns.SYSTEM.lookup(hostname)
            }
        }

        return OkHttpClient.Builder()
            .dns(resilientDns)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideGroqApi(
        client: OkHttpClient,
        json: Json
    ): GroqApi =
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/openai/v1/")
            .client(client)
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(GroqApi::class.java)

    @Provides
    @Singleton
    fun provideCloudflareApi(
        client: OkHttpClient,
        json: Json
    ): CloudflareApi =
        Retrofit.Builder()
            .baseUrl("https://api.cloudflare.com/client/v4/")
            .client(client)
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(CloudflareApi::class.java)
}
