package com.screenrecorder.pro.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.screenrecorder.pro.codec.HardwareCodecInspector
import com.screenrecorder.pro.config.InternalRecordingConfig
import com.screenrecorder.pro.model.AudioSourceOption
import com.screenrecorder.pro.model.BitrateOption
import com.screenrecorder.pro.model.EncoderResolutionResult
import com.screenrecorder.pro.model.MicrophoneRoutingState
import com.screenrecorder.pro.model.NormalizedRegion
import com.screenrecorder.pro.model.RecordingMode
import com.screenrecorder.pro.model.RecordingStatus
import com.screenrecorder.pro.util.DisplayMetricsHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class EngineSnapshot(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val elapsedMs: Long = 0L,
    val activeQualitySummary: String? = null,
    val noticeMessage: String? = null,
    val errorMessage: String? = null,
    val micRoutingState: MicrophoneRoutingState = MicrophoneRoutingState(),
    val lastSavedUri: Uri? = null
)

/**
 * UI-independent hardware screen recording engine using MediaProjection, VirtualDisplay,
 * OpenGL ES Surface mirroring/cropping, hardware-only MediaCodec AVC encoding, AudioRecord,
 * and MediaMuxer.
 */
class ScreenRecorderEngine(private val context: Context) {

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val projectionManager = context.getSystemService(MediaProjectionManager::class.java)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var glRenderer: GlSurfaceCropRenderer? = null
    private var muxerCoordinator: MuxerCoordinator? = null
    private var audioEngine: AudioCaptureEngine? = null
    private var videoDrainThread: Thread? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var pendingMediaUri: Uri? = null

    private val isRecordingActive = AtomicBoolean(false)
    private var activeStartWallMs: Long = 0L
    private var accumulatedPausedWallMs: Long = 0L
    private var pauseStartWallMs: Long = 0L

    private var timerRunnable: Runnable? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var projectionCallback: MediaProjection.Callback? = null

    fun startRecording(
        resultCode: Int,
        resultData: Intent,
        audioOption: AudioSourceOption,
        bitrateOption: BitrateOption,
        recordingMode: RecordingMode,
        region: NormalizedRegion?
    ) {
        if (isRecordingActive.get()) return

        _snapshot.value = _snapshot.value.copy(
            status = RecordingStatus.PREPARING,
            errorMessage = null,
            noticeMessage = null
        )

        val encoderPlan = HardwareCodecInspector.resolveEncoderPlan(bitrateOption)
        if (encoderPlan is EncoderResolutionResult.Unavailable) {
            _snapshot.value = _snapshot.value.copy(
                status = RecordingStatus.ERROR,
                errorMessage = encoderPlan.reason
            )
            return
        }
        val readyPlan = encoderPlan as EncoderResolutionResult.Ready

        try {
            val displaySnap = DisplayMetricsHelper.getCurrentDisplaySnapshot(context)
            val cropRegion = if (recordingMode == RecordingMode.REGION) {
                (region ?: NormalizedRegion()).clamped()
            } else {
                null
            }

            val targetWidth: Int
            val targetHeight: Int
            if (cropRegion != null) {
                val px = DisplayMetricsHelper.toPixelRegion(
                    cropRegion,
                    displaySnap.width,
                    displaySnap.height
                )
                targetWidth = px.width
                targetHeight = px.height
            } else {
                targetWidth = displaySnap.width
                targetHeight = displaySnap.height
            }

            val clampedOutW = targetWidth.coerceAtMost(readyPlan.profile.maxSupportedWidth) and 1.inv()
            val clampedOutH = targetHeight.coerceAtMost(readyPlan.profile.maxSupportedHeight) and 1.inv()

            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection = projection

            val projCb = object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        if (isRecordingActive.get()) {
                            stopRecording()
                        }
                    }
                }
            }
            projectionCallback = projCb
            projection.registerCallback(projCb, mainHandler)

            val outputUri = createMediaStoreVideoUri()
            pendingMediaUri = outputUri
            val pfd = context.contentResolver.openFileDescriptor(outputUri, "rw")
                ?: error("Unable to open MediaStore file descriptor for recording")
            outputPfd = pfd

            val muxer = MuxerCoordinator(pfd.fileDescriptor, expectAudioTrack = true)
            muxerCoordinator = muxer

            val videoFormat = MediaFormat.createVideoFormat(
                InternalRecordingConfig.FIXED_VIDEO_MIME_TYPE,
                clampedOutW,
                clampedOutH
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, InternalRecordingConfig.FIXED_COLOR_FORMAT)
                setInteger(MediaFormat.KEY_FRAME_RATE, InternalRecordingConfig.FIXED_FRAME_RATE)
                setInteger(
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    InternalRecordingConfig.FIXED_I_FRAME_INTERVAL_SECONDS
                )
                setInteger(MediaFormat.KEY_BIT_RATE, readyPlan.effectiveBitrateBps)
                if (readyPlan.isMathematicallyLossless && readyPlan.profile.supportsConstantQualityMode) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                    )
                    setInteger(MediaFormat.KEY_QUALITY, 100)
                } else {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                }
            }

            val codec = MediaCodec.createByCodecName(readyPlan.profile.codecName)
            codec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = codec.createInputSurface()
            codec.start()
            videoEncoder = codec

            val renderer = GlSurfaceCropRenderer(
                encoderInputSurface = encoderSurface,
                outputWidth = clampedOutW,
                outputHeight = clampedOutH,
                initialDisplayWidth = displaySnap.width,
                initialDisplayHeight = displaySnap.height,
                cropRegion = cropRegion
            )
            glRenderer = renderer
            val captureInputSurface = renderer.start()

            virtualDisplay = projection.createVirtualDisplay(
                "ScreenRecorderDisplay",
                displaySnap.width,
                displaySnap.height,
                displaySnap.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                captureInputSurface,
                null,
                mainHandler
            )

            registerDisplayOrientationListener()

            val audio = AudioCaptureEngine(
                context = context,
                audioOption = audioOption,
                mediaProjection = projection,
                muxerCoordinator = muxer,
                onRoutingChanged = { routing ->
                    _snapshot.value = _snapshot.value.copy(
                        micRoutingState = routing,
                        noticeMessage = routing.alertMessage ?: _snapshot.value.noticeMessage
                    )
                },
                onError = { err ->
                    _snapshot.value = _snapshot.value.copy(noticeMessage = err)
                }
            )
            audioEngine = audio
            audio.start()

            isRecordingActive.set(true)
            activeStartWallMs = System.currentTimeMillis()
            accumulatedPausedWallMs = 0L

            videoDrainThread = Thread({ drainVideoEncoder(codec, muxer) }, "VideoEncoderDrain").apply {
                start()
            }

            _snapshot.value = _snapshot.value.copy(
                status = RecordingStatus.RECORDING,
                elapsedMs = 0L,
                activeQualitySummary = readyPlan.modeDescription,
                noticeMessage = readyPlan.noticeMessage,
                errorMessage = null
            )
            startTimerLoop()
        } catch (t: Throwable) {
            cleanupResources(discardPendingFile = true)
            _snapshot.value = _snapshot.value.copy(
                status = RecordingStatus.ERROR,
                errorMessage = t.message ?: "Failed to initialize hardware screen recording"
            )
        }
    }

    fun pauseRecording() {
        if (!isRecordingActive.get() || _snapshot.value.status != RecordingStatus.RECORDING) return
        pauseStartWallMs = System.currentTimeMillis()
        glRenderer?.setPaused(true)
        audioEngine?.setPaused(true)
        _snapshot.value = _snapshot.value.copy(status = RecordingStatus.PAUSED)
    }

    fun resumeRecording() {
        if (!isRecordingActive.get() || _snapshot.value.status != RecordingStatus.PAUSED) return
        accumulatedPausedWallMs += (System.currentTimeMillis() - pauseStartWallMs).coerceAtLeast(0L)
        glRenderer?.setPaused(false)
        audioEngine?.setPaused(false)
        _snapshot.value = _snapshot.value.copy(status = RecordingStatus.RECORDING)
    }

    fun stopRecording() {
        if (!isRecordingActive.getAndSet(false)) return
        _snapshot.value = _snapshot.value.copy(status = RecordingStatus.STOPPING)
        stopTimerLoop()

        Thread({
            val savedUri = cleanupResources(discardPendingFile = false)
            mainHandler.post {
                _snapshot.value = _snapshot.value.copy(
                    status = RecordingStatus.IDLE,
                    elapsedMs = 0L,
                    lastSavedUri = savedUri
                )
            }
        }, "RecordingStopThread").start()
    }

    private fun registerDisplayOrientationListener() {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (!isRecordingActive.get()) return
                val snap = DisplayMetricsHelper.getCurrentDisplaySnapshot(context)
                virtualDisplay?.resize(snap.width, snap.height, snap.densityDpi)
                glRenderer?.updateDisplaySize(snap.width, snap.height)
            }
        }
        displayListener = listener
        displayManager.registerDisplayListener(listener, mainHandler)
    }

    private fun drainVideoEncoder(codec: MediaCodec, muxer: MuxerCoordinator) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxer.addVideoTrack(codec.outputFormat)
                }

                outIndex >= 0 -> {
                    val encoded = codec.getOutputBuffer(outIndex)
                    if (encoded != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        if (muxer.isStarted()) {
                            muxer.writeVideoSample(encoded, info)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun cleanupResources(discardPendingFile: Boolean): Uri? {
        stopTimerLoop()
        displayListener?.let { displayManager.unregisterDisplayListener(it) }
        displayListener = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { audioEngine?.stopAndRelease() }
        audioEngine = null

        runCatching { videoEncoder?.signalEndOfInputStream() }
        runCatching { videoDrainThread?.join(2000) }
        videoDrainThread = null

        runCatching { glRenderer?.release() }
        glRenderer = null

        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null

        val wroteSamples = muxerCoordinator?.stopAndRelease() ?: false
        muxerCoordinator = null

        projectionCallback?.let { cb ->
            runCatching { mediaProjection?.unregisterCallback(cb) }
        }
        projectionCallback = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        runCatching { outputPfd?.close() }
        outputPfd = null

        val uri = pendingMediaUri
        pendingMediaUri = null

        if (uri != null) {
            if (discardPendingFile || !wroteSamples) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                return null
            } else {
                val finalizeValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                runCatching { context.contentResolver.update(uri, finalizeValues, null, null) }
            }
        }
        return uri
    }

    private fun createMediaStoreVideoUri(): Uri {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "ScreenRecording_$timestamp.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/ScreenRecorder"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return requireNotNull(
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ) { "Failed to insert MediaStore video entry" }
    }

    private fun startTimerLoop() {
        stopTimerLoop()
        val runnable = object : Runnable {
            override fun run() {
                if (!isRecordingActive.get()) return
                val currentStatus = _snapshot.value.status
                if (currentStatus == RecordingStatus.RECORDING) {
                    val activeMs = (System.currentTimeMillis() - activeStartWallMs - accumulatedPausedWallMs)
                        .coerceAtLeast(0L)
                    _snapshot.value = _snapshot.value.copy(elapsedMs = activeMs)
                }
                mainHandler.postDelayed(this, 100L)
            }
        }
        timerRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopTimerLoop() {
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        timerRunnable = null
    }
}
