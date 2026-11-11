package com.netdisk.player4kid.player

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.netdisk.player4kid.baidu.PanFile

/**
 * 全局唯一的音频播放器（不计时）。由 [AudioService] 包装成 MediaSession，
 * 以前台服务方式运行，息屏后继续播放。
 */
@OptIn(UnstableApi::class)
object AudioEngine {
    private var player: ExoPlayer? = null

    var lastError: String? = null
        private set

    private var consecutiveErrors = 0

    fun get(context: Context): ExoPlayer {
        player?.let { return it }
        val app = context.applicationContext
        val p = ExoPlayer.Builder(app, AudioLevelMeter.renderersFactory(app, "audio"))
            .setMediaSourceFactory(PanMedia.mediaSourceFactory(app))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK) // 息屏后保持 CPU 和 Wi-Fi
            .build()
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val apiErr = PanMedia.apiErrorOf(error)
                lastError = apiErr?.message ?: error.cause?.message ?: error.message
                consecutiveErrors++
                // 跳过播不了的，继续下一首；连续出错或登录/次数问题时停下，避免一直请求接口
                if (p.mediaItemCount > 1 && consecutiveErrors < 3 && !PanMedia.isFatalApiError(apiErr)) {
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    consecutiveErrors = 0
                    lastError = null
                }
            }
        })
        player = p
        return p
    }

    val current: ExoPlayer? get() = player

    val isPlaying: Boolean get() = player?.isPlaying == true

    /** 正在播放或准备播放（缓冲中） */
    val isActive: Boolean
        get() = player?.let { it.playWhenReady && it.playbackState != Player.STATE_IDLE && it.playbackState != Player.STATE_ENDED } == true

    /**
     * 播放 [files] 中的第 [startIndex] 个，同目录其余的音频自动加入播放列表。
     * 播放列表保持目录里的顺序（001、002、…，见 [PanFile.naturalCompare]），
     * 从点的那个开始播，播到最后一个后回到第一个（列表循环）。
     *
     * @param dir 所在目录的完整标题（如“RAZ全套音频 / Level C”），首页音频条会显示
     */
    fun playList(context: Context, files: List<PanFile>, startIndex: Int, dir: String? = null) {
        if (files.isEmpty()) return
        val start = startIndex.coerceIn(0, files.size - 1)
        val p = get(context)
        lastError = null
        consecutiveErrors = 0
        p.setMediaItems(files.map { PanMedia.audioItem(it, dir) }, start, 0L)
        p.prepare()
        p.play()
        // 启动服务：创建 MediaSession；开始播放后 Media3 会自动转为前台服务并显示通知
        context.startService(Intent(context, AudioService::class.java))
    }

    // ------------------------------------------------------------------ 播放列表（以播放器里的为准）

    val count: Int get() = player?.mediaItemCount ?: 0

    val currentIndex: Int get() = player?.takeIf { it.mediaItemCount > 0 }?.currentMediaItemIndex ?: -1

    fun titleAt(index: Int): String =
        player?.takeIf { index in 0 until it.mediaItemCount }
            ?.getMediaItemAt(index)?.mediaMetadata?.title?.toString() ?: ""

    /** 从头播放列表里的第 [index] 个 */
    fun playAt(index: Int) {
        val p = player ?: return
        if (index !in 0 until p.mediaItemCount) return
        consecutiveErrors = 0
        p.seekToDefaultPosition(index)
        p.prepare()
        p.play()
    }

    /** 调整顺序：把第 [from] 个移到第 [to] 个，正在播放的不受影响 */
    fun move(from: Int, to: Int) {
        val p = player ?: return
        if (from !in 0 until p.mediaItemCount || to !in 0 until p.mediaItemCount || from == to) return
        p.moveMediaItem(from, to)
    }

    /** 从播放列表删除；删的是正在播放的就接着播下一个，删光了就停止 */
    fun remove(index: Int) {
        val p = player ?: return
        val n = p.mediaItemCount
        if (index !in 0 until n) return
        if (n == 1) {
            stop()
            return
        }
        val wasCurrent = index == p.currentMediaItemIndex
        val wasPlaying = p.playWhenReady
        p.removeMediaItem(index)
        if (wasCurrent) {
            // 删的是最后一个就回到第一个
            p.seekToDefaultPosition(if (index < p.mediaItemCount) index else 0)
            p.prepare()
            p.playWhenReady = wasPlaying
        }
    }

    fun pause() {
        player?.pause()
    }

    fun stop() {
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    /** 关掉音频：停止播放、清空播放列表，并结束后台服务（通知栏的播放通知也会消失） */
    fun close(context: Context) {
        stop()
        context.stopService(Intent(context, AudioService::class.java))
    }

    fun currentTitle(): String? =
        player?.currentMediaItem?.mediaMetadata?.title?.toString()

    /** 正在播放的音频所在目录的完整标题 */
    fun currentDir(): String? =
        player?.currentMediaItem?.mediaMetadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() }

    internal fun release() {
        player?.release()
        player = null
    }
}
