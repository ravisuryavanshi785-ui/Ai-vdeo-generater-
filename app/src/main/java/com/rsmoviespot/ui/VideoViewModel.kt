package com.rsmoviespot.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsmoviespot.data.repository.VideoRepository
import com.rsmoviespot.domain.models.VideoLanguage
import com.rsmoviespot.domain.models.VideoScriptResponse
import com.rsmoviespot.domain.models.VoiceGender
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
    val selectedLanguage: VideoLanguage = VideoLanguage.AUTO,
    val selectedGender: VoiceGender = VoiceGender.MALE,
    val selectedBgmUri: Uri? = null,
    val selectedBgmName: String = "Built-in Cinematic BGM",
    val isLoadingTrending: Boolean = false,
    val trendingTopics: List<String> = emptyList(),
    val isPreviewingScript: Boolean = false,
    val draftScript: VideoScriptResponse? = null,
    val isGenerating: Boolean = false,
    val progressText: String = "",
    val progressPercentage: Int = 0,
    val statusMessage: String = "",
    val generatedVideoUri: Uri? = null,
    val generatedThumbnailUri: Uri? = null,
    val completedScript: VideoScriptResponse? = null
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
                        generatedVideoUri = serviceState.generatedVideoUri,
                        generatedThumbnailUri = serviceState.generatedThumbnailUri,
                        completedScript = serviceState.completedScript
                    )
                }
            }
        }
    }

    fun onTopicChanged(topic: String) {
        _uiState.update { it.copy(topic = topic, draftScript = null) }
    }

    fun onTopicSelectedFromTrending(selectedTopic: String) {
        _uiState.update {
            it.copy(
                topic = selectedTopic,
                draftScript = null,
                statusMessage = ""
            )
        }
    }

    fun loadTrendingTopics() {
        if (_uiState.value.isLoadingTrending) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTrending = true) }
            try {
                val topics = videoRepository.fetchTrendingMovieTopics()
                _uiState.update {
                    it.copy(
                        isLoadingTrending = false,
                        trendingTopics = topics,
                        statusMessage = if (topics.isEmpty()) {
                            "Could not load trending movie news right now."
                        } else {
                            ""
                        }
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoadingTrending = false,
                        statusMessage = "Trending fetch failed: ${error.message ?: error::class.java.simpleName}"
                    )
                }
            }
        }
    }

    fun onShortsChanged(isShorts: Boolean) {
        _uiState.update { it.copy(isShorts = isShorts, draftScript = null) }
    }

    fun onLanguageSelected(language: VideoLanguage) {
        _uiState.update { it.copy(selectedLanguage = language, draftScript = null) }
    }

    fun onGenderSelected(gender: VoiceGender) {
        _uiState.update { it.copy(selectedGender = gender, draftScript = null) }
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
                val script = videoRepository.generateScript(
                    topic = topic,
                    isShorts = currentState.isShorts,
                    language = currentState.selectedLanguage,
                    voiceGender = currentState.selectedGender
                )
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

    fun onSlabVisualSourceChanged(index: Int, newSource: String) {
        _uiState.update { current ->
            val script = current.draftScript ?: return@update current
            if (index !in script.slabs.indices) return@update current
            val updatedSlabs = script.slabs.toMutableList()
            val target = updatedSlabs[index]
            val fallbackQuery = if (newSource != "ai_generated" && target.real_photo_query.isBlank()) {
                current.topic.trim().take(60)
            } else {
                target.real_photo_query
            }
            updatedSlabs[index] = target.copy(
                visual_source = newSource,
                real_photo_query = fallbackQuery
            )
            current.copy(draftScript = script.copy(slabs = updatedSlabs))
        }
    }

    fun onSlabRealPhotoQueryChanged(index: Int, newQuery: String) {
        _uiState.update { current ->
            val script = current.draftScript ?: return@update current
            if (index !in script.slabs.indices) return@update current
            val updatedSlabs = script.slabs.toMutableList()
            updatedSlabs[index] = updatedSlabs[index].copy(real_photo_query = newQuery)
            current.copy(draftScript = script.copy(slabs = updatedSlabs))
        }
    }

    fun onSlabLayoutModeChanged(index: Int, newLayoutMode: String) {
        _uiState.update { current ->
            val script = current.draftScript ?: return@update current
            if (index !in script.slabs.indices) return@update current
            val updatedSlabs = script.slabs.toMutableList()
            val target = updatedSlabs[index]
            val secondSource = if (newLayoutMode != "single" && target.visual_source_2.isBlank()) {
                "ai_generated"
            } else {
                target.visual_source_2
            }
            val secondPrompt = if (newLayoutMode != "single" && target.image_prompt_2.isBlank()) {
                target.image_prompt
            } else {
                target.image_prompt_2
            }
            updatedSlabs[index] = target.copy(
                layout_mode = newLayoutMode,
                visual_source_2 = secondSource,
                image_prompt_2 = secondPrompt
            )
            current.copy(draftScript = script.copy(slabs = updatedSlabs))
        }
    }

    fun onSlabStatBadgeChanged(index: Int, newBadge: String) {
        _uiState.update { current ->
            val script = current.draftScript ?: return@update current
            if (index !in script.slabs.indices) return@update current
            val updatedSlabs = script.slabs.toMutableList()
            updatedSlabs[index] = updatedSlabs[index].copy(stat_badge = newBadge)
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
            preGeneratedScript = currentState.draftScript,
            language = currentState.selectedLanguage,
            voiceGender = currentState.selectedGender
        )
    }

    fun cancelGeneratingVideo() {
        VideoRenderService.cancelRender(context)
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
