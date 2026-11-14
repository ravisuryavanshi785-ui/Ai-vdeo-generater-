package com.rsmoviespot.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsmoviespot.service.VideoRenderEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VideoUiState(
    val topic: String = "",
    val isShorts: Boolean = true,
    val isGenerating: Boolean = false,
    val progressText: String = "",
    val progressPercentage: Int = 0,
    val statusMessage: String = "",
    val generatedVideoUri: Uri? = null
)

@HiltViewModel
class VideoViewModel @Inject constructor(
    private val videoRenderEngine: VideoRenderEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    fun onTopicChanged(topic: String) {
        _uiState.update { it.copy(topic = topic) }
    }

    fun onShortsChanged(isShorts: Boolean) {
        _uiState.update { it.copy(isShorts = isShorts) }
    }

    fun startGeneratingVideo() {
        val currentState = _uiState.value
        if (currentState.isGenerating) return

        val topic = currentState.topic.trim()
        if (topic.isBlank()) {
            _uiState.update {
                it.copy(statusMessage = "Enter a topic before generating a video.")
            }
            return
        }

        _uiState.update {
            it.copy(
                isGenerating = true,
                progressText = "Starting video generation…",
                progressPercentage = 0,
                statusMessage = "",
                generatedVideoUri = null
            )
        }

        viewModelScope.launch {
            try {
                val result = videoRenderEngine.render(
                    topic = topic,
                    isShorts = currentState.isShorts
                ) { percent, message ->
                    _uiState.update {
                        it.copy(
                            progressPercentage = percent.coerceIn(0, 100),
                            progressText = message
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        progressPercentage = 100,
                        progressText = "Video saved to your gallery.",
                        statusMessage = "Video generation complete.",
                        generatedVideoUri = result.galleryUri
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        statusMessage = exception.message
                            ?.takeIf(String::isNotBlank)
                            ?.let { message -> "Generation failed: $message" }
                            ?: "Video generation failed. Please try again."
                    )
                }
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }
}
