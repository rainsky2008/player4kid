package com.netdisk.player4kid.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.netdisk.player4kid.App
import com.netdisk.player4kid.R
import com.netdisk.player4kid.core.Kiosk
import com.netdisk.player4kid.databinding.ActivityAudioBinding
import com.netdisk.player4kid.databinding.ItemAudioBinding
import com.netdisk.player4kid.player.AudioEngine

/**
 * 音频播放界面。不限时，但无操作几秒后自动息屏（只听不看）。
 *
 * 播放列表可以编辑：点一项播放它，✕ 删除，按住 ≡ 拖动调整顺序；可以收起 / 展开。
 * 顶部显示目录完整标题和文件名，太长时换行显示完整。
 */
@OptIn(UnstableApi::class)
class AudioPlayerActivity : BaseKidActivity() {

    // 深色背景：状态栏同色、图标用浅色
    override val statusBarColorRes = R.color.audio_top
    override val lightStatusBar = false

    private lateinit var b: ActivityAudioBinding
    private val adapter = PlaylistAdapter()

    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            uiHandler.postDelayed(this, 500)
        }
    }

    /** 正在拖动调整顺序：这时不能整体刷新列表，否则拖动会中断 */
    private var dragging = false

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refresh()
            scrollToCurrent()
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refresh()
            resetIdleTimer()
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) = refresh()
    }

    /** 拖动调整顺序（按住 ≡ 立即拖动，长按整行也可以） */
    private val touchHelper = ItemTouchHelper(object :
        ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            dragging = true
            AudioEngine.move(from, to)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragging = true
                vh?.itemView?.alpha = 0.8f
            }
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
            dragging = false
            refresh()
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAudioBinding.inflate(layoutInflater)
        setContentView(b.root)

        val player = AudioEngine.get(this)
        b.controlView.player = player
        b.controlView.show()
        player.addListener(listener)

        b.playlist.layoutManager = LinearLayoutManager(this)
        b.playlist.adapter = adapter
        touchHelper.attachToRecyclerView(b.playlist)

        b.btnBack.setOnClickListener { finish() }
        b.btnScreenOff.setOnClickListener { screenOffNow() }
        b.blackout.setOnClickListener { hideBlackout() }
        b.btnHidePlaylist.setOnClickListener { setPlaylistExpanded(false) }
        b.btnShowPlaylist.setOnClickListener { setPlaylistExpanded(true) }

        setPlaylistExpanded(App.prefs.audioPlaylistExpanded)
        refresh()
        scrollToCurrent()
        handleBlackoutExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleBlackoutExtra(intent)
    }

    private fun handleBlackoutExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_BLACKOUT, false) == true) showBlackout()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        AudioEngine.current?.removeListener(listener)
        b.controlView.player = null
        super.onDestroy()
    }

    /** 收起 / 展开播放列表（横屏收起后左边占满全屏） */
    private fun setPlaylistExpanded(expanded: Boolean) {
        App.prefs.audioPlaylistExpanded = expanded
        b.playlistPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        b.btnShowPlaylist.visibility = if (expanded) View.GONE else View.VISIBLE
        if (expanded) b.playlist.post { scrollToCurrent() }
    }

    private fun refresh() {
        b.txtTitle.text = AudioEngine.currentTitle() ?: "没有在播放"
        val dir = AudioEngine.currentDir()
        b.txtDir.text = dir ?: ""
        b.txtDir.visibility = if (dir != null) View.VISIBLE else View.GONE
        b.btnShowPlaylist.text = "☰ 播放列表（${AudioEngine.count}）"
        val err = AudioEngine.lastError
        b.txtError.visibility = if (err != null) View.VISIBLE else View.GONE
        b.txtError.text = if (err != null) "播放出错：$err" else ""
        val n = AudioEngine.count
        b.txtPlaylistHeader.text = if (n > 0) {
            "播放列表 $n 首 · 按住 ≡ 拖动排序 · 点 ✕ 删除"
        } else {
            "播放列表是空的，回到目录里点一个音频就会开始播放"
        }
        if (!dragging) adapter.notifyDataSetChanged()
    }

    /** 让正在播放的那一项出现在列表里 */
    private fun scrollToCurrent() {
        val i = AudioEngine.currentIndex
        if (i < 0 || dragging) return
        val lm = b.playlist.layoutManager as LinearLayoutManager
        if (i < lm.findFirstCompletelyVisibleItemPosition() || i > lm.findLastCompletelyVisibleItemPosition()) {
            lm.scrollToPositionWithOffset(i, 0)
        }
    }

    private fun updateCountdown() {
        if (!AudioEngine.isActive) {
            b.txtScreenOff.text = "暂停中"
            return
        }
        val left = ((idleDeadline - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
        b.txtScreenOff.text = if (idleDeadline > 0) "$left 秒后自动息屏，闭上眼睛听吧" else ""
    }

    override fun onIdle() {
        if (!AudioEngine.isActive) {
            resetIdleTimer()
            return
        }
        screenOffNow()
    }

    private fun screenOffNow() {
        if (!Kiosk.screenOff(this)) showBlackout()
    }

    /** 没有设备管理器权限时：全黑 + 最低亮度，并允许系统按超时自动灭屏 */
    private fun showBlackout() {
        b.blackout.visibility = View.VISIBLE
        b.blackout.setBackgroundColor(Color.BLACK)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hideBlackout() {
        b.blackout.visibility = View.GONE
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        resetIdleTimer()
    }

    // ------------------------------------------------------------------ 播放列表

    private inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistAdapter.VH>() {

        inner class VH(val v: ItemAudioBinding) : RecyclerView.ViewHolder(v.root)

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val h = VH(ItemAudioBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            // 位置在拖动、删除后会变，点击时再取
            h.v.root.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) AudioEngine.playAt(pos)
            }
            h.v.btnRemove.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) AudioEngine.remove(pos)
                refresh()
            }
            h.v.imgDrag.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(h)
                false
            }
            return h
        }

        override fun getItemCount() = AudioEngine.count

        override fun onBindViewHolder(holder: VH, position: Int) {
            val playing = position == AudioEngine.currentIndex
            holder.v.txtName.text = AudioEngine.titleAt(position)
            holder.v.txtName.setTextColor(if (playing) 0xFFFFC233.toInt() else Color.WHITE)
            holder.v.txtPlaying.visibility = if (playing) View.VISIBLE else View.INVISIBLE
            holder.v.root.setBackgroundColor(if (playing) 0x1FFFFFFF else Color.TRANSPARENT)
        }
    }

    companion object {
        const val EXTRA_BLACKOUT = "blackout"
    }
}
