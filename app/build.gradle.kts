plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.rsmoviespot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rsmoviespot"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        val groqKey = (project.findProperty("GROQ_API_KEY") as? String ?: System.getenv("GROQ_API_KEY") ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        val cloudflareAccountId = (project.findProperty("CLOUDFLARE_ACCOUNT_ID") as? String ?: System.getenv("CLOUDFLARE_ACCOUNT_ID") ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        val cloudflareApiToken = (project.findProperty("CLOUDFLARE_API_TOKEN") as? String ?: System.getenv("CLOUDFLARE_API_TOKEN") ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        buildConfigField("String", "GROQ_API_KEY", "\"$groqKey\"")
        buildConfigField("String", "CLOUDFLARE_ACCOUNT_ID", "\"$cloudflareAccountId\"")
        buildConfigField("String", "CLOUDFLARE_API_TOKEN", "\"$cloudflareApiToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs +=
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51")
    ksp("com.google.dagger:hilt-compiler:2.51")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Retrofit / OkHttp / DoH
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation(
        "com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0"
    )

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // FFmpegKit
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
}
