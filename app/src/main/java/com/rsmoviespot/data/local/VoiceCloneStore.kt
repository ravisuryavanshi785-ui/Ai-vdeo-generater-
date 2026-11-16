package com.rsmoviespot.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceCloneConfig(
    val serverUrl: String = "",
    val secretPin: String = "",
    val baseIsFemale: Boolean = false,
    val sampleName: String = "",
    val hasSample: Boolean = false
) {
    val isReady: Boolean
        get() = serverUrl.trim().startsWith("http") && hasSample
}

@Singleton
class VoiceCloneStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val voiceDir: File
        get() = File(context.filesDir, "voice_clone").apply { mkdirs() }
    private val savedSampleFile: File
        get() = File(voiceDir, "my_voice_sample.bin")

    fun getConfig(): VoiceCloneConfig {
        val sampleExists = savedSampleFile.exists() && savedSampleFile.length() > 1024L
        return VoiceCloneConfig(
            serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty().trim(),
            secretPin = prefs.getString(KEY_SECRET_PIN, "").orEmpty(),
            baseIsFemale = prefs.getBoolean(KEY_BASE_IS_FEMALE, false),
            sampleName = if (sampleExists) {
                prefs.getString(KEY_SAMPLE_NAME, "Saved Voice Sample").orEmpty()
                    .ifBlank { "Saved Voice Sample" }
            } else {
                ""
            },
            hasSample = sampleExists
        )
    }

    fun saveSettings(serverUrl: String, secretPin: String, baseIsFemale: Boolean) {
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl.trim())
            .putString(KEY_SECRET_PIN, secretPin.trim())
            .putBoolean(KEY_BASE_IS_FEMALE, baseIsFemale)
            .apply()
    }

    fun saveSampleFromUri(uri: Uri): Boolean {
        val tempFile = File(voiceDir, "sample_tmp.bin")
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false

            if (!copied || !tempFile.exists() || tempFile.length() <= 1024L) {
                tempFile.delete()
                return false
            }

            tempFile.copyTo(savedSampleFile, overwrite = true)
            tempFile.delete()

            val displayName = resolveDisplayName(uri) ?: "Picked Audio Sample"
            prefs.edit().putString(KEY_SAMPLE_NAME, displayName).apply()
            true
        } catch (_: Exception) {
            tempFile.delete()
            false
        }
    }

    fun createRecordingTempFile(): File = File(voiceDir, "mic_recording_tmp.m4a")

    fun commitRecordedSample(tempFile: File): Boolean {
        if (!tempFile.exists() || tempFile.length() <= 1024L) {
            tempFile.delete()
            return false
        }
        return try {
            tempFile.copyTo(savedSampleFile, overwrite = true)
            tempFile.delete()
            prefs.edit().putString(KEY_SAMPLE_NAME, "In-App Mic Recording").apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getSampleFile(): File? =
        savedSampleFile.takeIf { it.exists() && it.length() > 1024L }

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    private companion object {
        const val PREFS_NAME = "rs_voice_clone_prefs"
        const val KEY_SERVER_URL = "modal_server_url"
        const val KEY_SECRET_PIN = "modal_secret_pin"
        const val KEY_BASE_IS_FEMALE = "base_is_female"
        const val KEY_SAMPLE_NAME = "sample_display_name"
    }
}
