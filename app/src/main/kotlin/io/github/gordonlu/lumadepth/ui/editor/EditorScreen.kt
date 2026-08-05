// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gordonlu.lumadepth.R
import io.github.gordonlu.lumadepth.model.Stage
import io.github.gordonlu.lumadepth.ui.components.CompareSlider

@Composable
fun EditorScreen(uri: android.net.Uri, onBack: () -> Unit) {
    val viewModel = EditorViewModel.factory()
    val state by viewModel.uiState.collectAsState()

    androidx.compose.runtime.LaunchedEffect(uri) {
        viewModel.setUri(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp)
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                text = stringResource(R.string.editor_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 预览
            val sdr = state.sdrPreview
            val hdr = state.hdrPreview
            if (state.loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.stage_reading),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (sdr != null && hdr != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Box {
                        CompareSlider(
                            original = sdr,
                            enhanced = hdr,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                        )
                        Label(
                            text = stringResource(R.string.original),
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                        Label(
                            text = stringResource(R.string.hdr_preview),
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.compare_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            // HDR 显示能力提示
            HdrHint(hdrAvailable = viewModel.isHdrDisplayAvailable())

            Spacer(Modifier.height(16.dp))

            // 参数区
            if (state.sdrPreview != null) {
                ParameterCard(state) { intensity, local, auto ->
                    viewModel.setHdrIntensity(intensity)
                    viewModel.setLocalEnhancement(local)
                    viewModel.setAutoOptimize(auto)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 导出
            if (state.exporting) {
                StageIndicator(stage = state.stage)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { viewModel.export() },
                enabled = !state.exporting && state.sdrPreview != null && !state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
            ) {
                Text(
                    text = stringResource(R.string.export),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(16.dp))

            // 结果
            state.exportResult?.let { result ->
                ResultCard(result, onDone = onBack)
                Spacer(Modifier.height(16.dp))
            }

            // 错误
            state.errorMessage?.let { message ->
                ErrorCard(message, onDismiss = { viewModel.clearError() })
                Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        modifier = modifier.padding(10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun HdrHint(hdrAvailable: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(
                if (hdrAvailable) R.string.hdr_display_hint
                else R.string.no_hdr_display_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ParameterCard(
    state: EditorUiState,
    onChange: (intensity: Float, local: Float, auto: Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.hdr_intensity),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = state.intensity01,
                    onValueChange = { onChange(it, state.local01, state.autoOptimize) },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(state.intensity01 * 100).toInt()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.local_enhancement),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = state.local01,
                    onValueChange = { onChange(state.intensity01, it, state.autoOptimize) },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(state.local01 * 100).toInt()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.auto_optimize),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.auto_optimize_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.autoOptimize,
                    onCheckedChange = { onChange(state.intensity01, state.local01, it) },
                )
            }
        }
    }
}

@Composable
private fun StageIndicator(stage: Stage?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = stageText(stage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun stageText(stage: Stage?): String = when (stage) {
    Stage.READING -> stringResource(R.string.stage_reading)
    Stage.ANALYZING -> stringResource(R.string.stage_analyzing)
    Stage.TONE_MAPPING -> stringResource(R.string.stage_tone_mapping)
    Stage.GAIN_MAP -> stringResource(R.string.stage_gain_map)
    Stage.ENCODING -> stringResource(R.string.stage_encoding)
    Stage.VERIFYING -> stringResource(R.string.stage_verifying)
    Stage.SAVING -> stringResource(R.string.stage_saving)
    null -> stringResource(R.string.stage_encoding)
}

@Composable
private fun ResultCard(result: ExportResultUi, onDone: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.result_saved),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.result_verified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            ResultRow(stringResource(R.string.result_file_name), result.fileName)
            ResultRow(stringResource(R.string.result_dimensions), "${result.width} × ${result.height}")
            ResultRow(stringResource(R.string.result_file_size), formatSize(result.fileSizeBytes))
            ResultRow(stringResource(R.string.result_gainmap_size), "${result.gainMapWidth} × ${result.gainMapHeight}")
            ResultRow(stringResource(R.string.result_has_gainmap), if (result.hasGainmap) "是" else "否")
            ResultRow(stringResource(R.string.result_location), stringResource(R.string.result_location_desc))
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
            ) {
                Text(stringResource(R.string.result_ok))
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.result_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
            ) {
                Text(stringResource(R.string.result_ok))
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.2f MB", bytes / 1_000_000f)
        bytes >= 1_000 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_000f)
        else -> "$bytes B"
    }
}
