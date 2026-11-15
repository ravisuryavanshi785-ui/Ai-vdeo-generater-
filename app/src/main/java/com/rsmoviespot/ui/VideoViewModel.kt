package com.rsmoviespot.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsmoviespot.data.repository.VideoRepository
import com.rsmoviespot.domain.models.VideoScriptResponse
import com.rsmoviespot.service.VideoRenderService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VideoUiState(
    val topic: String = "",
    val isShorts: Boolean = true,
    val selectedBgmUri: Uri? = null,
    val selectedBgmName: String = "Built-in Cinematic BGM",
    val isPreviewingScript: Boolean = false,
    val draftScript: VideoScriptResponse? = null,
    val isGenerating: Boolean = false,
    val progressText: String = "",
    val progressPercentage: Int = 0,
    val statusMessage: String = "",
    val generatedVideoUri: Uri? = null
)

@HiltViewModel
class VideoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            VideoRenderService.renderState.collect { serviceState ->
                _uiState.update { current ->
                    current.copy(
                        isGenerating = serviceState.isGenerating,
                        progressText = serviceState.progressText,
                        progressPercentage = serviceState.progressPercentage,
                        statusMessage = serviceState.statusMessage,
                        generatedVideoUri = serviceState.generatedVideoUri
                    )
                }
            }
        }
    }

    fun onTopicChanged(topic: String) {
        _uiState.update { it.copy(topic = topic, draftScript = null) }
    }

    fun onShortsChanged(isShorts: Boolean) {
        _uiState.update { it.copy(isShorts = isShorts, draftScript = null) }
    }

    fun onBgmSelected(uri: Uri?) {
        if (uri == null) return
        val fileName = resolveDisplayName(uri) ?: "Custom Audio"
        _uiState.update {
            it.copy(
                selectedBgmUri = uri,
                selectedBgmName = fileName
            )
        }
    }

    fun clearBgm() {
        _uiState.update {
            it.copy(
                selectedBgmUri = null,
                selectedBgmName = "Built-in Cinematic BGM"
            )
        }
    }

    fun previewScript() {
        val currentState = _uiState.value
        if (currentState.isGenerating || currentState.isPreviewingScript) return

        val topic = currentState.topic.trim()
        if (topic.isBlank()) {
            _uiState.update {
                it.copy(statusMessage = "Enter a topic before previewing a script.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPreviewingScript = true,
                    statusMessage = "",
                    draftScript = null
                )
            }
            try {
                val script = videoRepository.generateScript(topic, currentState.isShorts)
                _uiState.update {
                    it.copy(
                        isPreviewingScript = false,
                        draftScript = script
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isPreviewingScript = false,
                        statusMessage = "Script error: ${error.message ?: error::class.java.simpleName}"
                    )
                }
            }
        }
    }

    fun onSlabVoiceoverChanged(index: Int, newText: String) {
        _uiState.update { current ->
            val script = current.draftScript ?: return@update current
            if (index !in script.slabs.indices) return@update current
            val updatedSlabs = script.slabs.toMutableList()
            updatedSlabs[index] = updatedSlabs[index].copy(voiceover_text = newText)
            current.copy(draftScript = script.copy(slabs = updatedSlabs))
        }
    }

    fun startGeneratingVideo() {
        val currentState = _uiState.value
        if (currentState.isGenerating || currentState.isPreviewingScript) return

        val topic = currentState.topic.trim()
        if (topic.isBlank()) {
            _uiState.update {
                it.copy(statusMessage = "Enter a topic before generating a video.")
            }
            return
        }

        VideoRenderService.startRender(
            context = context,
            topic = topic,
            isShorts = currentState.isShorts,
            bgmUri = currentState.selectedBgmUri,
            preGeneratedScript = currentState.draftScript
        )
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
