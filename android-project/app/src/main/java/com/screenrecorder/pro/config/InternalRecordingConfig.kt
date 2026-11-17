package com.screenrecorder.pro.config

import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Fixed internal recording parameters.
 * Per specification, these values remain strictly internal and are never exposed
 * as editable controls in the user interface.
 */
object InternalRecordingConfig {
    const val FIXED_VIDEO_MIME_TYPE: String = MediaFormat.MIMETYPE_VIDEO_AVC
    const val FIXED_FRAME_RATE: Int = 60
    const val FIXED_I_FRAME_INTERVAL_SECONDS: Int = 1
    const val FIXED_COLOR_FORMAT: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface

    const val PREFERRED_QUALCOMM_ENCODER: String = "c2.qti.avc.encoder"
    const val PREFERRED_MEDIATEK_ENCODER: String = "c2.mtk.avc.encoder"

    const val FIXED_AUDIO_MIME_TYPE: String = MediaFormat.MIMETYPE_AUDIO_AAC
    const val FIXED_AUDIO_SAMPLE_RATE: Int = 48_000
    const val FIXED_AUDIO_CHANNEL_COUNT: Int = 2
    const val FIXED_AUDIO_BITRATE_BPS: Int = 320_000
}
