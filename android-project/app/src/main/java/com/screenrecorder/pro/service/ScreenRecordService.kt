package com.screenrecorder.pro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.screenrecorder.pro.MainActivity
import com.screenrecorder.pro.R
import com.screenrecorder.pro.engine.EngineSnapshot
import com.screenrecorder.pro.engine.ScreenRecorderEngine
import com.screenrecorder.pro.model.AudioSourceOption
import com.screenrecorder.pro.model.BitrateOption
import com.screenrecorder.pro.model.NormalizedRegion
import com.screenrecorder.pro.model.RecordingMode
import com.screenrecorder.pro.model.RecordingStatus
import com.screenrecorder.pro.overlay.RegionBorderOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ScreenRecordService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ScreenRecordService = this@ScreenRecordService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var engine: ScreenRecorderEngine
    private lateinit var overlayController: RegionBorderOverlayController
    private lateinit var notificationManager: NotificationManager

    private var stateObserverJob: Job? = null

    val snapshot: StateFlow<EngineSnapshot>
        get() = engine.snapshot

    override fun onCreate() {
        super.onCreate()
        engine = ScreenRecorderEngine(applicationContext)
        overlayController = RegionBorderOverlayController(applicationContext)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        observeEngineSnapshot()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                val audioName = intent.getStringExtra(EXTRA_AUDIO_OPTION)
                val bitrateName = intent.getStringExtra(EXTRA_BITRATE_OPTION)
                val modeName = intent.getStringExtra(EXTRA_RECORDING_MODE)

                val audio = runCatching { AudioSourceOption.valueOf(audioName!!) }
                    .getOrDefault(AudioSourceOption.INTERNAL_SOUND)
                val bitrate = runCatching { BitrateOption.valueOf(bitrateName!!) }
                    .getOrDefault(BitrateOption.LOSSLESS)
                val mode = runCatching { RecordingMode.valueOf(modeName!!) }
                    .getOrDefault(RecordingMode.FULL_SCREEN)

                val region = if (mode == RecordingMode.REGION) {
                    NormalizedRegion(
                        left = intent.getFloatExtra(EXTRA_REGION_LEFT, 0.1f),
                        top = intent.getFloatExtra(EXTRA_REGION_TOP, 0.18f),
                        right = intent.getFloatExtra(EXTRA_REGION_RIGHT, 0.9f),
                        bottom = intent.getFloatExtra(EXTRA_REGION_BOTTOM, 0.72f)
                    ).clamped()
                } else {
                    null
                }

                val serviceType = if (audio == AudioSourceOption.MICROPHONE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(RecordingStatus.PREPARING, 0L),
                    serviceType
                )

                if (resultCode != Int.MIN_VALUE && resultData != null) {
                    if (region != null) {
                        overlayController.showIfPermitted(region)
                    }
                    engine.startRecording(
                        resultCode = resultCode,
                        resultData = resultData,
                        audioOption = audio,
                        bitrateOption = bitrate,
                        recordingMode = mode,
                        region = region
                    )
                } else {
                    stopSelf()
                }
            }

            ACTION_PAUSE -> engine.pauseRecording()
            ACTION_RESUME -> engine.resumeRecording()
            ACTION_STOP -> stopSession()
        }
        return START_NOT_STICKY
    }

    fun pauseRecording() = engine.pauseRecording()

    fun resumeRecording() = engine.resumeRecording()

    fun stopSession() {
        overlayController.dismiss()
        engine.stopRecording()
    }

    private fun observeEngineSnapshot() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            engine.snapshot.collect { state ->
                when (state.status) {
                    RecordingStatus.RECORDING,
                    RecordingStatus.PAUSED -> {
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildNotification(state.status, state.elapsedMs)
                        )
                    }

                    RecordingStatus.IDLE,
                    RecordingStatus.ERROR -> {
                        overlayController.dismiss()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }

                    else -> Unit
                }
            }
        }
    }

    override fun onDestroy() {
        overlayController.dismiss()
        engine.stopRecording()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: RecordingStatus, elapsedMs: Long): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (status == RecordingStatus.PAUSED) {
            getString(R.string.notification_paused_title)
        } else {
            getString(R.string.notification_recording_title)
        }

        val totalSec = elapsedMs / 1000L
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        val timerText = String.format("%02d:%02d", mins, secs)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(title)
            .setContentText("Duration: $timerText")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)

        if (status == RecordingStatus.PAUSED) {
            val resumeIntent = PendingIntent.getService(
                this,
                102,
                Intent(this, ScreenRecordService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.action_resume), resumeIntent)
        } else if (status == RecordingStatus.RECORDING) {
            val pauseIntent = PendingIntent.getService(
                this,
                103,
                Intent(this, ScreenRecordService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.action_pause), pauseIntent)
        }

        builder.addAction(0, getString(R.string.action_stop), stopIntent)
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "screen_recording_channel"
        private const val NOTIFICATION_ID = 4001

        const val ACTION_START = "com.screenrecorder.pro.action.START"
        const val ACTION_PAUSE = "com.screenrecorder.pro.action.PAUSE"
        const val ACTION_RESUME = "com.screenrecorder.pro.action.RESUME"
        const val ACTION_STOP = "com.screenrecorder.pro.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_AUDIO_OPTION = "extra_audio_option"
        const val EXTRA_BITRATE_OPTION = "extra_bitrate_option"
        const val EXTRA_RECORDING_MODE = "extra_recording_mode"
        const val EXTRA_REGION_LEFT = "extra_region_left"
        const val EXTRA_REGION_TOP = "extra_region_top"
        const val EXTRA_REGION_RIGHT = "extra_region_right"
        const val EXTRA_REGION_BOTTOM = "extra_region_bottom"

        fun createStartIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            audioOption: AudioSourceOption,
            bitrateOption: BitrateOption,
            recordingMode: RecordingMode,
            region: NormalizedRegion?
        ): Intent {
            return Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_AUDIO_OPTION, audioOption.name)
                putExtra(EXTRA_BITRATE_OPTION, bitrateOption.name)
                putExtra(EXTRA_RECORDING_MODE, recordingMode.name)
                if (region != null) {
                    putExtra(EXTRA_REGION_LEFT, region.left)
                    putExtra(EXTRA_REGION_TOP, region.top)
                    putExtra(EXTRA_REGION_RIGHT, region.right)
                    putExtra(EXTRA_REGION_BOTTOM, region.bottom)
                }
            }
        }
    }
}
