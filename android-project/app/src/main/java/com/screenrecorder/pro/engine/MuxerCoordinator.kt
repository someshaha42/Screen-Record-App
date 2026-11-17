package com.screenrecorder.pro.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Coordinates synchronized MediaMuxer track registration and sample writing
 * for both video (H.264/AVC) and audio (AAC-LC) encoders.
 */
class MuxerCoordinator(
    outputFd: FileDescriptor,
    private val expectAudioTrack: Boolean
) {
    private val muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Any()

    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    private var started: Boolean = false
    private var stopped: Boolean = false
    private var hasWrittenVideoSample: Boolean = false

    fun addVideoTrack(format: MediaFormat): Int {
        synchronized(lock) {
            if (videoTrackIndex == -1) {
                videoTrackIndex = muxer.addTrack(format)
                tryStartLocked()
            }
            return videoTrackIndex
        }
    }

    fun addAudioTrack(format: MediaFormat): Int {
        synchronized(lock) {
            if (audioTrackIndex == -1) {
                audioTrackIndex = muxer.addTrack(format)
                tryStartLocked()
            }
            return audioTrackIndex
        }
    }

    private fun tryStartLocked() {
        val ready = videoTrackIndex != -1 && (!expectAudioTrack || audioTrackIndex != -1)
        if (ready && !started && !stopped) {
            muxer.start()
            started = true
        }
    }

    fun isStarted(): Boolean = synchronized(lock) { started }

    fun writeVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started || stopped || videoTrackIndex == -1) return
            muxer.writeSampleData(videoTrackIndex, buffer, info)
            hasWrittenVideoSample = true
        }
    }

    fun writeAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started || stopped || audioTrackIndex == -1) return
            muxer.writeSampleData(audioTrackIndex, buffer, info)
        }
    }

    fun stopAndRelease(): Boolean {
        synchronized(lock) {
            if (stopped) return hasWrittenVideoSample
            stopped = true
            try {
                if (started && hasWrittenVideoSample) {
                    muxer.stop()
                }
            } catch (_: Throwable) {
                // Ignore stop errors if no complete frames were flushed before cancellation
            } finally {
                runCatching { muxer.release() }
            }
            return hasWrittenVideoSample
        }
    }
}
