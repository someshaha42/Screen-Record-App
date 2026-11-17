package com.screenrecorder.pro.data

import android.content.Context
import android.content.SharedPreferences
import com.screenrecorder.pro.model.AudioSourceOption
import com.screenrecorder.pro.model.BitrateOption
import com.screenrecorder.pro.model.NormalizedRegion
import com.screenrecorder.pro.model.RecordingMode

class PreferencesRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "screen_recorder_prefs",
        Context.MODE_PRIVATE
    )

    fun getAudioOption(): AudioSourceOption {
        val name = prefs.getString(KEY_AUDIO, AudioSourceOption.INTERNAL_SOUND.name)
        return runCatching { AudioSourceOption.valueOf(name!!) }
            .getOrDefault(AudioSourceOption.INTERNAL_SOUND)
    }

    fun setAudioOption(option: AudioSourceOption) {
        prefs.edit().putString(KEY_AUDIO, option.name).apply()
    }

    fun getBitrateOption(): BitrateOption {
        val name = prefs.getString(KEY_BITRATE, BitrateOption.LOSSLESS.name)
        return runCatching { BitrateOption.valueOf(name!!) }
            .getOrDefault(BitrateOption.LOSSLESS)
    }

    fun setBitrateOption(option: BitrateOption) {
        prefs.edit().putString(KEY_BITRATE, option.name).apply()
    }

    fun getRecordingMode(): RecordingMode {
        val name = prefs.getString(KEY_MODE, RecordingMode.FULL_SCREEN.name)
        return runCatching { RecordingMode.valueOf(name!!) }
            .getOrDefault(RecordingMode.FULL_SCREEN)
    }

    fun setRecordingMode(mode: RecordingMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun hasSavedRegion(): Boolean = prefs.getBoolean(KEY_HAS_REGION, false)

    fun getSavedRegion(): NormalizedRegion {
        return NormalizedRegion(
            left = prefs.getFloat(KEY_REGION_LEFT, 0.1f),
            top = prefs.getFloat(KEY_REGION_TOP, 0.18f),
            right = prefs.getFloat(KEY_REGION_RIGHT, 0.9f),
            bottom = prefs.getFloat(KEY_REGION_BOTTOM, 0.72f)
        ).clamped()
    }

    fun saveRegion(region: NormalizedRegion) {
        val safe = region.clamped()
        prefs.edit()
            .putBoolean(KEY_HAS_REGION, true)
            .putFloat(KEY_REGION_LEFT, safe.left)
            .putFloat(KEY_REGION_TOP, safe.top)
            .putFloat(KEY_REGION_RIGHT, safe.right)
            .putFloat(KEY_REGION_BOTTOM, safe.bottom)
            .apply()
    }

    companion object {
        private const val KEY_AUDIO = "audio_option"
        private const val KEY_BITRATE = "bitrate_option"
        private const val KEY_MODE = "recording_mode"
        private const val KEY_HAS_REGION = "has_saved_region"
        private const val KEY_REGION_LEFT = "region_left"
        private const val KEY_REGION_TOP = "region_top"
        private const val KEY_REGION_RIGHT = "region_right"
        private const val KEY_REGION_BOTTOM = "region_bottom"
    }
}
