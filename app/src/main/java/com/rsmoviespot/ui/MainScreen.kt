package com.rsmoviespot.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.text.style.TextOverflow
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
        borderColor = BorderGreen,
        selectedBorderColor = NeonGreen
    )

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NeonGreen,
        unfocusedBorderColor = BorderGreen,
        focusedLabelColor = NeonGreen,
        unfocusedLabelColor = TextMuted,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = NeonGreen
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
            text = "Enter a topic, tap Trending Ideas, or paste your full script. Photos, movie clips & memes are fetched automatically from the internet.",
            color = TextMuted,
            fontSize = 13.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Topic or Script",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedButton(
                onClick = viewModel::loadTrendingTopics,
                enabled = !uiState.isLoadingTrending && !uiState.isGenerating,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, BorderGreen),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
            ) {
                Text(
                    text = if (uiState.isLoadingTrending) "Loading…" else "Trending Ideas",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (uiState.trendingTopics.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.trendingTopics.forEach { idea ->
                    FilterChip(
                        selected = uiState.topic == idea,
                        onClick = { viewModel.onTopicSelectedFromTrending(idea) },
                        enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
                        label = {
                            Text(
                                text = idea,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = chipColors,
                        border = chipBorder
                    )
                }
            }
        }

        OutlinedTextField(
            value = uiState.topic,
            onValueChange = viewModel::onTopicChanged,
            label = { Text("Topic or full script (>25 words = exact script)") },
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            enabled = !uiState.isGenerating && !uiState.isPreviewingScript,
            colors = fieldColors
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
                Text(uiState.selectedBgmName, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = viewModel::cancelGeneratingVideo,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFF7A3A3A)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A8A))
                    ) {
                        Text("Cancel Render", fontSize = 12.sp)
                    }
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

        uiState.generatedThumbnailUri?.let { thumbUri ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cover Thumbnail Ready",
                            color = NeonGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Saved in Pictures/RS Movie Spot",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val viewThumb = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(thumbUri, "image/jpeg")
                                addFlags(
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_ACTIVITY_NEW_TASK
                                )
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(viewThumb, "View Cover"))
                            }.onFailure {
                                Toast.makeText(context, "Saved in Gallery > Pictures/RS Movie Spot", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, BorderGreen),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
                    ) {
                        Text("View Cover", fontSize = 12.sp)
                    }
                }
            }
        }

        val activeKitScript = uiState.completedScript ?: uiState.draftScript
        if (activeKitScript != null &&
            (activeKitScript.youtube_title.isNotBlank() ||
                activeKitScript.youtube_description.isNotBlank() ||
                activeKitScript.pinned_comment.isNotBlank())
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "YouTube Upload Kit",
                        color = NeonGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (activeKitScript.youtube_title.isNotBlank()) {
                        Text(
                            text = "Title: ${activeKitScript.youtube_title}",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                    if (activeKitScript.youtube_description.isNotBlank()) {
                        Text(
                            text = "Description: ${activeKitScript.youtube_description}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (activeKitScript.pinned_comment.isNotBlank()) {
                        Text(
                            text = "Pinned Comment: ${activeKitScript.pinned_comment}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (activeKitScript.youtube_title.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    copyToClipboard(context, "YouTube Title", activeKitScript.youtube_title)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, BorderGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
                            ) {
                                Text("Copy Title", fontSize = 11.sp)
                            }
                        }
                        if (activeKitScript.youtube_description.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    copyToClipboard(context, "YouTube Description", activeKitScript.youtube_description)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, BorderGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
                            ) {
                                Text("Copy Desc", fontSize = 11.sp)
                            }
                        }
                        if (activeKitScript.pinned_comment.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    copyToClipboard(context, "Pinned Comment", activeKitScript.pinned_comment)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, BorderGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
                            ) {
                                Text("Copy Pin", fontSize = 11.sp)
                            }
                        }
                    }
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
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Scene ${index + 1} · ${slab.voice_emotion} · ${slab.sfx_cue}",
                            color = TextMuted,
                            fontSize = 12.sp
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "ai_generated" to "AI",
                                "real_photo" to "Photo",
                                "video_clip" to "Clip",
                                "meme_clip" to "Meme"
                            ).forEach { (sourceKey, label) ->
                                FilterChip(
                                    selected = slab.visual_source.equals(sourceKey, ignoreCase = true),
                                    onClick = { viewModel.onSlabVisualSourceChanged(index, sourceKey) },
                                    enabled = !uiState.isGenerating,
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = chipColors,
                                    border = chipBorder
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "single" to "Single",
                                "split" to "Split 2",
                                "flip" to "Flip 2"
                            ).forEach { (layoutKey, label) ->
                                FilterChip(
                                    selected = slab.layout_mode.equals(layoutKey, ignoreCase = true),
                                    onClick = { viewModel.onSlabLayoutModeChanged(index, layoutKey) },
                                    enabled = !uiState.isGenerating,
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = chipColors,
                                    border = chipBorder
                                )
                            }
                        }

                        if (!slab.visual_source.equals("ai_generated", ignoreCase = true)) {
                            OutlinedTextField(
                                value = slab.real_photo_query,
                                onValueChange = { viewModel.onSlabRealPhotoQueryChanged(index, it) },
                                label = { Text("Internet query (photo / clip / meme)") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !uiState.isGenerating,
                                singleLine = true,
                                colors = fieldColors
                            )
                        }

                        OutlinedTextField(
                            value = slab.stat_badge,
                            onValueChange = { viewModel.onSlabStatBadgeChanged(index, it) },
                            label = { Text("Stat Badge (optional e.g. ₹500 Cr)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isGenerating,
                            singleLine = true,
                            colors = fieldColors
                        )

                        OutlinedTextField(
                            value = slab.voiceover_text,
                            onValueChange = { viewModel.onSlabVoiceoverChanged(index, it) },
                            label = { Text("Voiceover") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isGenerating,
                            colors = fieldColors
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}
