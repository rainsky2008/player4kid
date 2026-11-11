package com.netdisk.player4kid.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.netdisk.player4kid.App
import com.netdisk.player4kid.R
import com.netdisk.player4kid.baidu.PanFile
import com.netdisk.player4kid.core.FolderCovers
import com.netdisk.player4kid.core.KidNames
import com.netdisk.player4kid.core.Kiosk
import com.netdisk.player4kid.core.PlayTimeManager
import com.netdisk.player4kid.core.TimeTexts
import com.netdisk.player4kid.databinding.ActivityLauncherBinding
import com.netdisk.player4kid.databinding.ItemRootBinding
import com.netdisk.player4kid.player.AudioEngine
import com.netdisk.player4kid.ui.parent.ParentGate
import com.netdisk.player4kid.web.WebLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面（HOME）。显示今天剩余的观看时间和资源卡片：
 *  - 网盘资源目录（默认 /apps/player4kid）里的每个文件夹一张卡片（平铺）
 *  - 家长单独添加的网盘目录
 *  - 视频服务器
 */
class LauncherActivity : BaseKidActivity() {

    private lateinit var b: ActivityLauncherBinding
    private val adapter = RootAdapter()
    private var homeJob: Job? = null
    /** 今天的视频时间已用完：视频文件夹的卡片显示为“睡觉” */
    private var videosAsleep = false
    private var coverJob: Job? = null
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.US)

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 每张卡片约 200dp 宽：横屏平板 4 列
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        b.recycler.layoutManager = GridLayoutManager(this, (widthDp / 200).toInt().coerceIn(2, 5))
        b.recycler.adapter = adapter

        b.btnSettings.setOnClickListener { ParentGate.showSettingsMenu(this) }
        // 隐藏入口：长按时钟也能打开设置
        b.txtClock.setOnLongClickListener {
            ParentGate.showSettingsMenu(this)
            true
        }
        b.audioBar.setOnClickListener {
            startActivity(Intent(this, AudioPlayerActivity::class.java))
        }
        b.btnAudioToggle.setOnClickListener {
            AudioEngine.current?.let { if (it.isPlaying) it.pause() else it.play() }
            updateStatus()
        }
        b.txtAudio.isSelected = true // 太长时滚动显示（marquee）
        b.btnAudioClose.setOnClickListener {
            AudioEngine.close(this)
            updateStatus()
        }

        // 桌面上按返回键不做任何事
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    override fun onResume() {
        // 回到桌面说明家长的临时操作已经结束，恢复防退出
        Kiosk.resumeGuard()
        super.onResume()
        if (App.playTime.restRemainingMs() > 0 && App.playTime.dailyRemainingMs() > 0) {
            startActivity(Intent(this, RestActivity::class.java))
            return
        }
        loadRoots()
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(ticker)
        coverJob?.cancel() // 不在桌面时不去找封面，省接口次数
    }

    /** 逐个给卡片找封面（只处理还没有或已过期的），找到一个刷新一个 */
    private fun loadCovers(roots: List<Root>) {
        coverJob?.cancel()
        val home = App.prefs.panHome
        val todo = roots.filter { r ->
            (panReady || WebLibrary.isWebPath(r.path)) && FolderCovers.needsRefresh(r.path)
        }
        if (todo.isEmpty()) return
        coverJob = lifecycleScope.launch {
            for (r in todo) {
                val changed = withContext(Dispatchers.IO) {
                    runCatching { FolderCovers.refreshIfNeeded(r.path, descend = r.path != home) }
                        .onFailure { Log.w("FolderCovers", "cover ${r.path}: ${it.message}") }
                        .getOrDefault(false)
                }
                if (changed) adapter.coverChanged(r.path)
            }
        }
    }

    private val panReady: Boolean
        get() = App.prefs.appKey.isNotBlank() && App.prefs.isLoggedIn

    /** 先用上次的结果显示，再在后台读取资源目录（有 6 小时缓存，不会每次都调接口） */
    private fun loadRoots() {
        renderRoots()
        val home = App.prefs.panHome
        if (!panReady || home.isEmpty()) return
        homeJob?.cancel()
        homeJob = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { App.api.listDir(home) } }
            homeListing = r.fold(
                onSuccess = { HomeListing(home, it, null) },
                onFailure = { e ->
                    // 接口失败且没有缓存：保留上次的结果，并记下错误
                    val old = homeListing?.takeIf { it.path == home }?.files ?: emptyList()
                    HomeListing(home, old, e.message ?: "读取失败")
                },
            )
            renderRoots()
        }
    }

    private fun renderRoots() {
        val prefs = App.prefs
        val home = prefs.panHome
        val listing = homeListing?.takeIf { it.path == home && home.isNotEmpty() }
        val roots = ArrayList<Root>()
        if (panReady) {
            // 资源目录平铺：里面的每个文件夹一张卡片；直接放在资源目录里的文件归到“其他”
            listing?.files?.filter { it.isDir }?.forEach { roots += Root(it.path, it.name) }
            if (listing?.files?.any { it.kind == PanFile.Kind.VIDEO || it.kind == PanFile.Kind.AUDIO } == true) {
                roots += Root(home, "其他")
            }
            // 单独添加的目录（资源目录本身已经平铺了，不再重复显示）
            prefs.rootDirs.filter { p -> p != home && roots.none { it.path == p } }.forEach { path ->
                roots += Root(path, path.trimEnd('/').substringAfterLast('/').ifEmpty { "全部" })
            }
        }
        roots += prefs.webSources.map { Root(it.url, it.name.ifBlank { "视频" }) }
        adapter.submit(roots)
        loadCovers(roots)
        val msg = when {
            roots.isNotEmpty() -> null
            prefs.appKey.isBlank() -> "欢迎使用！\n\n请家长点击右上角 ⚙ 设置按钮，\n填写百度开放平台 AppKey 并登录网盘。"
            !prefs.isLoggedIn -> "还没有登录百度网盘\n\n请家长点击右上角 ⚙ 设置按钮登录。"
            home.isNotEmpty() && listing == null -> "正在读取网盘目录…"
            listing?.error != null -> "读取网盘目录失败：${listing.error}\n\n请检查网络，或请家长在设置里点“刷新目录”。"
            home.isNotEmpty() -> "网盘的 $home 里还没有文件夹\n\n请家长在百度网盘 App 的“我的应用数据 → ${home.substringAfterLast('/')}”里\n新建文件夹，放入视频或音频。"
            else -> "还没有选择要看的目录\n\n请家长点击右上角 ⚙ 设置按钮添加。"
        }
        b.txtEmpty.text = msg
        b.txtEmpty.visibility = if (msg != null) View.VISIBLE else View.GONE
        b.recycler.visibility = if (msg == null) View.VISIBLE else View.GONE
    }

    private fun updateStatus() {
        b.txtClock.text = clockFormat.format(Date())

        val pt = App.playTime
        val daily = pt.dailyRemainingMs()
        b.timeBar.set(daily, pt.dailyLimitMs)
        // 和目录页、播放页用同一套文字（TimeTexts），数字保持一致
        b.txtStatus.text = TimeTexts.today()
        b.txtStatusSub.text = TimeTexts.thisTime()
        // 视频时间刚用完 / 家长又加了时间：视频卡片“睡觉”或“醒来”
        val exhausted = daily <= 0
        if (exhausted != videosAsleep) {
            videosAsleep = exhausted
            adapter.notifyDataSetChanged()
        }

        val audio = AudioEngine.current
        if (audio != null && audio.mediaItemCount > 0) {
            b.audioBar.visibility = View.VISIBLE
            val title = AudioEngine.currentTitle() ?: ""
            val full = AudioEngine.currentDir()?.let { "$it / $title" } ?: title
            val text = if (audio.isPlaying) "正在听：$full" else "已暂停：$full"
            // 每秒都会刷新：文字没变就不要重设，否则滚动会从头开始
            if (b.txtAudio.text.toString() != text) b.txtAudio.text = text
            b.btnAudioToggle.setImageResource(if (audio.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        } else {
            b.audioBar.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ 目录卡片

    /** 桌面上的一张卡片：网盘目录，或视频服务器（path 为 http 地址） */
    private data class Root(val path: String, val name: String)

    private inner class RootAdapter : RecyclerView.Adapter<RootAdapter.VH>() {
        private var dirs: List<Root> = emptyList()

        fun submit(list: List<Root>) {
            if (list == dirs) return
            dirs = list
            notifyDataSetChanged()
        }

        fun coverChanged(path: String) {
            val i = dirs.indexOfFirst { it.path == path }
            if (i >= 0) notifyItemChanged(i)
        }

        inner class VH(val v: ItemRootBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val h = VH(ItemRootBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            // 卡片和封面都按圆角裁剪（按下时的水波纹也不会超出圆角）
            h.v.root.clipToOutline = true
            h.v.coverBox.clipToOutline = true
            return h
        }

        override fun getItemCount() = dirs.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (path, name) = dirs[position]
            val v = holder.v
            val shown = KidNames.folder(name)
            val dp = resources.displayMetrics.density
            v.txtName.text = shown
            v.txtEmoji.text = emojiFor(shown, position)

            // 玩具积木：白色卡片 + 底边一道彩色厚边；封面区域没有封面时是同色系渐变
            val color = COLORS[position % COLORS.size]
            v.root.background = LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(color.second); cornerRadius = 20 * dp },
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 20 * dp },
            )).apply { setLayerInset(1, 0, 0, 0, (5 * dp).toInt()) }
            v.coverBox.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(color.first, color.second)
            ).apply { cornerRadius = 14 * dp }
            v.root.setOnClickListener {
                startActivity(BrowseActivity.intent(this@LauncherActivity, path, shown))
            }

            val cover = FolderCovers.cached(path)
            if (cover != null) {
                v.imgCover.visibility = View.VISIBLE
                v.txtEmoji.visibility = View.GONE
                var req = Glide.with(v.imgCover).load(cover).centerCrop()
                // 本地封面每 7 天会更新，用修改时间区分新旧图片
                if (cover is File) req = req.signature(ObjectKey(cover.lastModified()))
                req.into(v.imgCover)
            } else {
                Glide.with(v.imgCover).clear(v.imgCover)
                v.imgCover.visibility = View.GONE
                v.txtEmoji.visibility = View.VISIBLE
            }

            // 左上角：听的文件夹标一个 🎧（不计时，视频时间用完也能听）；视频是默认，不加标记
            val kind = FolderCovers.kind(path)
            v.txtKind.visibility = if (kind == FolderCovers.Kind.AUDIO) View.VISIBLE else View.GONE
            v.txtKind.text = "🎧"

            // 今天的视频时间用完了：视频文件夹“睡觉”（变灰 + 🌙 明天再看），听的照常亮着
            val asleep = videosAsleep && kind != FolderCovers.Kind.AUDIO
            v.imgCover.colorFilter = if (asleep) GRAYSCALE else null
            v.dim.visibility = if (asleep) View.VISIBLE else View.GONE
            v.txtState.visibility = if (asleep) View.VISIBLE else View.GONE
            v.txtEmoji.alpha = if (asleep) 0.35f else 1f
            v.txtName.alpha = if (asleep) 0.55f else 1f
        }
    }

    /** 资源目录的内容；error 不为空表示最近一次读取失败 */
    private data class HomeListing(val path: String, val files: List<PanFile>, val error: String?)

    companion object {
        /** 上次读取到的资源目录，回到桌面时先用它显示，避免闪一下空白 */
        private var homeListing: HomeListing? = null

        private val COLORS = listOf(
            0xFFFF8A5B.toInt() to 0xFFFF5F8F.toInt(),
            0xFF4FACFE.toInt() to 0xFF5B6CFF.toInt(),
            0xFF43E97B.toInt() to 0xFF20B2AA.toInt(),
            0xFFB38BFA.toInt() to 0xFF7B5CF5.toInt(),
            0xFFFFC94D.toInt() to 0xFFFF9A3C.toInt(),
            0xFF35D0C7.toInt() to 0xFF2C8DE8.toInt(),
        )
        private val DEFAULT_EMOJI = listOf("🎬", "🧸", "🚀", "🦄", "🐳", "🌈", "🦁", "🎈")

        private val GRAYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })


        fun emojiFor(name: String, index: Int): String = when {
            Regex("儿歌|音乐|歌|music", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "🎵"
            Regex("故事|绘本|听|书|story", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "📖"
            Regex("英语|english|abc", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "🔤"
            Regex("数学|科学|百科|知识").containsMatchIn(name) -> "🔬"
            Regex("动画|卡通|动漫|cartoon", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "🎬"
            else -> DEFAULT_EMOJI[index % DEFAULT_EMOJI.size]
        }
    }
}
