package com.netdisk.player4kid.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.netdisk.player4kid.App
import com.netdisk.player4kid.baidu.PanFile
import com.netdisk.player4kid.core.PlayTimeManager
import com.netdisk.player4kid.core.TimeTexts
import com.netdisk.player4kid.core.Prefs
import com.netdisk.player4kid.databinding.ActivityVideoBinding
import com.netdisk.player4kid.player.AudioEngine
import com.netdisk.player4kid.player.AudioLevelMeter
import com.netdisk.player4kid.player.PanMedia
import com.netdisk.player4kid.player.VideoQueue

/**
 * 视频播放。每秒累计观看时长：
 *  - 单次连续观看到点 → 暂停并强制进入休息界面
 *  - 今日时长用完 → 停止播放
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : BaseKidActivity() {

    override val autoScreenOffForAudio = false

    private lateinit var b: ActivityVideoBinding
    private var player: ExoPlayer? = null
    private var files: List<PanFile> = emptyList()
    private var lastTick = 0L
    private var lastPositionSave = 0L
    private var currentIndex = 0
    private var consecutiveErrors = 0
    private var blocked = false
    /** 已经因为转码失败改用原画的文件 */
    private val fallbackTried = HashSet<Long>()

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnBlockOk.setOnClickListener { finish() }
        b.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
            b.topBar.visibility = vis
        })

        if (!checkAllowed()) return

        files = VideoQueue.files
        if (files.isEmpty()) {
            finish()
            return
        }
        AudioEngine.pause()
        initPlayer()
    }

    /** 是否允许现在看视频；不允许时跳转/提示 */
    private fun checkAllowed(): Boolean {
        return when (App.playTime.videoBlock()) {
            PlayTimeManager.Block.RESTING -> {
                startActivity(Intent(this, RestActivity::class.java))
                finish()
                false
            }
            PlayTimeManager.Block.EXHAUSTED -> {
                showExhausted()
                false
            }
            else -> true
        }
    }

    private fun initPlayer() {
        val p = ExoPlayer.Builder(this, AudioLevelMeter.renderersFactory(this, "video"))
            .setMediaSourceFactory(PanMedia.mediaSourceFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()
        player = p
        b.playerView.player = p

        val start = VideoQueue.index.coerceIn(0, files.size - 1)
        currentIndex = start
        val items = files.map { PanMedia.videoItem(it) }
        p.setMediaItems(items, start, App.prefs.getPosition(files[start].fsId))
        p.addListener(listener)
        p.prepare()
        p.playWhenReady = true
        updateTitle()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            // 上一集自动播完了：清除它的进度记录
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                files.getOrNull(currentIndex)?.let { App.prefs.clearPosition(it.fsId) }
            }
            currentIndex = p.currentMediaItemIndex
            VideoQueue.saveIndex(currentIndex)
            updateTitle()
            // 手动切换到某一集时，从上次看到的位置继续
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                val pos = App.prefs.getPosition(PanMedia.fsIdOf(mediaItem))
                if (pos > 0) p.seekTo(pos)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            val p = player ?: return
            updateLoading()
            if (state == Player.STATE_ENDED) {
                files.getOrNull(p.currentMediaItemIndex)?.let { App.prefs.clearPosition(it.fsId) }
                finish()
            }
            if (state == Player.STATE_READY) {
                b.txtLoading.visibility = View.GONE
                consecutiveErrors = 0
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            lastTick = SystemClock.elapsedRealtime()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "player error", error)
            val p = player ?: return
            val idx = p.currentMediaItemIndex
            val f = files.getOrNull(idx) ?: return
            // 转码失败 → 自动改用原画再试一次
            if (PanMedia.isStream(p.currentMediaItem) && fallbackTried.add(f.fsId)) {
                val pos = App.prefs.getPosition(f.fsId)
                p.replaceMediaItem(idx, PanMedia.videoItem(f, Prefs.MODE_ORIGINAL))
                p.seekTo(idx, pos)
                p.prepare()
                p.play()
                return
            }
            val apiErr = PanMedia.apiErrorOf(error)
            val msg = apiErr?.message ?: error.cause?.message ?: error.errorCodeName
            consecutiveErrors++
            if (PanMedia.isFatalApiError(apiErr)) {
                showBlock("😢", "暂时不能播放", msg)
                return
            }
            if (idx < files.size - 1 && consecutiveErrors < 3) {
                b.txtLoading.visibility = View.VISIBLE
                b.txtLoading.text = "这个视频播放不了（$msg），换下一个…"
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            } else {
                showBlock("😢", "视频播放不了", msg)
            }
        }
    }

    private fun updateTitle() {
        b.txtTitle.text = player?.currentMediaItem?.mediaMetadata?.title ?: ""
    }

    private fun updateLoading() {
        val p = player ?: return
        val buffering = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady
        if (buffering && PanMedia.isStream(p.currentMediaItem) && p.currentPosition < 1000) {
            b.txtLoading.visibility = View.VISIBLE
            b.txtLoading.text = if (App.prefs.vipType == 0) "正在准备视频…（普通用户需要等十几秒）" else "正在准备视频…"
        } else if (!buffering) {
            b.txtLoading.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (blocked) return
        // 从后台回来时再检查一次（比如休息中被唤醒）
        if (player != null && !checkAllowed()) return
        lastTick = SystemClock.elapsedRealtime()
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(ticker)
        tick() // 把最后一点时间也算上
        player?.pause()
        savePosition()
        App.playTime.flush()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(ticker)
        player?.removeListener(listener)
        player?.release()
        player = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 计时

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastTick).coerceIn(0, 2000)
        lastTick = now
        val p = player ?: return
        if (blocked) return

        if (p.isPlaying) {
            when (App.playTime.addVideoTime(delta)) {
                PlayTimeManager.Block.RESTING -> {
                    goRest()
                    return
                }
                PlayTimeManager.Block.EXHAUSTED -> {
                    p.pause()
                    savePosition()
                    showExhausted()
                    return
                }
                else -> {}
            }
            // 看到哪里也每 5 秒存一次：被强行结束、重启后能接着看
            if (now - lastPositionSave >= 5_000) {
                lastPositionSave = now
                savePosition()
            }
        }

        // 和首页、目录页用同一套文字（TimeTexts），数字保持一致
        val left = App.playTime.watchableNowMs()
        b.txtTimeLeft.text = TimeTexts.playerLine()
        if (left <= WARN_MS) {
            b.txtWarn.visibility = View.VISIBLE
            b.txtWarn.text = TimeTexts.playerWarning()
        } else {
            b.txtWarn.visibility = View.GONE
        }
    }

    private fun savePosition() {
        val p = player ?: return
        val f = files.getOrNull(p.currentMediaItemIndex) ?: return
        if (p.playbackState == Player.STATE_IDLE) return
        App.prefs.savePosition(f.fsId, p.currentPosition, p.duration.takeIf { it > 0 } ?: 0L)
        VideoQueue.saveIndex(p.currentMediaItemIndex)
    }

    private fun goRest() {
        blocked = true
        player?.pause()
        savePosition()
        startActivity(Intent(this, RestActivity::class.java))
        finish()
    }

    private fun showExhausted() {
        showBlock("🌙", "今天的视频时间用完啦", "明天再来看吧！\n现在可以去听听故事和儿歌哦")
    }

    private fun showBlock(emoji: String, title: String, msg: String) {
        blocked = true
        player?.pause()
        b.txtWarn.visibility = View.GONE
        b.txtLoading.visibility = View.GONE
        b.blockOverlay.visibility = View.VISIBLE
        b.txtBlockEmoji.text = emoji
        b.txtBlockTitle.text = title
        b.txtBlockMsg.text = msg
    }

    companion object {
        private const val TAG = "VideoPlayer"
        private const val WARN_MS = 60_000L
    }
}
