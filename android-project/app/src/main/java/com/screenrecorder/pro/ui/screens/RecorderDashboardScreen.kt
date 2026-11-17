package com.screenrecorder.pro.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.screenrecorder.pro.model.AudioSourceOption
import com.screenrecorder.pro.model.BitrateOption
import com.screenrecorder.pro.model.RecordingMode
import com.screenrecorder.pro.model.RecordingStatus
import com.screenrecorder.pro.ui.MainUiState
import com.screenrecorder.pro.ui.components.AnimatedDeepRedDashedBorder
import com.screenrecorder.pro.util.DisplayMetricsHelper
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecorderDashboardScreen(
    uiState: MainUiState,
    onSelectAudio: (AudioSourceOption) -> Unit,
    onSelectBitrate: (BitrateOption) -> Unit,
    onSelectMode: (RecordingMode) -> Unit,
    onEditRegion: () -> Unit,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val status = uiState.engineSnapshot.status
    val isIdleOrError = status == RecordingStatus.IDLE || status == RecordingStatus.ERROR
    val displaySnap = remember(context) { DisplayMetricsHelper.getCurrentDisplaySnapshot(context) }
    val pxRegion = remember(uiState.savedRegion, displaySnap) {
        DisplayMetricsHelper.toPixelRegion(uiState.savedRegion, displaySnap.width, displaySnap.height)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status & Timer Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = when (status) {
                                RecordingStatus.IDLE -> "Ready to Record"
                                RecordingStatus.PREPARING -> "Preparing Hardware Pipeline"
                                RecordingStatus.RECORDING -> "Recording Active"
                                RecordingStatus.PAUSED -> "Recording Paused"
                                RecordingStatus.STOPPING -> "Finalizing MP4"
                                RecordingStatus.ERROR -> "Recording Error"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Free storage: ${formatStorageGb(uiState.storageInfo.availableBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = formatElapsedTimer(uiState.engineSnapshot.elapsedMs),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                // Active or validation notices (only shown when relevant, e.g. lossless fallback or HW missing)
                val errorText = uiState.encoderError ?: uiState.engineSnapshot.errorMessage
                if (errorText != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            if (uiState.engineSnapshot.status == RecordingStatus.ERROR) {
                                OutlinedButton(onClick = onDismissError) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }

                val noticeText = uiState.engineSnapshot.noticeMessage ?: uiState.encoderNotice
                if (noticeText != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = noticeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }
                }

                // Primary Recording Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (status) {
                        RecordingStatus.IDLE, RecordingStatus.ERROR -> {
                            Button(
                                onClick = onStartRecording,
                                enabled = uiState.encoderError == null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFB91C1C),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Start Recording",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        RecordingStatus.RECORDING -> {
                            OutlinedButton(
                                onClick = onPauseRecording,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Pause")
                            }
                            Button(
                                onClick = onStopRecording,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Stop")
                            }
                        }

                        RecordingStatus.PAUSED -> {
                            Button(
                                onClick = onResumeRecording,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Resume")
                            }
                            Button(
                                onClick = onStopRecording,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Stop")
                            }
                        }

                        RecordingStatus.PREPARING, RecordingStatus.STOPPING -> {
                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    if (status == RecordingStatus.PREPARING) {
                                        "Starting..."
                                    } else {
                                        "Saving..."
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Setting A: Audio
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Audio",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AudioSourceOption.entries.forEach { option ->
                        FilterChip(
                            selected = uiState.audioOption == option,
                            onClick = { onSelectAudio(option) },
                            enabled = isIdleOrError,
                            leadingIcon = {
                                Icon(
                                    imageVector = if (option == AudioSourceOption.INTERNAL_SOUND) {
                                        Icons.Default.VolumeUp
                                    } else {
                                        Icons.Default.Mic
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            label = { Text(option.label) }
                        )
                    }
                }
                if (uiState.audioOption == AudioSourceOption.MICROPHONE) {
                    val activeMic = if (uiState.engineSnapshot.status == RecordingStatus.RECORDING ||
                        uiState.engineSnapshot.status == RecordingStatus.PAUSED
                    ) {
                        uiState.engineSnapshot.micRoutingState
                    } else {
                        uiState.micRouting
                    }
                    Text(
                        text = "Active input: ${activeMic.deviceName} (${activeMic.connectionTypeLabel})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Setting B: Bitrate
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Bitrate",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BitrateOption.entries.forEach { option ->
                        FilterChip(
                            selected = uiState.bitrateOption == option,
                            onClick = { onSelectBitrate(option) },
                            enabled = isIdleOrError,
                            label = { Text(option.label) }
                        )
                    }
                }
            }
        }

        // Setting C & D: Recording Mode + Region Area Selection
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Recording Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RecordingMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.recordingMode == mode,
                            onClick = { onSelectMode(mode) },
                            enabled = isIdleOrError,
                            leadingIcon = {
                                Icon(
                                    imageVector = if (mode == RecordingMode.FULL_SCREEN) {
                                        Icons.Default.Fullscreen
                                    } else {
                                        Icons.Default.Crop
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            label = { Text(mode.label) }
                        )
                    }
                }

                if (uiState.recordingMode == RecordingMode.REGION) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // In-app Region Selection Visual Indicator (retains deep-red dashed indicator even without overlay permission)
                        Surface(
                            modifier = Modifier
                                .width(84.dp)
                                .aspectRatio(displaySnap.width.toFloat() / displaySnap.height.toFloat().coerceAtLeast(1f)),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AnimatedDeepRedDashedBorder(
                                    region = uiState.savedRegion,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Selected Area: ${pxRegion.width} × ${pxRegion.height} px",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Origin (${pxRegion.left}, ${pxRegion.top}) · Saved for next session",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = onEditRegion,
                                enabled = isIdleOrError
                            ) {
                                Text("Adjust Region")
                            }
                        }
                    }

                    // Optional non-mandatory floating border helper
                    if (!uiState.canDrawOverlays) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Optional: Allow Display Over Other Apps to show the red dashed guide outside this app. Region recording works without it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                )
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    }
                                ) {
                                    Text("Optional")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatElapsedTimer(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatStorageGb(bytes: Long): String {
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f GB", gb)
}
