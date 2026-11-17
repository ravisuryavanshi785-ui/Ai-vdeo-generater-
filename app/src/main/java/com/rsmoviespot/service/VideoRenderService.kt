package com.rsmoviespot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rsmoviespot.domain.models.VideoLanguage
import com.rsmoviespot.domain.models.VideoScriptResponse
import com.rsmoviespot.domain.models.VoiceGender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class RenderServiceState(
    val isGenerating: Boolean = false,
    val progressText: String = "",
    val progressPercentage: Int = 0,
    val statusMessage: String = "",
    val generatedVideoUri: Uri? = null,
    val generatedThumbnailUri: Uri? = null,
    val completedScript: VideoScriptResponse? = null
)

@AndroidEntryPoint
class VideoRenderService : Service() {

    @Inject
    lateinit var videoRenderEngine: VideoRenderEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renderJob: Job? = null

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RENDER -> {
                val topic = intent.getStringExtra(EXTRA_TOPIC)?.trim().orEmpty()
                val isShorts = intent.getBooleanExtra(EXTRA_IS_SHORTS, true)
                val bgmUri = intent.getStringExtra(EXTRA_BGM_URI)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Uri.parse(it) }
                val preGeneratedScript = intent.getStringExtra(EXTRA_SCRIPT_JSON)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { jsonStr ->
                        runCatching {
                            serviceJson.decodeFromString(VideoScriptResponse.serializer(), jsonStr)
                        }.getOrNull()
                    }
                val language = intent.getStringExtra(EXTRA_LANGUAGE)
                    ?.let { name -> runCatching { VideoLanguage.valueOf(name) }.getOrNull() }
                    ?: VideoLanguage.AUTO
                val voiceGender = intent.getStringExtra(EXTRA_VOICE_GENDER)
                    ?.let { name -> runCatching { VoiceGender.valueOf(name) }.getOrNull() }
                    ?: VoiceGender.MALE

                startAsForeground(
                    buildNotification("Preparing video render…", 0)
                )

                if (topic.isBlank()) {
                    _renderState.update {
                        it.copy(
                            isGenerating = false,
                            statusMessage = "Enter a topic before generating a video."
                        )
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                startRenderJob(
                    topic = topic,
                    isShorts = isShorts,
                    customBgmUri = bgmUri,
                    preGeneratedScript = preGeneratedScript,
                    language = language,
                    voiceGender = voiceGender
                )
            }

            ACTION_CANCEL_RENDER -> {
                renderJob?.cancel()
                _renderState.update {
                    it.copy(
                        isGenerating = false,
                        progressText = "",
                        statusMessage = "Video generation cancelled."
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                startAsForeground(
                    buildNotification("Preparing video render…", 0)
                )
            }
        }

        return START_NOT_STICKY
    }

    private fun startRenderJob(
        topic: String,
        isShorts: Boolean,
        customBgmUri: Uri?,
        preGeneratedScript: VideoScriptResponse?,
        language: VideoLanguage,
        voiceGender: VoiceGender
    ) {
        if (renderJob?.isActive == true) return

        _renderState.value = RenderServiceState(
            isGenerating = true,
            progressText = "Starting video generation…",
            progressPercentage = 0,
            statusMessage = "",
            generatedVideoUri = null,
            generatedThumbnailUri = null,
            completedScript = null
        )

        val wakeLock = runCatching {
            val pm = getSystemService(PowerManager::class.java)
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RSMovieSpot::RenderLock")?.apply {
                acquire(15 * 60 * 1000L)
            }
        }.getOrNull()

        renderJob = serviceScope.launch {
            var lastPercent = -1
            var lastMessage = ""

            try {
                val result = videoRenderEngine.render(
                    topic = topic,
                    isShorts = isShorts,
                    customBgmUri = customBgmUri,
                    preGeneratedScript = preGeneratedScript,
                    language = language,
                    voiceGender = voiceGender
                ) { rawPercent, message ->
                    val percent = rawPercent.coerceIn(0, 100)
                    _renderState.update {
                        it.copy(
                            progressPercentage = percent,
                            progressText = message
                        )
                    }

                    if (percent != lastPercent || message != lastMessage) {
                        lastPercent = percent
                        lastMessage = message
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildNotification(message, percent)
                        )
                    }
                }

                _renderState.update {
                    it.copy(
                        isGenerating = false,
                        progressPercentage = 100,
                        progressText = "Video & cover thumbnail saved to gallery.",
                        statusMessage = "Video generation complete.",
                        generatedVideoUri = result.galleryUri,
                        generatedThumbnailUri = result.thumbnailUri,
                        completedScript = result.script
                    )
                }

                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification("Video render complete", 100)
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } catch (cancelled: CancellationException) {
                _renderState.update {
                    it.copy(
                        isGenerating = false,
                        statusMessage = "Video generation cancelled."
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                throw cancelled
            } catch (error: Throwable) {
                val errorMessage = error.message
                    ?.takeIf(String::isNotBlank)
                    ?.let { "Generation failed: $it" }
                    ?: "Generation failed (${error::class.java.simpleName})."

                _renderState.update {
                    it.copy(
                        isGenerating = false,
                        statusMessage = errorMessage
                    )
                }

                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(errorMessage, 0)
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } finally {
                runCatching {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
            }
        }
    }

    private fun startAsForeground(notification: Notification) {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundType
        )
    }

    private fun buildNotification(message: String, progress: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("RS Movie Spot")
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(progress in 1..99 || _renderState.value.isGenerating)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video rendering",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress notifications for video generation"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        renderJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_RENDER = "com.rsmoviespot.service.action.START_RENDER"
        const val ACTION_CANCEL_RENDER = "com.rsmoviespot.service.action.CANCEL_RENDER"

        const val EXTRA_TOPIC = "extra_topic"
        const val EXTRA_IS_SHORTS = "extra_is_shorts"
        const val EXTRA_BGM_URI = "extra_bgm_uri"
        const val EXTRA_SCRIPT_JSON = "extra_script_json"
        const val EXTRA_LANGUAGE = "extra_language"
        const val EXTRA_VOICE_GENDER = "extra_voice_gender"

        private const val CHANNEL_ID = "video_render_channel"
        private const val NOTIFICATION_ID = 4101

        private val serviceJson = Json { ignoreUnknownKeys = true }

        private val _renderState = MutableStateFlow(RenderServiceState())
        val renderState: StateFlow<RenderServiceState> = _renderState.asStateFlow()

        fun startRender(
            context: Context,
            topic: String,
            isShorts: Boolean,
            bgmUri: Uri? = null,
            preGeneratedScript: VideoScriptResponse? = null,
            language: VideoLanguage = VideoLanguage.AUTO,
            voiceGender: VoiceGender = VoiceGender.MALE
        ) {
            val scriptJson = preGeneratedScript?.let {
                serviceJson.encodeToString(VideoScriptResponse.serializer(), it)
            }
            val intent = Intent(context, VideoRenderService::class.java)
                .setAction(ACTION_START_RENDER)
                .putExtra(EXTRA_TOPIC, topic)
                .putExtra(EXTRA_IS_SHORTS, isShorts)
                .putExtra(EXTRA_BGM_URI, bgmUri?.toString())
                .putExtra(EXTRA_SCRIPT_JSON, scriptJson)
                .putExtra(EXTRA_LANGUAGE, language.name)
                .putExtra(EXTRA_VOICE_GENDER, voiceGender.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelRender(context: Context) {
            val intent = Intent(context, VideoRenderService::class.java)
                .setAction(ACTION_CANCEL_RENDER)
            context.startService(intent)
        }
    }
}
