package com.netdisk.player4kid.player

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 诊断用：统计播放器实际解码出来的声音电平（每秒写一次日志，TAG = AudioLevel），
 * 用来区分“内容本身没声音”和“设备喇叭不出声”。
 */
@OptIn(UnstableApi::class)
class AudioLevelMeter(private val name: String) : TeeAudioProcessor.AudioBufferSink {

    private var encoding = C.ENCODING_PCM_16BIT
    private var sampleRate = 44100
    private var channels = 2
    private var sumSquares = 0.0
    private var peak = 0.0
    private var count = 0L

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRate = sampleRateHz
        this.channels = channelCount
        this.encoding = encoding
        reset()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> while (b.remaining() >= 2) add(b.short / 32768.0)
            C.ENCODING_PCM_FLOAT -> while (b.remaining() >= 4) add(b.float.toDouble())
            else -> return
        }
        if (count >= sampleRate.toLong() * channels) report()
    }

    private fun add(v: Double) {
        sumSquares += v * v
        val a = if (v < 0) -v else v
        if (a > peak) peak = a
        count++
    }

    private fun report() {
        val rms = sqrt(sumSquares / count)
        lastRmsDb = if (rms > 0) 20 * log10(rms) else -120.0
        val peakDb = if (peak > 0) 20 * log10(peak) else -120.0
        Log.i(TAG, "%s rms=%.1f dBFS peak=%.1f dBFS (%dHz x%d)".format(name, lastRmsDb, peakDb, sampleRate, channels))
        reset()
    }

    private fun reset() {
        sumSquares = 0.0
        peak = 0.0
        count = 0
    }

    companion object {
        const val TAG = "AudioLevel"

        @Volatile
        var lastRmsDb = -120.0
            private set

        /** 带电平统计的渲染器工厂 */
        fun renderersFactory(context: Context, name: String): DefaultRenderersFactory =
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(AudioLevelMeter(name))))
                    .build()
            }

        /** 当前媒体音量，如 "8/15" */
        fun mediaVolumeText(context: Context): String {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return "${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
        }
    }
}
