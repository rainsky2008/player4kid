package com.netdisk.player4kid.core

import android.content.Context
import android.util.Log
import com.netdisk.player4kid.App
import com.netdisk.player4kid.baidu.PanFile
import com.netdisk.player4kid.web.WebLibrary
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 桌面卡片的封面，按顺序找：
 *  1. 家长放的封面图：文件夹里的 封面.jpg（也认 cover / folder，jpg / jpeg / png / webp，不分大小写），用原图；
 *  2. 随机挑一个视频的缩略图（第一个视频的画面常常是片头、黑屏或 logo）；
 *  3. 都没有时，随机到子文件夹里找（每层最多试 2 个，最多两层；纯音频的文件夹不往下找，省接口次数）。
 * 图片下载到本地保存，7 天才重新挑一次（视频画面会随机换一张）；没找到封面的文件夹 3 天内不再找。
 * 视频服务器：随机用一个节目的封面图（地址固定，交给 Glide 缓存）。
 *
 * 目录列表用的是 [com.netdisk.player4kid.baidu.BaiduApi.listDir] 的缓存（和浏览页共用），基本不额外调用接口。
 * 家长换了封面图后，在家长设置里点“刷新目录”就会重新挑。
 */
object FolderCovers {
    private const val TAG = "FolderCovers"
    private const val TTL_MS = 7 * 24 * 3600 * 1000L
    private const val NONE_TTL_MS = 3 * 24 * 3600 * 1000L
    /** 往子文件夹里找的层数 */
    private const val MAX_DEPTH = 2
    /** 每层最多试几个子文件夹 */
    private const val MAX_SUBDIRS = 2

    /** 自定义封面的文件名（不含扩展名，小写） */
    private val COVER_NAMES = setOf("封面", "cover", "folder")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    /** 找到的封面：图片地址（视频缩略图），或网盘里的原图（自定义封面） */
    private sealed class Pick {
        data class Url(val url: String) : Pick()
        data class PanImage(val fsId: Long) : Pick()
    }

    /** 挑选规则的版本：规则改了就加 1，升级后所有封面按新规则重新挑一次（3：同时记下文件夹是看的还是听的） */
    private const val RULE_VERSION = 3

    /** 文件夹里主要是什么：首页卡片据此显示 🎬 / 🎧，视频时间用完时视频文件夹会“睡觉” */
    enum class Kind { VIDEO, AUDIO }

    /** 找封面时顺便记下见到的内容 */
    private class Scan {
        var video = false
        var audio = false
        val kind: String get() = if (video) "video" else if (audio) "audio" else ""
    }

    private val dir by lazy { File(App.instance.filesDir, "covers").apply { mkdirs() } }
    private val sp by lazy {
        App.instance.getSharedPreferences("folder_covers", Context.MODE_PRIVATE).also { p ->
            if (p.getInt("rule_version", 1) < RULE_VERSION) {
                val e = p.edit()
                p.all.keys.filter { it.startsWith("t_") }.forEach { e.putLong(it, 0L) }
                e.putInt("rule_version", RULE_VERSION).apply()
            }
        }
    }

    /**
     * 已经有的封面，不发请求：本地图片 [File]、图片地址 [String]，或 null（还没有 / 没有封面）。
     */
    fun cached(path: String): Any? {
        val h = md5(path)
        if (WebLibrary.isWebPath(path)) return sp.getString("u_$h", null)
        val f = File(dir, "$h.jpg")
        return if (f.isFile && f.length() > 0) f else null
    }

    /** 文件夹是看的还是听的；还没找过封面时返回 null */
    fun kind(path: String): Kind? {
        if (WebLibrary.isWebPath(path)) return Kind.VIDEO
        return when (sp.getString("k_${md5(path)}", null)) {
            "video" -> Kind.VIDEO
            "audio" -> Kind.AUDIO
            else -> null
        }
    }

    /** 是否需要（重新）找封面 */
    fun needsRefresh(path: String): Boolean {
        val h = md5(path)
        val time = sp.getLong("t_$h", 0L)
        val ttl = if (sp.getBoolean("n_$h", false)) NONE_TTL_MS else TTL_MS
        return System.currentTimeMillis() - time > ttl
    }

    /**
     * 封面还没有或已过期时，找封面并保存（阻塞，请在 IO 线程调用）。
     * 同一时间只处理一个（桌面刷新两次时不会重复调接口）。
     * @return true 表示这次重新找过封面
     */
    @Synchronized
    fun refreshIfNeeded(path: String, descend: Boolean = true): Boolean {
        if (!needsRefresh(path)) return false
        resolve(path, descend)
        return true
    }

    /**
     * 找封面并保存。接口或网络出错时抛出 IOException，旧封面保留不动。
     * @param descend 是否到子文件夹里找（“其他”卡片对应资源目录本身，不往下找）
     * @return 新的封面（同 [cached]），没有封面时返回 null
     */
    private fun resolve(path: String, descend: Boolean): Any? {
        val h = md5(path)
        if (WebLibrary.isWebPath(path)) {
            val url = App.web.listDir(path).mapNotNull { it.thumb?.takeIf { t -> t.isNotEmpty() } }.randomOrNull()
            sp.edit().putString("u_$h", url).putLong("t_$h", System.currentTimeMillis())
                .putBoolean("n_$h", url == null).apply()
            return url
        }
        val scan = Scan()
        val pick = findPanCover(path, if (descend) MAX_DEPTH else 0, scan)
        val file = File(dir, "$h.jpg")
        if (pick == null) {
            file.delete()
            sp.edit().putLong("t_$h", System.currentTimeMillis()).putBoolean("n_$h", true)
                .putString("k_$h", scan.kind).apply()
            return null
        }
        val url = when (pick) {
            is Pick.Url -> pick.url
            // 自定义封面用原图（下载地址需要 UA: pan.baidu.com，App.http 默认就是）
            is Pick.PanImage -> App.api.downloadUrl(pick.fsId)
        }
        download(url, file)
        Log.i(TAG, "cover for $path: ${if (pick is Pick.PanImage) "custom image" else "video thumbnail"}")
        sp.edit().putLong("t_$h", System.currentTimeMillis()).putBoolean("n_$h", false)
            .putString("k_$h", scan.kind).apply()
        return file
    }

    /** 家长点“刷新目录”后：下次回到桌面时重新挑选封面（旧封面先继续显示） */
    fun markAllStale() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("t_") }.forEach { e.putLong(it, 0L) }
        e.apply()
    }

    /** 是不是家长放的封面图（封面.jpg / cover.png / folder.jpg …） */
    private fun isCoverImage(f: PanFile): Boolean =
        !f.isDir && f.ext in IMAGE_EXT && f.name.substringBeforeLast('.').trim().lowercase() in COVER_NAMES

    /** 自定义封面图 → 随机一个视频的缩略图 → 随机到子文件夹里找 */
    private fun findPanCover(path: String, depth: Int, scan: Scan): Pick? {
        val list = App.api.listDir(path)
        val videos = list.filter { it.kind == PanFile.Kind.VIDEO }
        if (videos.isNotEmpty()) scan.video = true
        if (list.any { it.kind == PanFile.Kind.AUDIO }) scan.audio = true

        list.firstOrNull { isCoverImage(it) }?.let { return Pick.PanImage(it.fsId) }

        videos.mapNotNull { it.thumb?.takeIf { t -> t.isNotEmpty() } }.randomOrNull()?.let { return Pick.Url(it) }
        if (videos.isNotEmpty()) {
            // list 接口不一定带缩略图：随机抽几个用 filemetas 查（一次调用）
            val sample = videos.shuffled().take(5)
            App.api.fileMetas(sample.map { it.fsId })
                .mapNotNull { PanFile.fromJson(it).thumb?.takeIf { t -> t.isNotEmpty() } }
                .randomOrNull()?.let { return Pick.Url(it) }
        }

        // 纯音频的文件夹（有音频、没视频）不往下找
        val audioOnly = videos.isEmpty() && list.any { it.kind == PanFile.Kind.AUDIO }
        if (depth > 0 && !audioOnly) {
            for (sub in list.filter { it.isDir }.shuffled().take(MAX_SUBDIRS)) {
                findPanCover(sub.path, depth - 1, scan)?.let { return it }
            }
        }
        return null
    }

    private fun download(url: String, target: File) {
        val req = Request.Builder().url(url).build()
        App.http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载封面失败（HTTP ${resp.code}）")
            val tmp = File(dir, target.name + ".tmp")
            resp.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("下载封面失败")
            if (tmp.length() == 0L) throw IOException("封面是空的")
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("保存封面失败")
            }
        }
        Log.i(TAG, "cover saved: ${target.name} (${target.length()} bytes)")
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
