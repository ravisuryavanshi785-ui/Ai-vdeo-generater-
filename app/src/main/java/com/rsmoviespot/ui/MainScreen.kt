package com.rsmoviespot.ui

import android.Manifest
import android.content.ClipData
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
    val clipboard = LocalClipboardManager.current

    var pendingPhotoTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = pendingPhotoTarget
        pendingPhotoTarget = null
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onSlabCustomPhotoPicked(target.first, target.second, uri)
        }
    }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "RS MOVIE SPOT",
                    color = NeonGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = "AI Video Studio",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            OutlinedButton(
                onClick = viewModel::fetchTrendingTopics,
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
                uiState.trendingTopics.forEach { headline ->
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.onTrendingTopicSelected(headline) },
                        label = {
                            Text(
                                text = if (headline.length > 44) headline.take(44) + "…" else headline,
                                maxLines = 1,
                                fontSize = 12.sp
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
            label = { Text("Topic, detailed prompt, or full script") },
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

        val uploadMetaScript = uiState.completedScript ?: uiState.draftScript
        if (uploadMetaScript != null && (uploadMetaScript.youtube_title.isNotBlank() || uploadMetaScript.pinned_comment.isNotBlank())) {
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
                    if (uiState.generatedThumbnailUri != null) {
                        Text(
                            text = "Cover Thumbnail saved to Pictures / RS Movie Spot",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    if (uploadMetaScript.youtube_title.isNotBlank()) {
                        Text(
                            text = "Title: ${uploadMetaScript.youtube_title}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (uploadMetaScript.youtube_description.isNotBlank()) {
                        Text(
                            text = "${uploadMetaScript.youtube_description}\n${uploadMetaScript.youtube_hashtags}",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    if (uploadMetaScript.pinned_comment.isNotBlank()) {
                        Text(
                            text = "Pinned Comment: ${uploadMetaScript.pinned_comment}",
                            color = Color(0xFFFFE135),
                            fontSize = 12.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (uploadMetaScript.youtube_title.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(uploadMetaScript.youtube_title))
                                    Toast.makeText(context, "Title copied!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, BorderGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
                            ) {
                                Text("Copy Title", fontSize = 12.sp)
                            }
                        }
                        if (uploadMetaScript.youtube_description.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    val fullDesc = "${uploadMetaScript.youtube_description}\n\n${uploadMetaScript.youtube_hashtags}".trim()
                                    clipboard.setText(AnnotatedString(fullDesc))
                                    Toast.makeText(context, "Desc & tags copied!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, BorderGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen)
                            ) {
                                Text("Copy Desc + Tags", fontSize = 12.sp)
                            }
                        }
                        if (uploadMetaScript.pinned_comment.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(uploadMetaScript.pinned_comment))
                                    Toast.makeText(context, "Comment copied!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, BorderGreen),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFE135))
                            ) {
                                Text("Copy Pin", fontSize = 12.sp)
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

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("single" to "Single", "split" to "Split 2", "flip" to "Flip 2").forEach { (modeKey, modeLabel) ->
                                FilterChip(
                                    selected = slab.layout_mode == modeKey,
                                    onClick = { viewModel.onSlabLayoutChanged(index, modeKey) },
                                    enabled = !uiState.isGenerating,
                                    label = { Text(modeLabel, fontSize = 11.sp) },
                                    colors = chipColors,
                                    border = chipBorder
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    pendingPhotoTarget = index to 1
                                    imagePickerLauncher.launch(arrayOf("image/*"))
                                },
                                enabled = !uiState.isGenerating,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BorderGreen),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (slab.custom_image_uri != null) NeonGreen else Color.White
                                )
                            ) {
                                Text(
                                    text = if (slab.custom_image_uri != null) "Photo 1 Set" else "Pick Photo 1",
                                    fontSize = 11.sp
                                )
                            }
                            if (slab.custom_image_uri != null) {
                                OutlinedButton(
                                    onClick = { viewModel.onSlabClearCustomPhoto(index, 1) },
                                    enabled = !uiState.isGenerating,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, BorderGreen),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                                ) {
                                    Text("X", fontSize = 11.sp)
                                }
                            }

                            if (slab.layout_mode != "single") {
                                OutlinedButton(
                                    onClick = {
                                        pendingPhotoTarget = index to 2
                                        imagePickerLauncher.launch(arrayOf("image/*"))
                                    },
                                    enabled = !uiState.isGenerating,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, BorderGreen),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = if (slab.custom_image_uri_2 != null) NeonGreen else Color.White
                                    )
                                ) {
                                    Text(
                                        text = if (slab.custom_image_uri_2 != null) "Photo 2 Set" else "Pick Photo 2",
                                        fontSize = 11.sp
                                    )
                                }
                                if (slab.custom_image_uri_2 != null) {
                                    OutlinedButton(
                                        onClick = { viewModel.onSlabClearCustomPhoto(index, 2) },
                                        enabled = !uiState.isGenerating,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, BorderGreen),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                                    ) {
                                        Text("X", fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = slab.stat_badge,
                            onValueChange = { viewModel.onSlabStatBadgeChanged(index, it) },
                            label = { Text("Top Stat Badge (optional, e.g. ₹80 CR DAY 1)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isGenerating,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonGreen,
                                unfocusedBorderColor = BorderGreen,
                                focusedLabelColor = NeonGreen,
                                unfocusedLabelColor = TextMuted,
                                focusedTextColor = Color(0xFFFFE135),
                                unfocusedTextColor = Color(0xFFFFE135)
                            )
                        )

                        OutlinedTextField(
                            value = slab.voiceover_text,
                            onValueChange = { viewModel.onSlabVoiceoverChanged(index, it) },
                            label = { Text("Voiceover") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isGenerating,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonGreen,
                                unfocusedBorderColor = BorderGreen,
                                focusedLabelColor = NeonGreen,
                                unfocusedLabelColor = TextMuted,
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
