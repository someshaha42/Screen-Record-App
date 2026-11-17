package com.screenrecorder.pro.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import com.screenrecorder.pro.codec.HardwareCodecInspector
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-decoder-only video player using MediaExtractor + explicit hardware MediaCodec
 * (never silently falls back to software decoding) + AudioTrack.
 */
class HardwareVideoPlayer(
    private val context: Context,
    private val onCompatibilityError: (String) -> Unit,
    private val onPlaybackCompleted: () -> Unit,
    private val onVideoDimensions: (width: Int, height: Int) -> Unit
) {
    private val isPlaying = AtomicBoolean(false)
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    fun start(uri: Uri, outputSurface: Surface) {
        stop()

        val hwDecoder = HardwareCodecInspector.findHardwareAvcDecoder()
        if (hwDecoder == null) {
            onCompatibilityError(
                "No hardware H.264/AVC decoder is available on this device. " +
                    "Software decoding fallback is disabled."
            )
            return
        }

        isPlaying.set(true)

        videoThread = Thread({
            playVideoTrack(uri, outputSurface, hwDecoder.codecName)
        }, "HwVideoDecoderThread").apply { start() }

        audioThread = Thread({
            playAudioTrack(uri)
        }, "AudioDecoderThread").apply { start() }
    }

    fun stop() {
        isPlaying.set(false)
        runCatching { videoThread?.join(1000) }
        runCatching { audioThread?.join(1000) }
        videoThread = null
        audioThread = null
    }

    private fun playVideoTrack(uri: Uri, surface: Surface, codecName: String) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var videoTrack = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    videoFormat = fmt
                    break
                }
            }

            if (videoTrack == -1 || videoFormat == null) {
                onCompatibilityError("Recording does not contain a valid video track.")
                return
            }

            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (!mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
                onCompatibilityError(
                    "Unsupported video format ($mime). Only hardware H.264/AVC playback is enabled."
                )
                return
            }

            val w = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val h = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            onVideoDimensions(w, h)

            decoder = MediaCodec.createByCodecName(codecName)
            decoder.configure(videoFormat, surface, null, 0)
            decoder.start()

            extractor.selectTrack(videoTrack)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val playbackStartNs = System.nanoTime()

            while (isPlaying.get() && !outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inIdx,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }

                    val targetNs = playbackStartNs + (info.presentationTimeUs * 1000L)
                    val waitMs = (targetNs - System.nanoTime()) / 1_000_000L
                    if (waitMs > 1L && isPlaying.get()) {
                        Thread.sleep(waitMs.coerceAtMost(50L))
                    }

                    val render = info.size > 0 && isPlaying.get()
                    decoder.releaseOutputBuffer(outIdx, render)
                }
            }

            if (isPlaying.get() && outputDone) {
                onPlaybackCompleted()
            }
        } catch (t: Throwable) {
            if (isPlaying.get()) {
                onCompatibilityError("Hardware video decoder error: ${t.message}")
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun playAudioTrack(uri: Uri) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var audioTrack: AudioTrack? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) return

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val channelOut = if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                channelOut,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelOut)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()
            audioTrack.play()

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()
            extractor.selectTrack(audioTrackIndex)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (isPlaying.get() && !outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)
                        if (buf != null) {
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inIdx,
                                    0,
                                    size,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0 && isPlaying.get()) {
                        val pcm = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(pcm)
                        audioTrack.write(pcm, 0, pcm.size)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                }
            }
        } catch (_: Throwable) {
            // Audio failure should not crash video playback loop
        } finally {
            runCatching { audioTrack?.stop() }
            runCatching { audioTrack?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }
}
