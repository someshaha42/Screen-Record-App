package com.screenrecorder.pro.model

import android.net.Uri

enum class AudioSourceOption(val label: String) {
    INTERNAL_SOUND("Internal Sound"),
    MICROPHONE("Microphone")
}

enum class BitrateOption(val label: String, val targetBps: Int?) {
    LOSSLESS("Lossless", null),
    MBPS_200("200 Mbps", 200_000_000),
    MBPS_150("150 Mbps", 150_000_000),
    MBPS_100("100 Mbps", 100_000_000),
    MBPS_50("50 Mbps", 50_000_000)
}

enum class RecordingMode(val label: String) {
    FULL_SCREEN("Full Screen"),
    REGION("Region / Area")
}

enum class RecordingStatus {
    IDLE,
    PREPARING,
    RECORDING,
    PAUSED,
    STOPPING,
    ERROR
}

/**
 * Normalized region bounds in [0f..1f] relative to the current display orientation.
 * Storing normalized fractions guarantees clean survival across display scaling and
 * portrait/landscape recalculation.
 */
data class NormalizedRegion(
    val left: Float = 0.1f,
    val top: Float = 0.18f,
    val right: Float = 0.9f,
    val bottom: Float = 0.72f
) {
    fun clamped(): NormalizedRegion {
        val minSpan = 0.12f
        val l = left.coerceIn(0f, 1f - minSpan)
        val t = top.coerceIn(0f, 1f - minSpan)
        val r = right.coerceIn(l + minSpan, 1f)
        val b = bottom.coerceIn(t + minSpan, 1f)
        return NormalizedRegion(l, t, r, b)
    }

    /**
     * Rotates the normalized region clockwise by 90 degrees when display orientation
     * changes by 90 degrees so the visual area stays locked to physical content.
     */
    fun rotatedBySteps(cwSteps: Int): NormalizedRegion {
        val steps = ((cwSteps % 4) + 4) % 4
        var current = this
        repeat(steps) {
            current = NormalizedRegion(
                left = 1f - current.bottom,
                top = current.left,
                right = 1f - current.top,
                bottom = current.right
            )
        }
        return current.clamped()
    }
}

data class PixelRegion(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val displayWidth: Int,
    val displayHeight: Int
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

data class HardwareEncoderProfile(
    val codecName: String,
    val vendorHint: String,
    val maxSupportedBitrateBps: Int,
    val minSupportedBitrateBps: Int,
    val maxSupportedWidth: Int,
    val maxSupportedHeight: Int,
    val maxSupportedFps: Int,
    val supportsMathematicallyLosslessAvc: Boolean,
    val supportsConstantQualityMode: Boolean
)

sealed class EncoderResolutionResult {
    data class Ready(
        val profile: HardwareEncoderProfile,
        val effectiveBitrateBps: Int,
        val isMathematicallyLossless: Boolean,
        val modeDescription: String,
        val noticeMessage: String?
    ) : EncoderResolutionResult()

    data class Unavailable(val reason: String) : EncoderResolutionResult()
}

data class HardwareDecoderInfo(
    val codecName: String
)

data class MicrophoneRoutingState(
    val deviceName: String = "Built-in Microphone",
    val isExternal: Boolean = false,
    val connectionTypeLabel: String = "Internal",
    val alertMessage: String? = null
)

data class RecordingItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSeconds: Long,
    val width: Int,
    val height: Int
)

data class StorageInfo(
    val availableBytes: Long,
    val totalBytes: Long
)
