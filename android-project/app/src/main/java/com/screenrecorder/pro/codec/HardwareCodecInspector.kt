package com.screenrecorder.pro.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.screenrecorder.pro.config.InternalRecordingConfig
import com.screenrecorder.pro.model.BitrateOption
import com.screenrecorder.pro.model.EncoderResolutionResult
import com.screenrecorder.pro.model.HardwareDecoderInfo
import com.screenrecorder.pro.model.HardwareEncoderProfile
import java.util.Locale

/**
 * Dynamically inspects device MediaCodec capabilities for hardware-only H.264/AVC
 * encoding and decoding. Never falls back silently to software codecs.
 */
object HardwareCodecInspector {

    private enum class SocVendor {
        QUALCOMM,
        MEDIATEK,
        OTHER
    }

    private fun detectSocVendor(): SocVendor {
        val signature = buildString {
            append(Build.SOC_MANUFACTURER)
            append(' ')
            append(Build.SOC_MODEL)
            append(' ')
            append(Build.HARDWARE)
            append(' ')
            append(Build.BOARD)
        }.lowercase(Locale.US)

        return when {
            signature.contains("qti") ||
                signature.contains("qcom") ||
                signature.contains("qualcomm") ||
                signature.contains("snapdragon") ||
                signature.contains("sm8") ||
                signature.contains("sm7") -> SocVendor.QUALCOMM

            signature.contains("mtk") ||
                signature.contains("mediatek") ||
                signature.contains("dimensity") ||
                signature.contains("helio") ||
                signature.matches(Regex(".*\\bmt\\d+.*")) -> SocVendor.MEDIATEK

            else -> SocVendor.OTHER
        }
    }

    private fun isStrictHardwareCodec(info: MediaCodecInfo): Boolean {
        val lower = info.name.lowercase(Locale.US)
        if (lower.startsWith("c2.android.") || lower.startsWith("omx.google.")) {
            return false
        }
        return info.isHardwareAccelerated && !info.isSoftwareOnly
    }

    fun inspectHardwareAvcEncoder(): HardwareEncoderProfile? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val mime = InternalRecordingConfig.FIXED_VIDEO_MIME_TYPE
        val hwEncoders = codecList.codecInfos.filter { info ->
            info.isEncoder &&
                isStrictHardwareCodec(info) &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

        if (hwEncoders.isEmpty()) return null

        val vendor = detectSocVendor()
        val preferredName = when (vendor) {
            SocVendor.QUALCOMM -> InternalRecordingConfig.PREFERRED_QUALCOMM_ENCODER
            SocVendor.MEDIATEK -> InternalRecordingConfig.PREFERRED_MEDIATEK_ENCODER
            SocVendor.OTHER -> null
        }

        val selectedInfo = preferredName?.let { target ->
            hwEncoders.firstOrNull { it.name.equals(target, ignoreCase = true) }
        } ?: hwEncoders.firstOrNull {
            it.name.equals(InternalRecordingConfig.PREFERRED_QUALCOMM_ENCODER, ignoreCase = true) ||
                it.name.equals(InternalRecordingConfig.PREFERRED_MEDIATEK_ENCODER, ignoreCase = true)
        } ?: hwEncoders.maxByOrNull { info ->
            runCatching {
                info.getCapabilitiesForType(mime).videoCapabilities.bitrateRange.upper
            }.getOrDefault(0)
        } ?: return null

        val caps = selectedInfo.getCapabilitiesForType(mime)
        val supportsSurface = caps.colorFormats.any {
            it == InternalRecordingConfig.FIXED_COLOR_FORMAT
        }
        if (!supportsSurface) {
            // Try another hardware encoder that accepts Surface input
            val fallbackHw = hwEncoders.firstOrNull { candidate ->
                runCatching {
                    candidate.getCapabilitiesForType(mime).colorFormats.any {
                        it == InternalRecordingConfig.FIXED_COLOR_FORMAT
                    }
                }.getOrDefault(false)
            } ?: return null
            return buildProfile(fallbackHw, mime, vendor)
        }

        return buildProfile(selectedInfo, mime, vendor)
    }

    private fun buildProfile(
        info: MediaCodecInfo,
        mime: String,
        vendor: SocVendor
    ): HardwareEncoderProfile {
        val caps = info.getCapabilitiesForType(mime)
        val videoCaps = caps.videoCapabilities
        val encoderCaps = caps.encoderCapabilities

        val supportsCq = encoderCaps?.isBitrateModeSupported(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
        ) == true

        // Genuine mathematically lossless H.264 requires High 4:4:4 Predictive profile with
        // lossless QP=0 support. Standard hardware AVC mobile encoders (8-bit 4:2:0) do not
        // implement lossless transform-bypass AVC, so we check strictly and never fake it.
        val supportsHigh444 = caps.profileLevels.any {
            it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444
        }
        val qualityRange = encoderCaps?.qualityRange
        val supportsLosslessRange = supportsCq && supportsHigh444 &&
            qualityRange != null && qualityRange.upper >= 100

        val vendorHint = when {
            info.name.equals(InternalRecordingConfig.PREFERRED_QUALCOMM_ENCODER, ignoreCase = true) ->
                "Qualcomm Snapdragon Hardware AVC (${info.name})"
            info.name.equals(InternalRecordingConfig.PREFERRED_MEDIATEK_ENCODER, ignoreCase = true) ->
                "MediaTek Hardware AVC (${info.name})"
            else -> "${vendor.name} Hardware AVC (${info.name})"
        }

        return HardwareEncoderProfile(
            codecName = info.name,
            vendorHint = vendorHint,
            maxSupportedBitrateBps = videoCaps.bitrateRange.upper,
            minSupportedBitrateBps = videoCaps.bitrateRange.lower,
            maxSupportedWidth = videoCaps.supportedWidths.upper,
            maxSupportedHeight = videoCaps.supportedHeights.upper,
            maxSupportedFps = videoCaps.supportedFrameRates.upper,
            supportsMathematicallyLosslessAvc = supportsLosslessRange,
            supportsConstantQualityMode = supportsCq
        )
    }

    fun resolveEncoderPlan(requestedBitrate: BitrateOption): EncoderResolutionResult {
        val profile = inspectHardwareAvcEncoder()
            ?: return EncoderResolutionResult.Unavailable(
                "No compatible hardware H.264/AVC encoder is available on this device. " +
                    "Software encoding fallback is disabled."
            )

        return when (requestedBitrate) {
            BitrateOption.LOSSLESS -> {
                if (profile.supportsMathematicallyLosslessAvc) {
                    EncoderResolutionResult.Ready(
                        profile = profile,
                        effectiveBitrateBps = profile.maxSupportedBitrateBps,
                        isMathematicallyLossless = true,
                        modeDescription = "Mathematically Lossless H.264/AVC",
                        noticeMessage = null
                    )
                } else {
                    val maxMbps = profile.maxSupportedBitrateBps / 1_000_000
                    EncoderResolutionResult.Ready(
                        profile = profile,
                        effectiveBitrateBps = profile.maxSupportedBitrateBps,
                        isMathematicallyLossless = false,
                        modeDescription = "Max Hardware Quality (${maxMbps} Mbps VBR)",
                        noticeMessage = "Mathematically lossless H.264/AVC is not supported by " +
                            "${profile.codecName}. Recording uses maximum validated hardware quality (${maxMbps} Mbps)."
                    )
                }
            }

            else -> {
                val target = requestedBitrate.targetBps ?: profile.maxSupportedBitrateBps
                val clamped = target.coerceIn(
                    profile.minSupportedBitrateBps,
                    profile.maxSupportedBitrateBps
                )
                val targetMbps = target / 1_000_000
                val clampedMbps = clamped / 1_000_000
                val notice = if (clamped < target) {
                    "Requested ${targetMbps} Mbps exceeds ${profile.codecName} hardware limit; " +
                        "clamped to ${clampedMbps} Mbps."
                } else {
                    null
                }
                EncoderResolutionResult.Ready(
                    profile = profile,
                    effectiveBitrateBps = clamped,
                    isMathematicallyLossless = false,
                    modeDescription = "${clampedMbps} Mbps Hardware AVC",
                    noticeMessage = notice
                )
            }
        }
    }

    /**
     * Finds a strict hardware H.264/AVC decoder. Returns null if only software decoders exist.
     */
    fun findHardwareAvcDecoder(): HardwareDecoderInfo? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val mime = InternalRecordingConfig.FIXED_VIDEO_MIME_TYPE
        val hwDecoder = codecList.codecInfos.firstOrNull { info ->
            !info.isEncoder &&
                isStrictHardwareCodec(info) &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        } ?: return null

        return HardwareDecoderInfo(codecName = hwDecoder.name)
    }
}
