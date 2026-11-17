package com.screenrecorder.pro

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenrecorder.pro.model.AudioSourceOption
import com.screenrecorder.pro.ui.MainViewModel
import com.screenrecorder.pro.ui.components.RegionSelectorDialog
import com.screenrecorder.pro.ui.screens.RecorderDashboardScreen
import com.screenrecorder.pro.ui.screens.RecordingLibraryScreen
import com.screenrecorder.pro.ui.theme.ScreenRecorderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var lastKnownRotation: Int = Surface.ROTATION_0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lastKnownRotation = runCatching { display.rotation }.getOrDefault(Surface.ROTATION_0)

        setContent {
            ScreenRecorderTheme {
                ScreenRecorderRoot(
                    viewModel = viewModel,
                    onRequestMediaProjection = { launcher ->
                        val mgr = getSystemService(MediaProjectionManager::class.java)
                        launcher.launch(mgr.createScreenCaptureIntent())
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshOverlayPermissionAndStorage()
        viewModel.refreshLibraryAndStorage()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val currentRotation = runCatching { display.rotation }.getOrDefault(lastKnownRotation)
        val cwSteps = (currentRotation - lastKnownRotation + 4) % 4
        if (cwSteps != 0) {
            viewModel.onDisplayRotated(cwSteps)
            lastKnownRotation = currentRotation
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenRecorderRoot(
    viewModel: MainViewModel,
    onRequestMediaProjection: (androidx.activity.result.ActivityResultLauncher<android.content.Intent>) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startRecordingWithProjection(result.resultCode, data)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Screen capture permission was cancelled.")
            }
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioGranted = if (uiState.audioOption == AudioSourceOption.INTERNAL_SOUND ||
            uiState.audioOption == AudioSourceOption.MICROPHONE
        ) {
            grants[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!audioGranted) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Audio permission is required for Internal Sound / Microphone capture."
                )
            }
            return@rememberLauncherForActivityResult
        }
        onRequestMediaProjection(mediaProjectionLauncher)
    }

    fun requestPermissionsAndStart() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (uiState.audioOption == AudioSourceOption.MICROPHONE &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (needed.isEmpty()) {
            onRequestMediaProjection(mediaProjectionLauncher)
        } else {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(if (selectedTab == 0) "Screen Recorder" else "Recordings")
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.FiberManualRecord, contentDescription = null) },
                    label = { Text("Recorder") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        viewModel.refreshLibraryAndStorage()
                    },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                    label = { Text("Library") }
                )
            }
        }
    ) { innerPadding ->
        if (selectedTab == 0) {
            RecorderDashboardScreen(
                uiState = uiState,
                onSelectAudio = viewModel::selectAudioOption,
                onSelectBitrate = viewModel::selectBitrateOption,
                onSelectMode = viewModel::selectRecordingMode,
                onEditRegion = viewModel::openRegionSelector,
                onStartRecording = ::requestPermissionsAndStart,
                onPauseRecording = viewModel::pauseRecording,
                onResumeRecording = viewModel::resumeRecording,
                onStopRecording = viewModel::stopRecording,
                onDismissError = viewModel::clearRecordingError,
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            RecordingLibraryScreen(
                recordings = uiState.recordings,
                thumbnails = uiState.thumbnails,
                activePlaybackItem = uiState.activePlaybackItem,
                onPlay = viewModel::openPlayback,
                onClosePlayback = viewModel::closePlayback,
                onRename = viewModel::renameRecording,
                onDelete = viewModel::deleteRecording,
                onShare = { item ->
                    context.startActivity(viewModel.buildShareIntent(item))
                },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    if (uiState.showRegionSelector) {
        RegionSelectorDialog(
            initialRegion = uiState.savedRegion,
            onConfirm = viewModel::confirmRegionSelection,
            onDismiss = viewModel::dismissRegionSelector
        )
    }
}
