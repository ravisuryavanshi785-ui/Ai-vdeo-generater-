package com.rsmoviespot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.rsmoviespot.domain.models.VideoLanguage
import com.rsmoviespot.domain.models.VoiceGender

private val NeonGreen = Color(0xFF39FF14)
private val DarkBg = Color(0xFF0A0E0B)
private val CardBg = Color(0xFF141A15)
private val SelectedChipBg = Color(0xFF1B3A18)
private val BorderGreen = Color(0xFF285C22)
private val TextMuted = Color(0xFFA0ACA2)

@Composable
fun MainScreen(
    viewModel: VideoViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

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
        enabled = true,
        selected = false,
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

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VoiceGender.entries.forEach { gender ->
                FilterChip(
                    selected = uiState.selectedGender == gender,
                    onClick = { viewModel.onGenderSelected(gender) },
                    enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
                    label = { Text("${gender.label} Voice") },
                    colors = chipColors,
                    border = chipBorder
                )
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
                        progress = { (uiState.progressPercentage / 100f).coerceIn(0f, 1f) },
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
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(playIntent) }
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
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(shareIntent, "Share Video"))
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
                        Text(
                            text = "Scene ${index + 1} · ${slab.voice_emotion} · ${slab.sfx_cue}",
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
}
