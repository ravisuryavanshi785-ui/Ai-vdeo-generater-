package com.rsmoviespot.ui

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.rsmoviespot.domain.models.VideoLanguage
import com.rsmoviespot.domain.models.VoiceGender
import kotlinx.coroutines.delay
import java.io.File

private val NeonGreen = Color(0xFF39FF14)
private val DarkBg = Color(0xFF0A0E0B)
private val CardBg = Color(0xFF141A15)
private val SelectedChipBg = Color(0xFF1B3A18)
private val BorderGreen = Color(0xFF285C22)
private val TextMuted = Color(0xFFA0ACA2)
private val WarnAmber = Color(0xFFFFB74D)

@Composable
fun MainScreen(
    viewModel: VideoViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showVoiceSetupDialog by remember { mutableStateOf(false) }

    val bgmPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onBgmSelected(uri)
        }
    }

    val voiceSamplePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onVoiceSamplePicked(uri)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.startGeneratingVideo()
    }

    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = SelectedChipBg,
        selectedLabelColor = NeonGreen,
        containerColor = CardBg,
        labelColor = Color.White
    )
    val chipBorder = FilterChipDefaults.filterChipBorder(
        borderColor = BorderGreen,
        selectedBorderColor = NeonGreen
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "RS MOVIE SPOT",
            color = NeonGreen,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )
        Text(
            text = "AI Video Generator",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "Enter a short topic or paste your full script.",
            color = TextMuted,
            fontSize = 14.sp
        )

        OutlinedTextField(
            value = uiState.topic,
            onValueChange = viewModel::onTopicChanged,
            label = { Text("Topic or full script (>25 words = exact script)") },
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonGreen,
                unfocusedBorderColor = BorderGreen,
                focusedLabelColor = NeonGreen,
                unfocusedLabelColor = TextMuted,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = NeonGreen
            )
        )

        Text(
            text = "Choose video format",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = uiState.isShorts,
                onClick = { viewModel.onShortsChanged(true) },
                enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
                label = { Text("Shorts · 9:16") },
                colors = chipColors,
                border = chipBorder
            )
            FilterChip(
                selected = !uiState.isShorts,
                onClick = { viewModel.onShortsChanged(false) },
                enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
                label = { Text("YouTube · 16:9") },
                colors = chipColors,
                border = chipBorder
            )
        }

        Text(
            text = "Language & Voice",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VideoLanguage.entries.forEach { language ->
                FilterChip(
                    selected = uiState.selectedLanguage == language,
                    onClick = { viewModel.onLanguageSelected(language) },
                    enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
                    label = { Text(language.label) },
                    colors = chipColors,
                    border = chipBorder
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            VoiceGender.entries.forEach { gender ->
                val labelText = if (gender == VoiceGender.MY_VOICE) {
                    "My Voice (Clone)"
                } else {
                    "${gender.label} Voice"
                }
                FilterChip(
                    selected = uiState.selectedGender == gender,
                    onClick = { viewModel.onGenderSelected(gender) },
                    enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
                    label = { Text(labelText) },
                    colors = chipColors,
                    border = chipBorder
                )
            }
        }

        if (uiState.selectedGender == VoiceGender.MY_VOICE) {
            val cloneConfig = uiState.voiceCloneConfig
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (cloneConfig.isReady) BorderGreen else WarnAmber
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (cloneConfig.isReady) {
                                "Voice Clone Ready"
                            } else {
                                "Setup Required (Edge Fallback Active)"
                            },
                            color = if (cloneConfig.isReady) NeonGreen else WarnAmber,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (cloneConfig.hasSample) {
                                "Sample: ${cloneConfig.sampleName}"
                            } else {
                                "Tap Setup to record 15s voice & add Modal URL"
                            },
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.clearVoiceTestMessage()
                            showVoiceSetupDialog = true
                        },
                        enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, NeonGreen),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
                    ) {
                        Text("Setup", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Text(
            text = "Background music (optional)",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { bgmPickerLauncher.launch(arrayOf("audio/*")) },
                enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, BorderGreen),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
            ) {
                Text(uiState.selectedBgmName, maxLines = 1)
            }
            if (uiState.selectedBgmUri != null) {
                OutlinedButton(
                    onClick = viewModel::clearBgm,
                    enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, BorderGreen),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Reset")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = viewModel::previewScript,
                enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                border = BorderStroke(1.dp, BorderGreen),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
            ) {
                Text(
                    text = when {
                        uiState.isPreviewingScript -> "Writing…"
                        uiState.draftScript != null -> "Regenerate"
                        else -> "Preview Script"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }

            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.startGeneratingVideo()
                    }
                },
                enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen,
                    contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF233024),
                    disabledContentColor = TextMuted
                )
            ) {
                Text(
                    text = if (uiState.isGenerating) "Rendering…" else "Generate Video",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (uiState.isGenerating) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = uiState.progressText.ifBlank { "Working…" },
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${uiState.progressPercentage}%",
                            color = NeonGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = (uiState.progressPercentage / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonGreen,
                        trackColor = Color(0xFF263327)
                    )
                }
            }
        }

        if (uiState.statusMessage.isNotBlank()) {
            val isError = uiState.statusMessage.contains("failed", ignoreCase = true) ||
                uiState.statusMessage.contains("error", ignoreCase = true)
            Text(
                text = uiState.statusMessage,
                color = if (isError) Color(0xFFFF6B6B) else NeonGreen,
                fontSize = 14.sp
            )
        }

        uiState.generatedVideoUri?.let { videoUri ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val playIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(videoUri, "video/mp4")
                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                            )
                        }
                        val chooser = Intent.createChooser(playIntent, "Play Video").apply {
                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                            )
                        }
                        runCatching {
                            context.startActivity(chooser)
                        }.onFailure { err ->
                            Toast.makeText(
                                context,
                                "Cannot open player: ${err.message ?: "No video player found"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonGreen,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Play Video", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, videoUri)
                            clipData = ClipData.newRawUri("RS Movie Spot Video", videoUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = Intent.createChooser(shareIntent, "Share Video").apply {
                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                            )
                        }
                        runCatching {
                            context.startActivity(chooser)
                        }.onFailure { err ->
                            Toast.makeText(
                                context,
                                "Cannot share video: ${err.message ?: "Share failed"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, NeonGreen),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
                ) {
                    Text("Share Video", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        uiState.draftScript?.let { script ->
            Text(
                text = "Script Preview (${script.slabs.size} scenes · ${script.voice_name})",
                color = NeonGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            script.slabs.forEachIndexed { index, slab ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val visualTag = if (
                            slab.visual_source == "real_photo" &&
                            slab.real_photo_query.isNotBlank()
                        ) {
                            "Real Photo: ${slab.real_photo_query}"
                        } else {
                            "AI Image"
                        }
                        Text(
                            text = "Scene ${index + 1} · $visualTag · ${slab.voice_emotion} · ${slab.sfx_cue}",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = slab.voiceover_text,
                            onValueChange = { viewModel.onSlabVoiceoverChanged(index, it) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isGenerating,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonGreen,
                                unfocusedBorderColor = BorderGreen,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                }
            }
        }
    }

    if (showVoiceSetupDialog) {
        VoiceCloneSetupDialog(
            initialConfig = uiState.voiceCloneConfig,
            isTesting = uiState.isTestingVoiceServer,
            testMessage = uiState.voiceTestMessage,
            onPickSample = { voiceSamplePickerLauncher.launch(arrayOf("audio/*")) },
            createTempRecordingFile = viewModel::createRecordingTempFile,
            onRecordedSample = viewModel::onVoiceSampleRecorded,
            onTestConnection = viewModel::testVoiceServer,
            onSave = { url, pin, baseIsFemale ->
                viewModel.saveVoiceCloneSettings(url, pin, baseIsFemale)
                showVoiceSetupDialog = false
            },
            onDismiss = { showVoiceSetupDialog = false }
        )
    }
}

@Composable
private fun VoiceCloneSetupDialog(
    initialConfig: com.rsmoviespot.data.local.VoiceCloneConfig,
    isTesting: Boolean,
    testMessage: String?,
    onPickSample: () -> Unit,
    createTempRecordingFile: () -> File,
    onRecordedSample: (File) -> Unit,
    onTestConnection: (String, String) -> Unit,
    onSave: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var serverUrl by remember(initialConfig) { mutableStateOf(initialConfig.serverUrl) }
    var secretPin by remember(initialConfig) { mutableStateOf(initialConfig.secretPin) }
    var baseIsFemale by remember(initialConfig) { mutableStateOf(initialConfig.baseIsFemale) }

    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var activeRecordFile by remember { mutableStateOf<File?>(null) }

    fun stopAndSaveRecording() {
        val currentRecorder = recorder
        val file = activeRecordFile
        recorder = null
        activeRecordFile = null
        isRecording = false
        runCatching {
            currentRecorder?.stop()
        }
        runCatching {
            currentRecorder?.release()
        }
        if (file != null) {
            onRecordedSample(file)
        }
    }

    fun startMicRecording() {
        val targetFile = createTempRecordingFile()
        runCatching {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(targetFile.absolutePath)
                prepare()
                start()
            }
            recorder = mr
            activeRecordFile = targetFile
            recordSeconds = 0
            isRecording = true
        }.onFailure { err ->
            Toast.makeText(
                context,
                "Mic error: ${err.message ?: "Could not start recording"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMicRecording()
        } else {
            Toast.makeText(context, "Mic permission required to record voice.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000L)
            recordSeconds += 1
            if (recordSeconds >= 25) {
                stopAndSaveRecording()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NeonGreen,
        unfocusedBorderColor = BorderGreen,
        focusedLabelColor = NeonGreen,
        unfocusedLabelColor = TextMuted,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = NeonGreen
    )

    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = SelectedChipBg,
        selectedLabelColor = NeonGreen,
        containerColor = DarkBg,
        labelColor = Color.White
    )
    val chipBorder = FilterChipDefaults.filterChipBorder(
        borderColor = BorderGreen,
        selectedBorderColor = NeonGreen
    )

    AlertDialog(
        onDismissRequest = {
            if (isRecording) stopAndSaveRecording()
            onDismiss()
        },
        containerColor = CardBg,
        title = {
            Text(
                text = "My Voice (Modal Clone)",
                color = NeonGreen,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Modal Server URL (.modal.run)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors
                )

                OutlinedTextField(
                    value = secretPin,
                    onValueChange = { secretPin = it },
                    label = { Text("Secret PIN (VOICE_API_SECRET)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors
                )

                Text(
                    text = "Base Pitch Tone",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !baseIsFemale,
                        onClick = { baseIsFemale = false },
                        label = { Text("Male Tone") },
                        colors = chipColors,
                        border = chipBorder
                    )
                    FilterChip(
                        selected = baseIsFemale,
                        onClick = { baseIsFemale = true },
                        label = { Text("Female Tone") },
                        colors = chipColors,
                        border = chipBorder
                    )
                }

                Text(
                    text = "Your 15–20s Voice Sample",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (initialConfig.hasSample) {
                        "Saved: ${initialConfig.sampleName}"
                    } else {
                        "No voice sample saved yet. Record in a quiet room."
                    },
                    color = if (initialConfig.hasSample) NeonGreen else TextMuted,
                    fontSize = 12.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (isRecording) {
                                stopAndSaveRecording()
                            } else if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                startMicRecording()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFE53935) else NeonGreen,
                            contentColor = if (isRecording) Color.White else Color.Black
                        )
                    ) {
                        Text(
                            text = if (isRecording) "Stop (${recordSeconds}s)" else "Record Mic",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onPickSample,
                        enabled = !isRecording,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, BorderGreen),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
                    ) {
                        Text("Pick Audio", fontSize = 12.sp)
                    }
                }

                OutlinedButton(
                    onClick = { onTestConnection(serverUrl, secretPin) },
                    enabled = !isTesting && !isRecording,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, BorderGreen),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text(if (isTesting) "Testing Server…" else "Test Server & PIN")
                }

                if (!testMessage.isNullOrBlank()) {
                    val isErr = testMessage.contains("failed", ignoreCase = true) ||
                        testMessage.contains("Invalid", ignoreCase = true)
                    Text(
                        text = testMessage,
                        color = if (isErr) Color(0xFFFF6B6B) else NeonGreen,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isRecording) stopAndSaveRecording()
                    onSave(serverUrl, secretPin, baseIsFemale)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen,
                    contentColor = Color.Black
                )
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isRecording) stopAndSaveRecording()
                    onDismiss()
                }
            ) {
                Text("Cancel", color = TextMuted)
            }
        }
    )
}
