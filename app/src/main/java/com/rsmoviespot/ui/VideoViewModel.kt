package com.rsmoviespot.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsmoviespot.service.VideoRenderService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    @ApplicationContext private val context: Context
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

        VideoRenderService.startRender(
            context = context,
            topic = topic,
            isShorts = currentState.isShorts
        )
    }
}
