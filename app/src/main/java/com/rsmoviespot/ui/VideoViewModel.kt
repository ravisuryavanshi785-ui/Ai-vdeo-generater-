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

    fun onShortsChanged(isShorts: Boolean) {
        _uiState.update { it.copy(isShorts = isShorts, draftScript = null) }
    }

    fun onLanguageSelected(language: VideoLanguage) {
        _uiState.update { it.copy(selectedLanguage = language, draftScript = null) }
    }

    fun onGenderSelected(gender: VoiceGender) {
        _uiState.update { it.copy(selectedGender = gender, draftScript = null) }
    }

    fun fetchTrendingTopics() {
        if (_uiState.value.isLoadingTrending) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTrending = true) }
            val topics = runCatching { videoRepository.fetchTrendingMovieTopics() }
                .getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    isLoadingTrending = false,
                    trendingTopics = topics,
                    statusMessage = if (topics.isEmpty()) "Could not load trending movie news." else ""
                )
            }
        }
    }

    fun onTrendingTopicSelected(headline: String) {
        _uiState.update {
            it.copy(
                topic = "Make a viral YouTube Short video on this movie news: $headline",
                draftScript = null
            )
        }
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

    fun onSlabLayoutChanged(index: Int, layoutMode: String) {
        _uiState.update { current ->
            val script = current.draftScript ?: return@update current
            if (index !in script.slabs.indices) return@update current
            val updatedSlabs = script.slabs.toMutableList()
            val existing = updatedSlabs[index]
            updatedSlabs[index] = existing.copy(
                layout_mode = layoutMode,
                image_prompt_2 = if (layoutMode != "single" && existing.image_prompt_2.isBlank()) {
                    existing.image_prompt
                } else {
                    existing.image_prompt_2
                }
            )
            current.copy(draftScript = script.copy(slabs = updatedSlabs))
        }
    }

    fun onSlabStatBadgeChanged(index: Int, badge: String) {
        _uiState.update { current ->
            val script = current.draftScript ?: return@update current
            if (index !in script.slabs.indices) return@update current
            val updatedSlabs = script.slabs.toMutableList()
            updatedSlabs[index] = updatedSlabs[index].copy(stat_badge = badge.take(32))
            current.copy(draftScript = script.copy(slabs = updatedSlabs))
        }
    }

    fun onSlabCustomPhotoPicked(index: Int, slotIndex: Int, uri: Uri) {
        _uiState.update { current ->
            val script = current.draftScript ?: return@update current
            if (index !in script.slabs.indices) return@update current
            val updatedSlabs = script.slabs.toMutableList()
            val slab = updatedSlabs[index]
            updatedSlabs[index] = if (slotIndex == 2) {
                slab.copy(
                    custom_image_uri_2 = uri.toString(),
                    layout_mode = if (slab.layout_mode == "single") "split" else slab.layout_mode
                )
            } else {
                slab.copy(custom_image_uri = uri.toString())
            }
            current.copy(draftScript = script.copy(slabs = updatedSlabs))
        }
    }

    fun onSlabClearCustomPhoto(index: Int, slotIndex: Int) {
        _uiState.update { current ->
            val script = current.draftScript ?: return@update current
            if (index !in script.slabs.indices) return@update current
            val updatedSlabs = script.slabs.toMutableList()
            val slab = updatedSlabs[index]
            updatedSlabs[index] = if (slotIndex == 2) {
                slab.copy(custom_image_uri_2 = null)
            } else {
                slab.copy(custom_image_uri = null)
            }
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
