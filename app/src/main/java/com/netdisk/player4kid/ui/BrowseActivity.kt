package com.netdisk.player4kid.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.netdisk.player4kid.App
import com.netdisk.player4kid.R
import com.netdisk.player4kid.baidu.PanFile
import com.netdisk.player4kid.core.PlayTimeManager
import com.netdisk.player4kid.core.TimeTexts
import com.netdisk.player4kid.databinding.ActivityBrowseBinding
import com.netdisk.player4kid.databinding.ItemFileBinding
import com.netdisk.player4kid.player.AudioEngine
import com.netdisk.player4kid.player.VideoQueue
import com.netdisk.player4kid.web.WebLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 浏览网盘目录或视频服务器上的节目：子目录、视频、音频 */
class BrowseActivity : BaseKidActivity() {

    private lateinit var b: ActivityBrowseBinding
    private lateinit var path: String
    /** 从桌面卡片一路点进来的完整标题，如“RAZ全套音频 / Level C” */
    private lateinit var trail: String
    /** 视频服务器上的目录（不是网盘） */
    private val isWeb get() = WebLibrary.isWebPath(path)
    private val adapter = FileAdapter()

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(b.root)

        path = intent.getStringExtra(EXTRA_PATH) ?: "/"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: path.trimEnd('/').substringAfterLast('/')
        b.txtTitle.text = title
        trail = intent.getStringExtra(EXTRA_TRAIL) ?: title

        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        b.recycler.layoutManager = GridLayoutManager(this, (widthDp / 170).toInt().coerceIn(2, 6))
        b.recycler.adapter = adapter

        b.btnBack.setOnClickListener { finish() }
        b.btnHome.setOnClickListener {
            startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        b.btnRetry.setOnClickListener { load(force = true) }
        // 长按标题：强制刷新（目录列表默认缓存 6 小时）
        b.txtTitle.setOnLongClickListener {
            load(force = true)
            true
        }

        load(force = false)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged() // 刷新“看到哪里”
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(ticker)
    }

    private fun load(force: Boolean) {
        b.progress.visibility = View.VISIBLE
        b.messageBox.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (isWeb) App.web.listDir(path, useCache = !force)
                    else App.api.listDir(path, useCache = !force)
                }
            }
            b.progress.visibility = View.GONE
            result.onSuccess { list ->
                val items = list.filter { it.kind != PanFile.Kind.OTHER }
                adapter.submit(items)
                if (items.isEmpty()) showMessage("这里还什么都没有哦", retry = false)
                loadVideoThumbs(items)
            }.onFailure { e ->
                showMessage("加载失败：${e.message}", retry = true)
            }
        }
    }

    /**
     * list 接口不一定返回视频缩略图，用 filemetas 补一下（同时会缓存播放地址），
     * 结果写回目录缓存。每个目录每次运行只补一次，节省接口调用次数。
     */
    private fun loadVideoThumbs(items: List<PanFile>) {
        if (isWeb || !thumbTried.add(path)) return
        val missing = items.filter { it.kind == PanFile.Kind.VIDEO && it.thumb.isNullOrEmpty() }
            .take(100).map { it.fsId }
        if (missing.isEmpty()) return
        lifecycleScope.launch {
            val metas = withContext(Dispatchers.IO) {
                runCatching { App.api.fileMetas(missing) }.getOrDefault(emptyList())
            }
            val thumbs = metas.mapNotNull { m ->
                val f = PanFile.fromJson(m)
                f.thumb?.let { f.fsId to it }
            }.toMap()
            if (thumbs.isNotEmpty()) {
                val updated = adapter.items.map { f -> thumbs[f.fsId]?.let { f.copy(thumb = it) } ?: f }
                adapter.submit(updated)
                withContext(Dispatchers.IO) { App.api.updateDirCache(path, updated) }
            }
        }
    }

    private fun showMessage(msg: String, retry: Boolean) {
        b.messageBox.visibility = View.VISIBLE
        b.txtMessage.text = msg
        b.btnRetry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun updateStatus() {
        // 和首页、播放页用同一套文字（TimeTexts），数字保持一致
        b.txtStatus.text = TimeTexts.browseLine()
    }

    private fun onItemClick(f: PanFile) {
        when (f.kind) {
            PanFile.Kind.DIR -> startActivity(intent(this, f.path, f.name, "$trail / ${f.name}"))
            PanFile.Kind.VIDEO -> playVideo(f)
            PanFile.Kind.AUDIO -> playAudio(f)
            else -> {}
        }
    }

    private fun playVideo(f: PanFile) {
        when (App.playTime.videoBlock()) {
            PlayTimeManager.Block.EXHAUSTED -> {
                AlertDialog.Builder(this)
                    .setTitle("今天的视频时间用完啦 🌙")
                    .setMessage("明天再来看吧！\n现在可以听听故事和儿歌哦。")
                    .setPositiveButton("好的", null)
                    .create()
                    .showOn(this)
                return
            }
            PlayTimeManager.Block.RESTING -> {
                startActivity(Intent(this, RestActivity::class.java))
                return
            }
            else -> {}
        }
        val videos = adapter.items.filter { it.kind == PanFile.Kind.VIDEO }
        VideoQueue.set(videos, videos.indexOfFirst { it.fsId == f.fsId }.coerceAtLeast(0))
        startActivity(Intent(this, VideoPlayerActivity::class.java))
    }

    private fun playAudio(f: PanFile) {
        val audios = adapter.items.filter { it.kind == PanFile.Kind.AUDIO }
        AudioEngine.playList(this, audios, audios.indexOfFirst { it.fsId == f.fsId }.coerceAtLeast(0), trail)
        startActivity(Intent(this, AudioPlayerActivity::class.java))
    }

    // ------------------------------------------------------------------ 列表

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.VH>() {
        var items: List<PanFile> = emptyList()
            private set

        fun submit(list: List<PanFile>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val v: ItemFileBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            val v = holder.v
            v.txtName.text = f.displayName
            v.imgIcon.setImageResource(
                when (f.kind) {
                    PanFile.Kind.DIR -> R.drawable.ic_folder
                    PanFile.Kind.AUDIO -> R.drawable.ic_music
                    else -> R.drawable.ic_movie
                }
            )
            // 视频缩略图；视频服务器上的节目（目录）有封面图
            if (f.kind != PanFile.Kind.AUDIO && !f.thumb.isNullOrEmpty()) {
                v.imgThumb.visibility = View.VISIBLE
                Glide.with(v.imgThumb).load(f.thumb).centerCrop().into(v.imgThumb)
            } else {
                Glide.with(v.imgThumb).clear(v.imgThumb)
                v.imgThumb.visibility = View.GONE
            }
            val pos = if (f.kind == PanFile.Kind.VIDEO) App.prefs.getPosition(f.fsId) else 0L
            if (pos > 0) {
                v.txtBadge.visibility = View.VISIBLE
                v.txtBadge.text = "看到 ${PlayTimeManager.formatMinSec(pos)}"
            } else {
                v.txtBadge.visibility = View.GONE
            }
            v.root.setOnClickListener { onItemClick(f) }
        }
    }

    companion object {
        /** 本次运行中已经尝试补过缩略图的目录 */
        private val thumbTried = HashSet<String>()

        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TRAIL = "trail"

        /** @param trail 完整标题（上级目录 / 本目录），默认就是 [title] */
        fun intent(ctx: Context, path: String, title: String, trail: String = title) =
            Intent(ctx, BrowseActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TRAIL, trail)
    }
}
