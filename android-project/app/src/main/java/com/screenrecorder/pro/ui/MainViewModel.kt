package com.screenrecorder.pro.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screenrecorder.pro.codec.HardwareCodecInspector
import com.screenrecorder.pro.data.PreferencesRepository
import com.screenrecorder.pro.data.RecordingLibraryRepository
import com.screenrecorder.pro.engine.AudioCaptureEngine
import com.screenrecorder.pro.engine.EngineSnapshot
import com.screenrecorder.pro.model.AudioSourceOption
import com.screenrecorder.pro.model.BitrateOption
import com.screenrecorder.pro.model.EncoderResolutionResult
import com.screenrecorder.pro.model.MicrophoneRoutingState
import com.screenrecorder.pro.model.NormalizedRegion
import com.screenrecorder.pro.model.RecordingItem
import com.screenrecorder.pro.model.RecordingMode
import com.screenrecorder.pro.model.RecordingStatus
import com.screenrecorder.pro.model.StorageInfo
import com.screenrecorder.pro.service.ScreenRecordService
import com.screenrecorder.pro.util.DisplayMetricsHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val audioOption: AudioSourceOption = AudioSourceOption.INTERNAL_SOUND,
    val bitrateOption: BitrateOption = BitrateOption.LOSSLESS,
    val recordingMode: RecordingMode = RecordingMode.FULL_SCREEN,
    val savedRegion: NormalizedRegion = NormalizedRegion(),
    val hasSavedRegion: Boolean = false,
    val showRegionSelector: Boolean = false,
    val engineSnapshot: EngineSnapshot = EngineSnapshot(),
    val encoderNotice: String? = null,
    val encoderError: String? = null,
    val micRouting: MicrophoneRoutingState = MicrophoneRoutingState(),
    val storageInfo: StorageInfo = StorageInfo(0L, 0L),
    val canDrawOverlays: Boolean = false,
    val recordings: List<RecordingItem> = emptyList(),
    val thumbnails: Map<Long, Bitmap> = emptyMap(),
    val activePlaybackItem: RecordingItem? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesRepository(application)
    private val libraryRepo = RecordingLibraryRepository(application)
    private val audioManager = application.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var recordService: ScreenRecordService? = null
    private var serviceObserverJob: Job? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? ScreenRecordService.LocalBinder)?.getService() ?: return
            recordService = service
            isBound = true
            serviceObserverJob?.cancel()
            serviceObserverJob = viewModelScope.launch {
                service.snapshot.collect { snap ->
                    val prevStatus = _uiState.value.engineSnapshot.status
                    _uiState.value = _uiState.value.copy(engineSnapshot = snap)
                    if (prevStatus == RecordingStatus.STOPPING && snap.status == RecordingStatus.IDLE) {
                        refreshLibraryAndStorage()
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            recordService = null
        }
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshMicrophoneState()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshMicrophoneState()
        }
    }

    init {
        loadInitialState()
        bindToService()
        audioManager.registerAudioDeviceCallback(audioCallback, mainHandler)
        refreshLibraryAndStorage()
    }

    private fun loadInitialState() {
        val audio = prefs.getAudioOption()
        val bitrate = prefs.getBitrateOption()
        val mode = prefs.getRecordingMode()
        val region = prefs.getSavedRegion()
        val hasRegion = prefs.hasSavedRegion()

        _uiState.value = _uiState.value.copy(
            audioOption = audio,
            bitrateOption = bitrate,
            recordingMode = mode,
            savedRegion = region,
            hasSavedRegion = hasRegion,
            storageInfo = DisplayMetricsHelper.getAvailableStorageInfo(),
            canDrawOverlays = Settings.canDrawOverlays(getApplication()),
            micRouting = AudioCaptureEngine.probeCurrentMicrophone(getApplication())
        )
        validateEncoderChoice(bitrate)
    }

    fun bindToService() {
        val app = getApplication<Application>()
        val intent = Intent(app, ScreenRecordService::class.java)
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun refreshOverlayPermissionAndStorage() {
        _uiState.value = _uiState.value.copy(
            canDrawOverlays = Settings.canDrawOverlays(getApplication()),
            storageInfo = DisplayMetricsHelper.getAvailableStorageInfo()
        )
    }

    fun refreshMicrophoneState() {
        _uiState.value = _uiState.value.copy(
            micRouting = AudioCaptureEngine.probeCurrentMicrophone(getApplication())
        )
    }

    fun selectAudioOption(option: AudioSourceOption) {
        if (_uiState.value.engineSnapshot.status != RecordingStatus.IDLE &&
            _uiState.value.engineSnapshot.status != RecordingStatus.ERROR
        ) return
        prefs.setAudioOption(option)
        _uiState.value = _uiState.value.copy(audioOption = option)
        refreshMicrophoneState()
    }

    fun selectBitrateOption(option: BitrateOption) {
        if (_uiState.value.engineSnapshot.status != RecordingStatus.IDLE &&
            _uiState.value.engineSnapshot.status != RecordingStatus.ERROR
        ) return
        prefs.setBitrateOption(option)
        _uiState.value = _uiState.value.copy(bitrateOption = option)
        validateEncoderChoice(option)
    }

    fun selectRecordingMode(mode: RecordingMode) {
        if (_uiState.value.engineSnapshot.status != RecordingStatus.IDLE &&
            _uiState.value.engineSnapshot.status != RecordingStatus.ERROR
        ) return
        prefs.setRecordingMode(mode)
        val openSelectorNow = mode == RecordingMode.REGION && !prefs.hasSavedRegion()
        _uiState.value = _uiState.value.copy(
            recordingMode = mode,
            showRegionSelector = openSelectorNow
        )
    }

    fun openRegionSelector() {
        _uiState.value = _uiState.value.copy(showRegionSelector = true)
    }

    fun dismissRegionSelector() {
        _uiState.value = _uiState.value.copy(showRegionSelector = false)
    }

    fun confirmRegionSelection(region: NormalizedRegion) {
        prefs.saveRegion(region)
        prefs.setRecordingMode(RecordingMode.REGION)
        _uiState.value = _uiState.value.copy(
            savedRegion = region.clamped(),
            hasSavedRegion = true,
            recordingMode = RecordingMode.REGION,
            showRegionSelector = false
        )
    }

    fun onDisplayRotated(cwSteps: Int) {
        if (cwSteps == 0) return
        val rotated = _uiState.value.savedRegion.rotatedBySteps(cwSteps)
        prefs.saveRegion(rotated)
        _uiState.value = _uiState.value.copy(savedRegion = rotated)
    }

    fun startRecordingWithProjection(resultCode: Int, resultData: Intent) {
        val state = _uiState.value
        val app = getApplication<Application>()
        val startIntent = ScreenRecordService.createStartIntent(
            context = app,
            resultCode = resultCode,
            resultData = resultData,
            audioOption = state.audioOption,
            bitrateOption = state.bitrateOption,
            recordingMode = state.recordingMode,
            region = if (state.recordingMode == RecordingMode.REGION) state.savedRegion else null
        )
        app.startForegroundService(startIntent)
        if (!isBound) {
            bindToService()
        }
    }

    fun pauseRecording() = recordService?.pauseRecording()

    fun resumeRecording() = recordService?.resumeRecording()

    fun stopRecording() = recordService?.stopSession()

    fun refreshLibraryAndStorage() {
        viewModelScope.launch {
            val storage = DisplayMetricsHelper.getAvailableStorageInfo()
            val items = libraryRepo.loadRecordings()
            _uiState.value = _uiState.value.copy(
                storageInfo = storage,
                recordings = items
            )
            items.forEach { item ->
                if (!_uiState.value.thumbnails.containsKey(item.id)) {
                    val bmp = libraryRepo.loadThumbnail(item.uri)
                    if (bmp != null) {
                        _uiState.value = _uiState.value.copy(
                            thumbnails = _uiState.value.thumbnails + (item.id to bmp)
                        )
                    }
                }
            }
        }
    }

    fun openPlayback(item: RecordingItem) {
        _uiState.value = _uiState.value.copy(activePlaybackItem = item)
    }

    fun closePlayback() {
        _uiState.value = _uiState.value.copy(activePlaybackItem = null)
    }

    fun renameRecording(item: RecordingItem, newName: String) {
        viewModelScope.launch {
            if (libraryRepo.renameRecording(item, newName)) {
                refreshLibraryAndStorage()
            }
        }
    }

    fun deleteRecording(item: RecordingItem) {
        viewModelScope.launch {
            if (libraryRepo.deleteRecording(item)) {
                refreshLibraryAndStorage()
            }
        }
    }

    fun buildShareIntent(item: RecordingItem): Intent = libraryRepo.buildShareIntent(item)

    fun clearRecordingError() {
        val snap = _uiState.value.engineSnapshot
        if (snap.status == RecordingStatus.ERROR) {
            _uiState.value = _uiState.value.copy(
                engineSnapshot = snap.copy(status = RecordingStatus.IDLE, errorMessage = null)
            )
        }
    }

    private fun validateEncoderChoice(bitrateOption: BitrateOption) {
        when (val result = HardwareCodecInspector.resolveEncoderPlan(bitrateOption)) {
            is EncoderResolutionResult.Ready -> {
                _uiState.value = _uiState.value.copy(
                    encoderNotice = result.noticeMessage,
                    encoderError = null
                )
            }

            is EncoderResolutionResult.Unavailable -> {
                _uiState.value = _uiState.value.copy(
                    encoderNotice = null,
                    encoderError = result.reason
                )
            }
        }
    }

    override fun onCleared() {
        audioManager.unregisterAudioDeviceCallback(audioCallback)
        if (isBound) {
            runCatching { getApplication<Application>().unbindService(serviceConnection) }
            isBound = false
        }
        super.onCleared()
    }
}
