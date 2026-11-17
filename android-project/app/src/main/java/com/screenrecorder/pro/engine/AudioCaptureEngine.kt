package com.screenrecorder.pro.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import com.screenrecorder.pro.config.InternalRecordingConfig
import com.screenrecorder.pro.model.AudioSourceOption
import com.screenrecorder.pro.model.MicrophoneRoutingState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures internal playback audio or microphone audio (with automatic external/USB/BT
 * microphone prioritization and safe mid-recording disconnection handling) and encodes
 * to AAC-LC into MuxerCoordinator.
 */
class AudioCaptureEngine(
    private val context: Context,
    private val audioOption: AudioSourceOption,
    private val mediaProjection: MediaProjection?,
    private val muxerCoordinator: MuxerCoordinator,
    private val onRoutingChanged: (MicrophoneRoutingState) -> Unit,
    private val onError: (String) -> Unit
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var captureThread: Thread? = null
    private var drainThread: Thread? = null

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private var pauseStartNs: Long = 0L
    private var accumulatedPausedNs: Long = 0L
    private var activeInputDeviceId: Int? = null
    private var deviceCallback: AudioDeviceCallback? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val sampleRate = InternalRecordingConfig.FIXED_AUDIO_SAMPLE_RATE
        val channelCount = InternalRecordingConfig.FIXED_AUDIO_CHANNEL_COUNT
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
            .coerceAtLeast(4096)

        val audioFormat = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val record = when (audioOption) {
            AudioSourceOption.INTERNAL_SOUND -> {
                val projection = requireNotNull(mediaProjection) {
                    "MediaProjection is required for Internal Sound capture"
                }
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBuf * 2)
                    .build()
            }

            AudioSourceOption.MICROPHONE -> {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBuf * 2)
                    .build()
            }
        }

        audioRecord = record

        if (audioOption == AudioSourceOption.MICROPHONE) {
            selectBestMicrophone(record, initialSelection = true)
            registerDeviceCallback(record)
        }

        val encoderFormat = MediaFormat.createAudioFormat(
            InternalRecordingConfig.FIXED_AUDIO_MIME_TYPE,
            sampleRate,
            channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, InternalRecordingConfig.FIXED_AUDIO_BITRATE_BPS)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
        }

        val encoder = MediaCodec.createEncoderByType(InternalRecordingConfig.FIXED_AUDIO_MIME_TYPE)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        audioEncoder = encoder

        record.startRecording()
        isRunning.set(true)

        drainThread = Thread({ drainAudioEncoder(encoder) }, "AudioEncoderDrain").apply { start() }
        captureThread = Thread({ pumpPcmToEncoder(record, encoder, minBuf) }, "AudioCapturePump").apply { start() }
    }

    fun setPaused(paused: Boolean) {
        if (paused && !isPaused.getAndSet(true)) {
            pauseStartNs = System.nanoTime()
        } else if (!paused && isPaused.getAndSet(false)) {
            accumulatedPausedNs += (System.nanoTime() - pauseStartNs).coerceAtLeast(0L)
        }
    }

    fun stopAndRelease() {
        if (!isRunning.getAndSet(false)) return
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null

        runCatching { audioRecord?.stop() }
        runCatching { captureThread?.join(1000) }
        runCatching { drainThread?.join(1500) }

        runCatching { audioRecord?.release() }
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }

        audioRecord = null
        audioEncoder = null
        if (audioOption == AudioSourceOption.MICROPHONE) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
    }

    private fun registerDeviceCallback(record: AudioRecord) {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (!isRunning.get()) return
                selectBestMicrophone(record, initialSelection = false)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (!isRunning.get()) return
                val activeWasRemoved = removedDevices.any { it.id == activeInputDeviceId }
                if (activeWasRemoved) {
                    selectBestMicrophone(record, initialSelection = false, disconnectedEvent = true)
                }
            }
        }
        deviceCallback = cb
        audioManager.registerAudioDeviceCallback(cb, mainHandler)
    }

    private fun selectBestMicrophone(
        record: AudioRecord,
        initialSelection: Boolean,
        disconnectedEvent: Boolean = false
    ) {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val externalWired = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
        val bluetoothMic = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val builtInMic = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        }

        val chosen = externalWired ?: bluetoothMic ?: builtInMic
        if (chosen != null) {
            record.preferredDevice = chosen
            activeInputDeviceId = chosen.id

            if (chosen.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                chosen.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            ) {
                val commDevice = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (commDevice != null) {
                    audioManager.setCommunicationDevice(commDevice)
                }
            } else {
                runCatching { audioManager.clearCommunicationDevice() }
            }

            val isExternal = chosen.type != AudioDeviceInfo.TYPE_BUILTIN_MIC
            val label = when (chosen.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB External"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired External"
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                else -> "Built-in"
            }
            val name = chosen.productName?.toString()?.ifBlank { null } ?: "$label Microphone"
            val alert = if (disconnectedEvent) {
                "External microphone disconnected. Switched safely to $name."
            } else if (!initialSelection && isExternal) {
                "External microphone detected ($name). Audio routed automatically."
            } else {
                null
            }

            onRoutingChanged(
                MicrophoneRoutingState(
                    deviceName = name,
                    isExternal = isExternal,
                    connectionTypeLabel = label,
                    alertMessage = alert
                )
            )
        } else {
            onRoutingChanged(
                MicrophoneRoutingState(
                    deviceName = "Built-in Microphone",
                    isExternal = false,
                    connectionTypeLabel = "Default",
                    alertMessage = if (disconnectedEvent) {
                        "External microphone disconnected; reverted to system default microphone."
                    } else null
                )
            )
        }
    }

    private fun pumpPcmToEncoder(
        record: AudioRecord,
        encoder: MediaCodec,
        bufferSize: Int
    ) {
        val pcmBuffer = ByteArray(bufferSize)
        while (isRunning.get()) {
            val read = record.read(pcmBuffer, 0, pcmBuffer.size)
            if (read < 0) {
                if (isRunning.get()) {
                    onError("AudioRecord read error ($read)")
                }
                break
            }
            if (read == 0 || isPaused.get()) {
                continue
            }

            val inputIndex = encoder.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuf = encoder.getInputBuffer(inputIndex) ?: continue
                inputBuf.clear()
                val bytesToWrite = read.coerceAtMost(inputBuf.remaining())
                inputBuf.put(pcmBuffer, 0, bytesToWrite)
                val ptsUs = ((System.nanoTime() - accumulatedPausedNs) / 1000L).coerceAtLeast(0L)
                encoder.queueInputBuffer(inputIndex, 0, bytesToWrite, ptsUs, 0)
            }
        }

        // Signal end of stream to audio encoder
        val eosIndex = encoder.dequeueInputBuffer(50_000)
        if (eosIndex >= 0) {
            val ptsUs = ((System.nanoTime() - accumulatedPausedNs) / 1000L).coerceAtLeast(0L)
            encoder.queueInputBuffer(eosIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    private fun drainAudioEncoder(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerCoordinator.addAudioTrack(encoder.outputFormat)
                }

                outIndex >= 0 -> {
                    val encoded = encoder.getOutputBuffer(outIndex)
                    if (encoded != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        if (muxerCoordinator.isStarted()) {
                            muxerCoordinator.writeAudioSample(encoded, info)
                        }
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }

                !isRunning.get() -> {
                    // Continue briefly until EOS arrives or timeout
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    }
                }
            }
        }
    }

    companion object {
        fun probeCurrentMicrophone(context: Context): MicrophoneRoutingState {
            val audioManager = context.getSystemService(AudioManager::class.java)
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val external = inputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            val chosen = external ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (chosen == null) {
                return MicrophoneRoutingState()
            }
            val isExt = chosen.type != AudioDeviceInfo.TYPE_BUILTIN_MIC
            val label = when (chosen.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB External"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired External"
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                else -> "Built-in"
            }
            val name = chosen.productName?.toString()?.ifBlank { null } ?: "$label Microphone"
            return MicrophoneRoutingState(
                deviceName = name,
                isExternal = isExt,
                connectionTypeLabel = label
            )
        }
    }
}
